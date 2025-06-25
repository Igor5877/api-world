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
// import java.util.UUID; // if using UUID type directly

@Mod(SkyBlockMod.MODID)
public class SkyBlockMod
{
    public static final String MODID = "skyblock";
    private static final Logger LOGGER = LogUtils.getLogger();
    // private static String islandOwnerUuid = null; // Replaced by IslandContext
    // private static boolean readySignalSent = false; // Logic removed

    // HTTP_CLIENT might be used by other parts (e.g. IslandCommand still uses its own, PlayerEventHandler will need one)
    // For now, keep it here if it's intended to be a shared client, or it can be moved/recreated where needed.
    // Given PlayerEventHandler will do API calls, a shared one could be useful.
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)) // Default, actual request timeout from Config
            .build();

    private static IslandContext islandContext = IslandContext.getDefault(); // Initialize with default

    public SkyBlockMod(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this);
        modEventBus.addListener(this::onModConfigEvent);
        // Register the common config (skyblock-common.toml)
        context.registerConfig(ModConfig.Type.COMMON, Config.SPEC, MODID + "-common.toml");
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        LOGGER.info("SkyBlockMod: Common Setup Initialized.");
        // Initialization that doesn't depend on world/server files can go here.
    }

    // Listener for the common config (skyblock-common.toml)
    public void onModConfigEvent(final ModConfigEvent configEvent) {
        if (configEvent.getConfig().getSpec() == Config.SPEC) {
            Config.bake();
            LOGGER.info("SkyBlockMod: Common configuration reloaded from skyblock-common.toml.");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        com.skyblock.dynamic.commands.IslandCommand.register(event.getDispatcher());
        LOGGER.info("SkyBlockMod: Registered /island command.");
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        // serverBasePath is the root directory of the server, where 'world', 'config', etc. reside.
        Path serverBasePath = event.getServer().getServerDirectory().toPath();
        loadIslandContextData(serverBasePath);

        // Old logic for island_meta.properties is removed.
        // loadIslandOwnerUuidIfPresent(serverBasePath);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Old logic for sending 'confirm_ready' is removed.
        // New logic, if any, for when the server is fully started can go here.
        // For example, logging the determined context.
        if (islandContext.isIslandServer()) {
            LOGGER.info("SkyBlockMod: Server started. Running as an ISLAND SERVER. Creator UUID: {}", islandContext.getCreatorUuid());
        } else {
            LOGGER.info("SkyBlockMod: Server started. Running as a HUB SERVER.");
        }
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
                .writingMode(WritingMode.REPLACE) // Or another mode if you prefer
                .build()) {
            
            config.load(); // Load the file

            boolean isIsland = config.getOptional("is_island_server")
                                    .map(obj -> Boolean.valueOf(String.valueOf(obj)))
                                    .orElse(false);
            String creatorUuid = config.getOptional("creator_uuid")
                                 .map(String::valueOf)
                                 .orElse(null);

            if (isIsland && (creatorUuid == null || creatorUuid.trim().isEmpty())) {
                LOGGER.error("SkyBlockMod: skyblock_island_data.toml indicates this is an island server, but 'creator_uuid' is missing or empty. Treating as HUB.");
                islandContext = IslandContext.getDefault(); // Fallback to default if critical info is missing
            } else if (isIsland) {
                try {
                    // Validate UUID format if it's present for an island server
                    java.util.UUID.fromString(creatorUuid.trim());
                    islandContext = new IslandContext(true, creatorUuid.trim());
                    LOGGER.info("SkyBlockMod: Successfully loaded island context: isIslandServer={}, creatorUuid={}", isIsland, creatorUuid);
                } catch (IllegalArgumentException e) {
                    LOGGER.error("SkyBlockMod: 'creator_uuid' in skyblock_island_data.toml is not a valid UUID: {}. Treating as HUB. Error: {}", creatorUuid, e.getMessage());
                    islandContext = IslandContext.getDefault();
                }
            } else {
                // Not an island server, UUID is not strictly needed but store if present
                islandContext = new IslandContext(false, creatorUuid != null ? creatorUuid.trim() : null);
                LOGGER.info("SkyBlockMod: Successfully loaded island context: isIslandServer=false. creator_uuid (if any) will be ignored for hub logic.");
            }

        } catch (Exception e) {
            LOGGER.error("SkyBlockMod: Failed to load or parse skyblock_island_data.toml. Using default context. Error: {}", e.getMessage(), e);
            islandContext = IslandContext.getDefault();
        }
    }

    // Getter methods for the context, accessible from other classes like IslandCommand and PlayerEventHandler
    public static boolean isIslandServer() {
        return islandContext.isIslandServer();
    }

    public static String getCreatorUuid() {
        return islandContext.getCreatorUuid();
    }
    
    // Old methods removed:
    // private void loadIslandOwnerUuidIfPresent(Path serverBasePath) { ... }
    // private void sendIslandReadyConfirmation() { ... }


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
