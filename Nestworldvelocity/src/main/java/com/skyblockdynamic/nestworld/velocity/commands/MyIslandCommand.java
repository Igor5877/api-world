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
    private final com.skyblockdynamic.nestworld.velocity.locale.LocaleManager localeManager;

    public MyIslandCommand(NestworldVelocityPlugin plugin, ProxyServer proxyServer, Logger logger, ApiClient apiClient, PluginConfig config, com.skyblockdynamic.nestworld.velocity.locale.LocaleManager localeManager) {
        this.plugin = plugin;
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.apiClient = apiClient;
        this.config = config;
        this.localeManager = localeManager;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            source.sendMessage(localeManager.getComponent("en", "command.player_only", NamedTextColor.RED));
            return;
        }
        Player player = (Player) source;
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        String islandServerName = "island-" + player.getUniqueId().toString();

        if (player.getCurrentServer().isPresent() && player.getCurrentServer().get().getServerInfo().getName().equals(islandServerName)) {
            player.sendMessage(localeManager.getComponent(lang, "myisland.already_on_island", NamedTextColor.YELLOW));
            return;
        }
        
        initiateIslandConnection(player);
    }

    private void initiateIslandConnection(Player player) {
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        player.sendMessage(localeManager.getComponent(lang, "myisland.status.checking", NamedTextColor.YELLOW));
        
        apiClient.getIslandDetails(player.getUniqueId()).thenAccept(apiResponse -> {
            if (!apiResponse.isSuccess() && apiResponse.statusCode() != 404) {
                player.sendMessage(Component.text(localeManager.getMessage(lang, "myisland.status.error").replace("{status_code}", String.valueOf(apiResponse.statusCode())), NamedTextColor.RED));
                return;
            }

            if (apiResponse.isSuccess()) {
                try {
                    JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    boolean minecraftReady = islandData.has("minecraft_ready") && islandData.get("minecraft_ready").getAsBoolean();

                    if ("RUNNING".equalsIgnoreCase(status) && minecraftReady) {
                        player.sendMessage(localeManager.getComponent(lang, "myisland.status.ready", NamedTextColor.GREEN));
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
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        player.sendMessage(localeManager.getComponent(lang, "myisland.status.not_ready", NamedTextColor.YELLOW));
        plugin.getAwaitingConnection().add(player.getUniqueId());

        apiClient.requestIslandStart(player.getUniqueId(), player.getUsername())
            .thenAccept(startResponse -> {
                if (startResponse.isSuccess() || startResponse.statusCode() == 409) {
                    player.sendMessage(localeManager.getComponent(lang, "myisland.status.start_accepted", NamedTextColor.AQUA));
                    connectToWebSocket(player);
                } else {
                    player.sendMessage(Component.text(localeManager.getMessage(lang, "myisland.status.start_failed").replace("{status_code}", String.valueOf(startResponse.statusCode())), NamedTextColor.RED));
                    plugin.getAwaitingConnection().remove(player.getUniqueId());
                }
            })
            .exceptionally(ex -> {
                player.sendMessage(localeManager.getComponent(lang, "myisland.status.start_error", NamedTextColor.RED));
                logger.error("Exception in requestIslandStart chain for {}: {}", player.getUsername(), ex.getMessage(), ex);
                plugin.getAwaitingConnection().remove(player.getUniqueId());
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
            }, plugin);
            client.connect();
            plugin.getWebSocketManagers().put(playerUuid, client);
        } catch (Exception e) {
            logger.error("Failed to create WebSocket client for player {}: {}", player.getUsername(), e.getMessage(), e);
            String lang = player.getPlayerSettings().getLocale().getLanguage();
            player.sendMessage(localeManager.getComponent(lang, "myisland.status.websocket_fail", NamedTextColor.RED));
        }
    }
    
    private void attemptSingleConnection(Player player, String ip, int port) {
        String serverName = "island-" + player.getUniqueId();
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));
        String lang = player.getPlayerSettings().getLocale().getLanguage();

        proxyServer.getServer(serverName).ifPresent(s -> proxyServer.unregisterServer(s.getServerInfo()));
        RegisteredServer serverToConnect = proxyServer.registerServer(serverInfo);
        
        player.createConnectionRequest(serverToConnect).connect()
            .thenAccept(result -> {
                if (!result.isSuccessful()) {
                    player.sendMessage(localeManager.getComponent(lang, "myisland.connect.fail", NamedTextColor.RED));
                    proxyServer.unregisterServer(serverInfo);
                }
            });
    }
    
    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    public void teleportToIsland(Player player, String targetPlayerName) {
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        proxyServer.getPlayer(targetPlayerName).ifPresent(targetPlayer -> {
            apiClient.getIslandDetails(targetPlayer.getUniqueId()).thenAccept(apiResponse -> {
                if (apiResponse.isSuccess()) {
                    try {
                        JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                        String status = islandData.get("status").getAsString();
                        boolean minecraftReady = islandData.has("minecraft_ready") && islandData.get("minecraft_ready").getAsBoolean();

                        if ("RUNNING".equalsIgnoreCase(status) && minecraftReady) {
                            player.sendMessage(Component.text(localeManager.getMessage(lang, "tpa.teleporting").replace("{player_name}", targetPlayerName), NamedTextColor.GREEN));
                            String ip = islandData.get("internal_ip_address").getAsString();
                            int port = islandData.get("internal_port").getAsInt();
                            attemptSingleConnection(player, ip, port);
                        } else {
                            player.sendMessage(Component.text(localeManager.getMessage(lang, "tpa.island_not_available").replace("{player_name}", targetPlayerName), NamedTextColor.RED));
                        }
                    } catch (Exception e) {
                        logger.error("Error parsing island status for {}: {}", targetPlayerName, e.getMessage());
                        player.sendMessage(Component.text(localeManager.getMessage(lang, "tpa.connect_error").replace("{player_name}", targetPlayerName), NamedTextColor.RED));
                    }
                } else {
                    player.sendMessage(Component.text(localeManager.getMessage(lang, "tpa.island_not_found").replace("{player_name}", targetPlayerName), NamedTextColor.RED));
                }
            });
        });
    }
}