package com.skyblockdynamic.nestworld.velocity.listener;

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
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;
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
        ScheduledTask pendingTask = pendingStopTasks.remove(player.getUniqueId());
        if (pendingTask != null) {
            pendingTask.cancel();
            logger.info("Player {} reconnected. Cancelled pending island stop task.", player.getUsername());
        }
        Optional<RegisteredServer> fallbackServer = proxyServer.getServer(config.getFallbackServerName());
        if (fallbackServer.isEmpty()) {
            player.disconnect(Component.text("Server configuration error.").color(NamedTextColor.RED));
            return;
        }
        event.setInitialServer(fallbackServer.get());
        if (!config.isAutoRedirectToIslandEnabled()) {
            return;
        }
        pollForRunningAndConnect(player, 0);
    }
    
    private void pollForRunningAndConnect(Player player, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) {
            player.sendMessage(Component.text("Your island took too long to start. Please use /myisland to try again.", NamedTextColor.RED));
            return;
        }
        apiClient.getIslandDetails(player.getUniqueId()).thenComposeAsync(detailsResponse -> {
            if (!detailsResponse.isSuccess()) {
                if (detailsResponse.statusCode() == 404) {
                    player.sendMessage(Component.text("Preparing your island, please wait...", NamedTextColor.YELLOW));
                    return apiClient.requestIslandStart(player.getUniqueId());
                }
                scheduleNextPoll(player, attempt + 1);
                return CompletableFuture.completedFuture(null);
            }
            JsonObject islandData = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
            String status = islandData.get("status").getAsString();
            boolean minecraftReady = islandData.has("minecraft_ready") && islandData.get("minecraft_ready").getAsBoolean();

            if ("RUNNING".equalsIgnoreCase(status)) {
                if (minecraftReady) {
                    String ip = islandData.get("internal_ip_address").getAsString();
                    int port = islandData.get("internal_port").getAsInt();
                    logger.info("Player {}'s island is RUNNING and minecraft_ready. Attempting connection to {}:{}", player.getUsername(), ip, port);
                    attemptSingleConnection(player, ip, port);
                    return CompletableFuture.completedFuture(null); // Stop polling
                } else {
                    logger.info("Player {}'s island is RUNNING but minecraft_ready is false. Will continue polling. Attempt: {}", player.getUsername(), attempt + 1);
                    player.sendMessage(Component.text("Your island is running, but Minecraft is still loading. Please wait...", NamedTextColor.AQUA));
                    scheduleNextPoll(player, attempt + 1);
                    return CompletableFuture.completedFuture(null); // Continue polling
                }
            } else if (status.startsWith("STOPPED") || status.startsWith("FROZEN") || status.startsWith("ERROR_START") || status.startsWith("ERROR_CREATE") || status.startsWith("ERROR")) {
                logger.info("Player {}'s island is {}. Requesting start. Attempt: {}", player.getUsername(), status, attempt + 1);
                player.sendMessage(Component.text("Your island is " + status.toLowerCase() + ". Attempting to start it...", NamedTextColor.YELLOW));
                return apiClient.requestIslandStart(player.getUniqueId()); // This returns a CF, which will be handled by thenAccept
            } else if (status.startsWith("PENDING_")) { // PENDING_START, PENDING_CREATION, PENDING_STOP, PENDING_FREEZE
                 logger.info("Player {}'s island is {}. Waiting. Attempt: {}", player.getUsername(), status, attempt + 1);
                 player.sendMessage(Component.text("Your island is currently " + status.toLowerCase() + ". Please wait...", NamedTextColor.GRAY));
                 scheduleNextPoll(player, attempt + 1);
                 return CompletableFuture.completedFuture(null); // Continue polling
            }
             else {
                // Default case if status is unknown or not handled above
                logger.warn("Player {}'s island has an unhandled status: {}. Will continue polling. Attempt: {}", player.getUsername(), status, attempt + 1);
                player.sendMessage(Component.text("Your island is in an unexpected state ("+ status +"). Trying again...", NamedTextColor.GOLD));
                scheduleNextPoll(player, attempt + 1);
                return CompletableFuture.completedFuture(null);
            }
        }).thenAccept(startResponse -> {
            // This block is reached if apiClient.requestIslandStart was called.
            // If startResponse is not null, it means a start request was made.
            // We then need to schedule the next poll.
            if (startResponse != null) { // apiClient.requestIslandStart was called
                if (startResponse.isSuccess()) {
                    logger.info("Player {}'s island start request was accepted by API (Status {}). Scheduling next poll.", player.getUsername(), startResponse.statusCode());
                } else {
                     logger.warn("Player {}'s island start request failed or was not successful (Status {}). Scheduling next poll anyway. Body: {}", player.getUsername(), startResponse.statusCode(), startResponse.body());
                }
                scheduleNextPoll(player, attempt + 1);
            }
        });
    }
    
    private void scheduleNextPoll(Player player, int nextAttempt) {
        proxyServer.getScheduler()
            .buildTask(plugin, () -> pollForRunningAndConnect(player, nextAttempt))
            .delay(config.getPollingIntervalMillis(), TimeUnit.MILLISECONDS)
            .schedule();
    }
    
    // FIX 1: Нова логіка з єдиною спробою підключення
    private void attemptSingleConnection(Player player, String ip, int port) {
        if (!player.isActive()) return;
        String serverName = "island-" + player.getUniqueId();
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));
        RegisteredServer serverToConnect = proxyServer.getServer(serverName).orElseGet(() -> proxyServer.registerServer(serverInfo));
        player.createConnectionRequest(serverToConnect).connect()
            .thenAccept(result -> {
                if (result.isSuccessful()) {
                    player.sendMessage(Component.text("Successfully connected to your island!", NamedTextColor.GREEN));
                    return;
                }
                String reasonString = result.getReasonComponent()
                    .map(c -> PlainComponentSerializer.plain().serialize(c).toLowerCase())
                    .orElse("unknown reason");
                if (reasonString.contains("still starting")) {
                    player.sendMessage(Component.text("Your island is still loading. Please wait 1-2 minutes and use /myisland to connect.", NamedTextColor.YELLOW));
                    // Після невдалого підключення, гравець залишається на fallback-сервері
                } else {
                    player.sendMessage(Component.text("Failed to connect to your island. You will stay in the lobby.", NamedTextColor.RED));
                }
            });
    }
    
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID playerUuid = player.getUniqueId();
        logger.info("Player {} disconnected. Scheduling island stop in 5 minutes.", player.getUsername());
        Runnable stopTaskRunnable = () -> {
            pendingStopTasks.remove(playerUuid);
            apiClient.requestIslandStop(playerUuid);
        };
        ScheduledTask scheduledTask = proxyServer.getScheduler()
            .buildTask(plugin, stopTaskRunnable)
            .delay(5, TimeUnit.MINUTES)
            .schedule();
        pendingStopTasks.put(playerUuid, scheduledTask);
    }
}