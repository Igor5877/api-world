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

            // Двостороння синхронізація: дії гравців у GUI FTB Teams
            // (прийняти запрошення, вийти, кік) прокидаються в API.
            // API лишається джерелом правди: якщо він відмовив — стан FTB
            // повертається до API-стану реконсиляцією.
            TeamEvent.PLAYER_JOINED_PARTY.register(NestworldTeamsAddon::onFtbPartyJoin);
            TeamEvent.PLAYER_LEFT_PARTY.register(NestworldTeamsAddon::onFtbPartyLeave);
        });
    }

    /** Гравець приєднався до FTB-паті через GUI → вступ у API-команду. */
    private static void onFtbPartyJoin(dev.ftb.mods.ftbteams.api.event.PlayerJoinedPartyTeamEvent e) {
        if (SYNC_SERVICE.isReconciling()) {
            return; // це наша власна синхронізація API -> FTB
        }
        UUID joined = e.getPlayer().getUUID();
        UUID partyOwner = e.getTeam().getOwner();
        if (joined.equals(partyOwner)) {
            return; // створення паті власником — не вступ
        }
        LOGGER.info("FTB GUI: {} joined the party of {} — forwarding to the API.", joined, partyOwner);
        API_CLIENT.getMyTeam(partyOwner).thenAccept(r -> {
            if (!r.isSuccess()) {
                LOGGER.warn("FTB GUI join: owner {} has no API team (HTTP {}) — reverting.", partyOwner, r.status());
                resyncTeamOf(partyOwner);
                return;
            }
            try {
                TeamState apiState = TeamState.fromJson(r.json());
                if (apiState.isMember(joined)) {
                    return; // вже в API-команді (синхронізація з іншого сервера)
                }
                API_CLIENT.forceJoin(apiState.teamId(), joined).thenAccept(res -> {
                    if (res.isSuccess()) {
                        LOGGER.info("FTB GUI: {} joined API team {}.", joined, apiState.teamId());
                    } else {
                        LOGGER.warn("FTB GUI: API refused join of {} to team {} (HTTP {}): {} — reverting.",
                                joined, apiState.teamId(), res.status(), res.body());
                        notifyPlayer(joined, "Не вдалося приєднатися до команди: " + apiDetail(res));
                        resyncTeamOf(partyOwner);
                    }
                });
            } catch (Exception ex) {
                LOGGER.error("FTB GUI join: malformed my_team response for {}", partyOwner, ex);
            }
        });
    }

    /** Гравець вийшов / був вигнаний із FTB-паті через GUI → вихід з API-команди. */
    private static void onFtbPartyLeave(dev.ftb.mods.ftbteams.api.event.PlayerLeftPartyTeamEvent e) {
        if (SYNC_SERVICE.isReconciling()) {
            return;
        }
        if (e.getTeamDeleted()) {
            // Розпуск паті НЕ транслюємо як масовий вихід — API-команда
            // лишається джерелом правди, паті буде відтворена реконсиляцією.
            LOGGER.warn("FTB GUI: party of {} was disbanded locally; the API team is untouched "
                    + "and the party will be recreated on the next sync.", e.getTeam().getOwner());
            return;
        }
        UUID left = e.getPlayerId();
        UUID partyOwner = e.getTeam().getOwner();
        if (left.equals(partyOwner)) {
            return; // власник не виходить (FTB вимагає передати володіння)
        }
        LOGGER.info("FTB GUI: {} left the party of {} — forwarding to the API.", left, partyOwner);
        API_CLIENT.getMyTeam(left).thenAccept(r -> {
            if (!r.isSuccess()) {
                return; // гравець і так не в API-команді
            }
            try {
                TeamState apiState = TeamState.fromJson(r.json());
                if (!apiState.ownerUuid().equals(partyOwner) || !apiState.isMember(left)) {
                    return; // інша команда або вже не член — нічого знімати
                }
                API_CLIENT.leaveTeam(apiState.teamId(), left).thenAccept(res -> {
                    if (res.isSuccess()) {
                        LOGGER.info("FTB GUI: {} left API team {}.", left, apiState.teamId());
                    } else {
                        LOGGER.warn("FTB GUI: API refused leave of {} from team {} (HTTP {}): {} — reverting.",
                                left, apiState.teamId(), res.status(), res.body());
                        resyncTeamOf(partyOwner);
                    }
                });
            } catch (Exception ex) {
                LOGGER.error("FTB GUI leave: malformed my_team response for {}", left, ex);
            }
        });
    }

    /** Повертає FTB-паті до API-стану команди власника (revert невдалих GUI-дій). */
    private static void resyncTeamOf(UUID ownerUuid) {
        API_CLIENT.getMyTeam(ownerUuid).thenAccept(r -> {
            if (!r.isSuccess()) {
                return;
            }
            try {
                TeamState state = TeamState.fromJson(r.json());
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    server.execute(() -> SYNC_SERVICE.reconcileTeam(server, state));
                }
            } catch (Exception ex) {
                LOGGER.error("Failed to resync team of {}", ownerUuid, ex);
            }
        });
    }

    private static void notifyPlayer(UUID playerUuid, String message) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerUuid);
            if (player != null) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message)
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
        });
    }

    private static String apiDetail(TeamApiClient.ApiResult r) {
        try {
            var json = r.json();
            if (json.has("detail")) {
                return json.get("detail").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "HTTP " + r.status();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!isIslandServer()) {
            LOGGER.info("Nestworld Teams Addon running on a HUB server; per-player team sync on login, "
                    + "auto-claim inactive.");
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
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        MinecraftServer server = event.getEntity().getServer();
        if (server == null) {
            return;
        }
        if (isIslandServer()) {
            server.execute(() -> {
                SYNC_SERVICE.reconcile(server);
                UUID owner = ownerUuid();
                if (owner != null) {
                    CLAIM_SERVICE.autoClaimIfNeeded(server, owner);
                }
            });
            return;
        }
        // Хаб: підтягуємо команду гравця з API, щоб GUI FTB Teams показував
        // реальний склад (без цього кожен на спавні виглядав "соло").
        UUID playerUuid = event.getEntity().getUUID();
        API_CLIENT.getMyTeam(playerUuid).thenAccept(r -> {
            if (!r.isSuccess()) {
                return; // гравець без команди — лишається в personal team FTB
            }
            try {
                TeamState state = TeamState.fromJson(r.json());
                if (state.isSolo()) {
                    return;
                }
                server.execute(() -> SYNC_SERVICE.reconcileTeam(server, state));
            } catch (Exception ex) {
                LOGGER.error("Hub team sync failed for {}", playerUuid, ex);
            }
        });
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
