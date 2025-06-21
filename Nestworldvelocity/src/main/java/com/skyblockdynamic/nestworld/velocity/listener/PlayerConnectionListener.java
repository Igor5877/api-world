package com.skyblockdynamic.nestworld.velocity.listener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class PlayerConnectionListener {

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final ApiClient apiClient;
    private final PluginConfig config;

    public PlayerConnectionListener(NestworldVelocityPlugin plugin, ProxyServer proxyServer, Logger logger, ApiClient apiClient, PluginConfig config) {
        this.plugin = plugin;
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.apiClient = apiClient;
        this.config = config;
    }

    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getUsername();

        logger.info("Player {} ({}) choosing initial server. Intercepting to check SkyBlock island status...", playerName, playerUuid);

        // Set a default/fallback server immediately.
        // The player will be sent here if their island isn't ready or if an error occurs.
        Optional<RegisteredServer> fallbackServer = proxyServer.getServer(config.getFallbackServerName());
        if (fallbackServer.isEmpty()) {
            logger.error("Fallback server '{}' not found! Player {} will be disconnected if island check fails.", config.getFallbackServerName(), playerName);
            // Disconnect if no fallback, or handle as a severe error
            player.disconnect(Component.text("Server configuration error. Please contact an administrator.").color(NamedTextColor.RED));
            return;
        }
        event.setInitialServer(fallbackServer.get());

        // Asynchronously check island status and potentially redirect.
        apiClient.getIslandDetails(playerUuid)
            .thenAcceptAsync(apiResponse -> { // Changed to thenAcceptAsync as we are not returning for the event
                if (!apiResponse.isSuccess()) {
                    if (apiResponse.statusCode() == 404) {
                        logger.info("Player {} ({}) has no island. They will remain on fallback server: {}.", playerName, playerUuid, config.getFallbackServerName());
                        // Optionally send a message: player.sendMessage(Component.text("You don't have an island yet! Type /island create on the hub."));
                    } else {
                        logger.error("Error fetching island details for {}: Status {}, Body: {}. Player remains on fallback.", playerName, apiResponse.statusCode(), apiResponse.body());
                        player.sendMessage(Component.text("Could not fetch your island details. Please try re-logging.").color(NamedTextColor.RED));
                    }
                    return; // Player stays on fallback
                }

                try {
                    JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    logger.info("Island status for {}: {}", playerName, status);

                    if ("SERVER_READY".equalsIgnoreCase(status)) {
                        logger.info("Island for {} is SERVER_READY. Attempting direct connection.", playerName);
                        String ip = islandData.has("internal_ip_address") && !islandData.get("internal_ip_address").isJsonNull() ? islandData.get("internal_ip_address").getAsString() : null;
                        int port = islandData.has("internal_port") && !islandData.get("internal_port").isJsonNull() ? islandData.get("internal_port").getAsInt() : 0;
                        if (ip != null && port != 0) {
                            connectPlayerToIsland(player, ip, port, "your island");
                        } else {
                            logger.error("Island for {} is SERVER_READY but IP/Port is missing. Player remains on fallback.", playerName);
                            player.sendMessage(Component.text("Your island is ready but its address is unavailable. Please contact support.").color(NamedTextColor.RED));
                        }
                    } else if ("RUNNING".equalsIgnoreCase(status)) {
                        logger.info("Island for {} is RUNNING (container is up). Waiting for Minecraft server to confirm ready. Starting polling...", playerName);
                        player.sendMessage(Component.text("Your island instance is running. Waiting for Minecraft server to initialize...").color(NamedTextColor.YELLOW));
                        pollIslandStatusAndConnect(player, playerUuid, 0); // Start polling, expecting it to go to SERVER_READY
                    } else if ("STOPPED".equalsIgnoreCase(status) || "FROZEN".equalsIgnoreCase(status) ||
                               "ERROR_START".equalsIgnoreCase(status) || "ERROR_CREATE".equalsIgnoreCase(status) ||
                               "QUEUED_START".equalsIgnoreCase(status) || "PENDING_CREATION".equalsIgnoreCase(status) || "PENDING_START".equalsIgnoreCase(status)) {
                        logger.info("Island for {} is {}. Attempting to start and then poll for server ready...", playerName, status);
                        player.sendMessage(Component.text("Your island is preparing, please wait...").color(NamedTextColor.YELLOW));
                        pollIslandStatusAndConnect(player, playerUuid, 0); // This will first try to start, then poll for SERVER_READY
                    } else {
                        logger.warn("Unknown island status for {}: {}. Player remains on fallback.", playerName, status);
                        player.sendMessage(Component.text("Your island has an unknown status. Please contact support.").color(NamedTextColor.RED));
                    }
                } catch (JsonSyntaxException | IllegalStateException e) {
                    logger.error("Error parsing island details JSON for {}: {}. Player remains on fallback.", playerName, e.getMessage());
                    player.sendMessage(Component.text("Error reading your island data. Please contact an admin.").color(NamedTextColor.RED));
                }
            }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule()) // Ensure CompletableFuture stages run on Velocity scheduler/async executor
            .exceptionally(ex -> {
                logger.error("Unhandled exception while checking island for {}: {}. Player remains on fallback.", playerName, ex.getMessage(), ex);
                player.sendMessage(Component.text("An unexpected error occurred while connecting to your island. Please try re-logging.").color(NamedTextColor.RED));
                return null;
            });
    }

    private void pollIslandStatusAndConnect(Player player, UUID playerUuid, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) {
            logger.warn("Max polling attempts reached for {}. Player remains on fallback server.", player.getUsername());
            player.sendMessage(Component.text("Your island took too long to start. Please try re-logging or contact support.").color(NamedTextColor.RED));
            // No need to connect to fallback, they are already there.
            return;
        }

        apiClient.requestIslandStart(playerUuid).thenComposeAsync(startResponse -> {
            if (!startResponse.isSuccess() && startResponse.statusCode() != 409 && startResponse.statusCode() != 200 && startResponse.statusCode() != 202) {
                logger.error("Failed to send/confirm start command for {}'s island (attempt {}), status: {}. Player remains on fallback.",
                    player.getUsername(), attempt + 1, startResponse.statusCode());
                player.sendMessage(Component.text("Could not start your island. Please try re-logging or contact support.").color(NamedTextColor.RED));
                return CompletableFuture.completedFuture(null); // End polling
            }
            
            // If start request accepted (202) or already running/starting (200, 409), proceed to get details
            return apiClient.getIslandDetails(playerUuid);
        }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule())
        .thenAcceptAsync(detailsResponse -> {
            if (detailsResponse == null) return; // Polling ended due to start failure

            if (!detailsResponse.isSuccess()) {
                logger.error("Failed to get island details for {} after start request (attempt {}). Status: {}. Retrying.",
                    player.getUsername(), attempt + 1, detailsResponse.statusCode());
                scheduleNextPoll(player, playerUuid, attempt + 1);
                return;
            }

            try {
                JsonObject islandData = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
                String status = islandData.get("status").getAsString();
                logger.info("Polling for {}: attempt {}, status {}", player.getUsername(), attempt + 1, status);

                if ("SERVER_READY".equalsIgnoreCase(status)) {
                    logger.info("Island for {} is SERVER_READY after polling. Attempting connection.", player.getUsername());
                    String ip = islandData.has("internal_ip_address") && !islandData.get("internal_ip_address").isJsonNull() ? islandData.get("internal_ip_address").getAsString() : null;
                    int port = islandData.has("internal_port") && !islandData.get("internal_port").isJsonNull() ? islandData.get("internal_port").getAsInt() : 0;
                    if (ip != null && port != 0) {
                        player.sendMessage(Component.text("Your Minecraft server is ready! Connecting...").color(NamedTextColor.GREEN));
                        connectPlayerToIsland(player, ip, port, "your island");
                    } else {
                        logger.error("Island for {} is SERVER_READY but IP/Port is missing. Player remains on fallback.", player.getUsername());
                        player.sendMessage(Component.text("Your island is ready but its address is unavailable. Please contact support.").color(NamedTextColor.RED));
                    }
                } else if ("RUNNING".equalsIgnoreCase(status)) {
                    logger.info("Island for {} is RUNNING (container up), waiting for SERVER_READY. Continuing poll.", player.getUsername());
                    player.sendMessage(Component.text("Island instance running, waiting for Minecraft server... (Attempt " + (attempt+1) + ")").color(NamedTextColor.YELLOW));
                    scheduleNextPoll(player, playerUuid, attempt + 1);
                } else if ("QUEUED_START".equalsIgnoreCase(status) || "PENDING_START".equalsIgnoreCase(status) || "PENDING_CREATION".equalsIgnoreCase(status)) {
                    player.sendMessage(Component.text("Your island is still preparing... (Status: " + status + ", Attempt " + (attempt + 1) + ")").color(NamedTextColor.YELLOW));
                    scheduleNextPoll(player, playerUuid, attempt + 1);
                } else { // ERROR, STOPPED, FROZEN after a start attempt, etc.
                    logger.warn("Island for {} is in status {} after polling (was expecting SERVER_READY). Player remains on fallback.", player.getUsername(), status);
                    player.sendMessage(Component.text("Your island could not be fully started (Status: " + status + "). Please contact support.").color(NamedTextColor.RED));
                }
            } catch (JsonSyntaxException | IllegalStateException e) {
                logger.error("Error parsing island details JSON during polling for {}: {}. Retrying.", player.getUsername(), e.getMessage());
                scheduleNextPoll(player, playerUuid, attempt + 1);
            }
        }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule())
        .exceptionally(ex -> {
            logger.error("Exception during polling for {}: {}. Player remains on fallback.", player.getUsername(), ex.getMessage(), ex);
            player.sendMessage(Component.text("An error occurred while preparing your island. Please try re-logging.").color(NamedTextColor.RED));
            return null;
        });
    }
    
    private void scheduleNextPoll(Player player, UUID playerUuid, int nextAttempt) {
        proxyServer.getScheduler()
            .buildTask(plugin, () -> pollIslandStatusAndConnect(player, playerUuid, nextAttempt))
            .delay(config.getPollingIntervalMillis(), TimeUnit.MILLISECONDS)
            .schedule();
    }

    private void connectPlayerToIsland(Player player, String ip, int port, String serverDisplayName) {
        String serverName = "island-" + player.getUniqueId().toString();
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));

        Optional<RegisteredServer> existingServer = proxyServer.getServer(serverName);
        RegisteredServer islandServerToConnect;

        if (existingServer.isPresent()) {
            // TODO: Potentially update the server's address if it can change, though unlikely for dynamic LXD.
            // For simplicity, we assume if it's registered, its info is current or Velocity handles it.
            // If IP can change for same container name, might need to unregister/re-register or update.
            // proxyServer.unregisterServer(existingServer.get().getServerInfo()); // Example if unregistering first
            islandServerToConnect = existingServer.get();
            logger.info("Server {} already registered. Attempting to connect player {} to it.", serverName, player.getUsername());
        } else {
            try {
                islandServerToConnect = proxyServer.registerServer(serverInfo);
                logger.info("Registered new dynamic server {} ({}:{}) for player {}.", serverName, ip, port, player.getUsername());
            } catch (Exception e) { // Catch broader exceptions during registration
                logger.error("Failed to register dynamic server {} for {}: {}", serverName, player.getUsername(), e.getMessage(), e);
                player.sendMessage(Component.text("Error configuring connection to your island. You remain on the hub.").color(NamedTextColor.RED));
                return; // Stay on fallback
            }
        }
        
        if (islandServerToConnect != null) {
            player.createConnectionRequest(islandServerToConnect).connect().thenAccept(result -> {
                if (result.isSuccessful()) {
                    player.sendMessage(Component.text("Successfully connected to " + serverDisplayName + "!").color(NamedTextColor.GREEN));
                    logger.info("Player {} successfully connected to their island {}.", player.getUsername(), serverName);
                } else {
                    player.sendMessage(Component.text("Failed to connect to " + serverDisplayName + ". Reason: " + result.getReasonComponent().map(Component::toString).orElse("Unknown reason")).color(NamedTextColor.RED));
                    logger.warn("Player {} failed to connect to their island {}. Reason: {}", player.getUsername(), serverName, result.getReasonComponent().map(Component::toString).orElse("Unknown reason"));
                    // Optionally, try to send them back to hub if the connection to island fails post-selection
                    // proxyServer.getServer(config.getFallbackServerName()).ifPresent(hub -> player.createConnectionRequest(hub).connect());
                }
            }).exceptionally(e -> {
                logger.error("Exception occurred when trying to connect player {} to {}: {}", player.getUsername(), serverName, e.getMessage(), e);
                player.sendMessage(Component.text("An error occurred while connecting to " + serverDisplayName + ".").color(NamedTextColor.RED));
                return null;
            });
        } else {
             logger.error("Island server object for {} became null before connection attempt for {}.", serverName, player.getUsername());
             player.sendMessage(Component.text("Could not establish connection to your island (server object null).").color(NamedTextColor.RED));
        }
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getUsername();

        // We don't know if the player was on their island or on the hub.
        // The API should be idempotent or handle "stop" requests for non-active islands gracefully.
        logger.info("Player {} ({}) disconnected from proxy. Sending stop request to API.", playerName, playerUuid);

        apiClient.requestIslandStop(playerUuid)
            .thenAcceptAsync(apiResponse -> {
                if (apiResponse.isSuccess()) {
                    logger.info("Successfully sent stop request for {}'s island. Status: {}", playerName, apiResponse.statusCode());
                } else {
                    // It's common for this to be a 404 if the island wasn't running or doesn't exist, which is fine.
                    // Or if it was already stopped/stopping.
                    if (apiResponse.statusCode() == 404) {
                        logger.info("Stop request for {}'s island returned 404. Island likely not running or already stopped.", playerName);
                    } else {
                        logger.error("Error sending stop request for {}'s island: Status {}, Body: {}", 
                                     playerName, apiResponse.statusCode(), apiResponse.body());
                    }
                }
            }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule()) // Execute on Velocity scheduler
            .exceptionally(ex -> {
                logger.error("Exception sending stop request for {}'s island: {}", playerName, ex.getMessage(), ex);
                return null;
            });
    }
}
