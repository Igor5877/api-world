package com.skyblockdynamic.nestworld.velocity.listener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.skyblockdynamic.nestworld.velocity.network.ApiResponse;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Listens for player connection events.
 */
public class PlayerConnectionListener {

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final ApiClient apiClient;
    private final PluginConfig config;
    private final Map<UUID, ScheduledTask> pendingStopTasks = new ConcurrentHashMap<>();
    private final Set<UUID> pendingStartPlayers = ConcurrentHashMap.newKeySet();
    private final Gson gson = new Gson();

    /**
     * Constructs a new PlayerConnectionListener.
     *
     * @param plugin      The plugin instance.
     * @param proxyServer The proxy server.
     * @param logger      The logger.
     * @param apiClient   The API client.
     * @param config      The plugin configuration.
     */
    public PlayerConnectionListener(NestworldVelocityPlugin plugin, ProxyServer proxyServer, Logger logger, ApiClient apiClient, PluginConfig config) {
        this.plugin = plugin;
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.apiClient = apiClient;
        this.config = config;
    }

    /**
     * Handles the player choose initial server event.
     *
     * @param event The player choose initial server event.
     */
    @Subscribe
    public void onPlayerChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        cancelPendingStopForPlayersTeam(player);

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
    
    /**
     * Polls for a running island and connects the player to it.
     *
     * @param player  The player.
     * @param attempt The current attempt number.
     */
    private void pollForRunningAndConnect(Player player, int attempt) {
        if (attempt >= config.getMaxPollingAttempts()) {
            pendingStartPlayers.remove(player.getUniqueId());
            player.sendMessage(Component.text("Your island took too long to start. Please use /myisland to try again.", NamedTextColor.RED));
            return;
        }
        apiClient.getIslandDetails(player.getUniqueId()).thenComposeAsync(detailsResponse -> {
            if (!detailsResponse.isSuccess()) {
                if (detailsResponse.statusCode() == 404) {
                    player.sendMessage(Component.text("Preparing your island, please wait...", NamedTextColor.YELLOW));
                    pendingStartPlayers.add(player.getUniqueId());
                    return apiClient.requestIslandStart(player.getUniqueId(), player.getUsername());
                }
                scheduleNextPoll(player, attempt + 1);
                return CompletableFuture.completedFuture(new ApiResponse(0, ""));
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
                pendingStartPlayers.add(player.getUniqueId());
                return apiClient.requestIslandStart(player.getUniqueId(), player.getUsername()); // This returns a CF, which will be handled by thenAccept
            } else if (status.startsWith("PENDING_")) { // PENDING_START, PENDING_CREATION, PENDING_STOP, PENDING_FREEZE
                logger.info("Player {}'s island is {}. Waiting. Attempt: {}", player.getUsername(), status, attempt + 1);
                player.sendMessage(Component.text("Your island is currently " + status.toLowerCase() + ". Please wait...", NamedTextColor.GRAY));
                scheduleNextPoll(player, attempt + 1);
                return CompletableFuture.completedFuture(null); // Continue polling
            } else {
                // Default case if status is unknown or not handled above
                logger.warn("Player {}'s island has an unhandled status: {}. Will continue polling. Attempt: {}", player.getUsername(), status, attempt + 1);
                player.sendMessage(Component.text("Your island is in an unexpected state (" + status + "). Trying again...", NamedTextColor.GOLD));
                scheduleNextPoll(player, attempt + 1);
                return CompletableFuture.completedFuture(null);
            }
        }).thenAccept(startResponse -> {
            if (startResponse != null) { 
                if (startResponse.isSuccess()) {
                    logger.info("Player {}'s island start request was accepted by API (Status {}). Scheduling next poll.", player.getUsername(), startResponse.statusCode());
                } else {
                    logger.warn("Player {}'s island start request failed or was not successful (Status {}). Scheduling next poll anyway. Body: {}", player.getUsername(), startResponse.statusCode(), startResponse.body());
                    pendingStartPlayers.remove(player.getUniqueId());
                }
                scheduleNextPoll(player, attempt + 1);
            }
        });
    }
    
