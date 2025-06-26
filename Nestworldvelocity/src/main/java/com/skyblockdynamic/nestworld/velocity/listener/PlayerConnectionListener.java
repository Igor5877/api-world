package com.skyblockdynamic.nestworld.velocity.listener;

// ... всі ваші імпорти залишаються тими ж ...
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


public class PlayerConnectionListener {

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final ApiClient apiClient;
    private final PluginConfig config;
    private final Map<UUID, ScheduledTask> pendingStopTasks = new ConcurrentHashMap<>();

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

        // Скасування відкладеної зупинки (логіка з попереднього кроку)
        ScheduledTask pendingTask = pendingStopTasks.remove(playerUuid);
        if (pendingTask != null) {
            pendingTask.cancel();
            logger.info("Player {} reconnected. Cancelled pending island stop task.", playerName);
        }

        // Завжди встановлюємо fallback-сервер
        Optional<RegisteredServer> fallbackServer = proxyServer.getServer(config.getFallbackServerName());
        if (fallbackServer.isEmpty()) {
            logger.error("Fallback server '{}' not found! Player {} will be disconnected if island check fails.", config.getFallbackServerName(), playerName);
            player.disconnect(Component.text("Server configuration error. Please contact an administrator.").color(NamedTextColor.RED));
            return;
        }
        event.setInitialServer(fallbackServer.get());
        
        // ADDED: Перевіряємо, чи ввімкнена функція авто-підключення
        if (!config.isAutoRedirectToIslandEnabled()) {
            logger.info("Auto-redirect to island is disabled in config. Player {} will connect to fallback server.", playerName);
            return; // Просто виходимо, гравець залишиться на fallback-сервері
        }

        logger.info("Player {} ({}) choosing initial server. Intercepting to check SkyBlock island status...", playerName, playerUuid);

        // Решта логіки авто-підключення виконується тільки якщо функція ввімкнена
        apiClient.getIslandDetails(playerUuid)
            .thenAcceptAsync(apiResponse -> {
                // ... весь інший код цього методу залишається без змін ...
                if (!apiResponse.isSuccess()) {
                    if (apiResponse.statusCode() == 404) {
                        logger.info("Player {} ({}) has no island. They will remain on fallback server: {}.", playerName, playerUuid, config.getFallbackServerName());
                    } else {
                        logger.error("Error fetching island details for {}: Status {}, Body: {}. Player remains on fallback.", playerName, apiResponse.statusCode(), apiResponse.body());
                        player.sendMessage(Component.text("Could not fetch your island details. Please try re-logging.").color(NamedTextColor.RED));
                    }
                    return;
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
                        pollIslandStatusAndConnect(player, playerUuid, 0);
                    } else {
                        logger.info("Island for {} is {}. Attempting to start and then poll for server ready...", playerName, status);
                        player.sendMessage(Component.text("Your island is preparing, please wait...").color(NamedTextColor.YELLOW));
                        pollIslandStatusAndConnect(player, playerUuid, 0);
                    }
                } catch (JsonSyntaxException | IllegalStateException e) {
                    logger.error("Error parsing island details JSON for {}: {}. Player remains on fallback.", playerName, e.getMessage());
                    player.sendMessage(Component.text("Error reading your island data. Please contact an admin.").color(NamedTextColor.RED));
                }
            }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule())
            .exceptionally(ex -> {
                logger.error("Unhandled exception while checking island for {}: {}. Player remains on fallback.", playerName, ex.getMessage(), ex);
                player.sendMessage(Component.text("An unexpected error occurred while connecting to your island. Please try re-logging.").color(NamedTextColor.RED));
                return null;
            });
    }

    // ... решта файлу залишається без змін ...
    private void pollIslandStatusAndConnect(Player player, UUID playerUuid, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) {
            logger.warn("Max polling attempts reached for {}. Player remains on fallback server.", player.getUsername());
            player.sendMessage(Component.text("Your island took too long to start. Please try re-logging or contact support.").color(NamedTextColor.RED));
            return;
        }

        apiClient.requestIslandStart(playerUuid).thenComposeAsync(startResponse -> {
            if (!startResponse.isSuccess() && startResponse.statusCode() != 409 && startResponse.statusCode() != 200 && startResponse.statusCode() != 202) {
                logger.error("Failed to send/confirm start command for {}'s island (attempt {}), status: {}. Player remains on fallback.",
                    player.getUsername(), attempt + 1, startResponse.statusCode());
                player.sendMessage(Component.text("Could not start your island. Please try re-logging or contact support.").color(NamedTextColor.RED));
                return CompletableFuture.completedFuture(null);
            }
            return apiClient.getIslandDetails(playerUuid);
        }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule())
        .thenAcceptAsync(detailsResponse -> {
            if (detailsResponse == null) return;

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
                    scheduleNextPoll(player, playerUuid, attempt + 1);
                } else if ("QUEUED_START".equalsIgnoreCase(status) || "PENDING_START".equalsIgnoreCase(status) || "PENDING_CREATION".equalsIgnoreCase(status)) {
                    scheduleNextPoll(player, playerUuid, attempt + 1);
                } else {
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
            islandServerToConnect = existingServer.get();
        } else {
            islandServerToConnect = proxyServer.registerServer(serverInfo);
        }
        player.createConnectionRequest(islandServerToConnect).connect();
    }
    
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        String playerName = player.getUsername();

        logger.info("Player {} disconnected. Scheduling island stop in 5 minutes.", playerName);

        Runnable stopTaskRunnable = () -> {
            pendingStopTasks.remove(playerUuid);
            logger.info("Executing delayed stop for {}'s island.", playerName);
            apiClient.requestIslandStop(playerUuid)
                .thenAcceptAsync(apiResponse -> {
                    if (apiResponse.isSuccess()) {
                        logger.info("Successfully sent delayed stop request for {}'s island.", playerName);
                    } else if (apiResponse.statusCode() != 404) {
                        logger.error("Error sending delayed stop request for {}'s island: Status {}, Body: {}",
                                playerName, apiResponse.statusCode(), apiResponse.body());
                    }
                }, runnable -> proxyServer.getScheduler().buildTask(plugin, runnable).schedule())
                .exceptionally(ex -> {
                    logger.error("Exception in delayed stop task for {}: {}", playerName, ex.getMessage(), ex);
                    return null;
                });
        };

        ScheduledTask scheduledTask = proxyServer.getScheduler()
                .buildTask(plugin, stopTaskRunnable)
                .delay(5, TimeUnit.MINUTES)
                .schedule();

        pendingStopTasks.put(playerUuid, scheduledTask);
    }
}