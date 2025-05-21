package com.example.containermod;

import com.example.containermod.command.CreateContainerCommand;
import com.example.containermod.config.ModConfigs;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

// The value here should match an entry in the META-INF/mods.toml file
@Mod(ContainerMod.MODID)
public class ContainerMod {

    public static final String MODID = "containermod";
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final Gson GSON = new GsonBuilder().create();

    public static String API_BASE_URL;
    private static CloseableHttpAsyncClient httpAsyncClient;

    public ContainerMod() {
        LOGGER.info("ContainerMod: Constructing and initializing.");
        // Register the setup method for modloading
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);

        // Register config
        ModConfigs.setup(); 
        LOGGER.info("ContainerMod: Configuration registration initiated.");

        // Register ourselves for server and other game events we are interested in
        MinecraftForge.EVENT_BUS.register(this);
        LOGGER.info("ContainerMod: Event bus registration complete.");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("ContainerMod: FMLCommonSetupEvent received.");
        // API_BASE_URL will be loaded in onServerAboutToStart
        LOGGER.info("ContainerMod: Common setup phase complete. API Base URL will be loaded on server start.");
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        LOGGER.info("ContainerMod: ServerAboutToStartEvent received. Server setup starting.");
        try {
            API_BASE_URL = ModConfigs.SERVER.API_BASE_URL.get();
            LOGGER.info("ContainerMod: Loaded API Base URL: {}", API_BASE_URL);

            final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder.create().build();
            httpAsyncClient = HttpAsyncClients.custom()
                    .setConnectionManager(connectionManager)
                    .build();
            httpAsyncClient.start();
            LOGGER.info("ContainerMod: Apache HttpAsyncClient started successfully.");
        } catch (Exception e) {
            LOGGER.error("ContainerMod: Critical error during server setup (HttpAsyncClient start or config load). Mod may not function correctly.", e);
            // Depending on severity, could rethrow or simply log. For now, logging.
        }
    }


    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("ContainerMod: ServerStartingEvent received. Server is starting!");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("ContainerMod: ServerStoppingEvent received. Server is stopping.");
        if (httpAsyncClient != null) {
            try {
                httpAsyncClient.close();
                LOGGER.info("ContainerMod: Apache HttpAsyncClient closed successfully.");
            } catch (IOException e) {
                LOGGER.error("ContainerMod: Failed to close HttpAsyncClient", e);
            }
        }
        LOGGER.info("ContainerMod: Server stopping process complete.");
    }

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        LOGGER.info("ContainerMod: RegisterCommandsEvent received. Registering commands. API URL: {}", API_BASE_URL);
        CreateContainerCommand.register(event.getDispatcher());
        LOGGER.info("ContainerMod: Commands registered.");
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerPlayer player = (ServerPlayer) event.getEntity();
        UUID playerUuid = player.getUUID();
        
        // Ensure API_BASE_URL is loaded, otherwise this will fail.
        // It should be loaded by onServerAboutToStart.
        if (API_BASE_URL == null || API_BASE_URL.isEmpty()) {
             LOGGER.error("API Base URL not configured or loaded. Cannot send stop request for player {}.", playerUuid);
             return;
        }
        String apiUrl = API_BASE_URL + "/stop";

        LOGGER.info("Player {} logged out. Attempting to call stop API at {}.", playerUuid, apiUrl);

        JsonObject payload = new JsonObject();
        payload.addProperty("player_identifier", playerUuid.toString());

        if (httpAsyncClient == null) {
            LOGGER.error("HTTP client not initialized. Cannot send stop request for player {}.", playerUuid);
            return;
        }

        try {
            URI requestUri = new URI(apiUrl);
            HttpHost target = new HttpHost(requestUri.getScheme(), requestUri.getHost(), requestUri.getPort());
            final SimpleHttpRequest request = SimpleRequestBuilder.post(target, requestUri.getPath())
                    .setBody(payload.toString(), ContentType.APPLICATION_JSON)
                    .build();

            LOGGER.debug("Sending container stop request for player {} to {}: {}", playerUuid, apiUrl, payload.toString());

            httpAsyncClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    try {
                        String responseBody = response.getBodyText();
                        LOGGER.debug("Received response from stop API for player {} (status {}): {}", playerUuid, response.getCode(), responseBody);
                        JsonObject jsonResponse = responseBody != null && !responseBody.isEmpty() ? JsonParser.parseString(responseBody).getAsJsonObject() : new JsonObject();

                        if (response.getCode() >= 200 && response.getCode() < 300) {
                            String message = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "Container stop processed.";
                            LOGGER.info("Successfully called stop API for player {}. Response: {}", playerUuid, message);
                        } else {
                            String apiErrorMessage = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "Unknown API error.";
                            LOGGER.error("API error when stopping container for player {} (code {}): {}", playerUuid, response.getCode(), apiErrorMessage);
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to parse API response for player {} during stop request.", playerUuid, e);
                    }
                }

                @Override
                public void failed(Exception ex) {
                    LOGGER.error("Connection error when calling stop API for player {}: {}", playerUuid, ex.getMessage(), ex);
                }

                @Override
                public void cancelled() {
                    LOGGER.warn("Stop API request cancelled for player {}.", playerUuid);
                }
            });

        } catch (Exception e) {
            LOGGER.error("Failed to construct or send container stop request for player {}.", playerUuid, e);
        }
    }

    public static CloseableHttpAsyncClient getHttpClient() {
        return httpAsyncClient;
    }

    // Optional helper methods for GSON, if needed more broadly.
    // public static String toJson(Object object) { return GSON.toJson(object); }
    // public static <T> T fromJson(String json, Class<T> clazz) { return GSON.fromJson(json, clazz); }
}