    /**
     * Schedules the next poll for a player's island.
     *
     * @param player      The player.
     * @param nextAttempt The next attempt number.
     */
    private void scheduleNextPoll(Player player, int nextAttempt) {
        long delay = Math.min(
            config.getPollingIntervalMillis() * (long) Math.pow(1.5, nextAttempt),
            30_000L
        );
        proxyServer.getScheduler()
            .buildTask(plugin, () -> pollForRunningAndConnect(player, nextAttempt))
            .delay(delay, TimeUnit.MILLISECONDS)
            .schedule();
    }
    
    /**
     * Attempts to connect a player to their island.
     *
     * @param player The player.
     * @param ip     The IP address of the island.
     * @param port   The port of the island.
     */
    private void attemptSingleConnection(Player player, String ip, int port) {
        if (!player.isActive()) {
            pendingStartPlayers.remove(player.getUniqueId());
            return;
        }
        String serverName = "island-" + player.getUniqueId();
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));
        RegisteredServer serverToConnect = proxyServer.getServer(serverName).orElseGet(() -> proxyServer.registerServer(serverInfo));
        player.createConnectionRequest(serverToConnect).connect()
            .thenAccept(result -> {
                if (result.isSuccessful()) {
                    pendingStartPlayers.remove(player.getUniqueId());
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
    
    /**
     * Handles the player disconnect event.
     *
     * @param event The player disconnect event.
     */
    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        UUID disconnectedPlayerUuid = player.getUniqueId();

        if (pendingStartPlayers.remove(disconnectedPlayerUuid)) {
            logger.info("Player {} disconnected while their island was starting. Requesting island stop to conserve resources.", player.getUsername());
            apiClient.requestIslandStop(disconnectedPlayerUuid);
            return; 
        }

        player.getCurrentServer().ifPresent(serverConnection ->
                handleLeftIslandServer(player, disconnectedPlayerUuid, serverConnection.getServer(),
                        serverConnection.getServerInfo().getName()));
    }

    /**
     * Switching to a different backend server (e.g. /myisland, /spawn) while
     * staying connected to the proxy must NEVER schedule a full stop — only
     * a genuine {@link DisconnectEvent} (actually leaving the network) does
     * that. Sitting on the hub for a while is allowed to leave the island
     * FROZEN at most; that already happens on its own, server-side, when the
     * island's last player logs out (mod-side PlayerLoggedOutEvent) — no
     * Velocity involvement needed. This handler only cancels a pending stop
     * if the player comes back to an island before it fires.
     *
     * @param event The server-connected event.
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (event.getServer().getServerInfo().getName().startsWith("island-")) {
            // Coming back to (any) island before the scheduled stop fired —
            // cancel it, same as a fresh proxy login already does.
            cancelPendingStopForPlayersTeam(event.getPlayer());
        }
    }

    /** Cancels any pending scheduled stop for the team {@code player} belongs to. */
    private void cancelPendingStopForPlayersTeam(Player player) {
        apiClient.getTeam(player.getUniqueId()).thenAcceptAsync(apiResponse -> {
            if (apiResponse.isSuccess() && !apiResponse.body().isEmpty()) {
                try {
                    JsonObject teamData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                    if (teamData.has("owner_uuid")) {
                        UUID ownerUuid = UUID.fromString(teamData.get("owner_uuid").getAsString());

                        ScheduledTask pendingTask = pendingStopTasks.remove(ownerUuid);
                        if (pendingTask != null) {
                            pendingTask.cancel();
                            logger.info("Player {} (team member of {}) is back. Cancelled pending island stop for owner {}.",
                                        player.getUsername(), ownerUuid, ownerUuid);
                        }
                    }
                } catch (JsonSyntaxException e) {
                    logger.error("Error parsing team data for player {} on connect: {}", player.getUsername(), e.getMessage());
                }
            } else if (!apiResponse.isSuccess()) {
                logger.warn("Could not retrieve team data for {} on connect to cancel stop task. Status: {}, Body: {}",
                            player.getUsername(), apiResponse.statusCode(), apiResponse.body());
            }
        }, plugin.getExecutorService());
    }

    /**
     * Schedules a smart stop for {@code islandServer} if the player who just
     * left it (via disconnect or server switch) was the last team member
     * still on it — checking the WHOLE team's presence there, not just the
     * island owner. If any other team member remains, stopping is skipped;
     * a lone remaining teammate is enough to keep the island up.
     */
    private void handleLeftIslandServer(Player player, UUID leftPlayerUuid, RegisteredServer islandServer, String serverName) {
        if (!serverName.startsWith("island-")) {
            logger.info("Player {} left a non-island server ({}). No action taken.", player.getUsername(), serverName);
            return;
        }

        apiClient.getTeam(leftPlayerUuid).thenAcceptAsync(apiResponse -> {
            if (!apiResponse.isSuccess()) {
                logger.warn("Failed to get team data for {} leaving {}: {}. Unable to perform smart stop.", player.getUsername(), serverName, apiResponse.body());
                return;
            }

            if (apiResponse.body() == null || apiResponse.body().isEmpty()) {
                logger.debug("Player {} is not in a team (empty response body). No stop action taken.", player.getUsername());
                return;
            }

            try {
                JsonObject teamData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                if (!teamData.has("owner_uuid") || !teamData.has("members")) {
                    logger.debug("Player {} is not in a team (missing team data). No stop action taken.", player.getUsername());
                    return;
                }
                UUID ownerUuid = UUID.fromString(teamData.get("owner_uuid").getAsString());
                JsonArray membersArray = teamData.getAsJsonArray("members");

                Set<UUID> teamMemberUuids = new HashSet<>();
                for (JsonElement memberElement : membersArray) {
                    teamMemberUuids.add(UUID.fromString(memberElement.getAsJsonObject().get("player_uuid").getAsString()));
                }

                // Один прохід — розбиваємо на teamMembers і guests одразу
                List<Player> teamMembers = new ArrayList<>();
                List<Player> guests = new ArrayList<>();
                for (Player p : islandServer.getPlayersConnected()) {
                    if (p.getUniqueId().equals(leftPlayerUuid)) continue;
                    (teamMemberUuids.contains(p.getUniqueId()) ? teamMembers : guests).add(p);
                }
                boolean otherTeamMembersOnline = !teamMembers.isEmpty();

                if (!otherTeamMembersOnline) {
                    Optional<RegisteredServer> fallbackServerOpt = proxyServer.getServer(config.getFallbackServerName());
                    if (fallbackServerOpt.isEmpty()) {
                        logger.error("Fallback server '{}' not found. Cannot move guest players.", config.getFallbackServerName());
                    } else {
                        RegisteredServer fallbackServer = fallbackServerOpt.get();

                        if (!guests.isEmpty()) {
                            logger.info("Moving {} guests from island {} to fallback server.", guests.size(), serverName);
                            guests.forEach(guest -> {
                                guest.sendMessage(Component.text("The island is closing. You have been returned to the spawn.", NamedTextColor.YELLOW));
                                guest.createConnectionRequest(fallbackServer).connect();
                            });
                        }
                    }

                    logger.info("Last team member {} left island {}. Scheduling stop for owner {}.",
                            player.getUsername(), serverName, ownerUuid);

                    // Повідомити API про вихід останнього гравця — тригер для update worker
                    // (якщо острів чекає оновлення, воно застосується після зупинки).
                    apiClient.notifyPlayerLeft(ownerUuid);

                    Runnable stopTaskRunnable = () -> {
                        logger.info("Executing scheduled stop for island of owner {}", ownerUuid);
                        apiClient.requestIslandStop(ownerUuid);
                        pendingStopTasks.remove(ownerUuid);
                    };

                    ScheduledTask existingTask = pendingStopTasks.remove(ownerUuid);
                    if (existingTask != null) {
                        existingTask.cancel();
                    }

                    ScheduledTask newScheduledTask = proxyServer.getScheduler()
                            .buildTask(plugin, stopTaskRunnable)
                            .delay(config.getStopDelaySeconds(), TimeUnit.SECONDS)
                            .schedule();
                    pendingStopTasks.put(ownerUuid, newScheduledTask);
                } else {
                    logger.info("Player {} left island {}, but other team members remain there. Not scheduling stop.",
                            player.getUsername(), serverName);
                }

            } catch (JsonSyntaxException | NullPointerException e) {
                logger.error("Error parsing team data for player {}: {}", player.getUsername(), e.getMessage(), e);
            }
        }, plugin.getExecutorService());
    }
}
