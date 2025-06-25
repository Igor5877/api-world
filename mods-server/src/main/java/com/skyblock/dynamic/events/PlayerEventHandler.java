package com.skyblock.dynamic.events;

import com.skyblock.dynamic.Config;
import com.skyblock.dynamic.SkyBlockMod;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Mod.EventBusSubscriber(modid = SkyBlockMod.MODID)
public class PlayerEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds())) // Default connect timeout
            .build();

    private static final AtomicInteger currentPlayerCount = new AtomicInteger(0);
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = Executors.defaultThreadFactory().newThread(runnable);
        thread.setName("SkyBlockAutoFreezeScheduler");
        thread.setDaemon(true);
        return thread;
    });

    private static ScheduledFuture<?> freezeTask = null;
    private static final AtomicInteger freezeRetryCount = new AtomicInteger(0);

    // TODO: Make these configurable via a common config file if desired
    private static final int MAX_FREEZE_RETRIES = 5;
    private static final long FREEZE_DELAY_MINUTES = 5;
    private static final long RETRY_DELAY_MINUTES = 1;

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        int count = currentPlayerCount.incrementAndGet();
        LOGGER.info("Player {} logged in. Current player count on this server: {}", event.getEntity().getName().getString(), count);

        if (SkyBlockMod.isIslandServer()) {
            if (freezeTask != null && !freezeTask.isDone()) {
                LOGGER.info("Player logged into island server. Cancelling auto-freeze timer and any pending freeze API calls.");
                freezeTask.cancel(false); // Cancel the scheduled task
                freezeTask = null;
                freezeRetryCount.set(0); // Reset retries as a player is back
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        int count = currentPlayerCount.decrementAndGet();
        LOGGER.info("Player {} logged out. Current player count on this server: {}", event.getEntity().getName().getString(), count);

        if (SkyBlockMod.isIslandServer()) {
            if (count == 0) {
                LOGGER.info("Last player logged out from island server. Starting auto-freeze timer ({} minutes).", FREEZE_DELAY_MINUTES);
                startFreezeTimer();
            } else if (count < 0) {
                // This case should ideally not happen with correct event handling, but as a safeguard:
                LOGGER.warn("Player count for island server dropped to {} (less than 0). Resetting to 0 and ensuring freeze timer is initiated if not already.", count);
                currentPlayerCount.set(0); // Correct the count
                // If timer wasn't already running (e.g., from a previous 0 state that was missed)
                if (freezeTask == null || freezeTask.isDone()) {
                    startFreezeTimer();
                }
            }
        }
    }

    private static void startFreezeTimer() {
        // If a freeze task is already scheduled, cancel it before starting a new one.
        if (freezeTask != null && !freezeTask.isDone()) {
            LOGGER.info("An existing freeze timer was found. Cancelling it before starting a new one.");
            freezeTask.cancel(false);
        }
        freezeRetryCount.set(0); // Reset retries for a new freeze sequence
        LOGGER.info("Scheduling auto-freeze task to run in {} minutes.", FREEZE_DELAY_MINUTES);
        freezeTask = scheduler.schedule(PlayerEventHandler::executeFreezeLogic, FREEZE_DELAY_MINUTES, TimeUnit.MINUTES);
    }

    private static void executeFreezeLogic() {
        if (!SkyBlockMod.isIslandServer()) {
            LOGGER.debug("executeFreezeLogic called, but this is not an island server. Aborting.");
            clearFreezeTask();
            return;
        }

        if (currentPlayerCount.get() > 0) {
            LOGGER.info("Auto-freeze execution check: Players are currently online ({}). Aborting freeze attempt.", currentPlayerCount.get());
            clearFreezeTask();
            return;
        }

        String creatorUuid = SkyBlockMod.getCreatorUuid();
        if (creatorUuid == null || creatorUuid.trim().isEmpty()) {
            LOGGER.error("Auto-freeze execution: Creator UUID is not configured for this island server. Cannot freeze.");
            clearFreezeTask();
            return;
        }

        LOGGER.info("Auto-freeze: Attempting to freeze island for creator UUID: {}. Attempt {}/{}",
                creatorUuid, freezeRetryCount.get() + 1, MAX_FREEZE_RETRIES);

        String apiUrl = Config.getApiBaseUrl() + "islands/" + creatorUuid + "/freeze";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                .build();

        try {
            // Using synchronous send here as this is running in its own scheduled thread.
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();

            if (statusCode >= 200 && statusCode < 300) { // Success (200 OK, 202 Accepted etc.)
                LOGGER.info("Auto-freeze API call for island {} successful. Status: {}", creatorUuid, statusCode);
                clearFreezeTask();
            } else if (statusCode == 404) { // Island not found or not in a freezable state
                LOGGER.warn("Auto-freeze API call for island {} returned 404. Island may not exist or is not running. No further retries for this instance. Body: {}", creatorUuid, response.body());
                clearFreezeTask();
            } else { // Other errors
                LOGGER.error("Auto-freeze API call for island {} failed. Status: {}, Body: {}", creatorUuid, statusCode, response.body());
                handleFreezeRetry(creatorUuid);
            }
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Exception during auto-freeze API call for island {}: {}", creatorUuid, e.getMessage(), e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // Restore interrupt status
            }
            handleFreezeRetry(creatorUuid);
        }
    }

    private static void handleFreezeRetry(String creatorUuid) {
        if (freezeRetryCount.incrementAndGet() < MAX_FREEZE_RETRIES) {
            if (currentPlayerCount.get() == 0) { // Only retry if still no players
                LOGGER.info("Auto-freeze for island {} failed. Retrying in {} minute(s). Attempt {}/{}",
                        creatorUuid, RETRY_DELAY_MINUTES, freezeRetryCount.get(), MAX_FREEZE_RETRIES);
                // Schedule the same logic to run again
                freezeTask = scheduler.schedule(PlayerEventHandler::executeFreezeLogic, RETRY_DELAY_MINUTES, TimeUnit.MINUTES);
            } else {
                LOGGER.info("Auto-freeze for island {} failed, but players came back online. Cancelling further retries for this sequence.", creatorUuid);
                clearFreezeTask();
            }
        } else {
            LOGGER.error("Auto-freeze for island {} failed after {} retries. Giving up for this 0-player instance.", creatorUuid, MAX_FREEZE_RETRIES);
            clearFreezeTask();
        }
    }
    
    private static void clearFreezeTask() {
        freezeTask = null;
        freezeRetryCount.set(0); // Reset for any new 0-player sequence in the future
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Server stopping. Shutting down auto-freeze scheduler and cancelling any pending tasks.");
        if (freezeTask != null && !freezeTask.isDone()) {
            freezeTask.cancel(true); // Attempt to interrupt if running
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.error("Auto-freeze scheduler did not terminate cleanly.");
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Auto-freeze scheduler shut down complete.");
    }
}
