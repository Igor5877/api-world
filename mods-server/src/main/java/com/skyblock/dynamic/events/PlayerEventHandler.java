package com.skyblock.dynamic.events;

import com.skyblock.dynamic.SkyBlockMod;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Handles player events.
 */
public class PlayerEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> freezeTask;

    private static final Method CACHED_GET_PLAYER_COUNT;
    static {
        Method m = null;
        try { m = MinecraftServer.class.getMethod("getPlayerCount"); }
        catch (Exception ignored) { }
        CACHED_GET_PLAYER_COUNT = m;
    }

    /**
     * Handles the player login event.
     *
     * @param event The player login event.
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (freezeTask != null && !freezeTask.isDone()) {
            freezeTask.cancel(false);
            LOGGER.info("Player logged in. Canceled scheduled island freeze.");
        }
    }

    /**
     * Handles the player logout event.
     *
     * @param event The player logout event.
     */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MinecraftServer server = null;
        try {
            server = event.getEntity().getServer();
        } catch (NoSuchMethodError e) {
            server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        }

        int playerCount = -1;
        if (server != null) {
            try {
                playerCount = server.getPlayerCount();
            } catch (NoSuchMethodError e) {
                try {
                    if (CACHED_GET_PLAYER_COUNT != null) {
                        playerCount = (int) CACHED_GET_PLAYER_COUNT.invoke(server);
                    } else {
                        playerCount = server.getPlayerList().getPlayerCount();
                    }
                } catch (Exception ex) {
                    playerCount = server.getPlayerList().getPlayerCount();
                }
            }
        }

        if (server != null && playerCount - 1 <= 0) {
            if (SkyBlockMod.isIslandServer() && SkyBlockMod.hasPlayerJoinedWithinFirstHour()) {
                LOGGER.info("Last player logged out. Scheduling island freeze in 5 minutes.");
                scheduleFreezeTask(SkyBlockMod.getOwnerUuid());
            } else if (SkyBlockMod.isIslandServer()) {
                LOGGER.info("Last player logged out, but no player joined within the first hour. Auto-freeze is disabled.");
            }
        }
    }

    /**
     * Schedules a task to freeze the island.
     *
     * @param ownerUuidStr The UUID of the island owner.
     */
    private void scheduleFreezeTask(String ownerUuidStr) {
        if (ownerUuidStr == null) {
            LOGGER.error("Cannot schedule freeze task: owner UUID is null.");
            return;
        }

        Runnable task = () -> {
            try {
                UUID ownerUuid = UUID.fromString(ownerUuidStr);
                NestworldModsServer.ISLAND_PROVIDER.sendFreeze(ownerUuid)
                    .orTimeout(10, TimeUnit.SECONDS)
                    .thenRun(() -> LOGGER.info("Island frozen successfully for owner: {}", ownerUuidStr))
                    .exceptionally(ex -> {
                        LOGGER.error("Freeze failed or timed out for owner {}: {}", ownerUuidStr, ex.getMessage());
                        return null;
                    });
            } catch (IllegalArgumentException e) {
                LOGGER.error("Cannot send freeze request: owner UUID '{}' is not a valid UUID.", ownerUuidStr, e);
            }
        };

        freezeTask = scheduler.schedule(task, 5, TimeUnit.MINUTES);
    }
}
