package com.skyblock.dynamic.events;

import com.skyblock.dynamic.SkyBlockMod;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

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
                    java.lang.reflect.Method getPlayerCountMethod = server.getClass().getMethod("getPlayerCount");
                    playerCount = (int) getPlayerCountMethod.invoke(server);
                } catch (Exception ex) {
                    playerCount = server.getPlayerList().getPlayerCount(); // Another potential fallback
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
                    .thenRun(() -> LOGGER.info("Successfully sent island freeze request for owner: {}", ownerUuidStr))
                    .exceptionally(ex -> {
                        LOGGER.error("Failed to send island freeze request for owner: {}", ownerUuidStr, ex);
                        return null;
                    });
            } catch (IllegalArgumentException e) {
                LOGGER.error("Cannot send freeze request: owner UUID '{}' is not a valid UUID.", ownerUuidStr, e);
            }
        };

        freezeTask = scheduler.schedule(task, 5, TimeUnit.MINUTES);
    }
}
