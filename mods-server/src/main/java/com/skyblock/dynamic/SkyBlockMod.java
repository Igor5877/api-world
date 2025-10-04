package com.skyblock.dynamic; // Changed package

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent; // Still present from original template, not used by new logic but harmless
import net.minecraftforge.event.server.ServerStartingEvent;     // Still present from original template
import net.minecraftforge.event.server.ServerAboutToStartEvent; // Added back
import net.minecraftforge.event.server.ServerStartedEvent;     // Added back
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent; // Was missing from first block, needed
import net.minecraftforge.fml.event.config.ModConfigEvent; // Was in first block
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger; // Was in first block

// Imports for new functionality (ensure they are not duplicated if already above)
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest; // Not used directly here anymore, but HTTP_CLIENT is kept for now
import java.net.http.HttpResponse; // Not used directly here anymore
import java.nio.file.Path;
import java.nio.file.Paths; // For constructing path to new config
import java.time.Duration;
// import java.util.Properties; // Not used anymore
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import com.skyblock.dynamic.utils.IslandContext; // Import the new IslandContext class
import net.minecraftforge.event.entity.player.PlayerEvent;
import java.util.UUID;

@Mod(SkyBlockMod.MODID)
public class SkyBlockMod
{
    public static final String MODID = "skyblock";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static IslandContext islandContext = IslandContext.getDefault();
    private static long serverStartTime = 0;
    private static boolean playerJoinedWithinFirstHour = false;

    public SkyBlockMod(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new com.skyblock.dynamic.events.PlayerEventHandler());
        modEventBus.addListener(this::onModConfigEvent);
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC, MODID + "-common.toml");
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        LOGGER.info("SkyBlockMod: Common Setup Initialized.");
    }

    public void onModConfigEvent(final ModConfigEvent configEvent) {
        if (configEvent.getConfig().getSpec() == Config.SPEC) {
            Config.bake();
            LOGGER.info("SkyBlockMod: Common configuration reloaded from skyblock-common.toml.");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        Path serverBasePath = event.getServer().getServerDirectory().toPath();
        loadIslandContextData(serverBasePath);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (islandContext.isIslandServer()) {
            serverStartTime = System.currentTimeMillis();
            LOGGER.info("SkyBlockMod: Server started. Running as an ISLAND SERVER. Owner UUID: {}", islandContext.getOwnerUuid());
            sendIslandReadyForPlayersSignal();
        } else {
            LOGGER.info("SkyBlockMod: Server started. Running as a HUB SERVER.");
        }
    }

    private void sendIslandReadyForPlayersSignal() {
        if (!islandContext.isIslandServer() || islandContext.getOwnerUuid() == null) {
            LOGGER.warn("SkyBlockMod: Attempted to send ready signal, but not an island server or UUID is missing.");
            return;
        }

        String ownerUuidStr = islandContext.getOwnerUuid();
        UUID ownerUuid;
        try {
            ownerUuid = UUID.fromString(ownerUuidStr);
        } catch (IllegalArgumentException e) {
            LOGGER.error("SkyBlockMod: Invalid owner_uuid format: {}. Cannot send ready signal.", ownerUuidStr, e);
            return;
        }

        com.skyblock.dynamic.nestworld.mods.NestworldModsServer.ISLAND_PROVIDER.sendReady(ownerUuid);
    }

    private void loadIslandContextData(Path serverBasePath) {
        Path islandDataPath = serverBasePath.resolve("world").resolve("serverconfig").resolve("skyblock_island_data.toml");
        LOGGER.info("SkyBlockMod: Attempting to load island context from: {}", islandDataPath.toString());

        if (!islandDataPath.toFile().exists()) {
            LOGGER.warn("SkyBlockMod: skyblock_island_data.toml not found at {}. Using default context (not an island server).", islandDataPath);
            islandContext = IslandContext.getDefault();
            return;
        }

        try (CommentedFileConfig config = CommentedFileConfig.builder(islandDataPath)
                .sync()
                .autosave()
                .writingMode(WritingMode.REPLACE)
                .build()) {
            
            config.load();

            boolean isIsland = config.getOptional("is_island_server")
                                    .map(obj -> Boolean.valueOf(String.valueOf(obj)))
                                    .orElse(false);
            String ownerUuid = config.getOptional("owner_uuid")
                                 .map(String::valueOf)
                                 .orElse(null);

            if (isIsland && (ownerUuid == null || ownerUuid.trim().isEmpty())) {
                LOGGER.error("SkyBlockMod: skyblock_island_data.toml indicates this is an island server, but 'owner_uuid' is missing or empty. Treating as HUB.");
                islandContext = IslandContext.getDefault();
            } else if (isIsland) {
                try {
                    java.util.UUID.fromString(ownerUuid.trim());
                    islandContext = new IslandContext(true, ownerUuid.trim());
                    LOGGER.info("SkyBlockMod: Successfully loaded island context: isIslandServer={}, ownerUuid={}", isIsland, ownerUuid);
                } catch (IllegalArgumentException e) {
                    LOGGER.error("SkyBlockMod: 'owner_uuid' in skyblock_island_data.toml is not a valid UUID: {}. Treating as HUB. Error: {}", ownerUuid, e.getMessage());
                    islandContext = IslandContext.getDefault();
                }
            } else {
                islandContext = new IslandContext(false, ownerUuid != null ? ownerUuid.trim() : null);
                LOGGER.info("SkyBlockMod: Successfully loaded island context: isIslandServer=false. owner_uuid (if any) will be ignored for hub logic.");
            }

        } catch (Exception e) {
            LOGGER.error("SkyBlockMod: Failed to load or parse skyblock_island_data.toml. Using default context. Error: {}", e.getMessage(), e);
            islandContext = IslandContext.getDefault();
        }
    }

    public static boolean isIslandServer() {
        return islandContext.isIslandServer();
    }

    public static String getOwnerUuid() {
        return islandContext.getOwnerUuid();
    }

    public static long getServerStartTime() {
        return serverStartTime;
    }

    public static boolean hasPlayerJoinedWithinFirstHour() {
        return playerJoinedWithinFirstHour;
    }

    public static void setPlayerJoinedWithinFirstHour(boolean value) {
        playerJoinedWithinFirstHour = value;
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        var player = event.getEntity();
        LOGGER.info("Player {} logged in, triggering island data refresh.", player.getName().getString());
        com.skyblock.dynamic.nestworld.mods.NestworldModsServer.ISLAND_PROVIDER.refreshAndGetTeamId(player.getUUID());
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            LOGGER.info("SkyBlockMod: Client Setup");
        }
    }
}