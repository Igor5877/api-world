package com.example.velocitycontainerplugin.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.example.velocitycontainerplugin.VelocityContainerPlugin; // Main plugin class
import org.slf4j.Logger;

public class PlayerConnectListener {

    private final VelocityContainerPlugin plugin;
    private final Logger logger;

    public PlayerConnectListener(VelocityContainerPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        RegisteredServer server = event.getServer();
        // In the future, we'll get the previous server from event.getPreviousServer() if needed

        // TODO: Get lobby server name from config
        String lobbyServerName = "lobby"; // Placeholder

        logger.info("Player " + player.getUsername() + " connected to server: " + server.getServerInfo().getName());

        if (server.getServerInfo().getName().equalsIgnoreCase(lobbyServerName)) {
            logger.info("Player " + player.getUsername() + " is on the lobby server (" + lobbyServerName + ").");
            // TODO:
            // 1. Check if player has an existing, running container (DBManager, LXDManager)
            //    - String containerName = dbManager.getActiveContainerName(player.getUniqueId());
            //    - if (containerName != null && lxdManager.isContainerRunning(containerName)) {
            //        // Proxy player to their container
            //        // plugin.proxyPlayerToContainer(player, containerName);
            //        logger.info("Player " + player.getUsername() + " has an active container. Needs proxying logic.");
            //    - } else {
            //        logger.info("Player " + player.getUsername() + " does not have an active container on the lobby. Ready for /server create command.");
            //    - }
        } else {
            // Player connected to a server that is not the lobby.
            // Potentially, if they were in a managed container and got moved, this is where we might not need to do anything,
            // or if they moved from their container to another non-lobby server.
             logger.info("Player " + player.getUsername() + " is on a non-lobby server: " + server.getServerInfo().getName());
        }
    }
}
