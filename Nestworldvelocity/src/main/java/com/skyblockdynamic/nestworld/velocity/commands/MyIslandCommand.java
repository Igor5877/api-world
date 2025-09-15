package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.UUID;
import com.skyblockdynamic.nestworld.velocity.network.WebSocketManager;

public class MyIslandCommand implements SimpleCommand {

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final ApiClient apiClient;
    private final PluginConfig config;

    public MyIslandCommand(NestworldVelocityPlugin plugin, ProxyServer proxyServer, Logger logger, ApiClient apiClient, PluginConfig config) {
        this.plugin = plugin;
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.apiClient = apiClient;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            source.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }
        Player player = (Player) source;
        String islandServerName = "island-" + player.getUniqueId().toString();

        if (player.getCurrentServer().isPresent() && player.getCurrentServer().get().getServerInfo().getName().equals(islandServerName)) {
            player.sendMessage(Component.text("You are already on your island.", NamedTextColor.YELLOW));
            return;
        }
        
        initiateIslandConnection(player);
    }

    private void initiateIslandConnection(Player player) {
        player.sendMessage(Component.text("Checking your island's status...", NamedTextColor.YELLOW));
        
        apiClient.getIslandDetails(player.getUniqueId()).thenAccept(apiResponse -> {
            if (!apiResponse.isSuccess() && apiResponse.statusCode() != 404) {
                player.sendMessage(Component.text("Could not retrieve island information. Status: " + apiResponse.statusCode(), NamedTextColor.RED));
                return;
            }

            if (apiResponse.isSuccess()) {
                try {
                    JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    boolean minecraftReady = islandData.has("minecraft_ready") && islandData.get("minecraft_ready").getAsBoolean();

                    if ("RUNNING".equalsIgnoreCase(status) && minecraftReady) {
                        player.sendMessage(Component.text("Your island is ready! Connecting...", NamedTextColor.GREEN));
                        String ip = islandData.get("internal_ip_address").getAsString();
                        int port = islandData.get("internal_port").getAsInt();
                        attemptSingleConnection(player, ip, port);
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Error parsing initial island status for {}: {}", player.getUsername(), e.getMessage());
                }
            }

            startAndListen(player);
        });
    }

    private void startAndListen(Player player) {
        player.sendMessage(Component.text("Your island is not ready. Requesting start...", NamedTextColor.YELLOW));

        apiClient.requestIslandStart(player.getUniqueId(), player.getUsername())
            .thenAccept(startResponse -> {
                if (startResponse.isSuccess() || startResponse.statusCode() == 409) {
                    player.sendMessage(Component.text("Island start request accepted. Listening for status updates...", NamedTextColor.AQUA));
                    connectToWebSocket(player);
                } else {
                    player.sendMessage(Component.text("Failed to start your island. API Status: " + startResponse.statusCode(), NamedTextColor.RED));
                }
            })
            .exceptionally(ex -> {
                player.sendMessage(Component.text("Error requesting island start.", NamedTextColor.RED));
                logger.error("Exception in requestIslandStart chain for {}: {}", player.getUsername(), ex.getMessage(), ex);
                return null;
            });
    }

    private void connectToWebSocket(Player player) {
        UUID playerUuid = player.getUniqueId();
        
        if (plugin.getWebSocketManagers().containsKey(playerUuid)) {
            plugin.getWebSocketManagers().remove(playerUuid).close();
        }

        String httpUrl = config.getApiUrl();
        String wsUrl = httpUrl.replace("/api/v1", "/ws/" + playerUuid.toString()).replaceFirst("http", "ws");

        try {
            WebSocketManager client = new WebSocketManager(new URI(wsUrl), logger, player, proxyServer, (islandData) -> {
                String ip = islandData.get("internal_ip_address").getAsString();
                int port = islandData.get("internal_port").getAsInt();
                proxyServer.getScheduler().buildTask(plugin, () -> attemptSingleConnection(player, ip, port)).schedule();
            });
            client.connect();
            plugin.getWebSocketManagers().put(playerUuid, client);
        } catch (Exception e) {
            logger.error("Failed to create WebSocket client for player {}: {}", player.getUsername(), e.getMessage(), e);
            player.sendMessage(Component.text("Failed to listen for island status updates.", NamedTextColor.RED));
        }
    }
    
    private void attemptSingleConnection(Player player, String ip, int port) {
        String serverName = "island-" + player.getUniqueId();
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));

        proxyServer.getServer(serverName).ifPresent(s -> proxyServer.unregisterServer(s.getServerInfo()));
        RegisteredServer serverToConnect = proxyServer.registerServer(serverInfo);
        
        player.createConnectionRequest(serverToConnect).connect()
            .thenAccept(result -> {
                if (!result.isSuccessful()) {
                    player.sendMessage(Component.text("Failed to connect. Please try `/myisland` again in a moment.", NamedTextColor.RED));
                    proxyServer.unregisterServer(serverInfo);
                }
            });
    }
    
    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

}