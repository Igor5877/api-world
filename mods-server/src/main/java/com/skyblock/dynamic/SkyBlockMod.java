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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
// import java.util.UUID; // if using UUID type directly

@Mod(SkyBlockMod.MODID)
public class SkyBlockMod
{
    public static final String MODID = "skyblock";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static String islandOwnerUuid = null; 
    private static boolean readySignalSent = false; 

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10)) 
            .build();

    public SkyBlockMod(FMLJavaModLoadingContext context)
    {
        IEventBus modEventBus = context.getModEventBus();
        modEventBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(this); 
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
            LOGGER.info("SkyBlockMod: Configuration reloaded.");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        com.skyblock.dynamic.commands.IslandCommand.register(event.getDispatcher());
        LOGGER.info("SkyBlockMod: Registered /island command.");
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        Path serverBasePath = event.getServer().getServerDirectory().toPath();
        loadIslandOwnerUuidIfPresent(serverBasePath);
    }
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (islandOwnerUuid != null && !readySignalSent) {
            LOGGER.info("SkyBlockMod: Server fully started. Sending island ready confirmation for {}.", islandOwnerUuid);
            sendIslandReadyConfirmation();
        } else if (islandOwnerUuid == null) {
            LOGGER.info("SkyBlockMod: Server started, but no island_meta.properties found or UUID missing. Assuming this is not a player island server requiring ready confirmation.");
        } else { // readySignalSent is true
            LOGGER.info("SkyBlockMod: Server started, ready signal already sent for {}.", islandOwnerUuid);
        }
    }

    private void loadIslandOwnerUuidIfPresent(Path serverBasePath) {
        File propertiesFile = serverBasePath.resolve("island_meta.properties").toFile();
        if (!propertiesFile.exists()) {
            LOGGER.info("SkyBlockMod: No island_meta.properties found at {}. This instance will not send a 'ready' confirmation.", propertiesFile.getAbsolutePath());
            islandOwnerUuid = null; 
            return;
        }
        
        Properties props = new Properties();
        try (InputStream input = new FileInputStream(propertiesFile)) {
            props.load(input);
            String uuidString = props.getProperty("player_uuid");
            if (uuidString != null && !uuidString.trim().isEmpty()) {
                try {
                    java.util.UUID.fromString(uuidString.trim()); 
                    islandOwnerUuid = uuidString.trim();
                    LOGGER.info("SkyBlockMod: Successfully loaded island owner UUID: {} from island_meta.properties.", islandOwnerUuid);
                } catch (IllegalArgumentException e) {
                    LOGGER.error("SkyBlockMod: 'player_uuid' in island_meta.properties is not a valid UUID: {}. Error: {}", uuidString, e.getMessage());
                    islandOwnerUuid = null;
                }
            } else {
                LOGGER.error("SkyBlockMod: 'player_uuid' not found or is empty in island_meta.properties.");
                islandOwnerUuid = null;
            }
        } catch (IOException e) {
            LOGGER.error("SkyBlockMod: Error loading island_meta.properties.", e);
            islandOwnerUuid = null;
        }
    }

    private void sendIslandReadyConfirmation() {
        if (islandOwnerUuid == null) {
            LOGGER.error("Cannot send island ready confirmation: islandOwnerUuid is null or invalid.");
            return;
        }

        String apiUrl = Config.getApiBaseUrl() + "islands/" + islandOwnerUuid + "/confirm_ready";
        LOGGER.info("SkyBlockMod: Sending island ready confirmation for UUID {} to URL: {}", islandOwnerUuid, apiUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .POST(HttpRequest.BodyPublishers.noBody())
                .header("Content-Type", "application/json") 
                .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> {
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    LOGGER.info("SkyBlockMod: Successfully sent island ready confirmation for UUID {}. Status: {}", islandOwnerUuid, response.statusCode());
                    readySignalSent = true; 
                } else {
                    LOGGER.error("SkyBlockMod: Failed to send island ready confirmation for UUID {}. Status: {}, Body: {}", islandOwnerUuid, response.statusCode(), response.body());
                }
            })
            .exceptionally(e -> {
                LOGGER.error("SkyBlockMod: Exception sending island ready confirmation for UUID {}: {}", islandOwnerUuid, e.getMessage(), e);
                return null;
            });
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
