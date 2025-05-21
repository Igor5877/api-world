package com.example.velocitycontainerplugin.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.example.velocitycontainerplugin.VelocityContainerPlugin; // Main plugin class
import com.example.velocitycontainerplugin.db.DBManager;
import com.example.velocitycontainerplugin.lxd.LXDManager;
import org.slf4j.Logger;

public class PlayerDisconnectListener {

    private final VelocityContainerPlugin plugin;
    private final Logger logger;
    private final DBManager dbManager;
    private final LXDManager lxdManager;

    public PlayerDisconnectListener(VelocityContainerPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dbManager = plugin.getDbManager();
        this.lxdManager = plugin.getLxdManager();
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        logger.info("Player " + player.getUsername() + " disconnected from the proxy.");

        // TODO:
        // 1. Check if the player has an active container (DBManager)
        //    - String containerName = dbManager.getActiveContainerName(player.getUniqueId()); // This method needs to be added to DBManager
        //    - if (containerName != null) {
        //        logger.info("Player " + player.getUsername() + " had an active container: " + containerName + ". Stopping it.");
        //        // 2. Stop the container (LXDManager)
        //        boolean stopped = lxdManager.stopContainer(containerName);
        //        if (stopped) {
        //            // 3. Update container status and last active time in DB (DBManager)
        //            dbManager.updateContainerStatus(containerName, "STOPPED");
        //            logger.info("Container " + containerName + " stopped and status updated in DB.");
        //        } else {
        //            logger.error("Failed to stop container " + containerName + " for player " + player.getUsername());
        //        }
        //    - } else {
        //        logger.info("Player " + player.getUsername() + " did not have an active container record upon disconnect.");
        //    - }
        logger.warn("PlayerDisconnectListener logic is mostly placeholder."); // Temporary message
    }
}
