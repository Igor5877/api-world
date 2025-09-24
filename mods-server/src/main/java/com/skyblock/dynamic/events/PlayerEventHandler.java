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
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MinecraftServer server = event.getEntity().getServer();
        if (server != null && server.getPlayerCount() - 1 == 0) {
            if (SkyBlockMod.isIslandServer()) {
                LOGGER.info("Last player logged out. Scheduling island freeze in 10 minutes.");
                scheduleFreezeTask(SkyBlockMod.getCreatorUuid());
            }
        }
    }

    private void scheduleFreezeTask(String creatorUuidStr) {
        if (creatorUuidStr == null) {
            LOGGER.error("Cannot schedule freeze task: creator UUID is null.");
            return;
        }

        Runnable task = () -> {
            UUID creatorUuid = UUID.fromString(creatorUuidStr);
            NestworldModsServer.ISLAND_PROVIDER.sendFreeze(creatorUuid)
                .thenRun(() -> LOGGER.info("Successfully sent island freeze request for creator: {}", creatorUuidStr))
                .exceptionally(ex -> {
                    LOGGER.error("Failed to send island freeze request for creator: {}", creatorUuidStr, ex);
                    return null;
                });
        };

        freezeTask = scheduler.schedule(task, 10, TimeUnit.MINUTES);
    }
}
