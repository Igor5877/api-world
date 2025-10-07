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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.skyblockdynamic.nestworld.velocity.network.WebSocketManager;

import java.nio.file.Path;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.listener.PlayerConnectionListener;
import com.skyblockdynamic.nestworld.velocity.commands.MyIslandCommand;
import com.skyblockdynamic.nestworld.velocity.commands.SpawnCommand;
import com.skyblockdynamic.nestworld.velocity.commands.TeamCommand;
import com.skyblockdynamic.nestworld.velocity.commands.IslandCommand;
import com.skyblockdynamic.nestworld.velocity.commands.TpaCommand;
import com.skyblockdynamic.nestworld.velocity.locale.LocaleManager;

@Plugin(
    id = "nestworldvelocity",
    name = "NestworldVelocity",
    version = "1.0.0",
    description = "Velocity plugin for Nestworld Dynamic SkyBlock to manage player connections to their islands.",
    authors = {"YourName/Team"}
)
public class NestworldVelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private PluginConfig pluginConfig;
    private ApiClient apiClient;
    private LocaleManager localeManager;
    private final Map<UUID, WebSocketManager> webSocketManagers = new ConcurrentHashMap<>();
    private final Set<UUID> awaitingConnection = ConcurrentHashMap.newKeySet();


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
        this.pluginConfig = PluginConfig.load(dataDirectory, logger);
        this.localeManager = new LocaleManager(dataDirectory, logger);
        
        this.apiClient = new ApiClient(logger, pluginConfig);

        server.getEventManager().register(this, new PlayerConnectionListener(this, server, logger, apiClient, pluginConfig));

        CommandManager commandManager = server.getCommandManager();
        
        MyIslandCommand myIslandCommandInstance = new MyIslandCommand(this, server, logger, this.apiClient, this.pluginConfig, this.localeManager);
        CommandMeta myIslandCommandMeta = commandManager.metaBuilder("myisland")
            .plugin(this)
            .build();
        commandManager.register(myIslandCommandMeta, myIslandCommandInstance);
        logger.info("Registered /myisland command.");

        CommandMeta spawnCommandMeta = commandManager.metaBuilder("spawn")
            .plugin(this)
            .build();
            
        commandManager.register(spawnCommandMeta, new SpawnCommand(server, logger, this.pluginConfig));
        logger.info("Registered /spawn command.");
        
        CommandMeta teamCommandMeta = commandManager.metaBuilder("team")
            .plugin(this)
            .build();
            
        commandManager.register(teamCommandMeta, new TeamCommand(this, this.apiClient, logger, this.localeManager));
        logger.info("Registered /team command.");

        CommandMeta islandCommandMeta = commandManager.metaBuilder("island")
            .plugin(this)
            .build();
        
        commandManager.register(islandCommandMeta, new IslandCommand(this, this.apiClient, logger, this.localeManager));
        logger.info("Registered /island command.");

        TpaCommand tpaCommand = new TpaCommand(this, server, logger, pluginConfig, myIslandCommandInstance, this.localeManager);
        commandManager.register(tpaCommand.createTpaCommand());
        commandManager.register(tpaCommand.createTpAcceptCommand());
        commandManager.register(tpaCommand.createTpDenyCommand());
        logger.info("Registered TPA commands.");
        
        logger.info("NestworldVelocityPlugin initialized successfully with listeners, config, and commands!");
        logger.info("API URL configured to: {}", pluginConfig.getApiUrl());
        logger.info("Fallback server configured to: {}", pluginConfig.getFallbackServerName());
    }
    
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

    public Map<UUID, WebSocketManager> getWebSocketManagers() {
        return webSocketManagers;
    }

    public Set<UUID> getAwaitingConnection() {
        return awaitingConnection;
    }
}
