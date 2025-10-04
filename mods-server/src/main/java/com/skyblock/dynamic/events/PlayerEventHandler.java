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

public class PlayerEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> freezeTask;

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (freezeTask != null && !freezeTask.isDone()) {
            freezeTask.cancel(false);
            LOGGER.info("Player logged in. Canceled scheduled island freeze.");
        }

        if (SkyBlockMod.isIslandServer() && !SkyBlockMod.hasPlayerJoinedWithinFirstHour()) {
            long uptime = System.currentTimeMillis() - SkyBlockMod.getServerStartTime();
            if (TimeUnit.MILLISECONDS.toHours(uptime) < 1) {
                SkyBlockMod.setPlayerJoinedWithinFirstHour(true);
                LOGGER.info("A player has joined within the first hour. The island is now eligible for auto-freeze.");
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MinecraftServer server = event.getEntity().getServer();
        if (server != null && server.getPlayerCount() - 1 == 0) {
            if (SkyBlockMod.isIslandServer() && SkyBlockMod.hasPlayerJoinedWithinFirstHour()) {
                LOGGER.info("Last player logged out. Scheduling island freeze in 5 minutes.");
                scheduleFreezeTask(SkyBlockMod.getOwnerUuid());
            } else {
                LOGGER.info("Last player logged out, but the island is not eligible for auto-freeze.");
            }
        }
    }

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
                LOGGER.error("Invalid UUID format for owner: {}", ownerUuidStr, e);
            }
        };

        freezeTask = scheduler.schedule(task, 5, TimeUnit.MINUTES);
    }
}