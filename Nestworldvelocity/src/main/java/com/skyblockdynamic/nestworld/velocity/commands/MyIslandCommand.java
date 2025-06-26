package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.skyblockdynamic.nestworld.velocity.network.ApiResponse;
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
        this.config = config; // Зберігаємо конфігурацію
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

        player.sendMessage(Component.text("Attempting to connect you to your island...", NamedTextColor.YELLOW));

        // Використовуємо асинхронний підхід з CompletableFuture
        apiClient.getIslandDetails(playerUuid)
            .thenCompose(apiResponse -> {
                if (!apiResponse.isSuccess()) {
                    if (apiResponse.statusCode() == 404) { // Острів не знайдено
                        player.sendMessage(Component.text("You don't have an island yet. Creating one for you...", NamedTextColor.AQUA));
                        // Логіка створення острова (можна додати окремий метод в ApiClient)
                        // Для прикладу, просто пробуємо запустити, що може бути еквівалентом створення
                        return startAndPollIsland(player);
                    } else {
                        player.sendMessage(Component.text("Could not retrieve your island information.", NamedTextColor.RED));
                        logger.warn("Failed to get island details for {}: status {}, body: {}", player.getUsername(), apiResponse.statusCode(), apiResponse.body());
                        return CompletableFuture.completedFuture(null);
                    }
                }

                try {
                    JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();

                    // Статуси, при яких острів готовий до підключення
                    if ("SERVER_READY".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status)) {
                        player.sendMessage(Component.text("Your island is running. Connecting...", NamedTextColor.GREEN));
                        String ip = islandData.get("internal_ip_address").getAsString();
                        int port = islandData.get("internal_port").getAsInt();
                        String containerName = "island-" + playerUuid;
                        return connectPlayerToIsland(player, containerName, ip, port);
                    } else {
                        // Для всіх інших статусів (STOPPED, FROZEN, PENDING_START і т.д.) запускаємо процес старту і очікування
                        player.sendMessage(Component.text("Your island is currently " + status.toLowerCase() + ". Attempting to start it...", NamedTextColor.YELLOW));
                        return startAndPollIsland(player);
                    }
                } catch (JsonSyntaxException | IllegalStateException e) {
                    logger.error("Error parsing JSON for {}: {}", player.getUsername(), e.getMessage());
                    player.sendMessage(Component.text("An error occurred while reading your island's data.", NamedTextColor.RED));
                    return CompletableFuture.completedFuture(null);
                }
            })
            .exceptionally(e -> {
                logger.error("[MyIslandCommand] Error processing command for {}: {}", player.getUsername(), e.getMessage(), e);
                player.sendMessage(Component.text("An unexpected error occurred. Please try again later.", NamedTextColor.RED));
                return null;
            });
    }
    
    private CompletableFuture<Void> startAndPollIsland(Player player) {
        // FIX 1: Правильна назва методу - requestIslandStart, не startIsland
        return apiClient.requestIslandStart(player.getUniqueId())
            .thenCompose(startResponse -> {
                // FIX 2: Перевіряємо .isSuccess() з ApiResponse
                // 409 Conflict означає, що острів вже стартує або працює, що є успіхом для нас.
                if (startResponse.isSuccess() || startResponse.statusCode() == 409) {
                    player.sendMessage(Component.text("Island start initiated. Waiting for it to be ready...", NamedTextColor.AQUA));
                    // Починаємо "опитування" стану острова
                    return pollForServerReady(player, 0);
                } else {
                    player.sendMessage(Component.text("Failed to start your island. Please contact an administrator.", NamedTextColor.RED));
                    logger.error("Failed to start island for {}: status {}, body: {}", player.getUsername(), startResponse.statusCode(), startResponse.body());
                    return CompletableFuture.completedFuture(null);
                }
            });
    }

    private CompletableFuture<Void> pollForServerReady(Player player, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) {
            player.sendMessage(Component.text("Your island took too long to start. Please try again later.", NamedTextColor.RED));
            logger.warn("Polling timed out for player {}", player.getUsername());
            return CompletableFuture.completedFuture(null);
        }

        return apiClient.getIslandDetails(player.getUniqueId())
            .thenCompose(detailsResponse -> {
                if (!detailsResponse.isSuccess()) {
                    logger.warn("Polling for {}: failed to get details on attempt {}. Retrying...", player.getUsername(), attempt + 1);
                    return scheduleNextPoll(player, attempt + 1);
                }

                try {
                    // FIX 3: .body() повертає String, який треба розпарсити
                    JsonObject islandData = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    logger.info("Polling for {}: attempt {}, status is {}", player.getUsername(), attempt + 1, status);

                    if ("SERVER_READY".equalsIgnoreCase(status) || "RUNNING".equalsIgnoreCase(status)) {
                        player.sendMessage(Component.text("Island is ready! Connecting...", NamedTextColor.GREEN));
                        String ip = islandData.get("internal_ip_address").getAsString();
                        int port = islandData.get("internal_port").getAsInt();
                        String containerName = "island-" + player.getUniqueId();
                        return connectPlayerToIsland(player, containerName, ip, port);
                    } else {
                        // Якщо статус інший (напр. PENDING_START), продовжуємо опитування
                        return scheduleNextPoll(player, attempt + 1);
                    }
                } catch (JsonSyntaxException | IllegalStateException e) {
                    logger.error("Polling for {}: error parsing JSON on attempt {}. Retrying...", player.getUsername(), attempt + 1, e);
                    return scheduleNextPoll(player, attempt + 1);
                }
            });
    }

    private CompletableFuture<Void> scheduleNextPoll(Player player, int nextAttempt) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        proxyServer.getScheduler()
            .buildTask(plugin, () -> pollForServerReady(player, nextAttempt).thenAccept(v -> future.complete(null)))
            .delay(config.getPollingIntervalMillis(), TimeUnit.MILLISECONDS)
            .schedule();
        return future;
    }


    private CompletableFuture<Void> connectPlayerToIsland(Player player, String containerName, String ipAddress, int port) {
        ServerInfo serverInfo = new ServerInfo(containerName, new InetSocketAddress(ipAddress, port));
        
        // Unregister server if it exists with a different address, then register the new one
        proxyServer.getServer(containerName).ifPresent(existingServer -> {
            if (!existingServer.getServerInfo().getAddress().equals(serverInfo.getAddress())) {
                logger.info("Updating address for server {}", containerName);
                proxyServer.unregisterServer(existingServer.getServerInfo());
            }
        });
        
        RegisteredServer serverToConnect = proxyServer.getServer(containerName)
                                            .orElseGet(() -> proxyServer.registerServer(serverInfo));

        return player.createConnectionRequest(serverToConnect).connect()
            .thenAccept(result -> {
                if (result.isSuccessful()) {
                    player.sendMessage(Component.text("Successfully connected to your island!", NamedTextColor.GREEN));
                } else {
                    String reason = result.getReasonComponent().map(Component::toString).orElse("Unknown reason");
                    player.sendMessage(Component.text("Failed to connect: " + reason, NamedTextColor.RED));
                    logger.warn("Failed to connect player {} to {}: {}", player.getUsername(), containerName, reason);
                }
            }).exceptionally(e -> {
                logger.error("Exception while connecting player {} to {}:{}", player.getUsername(), ipAddress, port, e);
                player.sendMessage(Component.text("Error during connection process.", NamedTextColor.RED));
                return null;
            });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}