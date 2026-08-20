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

    /** Heartbeat для API-watchdog: шлеться з server tick loop, тож зависання
     *  server thread автоматично означає тишу і реакцію watchdog. */
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;
    private long lastHeartbeatAtMs = 0L;

    /** Скільки тіків чекати після ServerStartedEvent, перш ніж слати ready-сигнал.
     *  Forge на важких модпаках ще кілька секунд після цієї події виконує фонову
     *  ініціалізацію моду і сам ще не відкрив прийом гравців ("Server is still
     *  starting!") — тому шлемо сигнал трохи пізніше, а не миттєво в onServerStarted. */
    private static final int READY_SIGNAL_DELAY_TICKS = 40; // ~2с при 20 тіках/с
    private boolean readySignalPending = false;
    private int ticksSinceServerStarted = 0;

    /**
     * The constructor for the SkyBlock mod.
     */
    public SkyBlockMod() {
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
        if (!islandContext.isIslandServer()) return;

        if (readySignalPending) {
            ticksSinceServerStarted++;
            if (ticksSinceServerStarted >= READY_SIGNAL_DELAY_TICKS) {
                readySignalPending = false;
                sendIslandReadyForPlayersSignal();
            }
        }

        long now = System.currentTimeMillis();
        if (now - lastHeartbeatAtMs < HEARTBEAT_INTERVAL_MS) return;
        lastHeartbeatAtMs = now;

        if (webSocketClient == null || !webSocketClient.isOpen()) {
            LOGGER.warn("SkyBlockMod: WebSocket is not open — attempting to reconnect.");
            initializeWebSocket();
            return;
        }

        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        double avgTickMs = server.getAverageTickTime();
        double tps = Math.min(20.0, 1000.0 / Math.max(avgTickMs, 0.001));

        com.google.gson.JsonObject heartbeat = new com.google.gson.JsonObject();
        heartbeat.addProperty("type", "heartbeat");
        heartbeat.addProperty("tps", Math.round(tps * 100.0) / 100.0);
        heartbeat.addProperty("players", server.getPlayerCount());
        webSocketClient.sendJson(heartbeat);
    }

    /**
     * Initializes the WebSocket client.
     */
    private void initializeWebSocket() {
        if (!islandContext.isIslandServer() || islandContext.getOwnerUuid() == null) {
            return;
        }
        try {
            // "core_" — щоб не ділити client_id ні з голим-uuid конектом Velocity
            // (/myisland), ні з "island_"-конектом RealMarket (MarketSyncManager).
            // Спільний client_id призводить до того, що ConnectionManager на
            // API вибиває старе з'єднання при новому — TEAM_UPDATED/heartbeat/
            // shutting_down губляться без ретраю.
            String wsUrl = Config.getApiBaseUrl().replaceFirst("http", "ws") + "ws/core_" + islandContext.getOwnerUuid();
            webSocketClient = new com.skyblock.dynamic.utils.IslandWebSocketClient(new URI(wsUrl), islandContext.getOwnerUuid());
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
        Path islandDataPath = serverBasePath.resolve("world").resolve("serverconfig").resolve("skyblock_island_data.toml");
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

            if (isIsland && (ownerUuid == null || ownerUuid.trim().isEmpty())) {
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
