package com.skyblock.dynamic;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import com.skyblock.dynamic.utils.IslandContext;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Function;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * The main class for the SkyBlock mod.
 */
@Mod(SkyBlockMod.MODID)
public class SkyBlockMod {
    /** The mod ID. */
    public static final String MODID = "skyblock";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static IslandContext islandContext = IslandContext.getDefault();
    private static long serverStartTime = 0L;
    private static boolean playerJoinedWithinFirstHour = false;
    private static com.skyblock.dynamic.utils.IslandWebSocketClient webSocketClient;

    /** Унікальний ідентифікатор цього runtime-запуску (генерується раз на JVM
     *  boot) — діагностичне поле в heartbeat/shutting_down, не використовується
     *  для маршрутизації (WS client_id лишається owner_uuid-based, бо в
     *  api-world 1 острів = 1 контейнер = 1 owner, структурно). */
    private static final String INSTANCE_ID = java.util.UUID.randomUUID().toString();

    /** Heartbeat для API-watchdog: шлеться з server tick loop, тож зависання
     *  server thread автоматично означає тишу і реакцію watchdog. Інтервал —
     *  конфігурований (Config.getHeartbeatIntervalSeconds()), не константа. */
    private long lastHeartbeatAtMs = 0L;
    private long heartbeatSequence = 0L;

    /** Backoff для reconnect-спроб, коли WS недоступний тривалий час — росте
     *  експоненційно (5с→10с→20с→30с-cap) замість фіксованих 30с завжди,
     *  скидається на успішне з'єднання. */
    private static final long RECONNECT_BACKOFF_INITIAL_MS = 5_000L;
    private static final long RECONNECT_BACKOFF_CAP_MS = 30_000L;
    private long reconnectBackoffMs = RECONNECT_BACKOFF_INITIAL_MS;
    private long lastReconnectAttemptMs = 0L;

    /** Скільки тіків чекати після ServerStartedEvent, перш ніж слати ready-сигнал.
     *  Forge на важких модпаках ще кілька секунд після цієї події виконує фонову
     *  ініціалізацію моду і сам ще не відкрив прийом гравців ("Server is still
     *  starting!") — тому шлемо сигнал трохи пізніше, а не миттєво в onServerStarted. */
    private static final int READY_SIGNAL_DELAY_TICKS = 40; // ~2с при 20 тіках/с
    private boolean readySignalPending = false;
    private int ticksSinceServerStarted = 0;

    /** Warm spare: finished booting and waiting to be claimed — reported to
     *  the API as `spare_ready` over WS (the API then freezes the container). */
    private boolean spareReady = false;
    /** Where skyblock_island_data.toml lives; a claim rewrites it so a later
     *  restart boots straight into the claimed owner. */
    private static Path islandDataPath;
    private static SkyBlockMod instance;

