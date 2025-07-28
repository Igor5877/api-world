package com.skyblockdynamic.nestworld.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.skyblockdynamic.nestworld.velocity.network.WebSocketManager;

import java.nio.file.Path;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.listener.PlayerConnectionListener;
import com.skyblockdynamic.nestworld.velocity.commands.MyIslandCommand; // Added import
import com.skyblockdynamic.nestworld.velocity.commands.SpawnCommand;

@Plugin(
    id = "nestworldvelocity",
    name = "NestworldVelocity",
    version = "1.0.0",
    description = "Velocity plugin for Nestworld Dynamic SkyBlock to manage player connections to their islands.",
    authors = {"YourName/Team"} // Replace with your name
)
public class NestworldVelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    // Config and API client will be initialized here
    private PluginConfig pluginConfig;
    private ApiClient apiClient;
    private final Map<UUID, WebSocketManager> webSocketManagers = new ConcurrentHashMap<>();


    @Inject
    public NestworldVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

        logger.info("NestworldVelocityPlugin is constructing...");
    }

   @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("NestworldVelocityPlugin onProxyInitialization started...");
        // Load configuration
        this.pluginConfig = PluginConfig.load(dataDirectory, logger);
        // PluginConfig.load handles logging errors and returning a fallback, so pluginConfig should not be null.
        
        this.apiClient = new ApiClient(logger, pluginConfig);

        // Register event listeners
        server.getEventManager().register(this, new PlayerConnectionListener(this, server, logger, apiClient, pluginConfig));

        // Register commands
        CommandManager commandManager = server.getCommandManager();
        
        // Реєстрація команди /myisland
        CommandMeta myIslandCommandMeta = commandManager.metaBuilder("myisland")
            //.aliases("island")
            .plugin(this)
            .build();
        
        commandManager.register(myIslandCommandMeta, new MyIslandCommand(this, server, logger, this.apiClient, this.pluginConfig));
        logger.info("Registered /myisland command.");

        // ================================================================
        // ===== ДОДАЙТЕ ЦЕЙ БЛОК КОДУ ДЛЯ РЕЄСТРАЦІЇ КОМАНДИ /SPAWN =====
        CommandMeta spawnCommandMeta = commandManager.metaBuilder("spawn")
            .plugin(this)
            .build();
            
        commandManager.register(spawnCommandMeta, new SpawnCommand(server, logger, this.pluginConfig));
        logger.info("Registered /spawn command.");
        // ================================================================
        
        logger.info("NestworldVelocityPlugin initialized successfully with listeners, config, and commands!");
        logger.info("API URL configured to: {}", pluginConfig.getApiUrl());
        logger.info("Fallback server configured to: {}", pluginConfig.getFallbackServerName());
    }
    

    // Getter methods if other classes need them
    // Added getter for pluginConfig and apiClient as they might be useful for the command or other components
    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public ApiClient getApiClient() {
        return apiClient;
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    // public PluginConfig getConfig() { // This was named config, but field is pluginConfig
    //    return pluginConfig; 
    // }

    // public ApiClient getApiClient() { // Already added above
    //    return apiClient;
    // }
    public Map<UUID, WebSocketManager> getWebSocketManagers() {
    return webSocketManagers;
}
}
