package com.skyblock.dynamic.events;

import com.skyblock.dynamic.SkyBlockMod;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
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
    // Daemon: відкладена заморозка — лише таймер; недаемонний потік тримав JVM
    // після зупинки сервера до SIGKILL wrapper'а (2026-09-30, thread dump).
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "NestWorld-freeze-scheduler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> freezeTask;
    /** Incremented on every login — lets an already-running freeze task
     *  recognize it was superseded even though cancel(false) couldn't stop
     *  it (freeze race protection, mods-server spec §28). */
    private final java.util.concurrent.atomic.AtomicLong freezeGeneration = new java.util.concurrent.atomic.AtomicLong(0);

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
        freezeGeneration.incrementAndGet();
        if (freezeTask != null && !freezeTask.isDone()) {
            freezeTask.cancel(false);
            LOGGER.info("Player logged in. Canceled scheduled island freeze.");
        }
        // Hub/spawn only: pull the player's island quest progress for read-only display.
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && serverPlayer.getServer() != null) {
            com.skyblock.dynamic.utils.QuestProgressSync.fetchAndApply(serverPlayer.getServer(), serverPlayer);
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
            } catch (Throwable e) {
                // NoSuchMethodError — це Error, не Exception: якщо його не
                // зловити тут, logout гравця валить увесь сервер.
                try {
                    if (CACHED_GET_PLAYER_COUNT != null) {
                        playerCount = (int) CACHED_GET_PLAYER_COUNT.invoke(server);
                    } else {
                        playerCount = server.getPlayerList().getPlayerCount();
                    }
                } catch (Throwable ex) {
                    LOGGER.warn("Could not determine player count on logout; assuming last player left.", ex);
                }
            }
        }

        // Island only: push the freshest quest progress to the API so the spawn
        // shows it when the player arrives there a few seconds later.
        if (server != null) {
            com.skyblock.dynamic.utils.QuestProgressSync.uploadIslandProgress(server, false);
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
     * The hub/spawn server's OVERWORLD is a safe zone: no PvP, no fall
     * damage, no mob damage, nothing. Islands are unaffected — players can
     * still die there. Other dimensions reachable from spawn (e.g. an End
     * portal placed in the hub) are real gameplay areas and must NOT
     * inherit the safe-zone rule just because the backend server itself is
     * "spawn" — only the Overworld dimension is the actual safe zone.
     *
     * @param event The living-hurt event.
     */
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || SkyBlockMod.isIslandServer()) {
            return;
        }
        if (player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return; // real gameplay dimension (End/Nether/custom) — damage applies normally
        }
        event.setCanceled(true);
        if (event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) {
            // Cancelling the damage alone leaves them falling forever —
            // put them back on solid ground instead.
            BlockPos spawn = player.level().getSharedSpawnPos();
            player.teleportTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
            player.fallDistance = 0f;
        }
    }

    /**
     * /hubspawn — teleports the executing player to THIS server's Overworld
     * shared spawn point, crossing dimensions if needed (e.g. called from
     * The End via a portal placed in the hub). Exists so Velocity's /spawn
     * has something real to do when the player is already connected to the
     * spawn backend but not physically at the spawn point (previously a
     * silent no-op — "you are already on spawn" while standing in The End).
     *
     * @param event The command registration event.
     */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("hubspawn").executes(ctx -> {
                CommandSourceStack source = ctx.getSource();
                if (!(source.getEntity() instanceof ServerPlayer player)) {
                    source.sendFailure(Component.literal("Only players can use this command."));
                    return 0;
                }
                ServerLevel overworld = source.getServer().overworld();
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
                player.fallDistance = 0f;
                return 1;
            })
        );
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

        long myGeneration = freezeGeneration.get();
        Runnable task = () -> {
            // Re-check state at execution time — cancel(false) on login can't
            // interrupt a task that already started running (freeze race
            // protection, mods-server spec §28).
            if (freezeGeneration.get() != myGeneration) {
                LOGGER.info("Freeze task superseded (generation mismatch) — skipping.");
                return;
            }
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            int currentPlayers = server != null ? server.getPlayerCount() : -1;
            if (currentPlayers != 0) {
                LOGGER.info("Freeze task: {} player(s) online at execution time — skipping.", currentPlayers);
                return;
            }
            try {
                UUID ownerUuid = UUID.fromString(ownerUuidStr);
                // sendFreeze already retries transient failures internally
                // (capped exponential backoff, ~5 attempts) — no external
                // timeout here, it would just cut that retry chain short.
                NestworldModsServer.ISLAND_PROVIDER.sendFreeze(ownerUuid)
                    .thenRun(() -> LOGGER.info("Island frozen successfully for owner: {}", ownerUuidStr))
                    .exceptionally(ex -> {
                        LOGGER.error("Freeze failed for owner {}: {}", ownerUuidStr, ex.getMessage());
                        return null;
                    });
            } catch (IllegalArgumentException e) {
                LOGGER.error("Cannot send freeze request: owner UUID '{}' is not a valid UUID.", ownerUuidStr, e);
            }
        };

        freezeTask = scheduler.schedule(task, 5, TimeUnit.MINUTES);
    }
}
