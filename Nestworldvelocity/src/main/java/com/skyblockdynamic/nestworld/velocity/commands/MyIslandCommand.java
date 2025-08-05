package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.skyblockdynamic.nestworld.velocity.network.WebSocketManager;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        UUID playerUuid = player.getUniqueId();
        String islandServerName = "island-" + playerUuid.toString();

        if (player.getCurrentServer().isPresent() && player.getCurrentServer().get().getServerInfo().getName().equals(islandServerName)) {
            player.sendMessage(Component.text("You are already on your island.", NamedTextColor.YELLOW));
            return;
        }

        if (config.isUseWebsockets()) {
            handleWebSocketConnection(player, playerUuid);
        } else {
            handleHttpPolling(player, playerUuid);
        }
    }

    public void teleportToIsland(Player player, Player targetPlayer) {
        apiClient.getIslandDetails(targetPlayer.getUniqueId()).thenAccept(apiResponse -> {
            if (apiResponse.isSuccess()) {
                JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                String ip = islandData.get("internal_ip_address").getAsString();
                int port = islandData.get("internal_port").getAsInt();
                attemptSingleConnection(player, ip, port, targetPlayer.getUniqueId());
            } else {
                player.sendMessage(Component.text("Could not get island details for " + targetPlayer.getUsername(), NamedTextColor.RED));
            }
        });
    }

    private void handleWebSocketConnection(Player player, UUID playerUuid) {
        player.sendMessage(Component.text("Checking your island's status...", NamedTextColor.YELLOW));

        apiClient.getIslandDetails(playerUuid).thenAccept(detailsResponse -> {
            if (!detailsResponse.isSuccess()) {
                player.sendMessage(Component.text("Could not retrieve your island information. API Status: " + detailsResponse.statusCode(), NamedTextColor.RED));
                return;
            }

            JsonObject islandData = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
            String status = islandData.get("status").getAsString();
            boolean minecraftReady = islandData.has("minecraft_ready") && islandData.get("minecraft_ready").getAsBoolean();

            if ("RUNNING".equalsIgnoreCase(status) && minecraftReady) {
                player.sendMessage(Component.text("Your island is already running and ready! Connecting...", NamedTextColor.GREEN));
                String ip = islandData.get("internal_ip_address").getAsString();
                int port = islandData.get("internal_port").getAsInt();
                attemptSingleConnection(player, ip, port, player.getUniqueId());
            } else if (status.startsWith("PENDING_") || ("RUNNING".equalsIgnoreCase(status) && !minecraftReady)) {
                player.sendMessage(Component.text("Island is starting up. Listening for status updates...", NamedTextColor.AQUA));
                connectToWebSocket(player, playerUuid);
            } else { // STOPPED, ERROR, etc.
                player.sendMessage(Component.text("Requesting island start...", NamedTextColor.YELLOW));
                apiClient.requestIslandStart(playerUuid)
                    .thenAccept(startResponse -> {
                        if (startResponse.isSuccess() || startResponse.statusCode() == 409) {
                            player.sendMessage(Component.text("Island start request accepted. Listening for status updates...", NamedTextColor.AQUA));
                            connectToWebSocket(player, playerUuid);
                        } else {
                            player.sendMessage(Component.text("Failed to send start request to your island. API Status: " + startResponse.statusCode(), NamedTextColor.RED));
                            logger.warn("API failed to start island for {}: Status {}, Body: {}", playerUuid, startResponse.statusCode(), startResponse.body());
                        }
                    })
                    .exceptionally(ex -> {
                        player.sendMessage(Component.text("Error requesting island start.", NamedTextColor.RED));
                        logger.error("Exception in requestIslandStart chain for {}: {}", player.getUsername(), ex.getMessage(), ex);
                        return null;
                    });
            }
        });
    }

    private void connectToWebSocket(Player player, UUID playerUuid) {
        String httpUrl = config.getApiUrl();
        String baseUrl = httpUrl.substring(0, httpUrl.indexOf("/api/v1"));
        String wsUrl = baseUrl.replaceFirst("http", "ws") + "/ws/" + playerUuid.toString();
        
        try {
            WebSocketManager client = new WebSocketManager(new URI(wsUrl), logger, player, proxyServer, (islandData) -> {
                String ip = islandData.get("internal_ip_address").getAsString();
                int port = islandData.get("internal_port").getAsInt();
                attemptSingleConnection(player, ip, port, player.getUniqueId());
            });
            client.connect();
        } catch (Exception e) {
            logger.error("Failed to create WebSocket client for player {}: {}", player.getUsername(), e.getMessage(), e);
            player.sendMessage(Component.text("Failed to listen for island status updates.", NamedTextColor.RED));
        }
    }

    private void handleHttpPolling(Player player, UUID playerUuid) {
        player.sendMessage(Component.text("Checking your island's status...", NamedTextColor.YELLOW));
        apiClient.getIslandDetails(playerUuid)
                .thenCompose(apiResponse -> {
                    if (!apiResponse.isSuccess()) {
                        player.sendMessage(Component.text("Could not retrieve your island information. API Status: " + apiResponse.statusCode(), NamedTextColor.RED));
                        logger.warn("API failed to get island details for {}: Status {}, Body: {}", playerUuid, apiResponse.statusCode(), apiResponse.body());
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                        String status = islandData.get("status").getAsString();
                        boolean minecraftReady = islandData.has("minecraft_ready") && islandData.get("minecraft_ready").getAsBoolean();

                        logger.info("Island status for {}: {}, minecraft_ready: {}", playerUuid, status, minecraftReady);

                        if ("STOPPED".equalsIgnoreCase(status) || "FROZEN".equalsIgnoreCase(status) || status.startsWith("ERROR")) {
                            player.sendMessage(Component.text("Your island is currently " + status.toLowerCase() + ". Attempting to start it...", NamedTextColor.YELLOW));
                            return startAndPollForRunning(player);
                        }
                        else if (status.startsWith("PENDING_")) {
                            player.sendMessage(Component.text("Your island is currently " + status.toLowerCase() + ". Please wait, checking status...", NamedTextColor.YELLOW));
                            return pollForRunning(player, 0);
                        }
                        else if ("RUNNING".equalsIgnoreCase(status)) {
                            if (minecraftReady) {
                                player.sendMessage(Component.text("Your island is running and ready! Attempting to connect...", NamedTextColor.GREEN));
                                String ip = islandData.get("internal_ip_address").getAsString();
                                int port = islandData.get("internal_port").getAsInt();
                                return attemptSingleConnection(player, ip, port, player.getUniqueId());
                            } else {
                                player.sendMessage(Component.text("Island container is running, but Minecraft is still starting. Waiting...", NamedTextColor.YELLOW));
                                return pollForMinecraftReady(player, 0);
                            }
                        }
                        else {
                            player.sendMessage(Component.text("Your island is in an unknown state: " + status, NamedTextColor.RED));
                            logger.warn("Player {} island in unknown state: {}", player.getUsername(), status);
                            return CompletableFuture.completedFuture(null);
                        }
                    } catch (Exception e) {
                        player.sendMessage(Component.text("An unexpected error occurred while checking your island's data.", NamedTextColor.RED));
                        logger.error("Unexpected exception processing island details for {}: {}", playerUuid, e.getMessage(), e);
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .exceptionally(ex -> {
                    player.sendMessage(Component.text("An error occurred while communicating with the island service.", NamedTextColor.RED));
                    logger.error("Exception in getIslandDetails chain for {}: {}", player.getUsername(), ex.getMessage(), ex);
                    return (Void) null;
                });
    }

    private CompletableFuture<Void> startAndPollForRunning(Player player) {
        return apiClient.requestIslandStart(player.getUniqueId())
            .thenCompose(startResponse -> {
                if (startResponse.isSuccess() || startResponse.statusCode() == 409) { 
                    player.sendMessage(Component.text("Island start request " + (startResponse.statusCode() == 409 ? "noted (already in process or running)" : "accepted") + ". Waiting for container to be ready...", NamedTextColor.AQUA));
                    return pollForRunning(player, 0);
                } else {
                    player.sendMessage(Component.text("Failed to send start request to your island. API Status: " + startResponse.statusCode(), NamedTextColor.RED));
                    logger.warn("API failed to start island for {}: Status {}, Body: {}", player.getUniqueId(), startResponse.statusCode(), startResponse.body());
                    return CompletableFuture.completedFuture(null);
                }
            })
            .exceptionally(ex -> {
                player.sendMessage(Component.text("Error requesting island start.", NamedTextColor.RED));
                logger.error("Exception in requestIslandStart chain for {}: {}", player.getUsername(), ex.getMessage(), ex);
                return (Void) null;
            });
    }

    private CompletableFuture<Void> pollForRunning(Player player, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) {
            player.sendMessage(Component.text("Your island's container took too long to start/become running.", NamedTextColor.RED));
            logger.warn("Polling timeout for container RUNNING state for player {}", player.getUsername());
            return CompletableFuture.completedFuture(null);
        }

        return apiClient.getIslandDetails(player.getUniqueId())
            .thenCompose(detailsResponse -> {
                if (!detailsResponse.isSuccess()) {
                    player.sendMessage(Component.text("Waiting for container... (status check failed, retrying)", NamedTextColor.GRAY));
                    logger.warn("getIslandDetails failed during pollForRunning for {}, attempt {}. Status: {}", player.getUsername(), attempt, detailsResponse.statusCode());
                    return schedulePollTask(player, attempt + 1, true);
                }
                try {
                    JsonObject islandData = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    logger.debug("Polling for RUNNING for {}: attempt {}, status: {}", player.getUsername(), attempt, status);

                    if ("RUNNING".equalsIgnoreCase(status)) {
                        player.sendMessage(Component.text("Island container is RUNNING. Now checking if Minecraft server is ready...", NamedTextColor.AQUA));
                        return pollForMinecraftReady(player, 0);
                    } else if (status.startsWith("PENDING_")) {
                         player.sendMessage(Component.text("Island status: " + status.toLowerCase() + ". Waiting for container...", NamedTextColor.YELLOW));
                         return schedulePollTask(player, attempt + 1, true);
                    } else if (status.startsWith("ERROR_")){
                        player.sendMessage(Component.text("Island encountered an error: " + status.toLowerCase() + ". Please contact support.", NamedTextColor.RED));
                        logger.error("Island for {} entered error state {} during pollForRunning.", player.getUsername(), status);
                        return CompletableFuture.completedFuture(null);
                    }
                     else {
                        player.sendMessage(Component.text("Island container is " + status.toLowerCase() + ". Trying to start it...", NamedTextColor.YELLOW));
                        return startAndPollForRunning(player);
                    }
                } catch (Exception e) {
                    logger.warn("Exception during pollForRunning for player {}: {}", player.getUsername(), e.getMessage(), e);
                    player.sendMessage(Component.text("Error checking island status. Retrying...", NamedTextColor.GRAY));
                    return schedulePollTask(player, attempt + 1, true);
                }
            })
            .exceptionally(ex -> {
                player.sendMessage(Component.text("An error occurred while polling for container status.", NamedTextColor.RED));
                logger.error("Exception in pollForRunning chain for {}: {}", player.getUsername(), ex.getMessage(), ex);
                 return (Void) null;
            });
    }

    private CompletableFuture<Void> pollForMinecraftReady(Player player, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) { 
            player.sendMessage(Component.text("Your island's Minecraft server took too long to become ready after the container started.", NamedTextColor.RED));
            logger.warn("Polling timeout for Minecraft READY state for player {}", player.getUsername());
            return CompletableFuture.completedFuture(null);
        }

        return apiClient.getIslandDetails(player.getUniqueId())
            .thenCompose(detailsResponse -> {
                if (!detailsResponse.isSuccess()) {
                    player.sendMessage(Component.text("Waiting for Minecraft server... (status check failed, retrying)", NamedTextColor.GRAY));
                    logger.warn("getIslandDetails failed during pollForMinecraftReady for {}, attempt {}. Status: {}", player.getUsername(), attempt, detailsResponse.statusCode());
                    return schedulePollTask(player, attempt + 1, false);
                }
                try {
                    JsonObject islandData = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    boolean minecraftReady = islandData.has("minecraft_ready") && islandData.get("minecraft_ready").getAsBoolean();
                    logger.debug("Polling for Minecraft READY for {}: attempt {}, status: {}, mc_ready: {}", player.getUsername(), attempt, status, minecraftReady);

                    if ("RUNNING".equalsIgnoreCase(status) && minecraftReady) {
                        player.sendMessage(Component.text("Minecraft server is ready! Connecting...", NamedTextColor.GREEN));
                        String ip = islandData.get("internal_ip_address").getAsString();
                        int port = islandData.get("internal_port").getAsInt();
                        return attemptSingleConnection(player, ip, port, player.getUniqueId());
                    } else if ("RUNNING".equalsIgnoreCase(status) && !minecraftReady) {
                        player.sendMessage(Component.text("Container is running, but Minecraft server is still starting. Waiting...", NamedTextColor.YELLOW));
                        return schedulePollTask(player, attempt + 1, false);
                    } else {
                        player.sendMessage(Component.text("Island is no longer running (status: " + status + ") while waiting for Minecraft server.", NamedTextColor.RED));
                        logger.warn("Island for {} changed status from RUNNING to {} while waiting for minecraft_ready.", player.getUsername(), status);
                        return CompletableFuture.completedFuture(null);
                    }
                } catch (Exception e) {
                    logger.warn("Exception during pollForMinecraftReady for player {}: {}", player.getUsername(), e.getMessage(), e);
                    player.sendMessage(Component.text("Error checking Minecraft readiness. Retrying...", NamedTextColor.GRAY));
                    return schedulePollTask(player, attempt + 1, false);
                }
            })
            .exceptionally(ex -> {
                player.sendMessage(Component.text("An error occurred while polling for Minecraft readiness.", NamedTextColor.RED));
                logger.error("Exception in pollForMinecraftReady chain for {}: {}", player.getUsername(), ex.getMessage(), ex);
                return (Void) null;
            });
    }

    private CompletableFuture<Void> schedulePollTask(Player player, int nextAttempt, boolean isPollingForContainerRunning) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        proxyServer.getScheduler()
            .buildTask(plugin, () -> {
                CompletableFuture<Void> nextPollFuture;
                if (isPollingForContainerRunning) {
                    nextPollFuture = pollForRunning(player, nextAttempt);
                } else {
                    nextPollFuture = pollForMinecraftReady(player, nextAttempt);
                }
                if (nextPollFuture != null) {
                    nextPollFuture.thenAccept(v -> future.complete(null))
                                  .exceptionally(ex -> {
                                      future.completeExceptionally(ex);
                                      return (Void) null;
                                  });
                } else {
                    future.complete(null);
                }
            })
            .delay(config.getPollingIntervalMillis(), TimeUnit.MILLISECONDS)
            .schedule();
        return future;
    }
    
    private CompletableFuture<Void> attemptSingleConnection(Player player, String ip, int port, final UUID islandOwnerUuid) {
        String serverName = "island-" + islandOwnerUuid;
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));

        Optional<RegisteredServer> existingServer = proxyServer.getServer(serverName);
        if (existingServer.isPresent()) {
            if (!existingServer.get().getServerInfo().getAddress().equals(serverInfo.getAddress())) {
                proxyServer.unregisterServer(existingServer.get().getServerInfo());
                proxyServer.registerServer(serverInfo);
            }
        } else {
            proxyServer.registerServer(serverInfo);
        }

        RegisteredServer serverToConnect = proxyServer.getServer(serverName).get();
        
        logger.info("Attempting to connect player {} to server {} ({}:{})", player.getUsername(), serverName, ip, port);

        return player.createConnectionRequest(serverToConnect).connect()
            .thenApply(result -> {
                if (result.isSuccessful()) {
                    String message = player.getUniqueId().equals(islandOwnerUuid) ? "your island" : "the island";
                    player.sendMessage(Component.text("Successfully connected to " + message + "!", NamedTextColor.GREEN));
                    logger.info("Player {} successfully connected to {}", player.getUsername(), serverName);
                } else {
                    String reasonString = result.getReasonComponent()
                        .map(c -> PlainComponentSerializer.plain().serialize(c).toLowerCase())
                        .orElse("unknown reason");
                    logger.warn("Player {} failed to connect to {}: {}", player.getUsername(), serverName, reasonString);

                    if (reasonString.contains("still starting") || reasonString.contains("connection refused") || reasonString.contains("timed out")) {
                        player.sendMessage(Component.text("The island server is still loading or couldn't be reached. Please wait a moment and try again.", NamedTextColor.YELLOW));
                    } else {
                        player.sendMessage(Component.text("Failed to connect: " + reasonString, NamedTextColor.RED));
                    }
                }
                return (Void) null;
            })
             .exceptionally(ex -> {
                player.sendMessage(Component.text("An error occurred during connection attempt.", NamedTextColor.RED));
                logger.error("Exception during attemptSingleConnection for {}: {}", player.getUsername(), ex.getMessage(), ex);
                return (Void) null;
            });
    }
    
    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}