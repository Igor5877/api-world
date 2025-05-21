package com.example.velocitycontainerplugin;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

// Import your manager classes
import com.example.velocitycontainerplugin.lxd.LXDManager;
import com.example.velocitycontainerplugin.db.DBManager;
// import com.example.velocitycontainerplugin.Config; // If you create a config class
import com.example.velocitycontainerplugin.listeners.PlayerConnectListener;
import com.example.velocitycontainerplugin.listeners.PlayerDisconnectListener;
import com.example.velocitycontainerplugin.commands.ServerManageCommand;
import com.example.velocitycontainerplugin.db.DBManager.ContainerInfo; // Nested class
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Plugin(
    id = "velocitycontainerplugin",
    name = "Velocity Container Plugin",
    version = "1.0-SNAPSHOT",
    description = "A plugin to manage LXD containers for players.",
    authors = {"PluginDeveloper"} // Replace YourName
)
public class VelocityContainerPlugin {

    private final ProxyServer server;
    private final Logger logger;

    private LXDManager lxdManager;
    private DBManager dbManager;
    // private Config config;

    @Inject
    public VelocityContainerPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;

        logger.info("VelocityContainerPlugin is being constructed!");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("VelocityContainerPlugin initializing...");

        // 1. Load Configuration (to be implemented later, for now, managers might use defaults)
        // this.config = loadConfig(); 
        // logger.info("Configuration loaded.");

        // 2. Initialize Managers
        // Pass config to managers if they need it
        this.lxdManager = new LXDManager(/* config */); 
        this.dbManager = new DBManager(/* config */);
        
        logger.info("LXDManager and DBManager initialized.");

        // 3. Initialize Database Schema
        // This should be called after DBManager is initialized
        this.dbManager.initializeDatabase();
        logger.info("Database schema initialization attempted.");
        
        // 4. Register Commands
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("server").aliases("s").build(), // Command "server" with alias "s"
            new ServerManageCommand(this)
        );
        logger.info("'/server' command registered.");

        // 5. Register Event Listeners
        server.getEventManager().register(this, new PlayerConnectListener(this));
        server.getEventManager().register(this, new PlayerDisconnectListener(this));
        logger.info("Player connect and disconnect event listeners registered.");

        // 6. Schedule Inactive Container Cleanup Task
        // TODO: Make schedule rates and inactivity period configurable
        long initialDelay = 5; // minutes
        long repeatInterval = 60; // minutes (e.g., hourly)
        long inactivityDays = 14; // days

        server.getScheduler().buildTask(this, () -> {
            logger.info("Running inactive container cleanup task...");

            // Calculate the timestamp for "olderThan"
            Timestamp olderThan = Timestamp.from(Instant.now().minus(inactivityDays, ChronoUnit.DAYS));
            logger.info("Checking for containers inactive since: " + olderThan.toString());

            List<ContainerInfo> inactiveContainers = dbManager.getInactiveContainers(olderThan);

            if (inactiveContainers.isEmpty()) {
                logger.info("No inactive containers found matching the criteria.");
                return;
            }

            logger.info("Found " + inactiveContainers.size() + " inactive containers to process for potential deletion.");

            for (ContainerInfo container : inactiveContainers) {
                logger.info("Processing inactive container: " + container.containerName + " (Player UUID: " + container.playerUuid + ")");
                
                // TODO: Implement actual deletion logic
                // 1. Call lxdManager.deleteContainer(container.containerName)
                //    boolean deleted = lxdManager.deleteContainer(container.containerName);
                //    if (deleted) {
                //        logger.info("Successfully deleted LXD container: " + container.containerName);
                //        // 2. Update database record (e.g., set status to DELETED or remove record)
                //        // dbManager.updateContainerStatus(container.containerName, "DELETED");
                //        // or dbManager.deleteContainerRecord(container.containerName); // (This method needs to be added to DBManager)
                //        logger.info("Updated database for deleted container: " + container.containerName);
                //    } else {
                //        logger.error("Failed to delete LXD container: " + container.containerName);
                //    }
                logger.warn("Actual LXD deletion and DB update for container " + container.containerName + " is a placeholder.");
            }
        }).delay(initialDelay, TimeUnit.MINUTES).repeat(repeatInterval, TimeUnit.MINUTES).schedule();

        logger.info("Scheduled inactive container cleanup task to run every " + repeatInterval + " minutes after an initial delay of " + initialDelay + " minutes.");

        logger.info("VelocityContainerPlugin successfully initialized!");
    }

    // Getter methods for managers if needed by commands/listeners
    public LXDManager getLxdManager() {
        return lxdManager;
    }

    public DBManager getDbManager() {
        return dbManager;
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    // private Config loadConfig() {
    //     // TODO: Implement configuration loading logic
    //     // This would typically involve loading a TOML/YAML file from resources
    //     // For now, can return a default config object or null
    //     logger.warn("Configuration loading is not yet implemented.");
    //     return null; // Or a new Config() with defaults
    // }
}
