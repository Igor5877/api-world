package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
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
import net.kyori.adventure.text.serializer.plain.PlainComponentSerializer;
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

        // FIX 2: Перевіряємо, чи гравець вже на своєму острові.
        if (player.getCurrentServer().isPresent() && player.getCurrentServer().get().getServerInfo().getName().equals(islandServerName)) {
            player.sendMessage(Component.text("You are already on your island.", NamedTextColor.YELLOW));
            return;
        }

        player.sendMessage(Component.text("Checking your island's status...", NamedTextColor.YELLOW));
        apiClient.getIslandDetails(playerUuid)
            .thenCompose(apiResponse -> {
                if (!apiResponse.isSuccess()) {
                    player.sendMessage(Component.text("Could not retrieve your island information.", NamedTextColor.RED));
                    return CompletableFuture.completedFuture(null);
                }
                try {
                    JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    if ("STOPPED".equalsIgnoreCase(status) || "FROZEN".equalsIgnoreCase(status) || status.startsWith("PENDING") || status.startsWith("ERROR")) {
                        player.sendMessage(Component.text("Your island is currently " + status.toLowerCase() + ". Starting it up...", NamedTextColor.YELLOW));
                        return startAndPollForRunning(player);
                    } else if ("RUNNING".equalsIgnoreCase(status)) {
                        player.sendMessage(Component.text("Your island's container is running. Attempting to connect...", NamedTextColor.AQUA));
                        String ip = islandData.get("internal_ip_address").getAsString();
                        int port = islandData.get("internal_port").getAsInt();
                        return attemptSingleConnection(player, ip, port); // FIX 1: Використовуємо новий метод
                    } else {
                        player.sendMessage(Component.text("Your island is in an unknown state: " + status, NamedTextColor.RED));
                        return CompletableFuture.completedFuture(null);
                    }
                } catch (Exception e) {
                    player.sendMessage(Component.text("An error occurred while reading your island's data.", NamedTextColor.RED));
                    return CompletableFuture.completedFuture(null);
                }
            });
    }

    private CompletableFuture<Void> startAndPollForRunning(Player player) {
        return apiClient.requestIslandStart(player.getUniqueId())
            .thenCompose(startResponse -> {
                if (startResponse.isSuccess() || startResponse.statusCode() == 409) {
                    player.sendMessage(Component.text("Start request accepted. Waiting for container to be ready...", NamedTextColor.AQUA));
                    return pollForRunning(player, 0);
                } else {
                    player.sendMessage(Component.text("Failed to start your island.", NamedTextColor.RED));
                    return CompletableFuture.completedFuture(null);
                }
            });
    }

    private CompletableFuture<Void> pollForRunning(Player player, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) {
            player.sendMessage(Component.text("Your island took too long to start its container.", NamedTextColor.RED));
            return CompletableFuture.completedFuture(null);
        }
        return apiClient.getIslandDetails(player.getUniqueId())
            .thenCompose(detailsResponse -> {
                if (!detailsResponse.isSuccess()) {
                    return scheduleNextPoll(player, attempt + 1);
                }
                try {
                    JsonObject islandData = JsonParser.parseString(detailsResponse.body()).getAsJsonObject();
                    String status = islandData.get("status").getAsString();
                    if ("RUNNING".equalsIgnoreCase(status)) {
                        player.sendMessage(Component.text("Container is ready! Now connecting to Minecraft...", NamedTextColor.GREEN));
                        String ip = islandData.get("internal_ip_address").getAsString();
                        int port = islandData.get("internal_port").getAsInt();
                        return attemptSingleConnection(player, ip, port); // FIX 1: Використовуємо новий метод
                    } else {
                        return scheduleNextPoll(player, attempt + 1);
                    }
                } catch (Exception e) {
                    return scheduleNextPoll(player, attempt + 1);
                }
            });
    }

    private CompletableFuture<Void> scheduleNextPoll(Player player, int nextAttempt) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        proxyServer.getScheduler()
            .buildTask(plugin, () -> pollForRunning(player, nextAttempt).thenAccept(future::complete))
            .delay(config.getPollingIntervalMillis(), TimeUnit.MILLISECONDS)
            .schedule();
        return future;
    }
    
    // FIX 1: Нова логіка з єдиною спробою підключення
    private CompletableFuture<Void> attemptSingleConnection(Player player, String ip, int port) {
        String serverName = "island-" + player.getUniqueId();
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));
        RegisteredServer serverToConnect = proxyServer.getServer(serverName).orElseGet(() -> proxyServer.registerServer(serverInfo));

        return player.createConnectionRequest(serverToConnect).connect()
            .thenAccept(result -> {
                if (result.isSuccessful()) {
                    player.sendMessage(Component.text("Successfully connected to your island!", NamedTextColor.GREEN));
                    return;
                }

                String reasonString = result.getReasonComponent()
                    .map(c -> PlainComponentSerializer.plain().serialize(c).toLowerCase())
                    .orElse("unknown reason");

                // Перевіряємо, чи сервер все ще запускається
                if (reasonString.contains("still starting")) {
                    player.sendMessage(Component.text("Your island is still loading. Please wait 1-2 minutes and try the command again.", NamedTextColor.YELLOW));
                } else {
                    player.sendMessage(Component.text("Failed to connect: " + reasonString, NamedTextColor.RED));
                }
            });
    }
    
    /*
    // Закоментована логіка автоматичних спроб, як ви просили
    private CompletableFuture<Void> connectWithRetries(Player player, String ip, int port, int retryAttempt) {
        // ...
    }
    */
    
    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}