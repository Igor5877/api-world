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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

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

        // We need to do this asynchronously and then set the initial server.
        // The event allows setting a CompletableFuture for the server.
        CompletableFuture<Optional<RegisteredServer>> futureServer = apiClient.getIslandDetails(playerUuid)
            .thenComposeAsync(apiResponse -> {
                if (!apiResponse.isSuccess()) {
                    if (apiResponse.statusCode() == 404) { // Island not found for player
                        logger.info("Player {} ({}) has no island. Sending to fallback server: {}.", playerName, playerUuid, config.getFallbackServerName());
                        return CompletableFuture.completedFuture(proxyServer.getServer(config.getFallbackServerName()));
                    }
                    logger.error("Error fetching island details for {}: Status {}, Body: {}", playerName, apiResponse.statusCode(), apiResponse.body());
                    player.disconnect(net.kyori.adventure.text.Component.text("Error connecting to your island service. Please try again later."));
                    return CompletableFuture.completedFuture(Optional.empty()); // Should not happen if disconnected
                }

                // Successfully got island details, parse status and IP
                try {
                    JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    logger.info("Island status for {}: {}", playerName, status);

                    if ("RUNNING".equalsIgnoreCase(status)) {
                        String ip = islandData.has("internal_ip_address") && !islandData.get("internal_ip_address").isJsonNull() ? islandData.get("internal_ip_address").getAsString() : null;
                        int port = islandData.has("internal_port") && !islandData.get("internal_port").isJsonNull() ? islandData.get("internal_port").getAsInt() : 0;
                        if (ip != null && port != 0) {
                            return connectPlayerToIsland(player, ip, port);
                        } else {
                            logger.error("Island for {} is RUNNING but IP/Port is missing. Sending to fallback.", playerName);
                            return CompletableFuture.completedFuture(proxyServer.getServer(config.getFallbackServerName()));
                        }
                    } else if ("STOPPED".equalsIgnoreCase(status) || "FROZEN".equalsIgnoreCase(status) || 
                               "ERROR_START".equalsIgnoreCase(status) || "ERROR_CREATE".equalsIgnoreCase(status) || 
                               "QUEUED_START".equalsIgnoreCase(status) || "PENDING_CREATION".equalsIgnoreCase(status) || "PENDING_START".equalsIgnoreCase(status)) {
                        // Island exists but is not running or needs attention, attempt to start it.
                        // If it's PENDING_CREATION, the start call might be too early, but API should handle it.
                        logger.info("Island for {} is {}. Attempting to start/poll...", playerName, status);
                        player.sendMessage(net.kyori.adventure.text.Component.text("Your island is starting up, please wait..."));
                        return pollIslandStatus(player, playerUuid, 0);
                    } else {
                        logger.warn("Unknown island status for {}: {}. Sending to fallback.", playerName, status);
                        return CompletableFuture.completedFuture(proxyServer.getServer(config.getFallbackServerName()));
                    }
                } catch (JsonSyntaxException | IllegalStateException e) {
                    logger.error("Error parsing island details JSON for {}: {}", playerName, e.getMessage());
                    player.disconnect(net.kyori.adventure.text.Component.text("Error reading your island data. Please contact an admin."));
                    return CompletableFuture.completedFuture(Optional.empty());
                }
            }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule()) // Ensure CompletableFuture stages run on Velocity scheduler/async executor
            .exceptionally(ex -> {
                logger.error("Unhandled exception while checking island for {}: {}", playerName, ex.getMessage(), ex);
                player.disconnect(net.kyori.adventure.text.Component.text("An unexpected error occurred. Please try again."));
                return Optional.empty();
            });
        
        event.setInitialServer((@Nullable RegisteredServer) futureServer);
    }

    private CompletableFuture<Optional<RegisteredServer>> pollIslandStatus(Player player, UUID playerUuid, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) {
            logger.warn("Max polling attempts reached for {}. Sending to fallback server.", player.getUsername());
            player.sendMessage(net.kyori.adventure.text.Component.text("Your island took too long to start. Please try re-logging or contact support."));
            return CompletableFuture.completedFuture(proxyServer.getServer(config.getFallbackServerName()));
        }

        // First, request a start, in case it's STOPPED/FROZEN or to confirm it's in a startable path
        return apiClient.requestIslandStart(playerUuid).thenComposeAsync(startResponse -> {
            if (!startResponse.isSuccess() && startResponse.statusCode() != 409) { // 409 might mean already starting or running
                 logger.error("Failed to send start command for {}'s island (attempt {}), status: {}. Sending to fallback.", 
                    player.getUsername(), attempt + 1, startResponse.statusCode());
                 player.sendMessage(net.kyori.adventure.text.Component.text("Could not initiate island start. Please try re-logging."));
                return CompletableFuture.completedFuture(proxyServer.getServer(config.getFallbackServerName()));
            }
            
            // If start request accepted (202) or potentially already started/queued (e.g. 409 if API handles it that way), proceed to get details
            return apiClient.getIslandDetails(playerUuid).thenComposeAsync(detailsResponse -> {
                if (!detailsResponse.isSuccess()) {
                    logger.error("Failed to get island details for {} after start request (attempt {}). Status: {}. Retrying or sending to fallback.", 
                        player.getUsername(), attempt + 1, detailsResponse.statusCode());
                    // Decide if to retry or fallback based on error
                    return scheduleNextPoll(player, playerUuid, attempt + 1);
                }

                try {
                    JsonObject islandData = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    logger.info("Polling for {}: attempt {}, status {}", player.getUsername(), attempt + 1, status);

                    if ("RUNNING".equalsIgnoreCase(status)) {
                        String ip = islandData.has("internal_ip_address") && !islandData.get("internal_ip_address").isJsonNull() ? islandData.get("internal_ip_address").getAsString() : null;
                        int port = islandData.has("internal_port") && !islandData.get("internal_port").isJsonNull() ? islandData.get("internal_port").getAsInt() : 0;
                        if (ip != null && port != 0) {
                            player.sendMessage(net.kyori.adventure.text.Component.text("Your island is ready! Connecting..."));
                            return connectPlayerToIsland(player, ip, port);
                        } else {
                             logger.error("Island for {} is RUNNING but IP/Port is missing after polling. Retrying or sending to fallback.", player.getUsername());
                             return scheduleNextPoll(player, playerUuid, attempt + 1); // Or fallback
                        }
                    } else if ("QUEUED_START".equalsIgnoreCase(status)) {
                        // Player is in queue, keep polling but maybe less frequently or inform them.
                        player.sendMessage(net.kyori.adventure.text.Component.text("You are in the queue to start your island... (Attempt " + (attempt+1) + ")"));
                        return scheduleNextPoll(player, playerUuid, attempt + 1);
                    } else if ("PENDING_START".equalsIgnoreCase(status) || "PENDING_CREATION".equalsIgnoreCase(status)) {
                         player.sendMessage(net.kyori.adventure.text.Component.text("Your island is still preparing... (Attempt " + (attempt+1) + ")"));
                        return scheduleNextPoll(player, playerUuid, attempt + 1);
                    } else { // ERROR, STOPPED, etc.
                        logger.warn("Island for {} is in status {} after polling. Sending to fallback.", player.getUsername(), status);
                        player.sendMessage(net.kyori.adventure.text.Component.text("Your island could not be started. Please contact support."));
                        return CompletableFuture.completedFuture(proxyServer.getServer(config.getFallbackServerName()));
                    }
                } catch (JsonSyntaxException | IllegalStateException e) {
                    logger.error("Error parsing island details JSON during polling for {}: {}", player.getUsername(), e.getMessage());
                    return scheduleNextPoll(player, playerUuid, attempt + 1); // Retry parsing or give up
                }
            }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule());
        }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule());
    }
    
    private CompletableFuture<Optional<RegisteredServer>> scheduleNextPoll(Player player, UUID playerUuid, int nextAttempt) {
        CompletableFuture<Optional<RegisteredServer>> nextPollFuture = new CompletableFuture<>();
        proxyServer.getScheduler()
            .buildTask(plugin, () -> pollIslandStatus(player, playerUuid, nextAttempt)
                                        .thenAccept(nextPollFuture::complete)
                                        .exceptionally(ex -> {
                                            nextPollFuture.completeExceptionally(ex);
                                            return null;
                                        }))
            .delay(config.getPollingIntervalMillis(), TimeUnit.MILLISECONDS)
            .schedule();
        return nextPollFuture;
    }

    private CompletableFuture<Optional<RegisteredServer>> connectPlayerToIsland(Player player, String ip, int port) {
        String serverName = "island-" + player.getUniqueId().toString(); // Unique name for this server registration
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));

        // Check if server is already registered. If so, use that. Otherwise, register.
        Optional<RegisteredServer> existingServer = proxyServer.getServer(serverName);
        if (existingServer.isPresent()) {
            // Potentially update ServerInfo if IP/port could change for a named server, though unlikely for dynamic ones.
            // For dynamically IP'd servers, it's often better to unregister and re-register if there's a change,
            // or ensure the name itself is tied to the specific IP:Port if they are very dynamic.
            // Here, we assume if it's registered with this name, it's the correct one.
             logger.info("Server {} already registered. Connecting player {} to it.", serverName, player.getUsername());
            return CompletableFuture.completedFuture(existingServer);
        } else {
            try {
                proxyServer.registerServer(serverInfo);
                logger.info("Registered new dynamic server {} ({}:{}) for player {}.", serverName, ip, port, player.getUsername());
                return CompletableFuture.completedFuture(proxyServer.getServer(serverName));
            } catch (Exception e) {
                logger.error("Failed to register dynamic server {} for {}: {}", serverName, player.getUsername(), e.getMessage(), e);
                player.sendMessage(net.kyori.adventure.text.Component.text("Error configuring connection to your island. Sending to hub."));
                return CompletableFuture.completedFuture(proxyServer.getServer(config.getFallbackServerName()));
            }
        }
    }
}