    /**
     * The constructor for the SkyBlock mod.
     */
    public SkyBlockMod() {
        instance = this;
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new com.skyblock.dynamic.events.PlayerEventHandler());
        modEventBus.addListener(this::onModConfigEvent);
        FMLJavaModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, MODID + "-common.toml");
    }

    /**
     * Handles the common setup event.
     *
     * @param event The common setup event.
     */
    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("SkyBlockMod: Common Setup Initialized.");

        event.enqueueWork(() -> {
            if (ModList.get().isLoaded("realmarket")) {
                try {
                    Class<?> apiClass = Class.forName("RealMarket.realmarket.api.MarketIslandApi");
                    Method registerMethod = apiClass.getMethod("registerProvider", Function.class);
                    
                    Function<UUID, UUID> provider = (playerUuid) -> {
                        if (com.skyblock.dynamic.nestworld.mods.NestworldModsServer.ISLAND_PROVIDER.isThisAnIslandServer()) {
                            String ownerUuidStr = com.skyblock.dynamic.nestworld.mods.NestworldModsServer.ISLAND_PROVIDER.getCurrentServerOwnerUuid();
                            if (ownerUuidStr != null && !ownerUuidStr.isEmpty()) {
                                try {
                                    return UUID.fromString(ownerUuidStr);
                                } catch (IllegalArgumentException e) {
                                    // Invalid UUID format
                                }
                            }
                        }
                        return com.skyblock.dynamic.nestworld.mods.NestworldModsServer.ISLAND_PROVIDER.getCachedTeamId(playerUuid);
                    };
                    
                    registerMethod.invoke(null, provider);
                    LOGGER.info("Successfully registered Island Provider with RealMarket.");
                } catch (Exception e) {
                    LOGGER.error("Failed to register Island Provider with RealMarket", e);
                }
            }
        });
    }

    /**
     * Handles the mod configuration event.
     *
     * @param configEvent The mod configuration event.
     */
    public void onModConfigEvent(final ModConfigEvent configEvent) {
        if (configEvent.getConfig().getSpec() == Config.SPEC) {
            Config.bake();
            LOGGER.info("SkyBlockMod: Common configuration reloaded from skyblock-common.toml.");
        }
    }

    /**
     * Handles the server about to start event.
     *
     * @param event The server about to start event.
     */
    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        serverStartTime = System.currentTimeMillis();
        playerJoinedWithinFirstHour = false;
        
        // Fix for NoSuchMethodError in some mappings environments (m_6237_())
        // Using Path to get the current working directory which is the server directory
        Path serverBasePath = java.nio.file.Paths.get("").toAbsolutePath();
        try {
            // Attempt to use the server directory if available, otherwise fallback to CWD
            java.io.File serverDir = event.getServer().getFile("");
            if (serverDir != null) {
                 serverBasePath = serverDir.toPath();
            }
        } catch (NoSuchMethodError | Exception e) {
             LOGGER.warn("Could not get server directory via getServer().getFile(\"\"). Using current working directory instead: " + serverBasePath);
        }
        
        loadIslandContextData(serverBasePath);

        if (islandContext.isIslandServer()) {
            LOGGER.info("SkyBlockMod: Island server detected. Synchronizing team data...");
            UUID ownerUuid = UUID.fromString(islandContext.getOwnerUuid());
            NestworldModsServer.ISLAND_PROVIDER.refreshAndGetTeamId(ownerUuid);
        }
    }

    /**
     * Handles the server started event.
     *
     * @param event The server started event.
     */
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (islandContext.isIslandServer()) {
            LOGGER.info("SkyBlockMod: Server started. Running as an ISLAND SERVER. Owner UUID: {}", islandContext.getOwnerUuid());
            ticksSinceServerStarted = 0;
            readySignalPending = true;
            initializeWebSocket();
        } else if (islandContext.isSpare()) {
            LOGGER.info("SkyBlockMod: Server started as WARM SPARE (island {}). Waiting for a claim.",
                    islandContext.getSpareIslandId());
            ticksSinceServerStarted = 0;
            readySignalPending = true;
            initializeWebSocket();
        } else {
            LOGGER.info("SkyBlockMod: Server started. Running as a HUB SERVER.");
        }
    }

    /**
     * Handles the server stopping event.
     *
     * @param event The server stopping event.
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        // Final quest progress upload before the island goes down.
        com.skyblock.dynamic.utils.QuestProgressSync.uploadIslandProgress(event.getServer(), true);
        if (webSocketClient != null && webSocketClient.isOpen()) {
            // Кажемо API, що це штатна зупинка — watchdog не запише її як краш.
            com.google.gson.JsonObject bye = new com.google.gson.JsonObject();
            bye.addProperty("type", "shutting_down");
            bye.addProperty("instance_id", INSTANCE_ID);
            webSocketClient.sendJsonBlocking(bye, 2000L);
            LOGGER.info("SkyBlockMod: Sent shutting_down signal, closing WebSocket connection.");
            webSocketClient.close();
        }
    }

    /**
     * Heartbeat до API кожні ~30с прямо з server tick loop. Якщо WS-з'єднання
     * розірване — перепідключаємось (раніше reconnect не існував узагалі).
     */
    @SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (!islandContext.isIslandServer() && !islandContext.isSpare()) return;

        if (readySignalPending) {
            ticksSinceServerStarted++;
            if (ticksSinceServerStarted >= READY_SIGNAL_DELAY_TICKS) {
                readySignalPending = false;
                if (islandContext.isSpare()) {
                    spareReady = true;
                    lastHeartbeatAtMs = 0L; // report spare_ready with the very next heartbeat
                } else {
                    sendIslandReadyForPlayersSignal();
                }
            }
        }

        long now = System.currentTimeMillis();

        // Reconnect on its own cadence (exponential backoff, independent of
        // the heartbeat interval) — previously gated behind the same 30s
        // heartbeat check, so a fixed 30s was the only possible retry rate.
        if (webSocketClient == null || !webSocketClient.isOpen()) {
            if (now - lastReconnectAttemptMs >= reconnectBackoffMs) {
                lastReconnectAttemptMs = now;
                LOGGER.warn("SkyBlockMod: WebSocket is not open — attempting to reconnect (backoff {}ms).", reconnectBackoffMs);
                initializeWebSocket();
                reconnectBackoffMs = Math.min(reconnectBackoffMs * 2, RECONNECT_BACKOFF_CAP_MS);
            }
            return;
        }
        reconnectBackoffMs = RECONNECT_BACKOFF_INITIAL_MS; // connected — reset for next time it's needed

        long heartbeatIntervalMs = Config.getHeartbeatIntervalSeconds() * 1000L;
        if (now - lastHeartbeatAtMs < heartbeatIntervalMs) return;
        lastHeartbeatAtMs = now;

        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        double avgTickMs = server.getAverageTickTime();
        double tps = Math.min(20.0, 1000.0 / Math.max(avgTickMs, 0.001));

        com.google.gson.JsonObject heartbeat = new com.google.gson.JsonObject();
        heartbeat.addProperty("type", "heartbeat");
        heartbeat.addProperty("instance_id", INSTANCE_ID);
        heartbeat.addProperty("owner_uuid", islandContext.getOwnerUuid());
        heartbeat.addProperty("timestamp", java.time.Instant.now().toString());
        heartbeat.addProperty("sequence", ++heartbeatSequence);
        heartbeat.addProperty("uptime", now - serverStartTime);
        heartbeat.addProperty("tps", Math.round(tps * 100.0) / 100.0);
        heartbeat.addProperty("mspt", Math.round(avgTickMs * 100.0) / 100.0);
        heartbeat.addProperty("players", server.getPlayerCount());
        webSocketClient.sendJson(heartbeat);

        // Repeated with every heartbeat until claimed: the API side is
        // idempotent, and a reconnect after a lost message still gets through.
        if (islandContext.isSpare() && spareReady) {
            com.google.gson.JsonObject ready = new com.google.gson.JsonObject();
            ready.addProperty("type", "spare_ready");
            ready.addProperty("instance_id", INSTANCE_ID);
            webSocketClient.sendJson(ready);
        }
    }

    /**
     * Initializes the WebSocket client.
     */
    private void initializeWebSocket() {
        String identity;
        if (islandContext.isIslandServer() && islandContext.getOwnerUuid() != null) {
            identity = islandContext.getOwnerUuid();
        } else if (islandContext.isSpare()) {
            // No owner yet — the API resolves core_spare-<id> to the spare's island row.
            identity = "spare-" + islandContext.getSpareIslandId();
        } else {
            return;
        }
        try {
            // "core_" — щоб не ділити client_id ні з голим-uuid конектом Velocity
            // (/myisland), ні з "island_"-конектом RealMarket (MarketSyncManager).
            // Спільний client_id призводить до того, що ConnectionManager на
            // API вибиває старе з'єднання при новому — TEAM_UPDATED/heartbeat/
            // shutting_down губляться без ретраю.
            String wsUrl = Config.getApiBaseUrl().replaceFirst("http", "ws") + "ws/core_" + identity;
            webSocketClient = new com.skyblock.dynamic.utils.IslandWebSocketClient(new URI(wsUrl), identity);
            LOGGER.info("SkyBlockMod: Attempting to connect to WebSocket at {}", wsUrl);
            webSocketClient.connect();
        } catch (URISyntaxException e) {
            LOGGER.error("SkyBlockMod: Invalid WebSocket URI syntax.", e);
        }
    }

    /**
     * Sends a signal to the API that the island is ready for players.
     */
    private void sendIslandReadyForPlayersSignal() {
        if (!islandContext.isIslandServer() || islandContext.getOwnerUuid() == null) {
            LOGGER.warn("SkyBlockMod: Attempted to send ready signal, but not an island server or UUID is missing.");
            return;
        }

        String ownerUuidStr = islandContext.getOwnerUuid();
        try {
            UUID ownerUuid = UUID.fromString(ownerUuidStr);
            com.skyblock.dynamic.nestworld.mods.NestworldModsServer.ISLAND_PROVIDER.sendReady(ownerUuid)
                .thenRun(() -> LOGGER.info("SkyBlockMod: Successfully sent 'island ready' signal for owner: {}", ownerUuidStr))
                .exceptionally(ex -> {
                    LOGGER.error("SkyBlockMod: Failed to send 'island ready' signal for owner: {}", ownerUuidStr, ex);
                    return null;
                });
        } catch (IllegalArgumentException e) {
            LOGGER.error("SkyBlockMod: Owner UUID '{}' is not a valid UUID. Cannot send ready signal.", ownerUuidStr, e);
        }
    }

    /**
     * Loads the island context data from the configuration file.
     *
     * @param serverBasePath The base path of the server.
     */
    private void loadIslandContextData(Path serverBasePath) {
        islandDataPath = serverBasePath.resolve("world").resolve("serverconfig").resolve("skyblock_island_data.toml");
        LOGGER.info("SkyBlockMod: Attempting to load island context from: {}", islandDataPath.toString());

        if (!islandDataPath.toFile().exists()) {
            LOGGER.warn("SkyBlockMod: skyblock_island_data.toml not found. Using default context (not an island server).");
            islandContext = IslandContext.getDefault();
            return;
        }

        try (CommentedFileConfig config = CommentedFileConfig.builder(islandDataPath).sync().autosave().writingMode(WritingMode.REPLACE).build()) {
            config.load();

            boolean isIsland = config.getOptional("is_island_server").map(obj -> Boolean.parseBoolean(String.valueOf(obj))).orElse(false);
            String ownerUuid = config.getOptional("owner_uuid").map(String::valueOf).orElse(null);
            boolean isSpare = config.getOptional("spare").map(obj -> Boolean.parseBoolean(String.valueOf(obj))).orElse(false);
            Integer spareIslandId = config.getOptional("spare_island_id")
                    .map(obj -> Integer.parseInt(String.valueOf(obj))).orElse(null);

            if (isSpare && spareIslandId != null) {
                islandContext = IslandContext.spare(spareIslandId);
                LOGGER.info("SkyBlockMod: Loaded WARM SPARE context (island {}) — no owner until claimed.", spareIslandId);
            } else if (isIsland &&(ownerUuid == null || ownerUuid.trim().isEmpty())) {
                LOGGER.error("SkyBlockMod: skyblock_island_data.toml indicates this is an island server, but 'owner_uuid' is missing or empty. Treating as HUB.");
                islandContext = IslandContext.getDefault();
            } else if (isIsland) {
                try {
                    UUID.fromString(ownerUuid.trim());
                    islandContext = new IslandContext(true, ownerUuid.trim());
                    LOGGER.info("SkyBlockMod: Successfully loaded island context: isIslandServer={}, ownerUuid={}", isIsland, ownerUuid);
                } catch (IllegalArgumentException e) {
                    LOGGER.error("SkyBlockMod: 'owner_uuid' in skyblock_island_data.toml is not a valid UUID: {}. Treating as HUB.", ownerUuid, e);
                    islandContext = IslandContext.getDefault();
                }
            } else {
                islandContext = new IslandContext(false, ownerUuid != null ? ownerUuid.trim() : null);
                LOGGER.info("SkyBlockMod: Successfully loaded island context: isIslandServer=false.");
            }
        } catch (Exception e) {
            LOGGER.error("SkyBlockMod: Failed to load or parse skyblock_island_data.toml. Using default context.", e);
            islandContext = IslandContext.getDefault();
        }
    }

    /**
     * The API handed this warm spare to a player's team (WS "claim"). Turns the
     * running server into that team's island without a restart: persists the
     * island data (a later restart boots as this owner), switches the context,
     * restarts the first-hour auto-freeze window (the spare may have waited
     * for hours), loads the team (FTB team/quests via TeamDataUpdatedEvent),
     * reconnects the WS as core_&lt;owner&gt; and then sends the usual /ready.
     * Must run on the server thread. Idempotent: a resent claim for the
     * same owner only re-sends /ready.
     */
    public static void handleClaim(com.google.gson.JsonObject claim) {
        if (instance == null) return;
        String ownerUuid = claim.get("owner_uuid").getAsString();
        try {
            UUID.fromString(ownerUuid);
        } catch (IllegalArgumentException e) {
            LOGGER.error("SkyBlockMod: claim with invalid owner_uuid '{}' ignored.", ownerUuid);
            return;
        }
        if (!islandContext.isSpare()) {
            if (ownerUuid.equals(islandContext.getOwnerUuid())) {
                LOGGER.info("SkyBlockMod: claim for current owner {} resent — re-sending ready.", ownerUuid);
                instance.sendIslandReadyForPlayersSignal();
            } else {
                LOGGER.warn("SkyBlockMod: claim for {} ignored — this server already belongs to {}.",
                        ownerUuid, islandContext.getOwnerUuid());
            }
            return;
        }

        StringBuilder members = new StringBuilder();
        if (claim.has("member_uuids")) {
            for (var element : claim.getAsJsonArray("member_uuids")) {
                if (members.length() > 0) members.append(", ");
                members.append('"').append(element.getAsString()).append('"');
            }
        }
        String toml = "is_island_server = true\n"
                + "team_id = " + claim.get("team_id").getAsInt() + "\n"
                + "owner_uuid = \"" + ownerUuid + "\"\n"
                + "member_uuids = [" + members + "]\n";
        try {
            java.nio.file.Files.createDirectories(islandDataPath.getParent());
            Path tmp = islandDataPath.resolveSibling(islandDataPath.getFileName() + ".tmp");
            java.nio.file.Files.writeString(tmp, toml);
            java.nio.file.Files.move(tmp, islandDataPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            // Keep going: the claim still works for this run, and the API's
            // fallback rewrites the file if /ready never arrives.
            LOGGER.error("SkyBlockMod: failed to persist island data for claim.", e);
        }

        LOGGER.info("SkyBlockMod: WARM SPARE {} claimed by owner {}.", islandContext.getSpareIslandId(), ownerUuid);
        islandContext = new IslandContext(true, ownerUuid);
        serverStartTime = System.currentTimeMillis();
        playerJoinedWithinFirstHour = false;
        instance.spareReady = false;

        if (webSocketClient != null) {
            webSocketClient.close();
            webSocketClient = null; // the tick loop reconnects as core_<owner>
        }
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        java.util.concurrent.CompletableFuture
                .runAsync(() -> NestworldModsServer.ISLAND_PROVIDER.refreshAndGetTeamId(UUID.fromString(ownerUuid)))
                .whenComplete((ok, ex) -> {
                    if (ex != null) LOGGER.error("SkyBlockMod: team sync after claim failed.", ex);
                    // /ready only after the team is loaded, so the player lands in a set-up island.
                    server.execute(() -> {
                        instance.ticksSinceServerStarted = 0;
                        instance.readySignalPending = true;
                    });
                });
    }

    /**
     * Checks if the server is an island server.
     *
     * @return True if the server is an island server, false otherwise.
     */
    public static boolean isIslandServer() {
        return islandContext.isIslandServer();
    }

    /**
     * Gets the UUID of the island owner.
     *
     * @return The UUID of the island owner.
     */
    public static String getOwnerUuid() {
        return islandContext.getOwnerUuid();
    }

    /**
     * Checks if a player has joined within the first hour of the server starting.
     *
     * @return True if a player has joined within the first hour, false otherwise.
     */
    public static boolean hasPlayerJoinedWithinFirstHour() {
        return playerJoinedWithinFirstHour;
    }

    /**
     * Handles the player login event.
     *
     * @param event The player login event.
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (isIslandServer() && !playerJoinedWithinFirstHour) {
            long now = System.currentTimeMillis();
            if (now - serverStartTime <= 3600000L) { // 1 hour
                playerJoinedWithinFirstHour = true;
                LOGGER.info("First player joined within one hour of server start. Auto-freeze is now enabled.");
            }
        }
        // The refresh is now handled by getCachedTeamId on demand, so this explicit call is no longer needed.
    }

    /**
     * Handles client-side mod events.
     */
    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        /**
         * Handles the client setup event.
         *
         * @param event The client setup event.
         */
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("SkyBlockMod: Client Setup");
        }
    }
}
