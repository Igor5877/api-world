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
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import net.minecraftforge.event.entity.player.PlayerEvent;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraftforge.event.server.ServerStoppingEvent;
import java.net.URI;
import java.net.URISyntaxException;

@Mod(SkyBlockMod.MODID)
public class SkyBlockMod {
    public static final String MODID = "skyblock";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static IslandContext islandContext = IslandContext.getDefault();
    private static long serverStartTime = 0L;
    private static boolean playerJoinedWithinFirstHour = false;
    private static com.skyblock.dynamic.utils.IslandWebSocketClient webSocketClient;

    public SkyBlockMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new com.skyblock.dynamic.events.PlayerEventHandler());
        modEventBus.addListener(this::onModConfigEvent);
        FMLJavaModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, MODID + "-common.toml");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("SkyBlockMod: Common Setup Initialized.");
    }

    public void onModConfigEvent(final ModConfigEvent configEvent) {
        if (configEvent.getConfig().getSpec() == Config.SPEC) {
            Config.bake();
            LOGGER.info("SkyBlockMod: Common configuration reloaded from skyblock-common.toml.");
        }
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        serverStartTime = System.currentTimeMillis();
        playerJoinedWithinFirstHour = false;
        Path serverBasePath = event.getServer().getServerDirectory().toPath();
        loadIslandContextData(serverBasePath);

        if (islandContext.isIslandServer()) {
            LOGGER.info("SkyBlockMod: Island server detected. Synchronizing team data...");
            UUID ownerUuid = UUID.fromString(islandContext.getOwnerUuid());
            NestworldModsServer.ISLAND_PROVIDER.refreshAndGetTeamId(ownerUuid);
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (islandContext.isIslandServer()) {
            LOGGER.info("SkyBlockMod: Server started. Running as an ISLAND SERVER. Owner UUID: {}", islandContext.getOwnerUuid());
            sendIslandReadyForPlayersSignal();
            initializeWebSocket();
        } else {
            LOGGER.info("SkyBlockMod: Server started. Running as a HUB SERVER.");
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            LOGGER.info("SkyBlockMod: Closing WebSocket connection.");
            webSocketClient.close();
        }
    }

    private void initializeWebSocket() {
        if (!islandContext.isIslandServer() || islandContext.getOwnerUuid() == null) {
            return;
        }
        try {
            String wsUrl = Config.getApiBaseUrl().replaceFirst("http", "ws") + "ws/" + islandContext.getOwnerUuid();
            webSocketClient = new com.skyblock.dynamic.utils.IslandWebSocketClient(new URI(wsUrl), islandContext.getOwnerUuid());
            LOGGER.info("SkyBlockMod: Attempting to connect to WebSocket at {}", wsUrl);
            webSocketClient.connect();
        } catch (URISyntaxException e) {
            LOGGER.error("SkyBlockMod: Invalid WebSocket URI syntax.", e);
        }
    }

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

    public static boolean isIslandServer() {
        return islandContext.isIslandServer();
    }

    public static String getOwnerUuid() {
        return islandContext.getOwnerUuid();
    }

    public static boolean hasPlayerJoinedWithinFirstHour() {
        return playerJoinedWithinFirstHour;
    }

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

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            LOGGER.info("SkyBlockMod: Client Setup");
        }
    }
}