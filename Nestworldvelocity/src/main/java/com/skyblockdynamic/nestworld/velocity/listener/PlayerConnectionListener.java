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
            if ("RUNNING".equalsIgnoreCase(status)) {
                String ip = islandData.get("internal_ip_address").getAsString();
                int port = islandData.get("internal_port").getAsInt();
                attemptSingleConnection(player, ip, port); // FIX 1: Використовуємо новий метод
                return CompletableFuture.completedFuture(null);
            } else if (status.startsWith("STOPPED") || status.startsWith("FROZEN") || status.startsWith("ERROR")) {
                return apiClient.requestIslandStart(player.getUniqueId());
            } else {
                scheduleNextPoll(player, attempt + 1);
                return CompletableFuture.completedFuture(null);
            }
        }).thenAccept(startResponse -> {
            if (startResponse != null) {
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