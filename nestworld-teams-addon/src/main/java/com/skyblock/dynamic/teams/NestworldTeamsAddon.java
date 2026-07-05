package com.skyblock.dynamic.teams;

import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.events.TeamDataUpdatedEvent;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import com.skyblock.dynamic.teams.api.TeamApiClient;
import com.skyblock.dynamic.teams.net.NetworkHandler;
import com.skyblock.dynamic.teams.progress.QuestProgressReporter;
import com.skyblock.dynamic.teams.sync.ChunkClaimService;
import com.skyblock.dynamic.teams.sync.TeamState;
import com.skyblock.dynamic.teams.sync.TeamSyncService;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.event.TeamEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side bridge between the Nestworld API team system and FTB Teams /
 * FTB Chunks. See mods.toml description and api/API_TEAMS_TODO.md.
 */
@Mod(NestworldTeamsAddon.MOD_ID)
public class NestworldTeamsAddon {
    public static final String MOD_ID = "nestworld_teams";
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final TeamApiClient API_CLIENT = new TeamApiClient();
    public static final TeamSyncService SYNC_SERVICE = new TeamSyncService();
    public static final ChunkClaimService CLAIM_SERVICE = new ChunkClaimService();
    public static final QuestProgressReporter PROGRESS_REPORTER = new QuestProgressReporter();

    private int tickCounter = 0;

    public NestworldTeamsAddon() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TeamsAddonConfig.SPEC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            NetworkHandler.register();

            if (TeamsAddonConfig.LOCK_MANUAL_PARTY_MANAGEMENT.get()) {
                FTBTeamsAPI.api().setPartyCreationFromAPIOnly(true);
            }

            // Party guard: revert any membership change that did not come from the API.
            TeamEvent.PLAYER_JOINED_PARTY.register(e -> {
                if (SYNC_SERVICE.isReconciling() || !isIslandServer()) {
                    return;
                }
                TeamState state = SYNC_SERVICE.getLastState();
                UUID joined = e.getPlayer().getUUID();
                if (state != null && !state.isMember(joined)) {
                    LOGGER.warn("Player {} joined party outside API control; scheduling reconciliation.", joined);
                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server != null) {
                        server.execute(() -> SYNC_SERVICE.reconcile(server));
                    }
                }
            });
        });
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!isIslandServer()) {
            LOGGER.info("Nestworld Teams Addon running on a HUB server; party sync and auto-claim are inactive.");
            return;
        }
        UUID owner = ownerUuid();
        if (owner == null) {
            return;
        }
        // Blocking HTTP call — keep it off the server thread. processTeamData()
        // fires TeamDataUpdatedEvent, which drives reconciliation and auto-claim.
        CompletableFuture.runAsync(() -> NestworldModsServer.ISLAND_PROVIDER.refreshAndGetTeamId(owner));
    }

    @SubscribeEvent
    public void onTeamDataUpdated(TeamDataUpdatedEvent event) {
        SYNC_SERVICE.updateState(new TeamState(
                event.getTeamId(), event.getTeamName(), event.getOwnerUuid(), event.getMemberUuids()));
        if (!isIslandServer()) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> {
                SYNC_SERVICE.reconcile(server);
                CLAIM_SERVICE.autoClaimIfNeeded(server, event.getOwnerUuid());
            });
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!isIslandServer() || event.getEntity().level().isClientSide()) {
            return;
        }
        MinecraftServer server = event.getEntity().getServer();
        if (server != null) {
            server.execute(() -> {
                SYNC_SERVICE.reconcile(server);
                UUID owner = ownerUuid();
                if (owner != null) {
                    CLAIM_SERVICE.autoClaimIfNeeded(server, owner);
                }
            });
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isIslandServer()) {
            return;
        }
        if (++tickCounter % 200 != 0) { // every ~10 seconds; the reporter throttles itself further
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            PROGRESS_REPORTER.tick(server.getWorldPath(LevelResource.ROOT));
        }
    }

    private static boolean isIslandServer() {
        try {
            return NestworldModsServer.ISLAND_PROVIDER.isThisAnIslandServer();
        } catch (Throwable t) {
            return false;
        }
    }

    private static UUID ownerUuid() {
        try {
            String raw = NestworldModsServer.ISLAND_PROVIDER.getCurrentServerOwnerUuid();
            return raw == null ? null : UUID.fromString(raw);
        } catch (Exception e) {
            LOGGER.error("Island owner UUID is missing or malformed", e);
            return null;
        }
    }
}
