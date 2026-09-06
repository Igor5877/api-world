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

/**
 * The /myisland command.
 */
public class MyIslandCommand implements SimpleCommand {

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final ApiClient apiClient;
    private final PluginConfig config;
    private final com.skyblockdynamic.nestworld.velocity.locale.LocaleManager localeManager;

    /**
     * Constructs a new MyIslandCommand.
     *
     * @param plugin        The plugin instance.
     * @param proxyServer   The proxy server.
     * @param logger        The logger.
     * @param apiClient     The API client.
     * @param config        The plugin configuration.
     * @param localeManager The locale manager.
     */
    public MyIslandCommand(NestworldVelocityPlugin plugin, ProxyServer proxyServer, Logger logger, ApiClient apiClient, PluginConfig config, com.skyblockdynamic.nestworld.velocity.locale.LocaleManager localeManager) {
        this.plugin = plugin;
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.apiClient = apiClient;
        this.config = config;
        this.localeManager = localeManager;
    }

    /**
     * Executes the /myisland command.
     *
     * @param invocation The command invocation.
     */
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

        if (player.getCurrentServer().isPresent()
                && player.getCurrentServer().get().getServerInfo().getName().equals(islandServerName)) {
            // The registered pseudo-server slot is always named after the
            // connecting player, regardless of which physical island it
            // points at — so a name match alone doesn't prove the player is
            // still on their CURRENT island (e.g. after a team change or
            // island swap the address behind this name can be stale). Verify
            // against a fresh API lookup before trusting it.
            ServerInfo currentInfo = player.getCurrentServer().get().getServerInfo();
            apiClient.getIslandDetails(player.getUniqueId()).thenAccept(apiResponse -> {
                if (apiResponse.isSuccess()) {
                    try {
                        JsonObject islandData = JsonParser.parseString(apiResponse.body()).getAsJsonObject();
                        String ip = islandData.get("internal_ip_address").getAsString();
                        int port = islandData.get("internal_port").getAsInt();
                        InetSocketAddress currentAddress = currentInfo.getAddress();
                        if (ip.equals(currentAddress.getHostString()) && port == currentAddress.getPort()) {
                            player.sendMessage(localeManager.getComponent(lang, "myisland.already_on_island", NamedTextColor.YELLOW));
                            return;
                        }
                    } catch (Exception ignored) {
                        // Fall through and just try to (re)connect.
                    }
                }
                initiateIslandConnection(player);
            });
            return;
        }

        initiateIslandConnection(player);
    }

    /**
     * Initiates the connection to the player's island.
     *
     * @param player The player.
     */
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
                        // Parse the connection target before telling the player
                        // we're connecting — a broken/missing IP on an already
                        // RUNNING island (e.g. lifecycle_stability_plan.md
                        // reconciliation gap) must not present as "connecting"
                        // followed by a silent, unfixable not_ready/start loop.
                        String ip = islandData.get("internal_ip_address").getAsString();
                        int port = islandData.get("internal_port").getAsInt();
                        player.sendMessage(localeManager.getComponent(lang, "myisland.status.ready", NamedTextColor.GREEN));
                        attemptSingleConnection(player, player.getUniqueId(), ip, port);
                        return;
                    }
                } catch (Exception e) {
                    // The island exists and reported RUNNING/ready but its data
                    // is malformed — requesting a start would be a silent no-op
                    // (island_service short-circuits an already-RUNNING island
                    // without touching internal_ip_address), so tell the player
                    // instead of looping them into startAndListen() forever.
                    logger.error("Error parsing initial island status for {}: {}", player.getUsername(), e.getMessage());
                    player.sendMessage(localeManager.getComponent(lang, "myisland.status.inconsistent", NamedTextColor.RED));
                    return;
                }
            }

            startAndListen(player);
        });
    }

    /**
     * Starts the island and listens for its status.
     *
     * @param player The player.
     */
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

    /**
     * Connects to the WebSocket.
     *
     * @param player The player.
     */
    private void connectToWebSocket(Player player) {
        UUID playerUuid = player.getUniqueId();
        String lang = player.getPlayerSettings().getLocale().getLanguage();

        WebSocketManager old = plugin.getWebSocketManagers().remove(playerUuid);
        if (old != null) old.close();

        String httpUrl = config.getApiUrl();
        String wsUrl = httpUrl.replace("/api/v1", "/ws/" + playerUuid.toString()).replaceFirst("http", "ws");

        try {
            WebSocketManager client = new WebSocketManager(new URI(wsUrl), logger, player, proxyServer, (islandData) -> {
                try {
                    String ip = islandData.get("internal_ip_address").getAsString();
                    int port = islandData.get("internal_port").getAsInt();
                    proxyServer.getScheduler().buildTask(plugin, () -> attemptSingleConnection(player, player.getUniqueId(), ip, port)).schedule();
                } catch (Exception e) {
                    // Same broken-data case as initiateIslandConnection, but
                    // reached via the WS push — WebSocketManager.onText only
                    // logs this exception, so the player would otherwise be
                    // stuck silently forever (already removed from
                    // awaitingConnection by the time this runs).
                    logger.error("Island reported ready via WebSocket but connection data is invalid for {}: {}", player.getUsername(), e.getMessage());
                    player.sendMessage(localeManager.getComponent(lang, "myisland.status.inconsistent", NamedTextColor.RED));
                }
            }, plugin);
            client.connect();
            plugin.getWebSocketManagers().put(playerUuid, client);
        } catch (Exception e) {
            logger.error("Failed to create WebSocket client for player {}: {}", player.getUsername(), e.getMessage(), e);
            player.sendMessage(localeManager.getComponent(lang, "myisland.status.websocket_fail", NamedTextColor.RED));
        }
    }
    
    /**
     * Attempts to connect the player to an island (their own, or — via TPA —
     * someone else's).
     *
     * @param player    The player being connected.
     * @param ownerUuid The UUID of the island's owner, for connection_failed
     *                  reporting (not always {@code player} — TPA connects a
     *                  visitor to someone else's island).
     * @param ip        The IP address of the island server.
     * @param port      The port of the island server.
     */
    private void attemptSingleConnection(Player player, UUID ownerUuid, String ip, int port) {
        String serverName = "island-" + player.getUniqueId();
        ServerInfo serverInfo = new ServerInfo(serverName, new InetSocketAddress(ip, port));
        String lang = player.getPlayerSettings().getLocale().getLanguage();

        proxyServer.getServer(serverName).ifPresent(s -> proxyServer.unregisterServer(s.getServerInfo()));
        RegisteredServer serverToConnect = proxyServer.registerServer(serverInfo);

        // Silent Server List Ping first — catches a resumed-but-actually-dead
        // JVM (DB already says RUNNING/minecraft_ready) before the player
        // hits a failed real connection with no record of why
        // (lifecycle_stability_plan.md §1).
        serverToConnect.ping()
            .thenAccept(ping -> player.createConnectionRequest(serverToConnect).connect()
                .thenAccept(result -> {
                    if (!result.isSuccessful()) {
                        player.sendMessage(localeManager.getComponent(lang, "myisland.connect.fail", NamedTextColor.RED));
                        proxyServer.unregisterServer(serverInfo);
                    }
                }))
            .exceptionally(ex -> {
                logger.warn("Silent ping failed for island of {} ({}:{}) before connecting {}: {}",
                        ownerUuid, ip, port, player.getUsername(), ex.getMessage());
                apiClient.notifyConnectionFailed(ownerUuid);
                player.sendMessage(localeManager.getComponent(lang, "myisland.connect.fail", NamedTextColor.RED));
                proxyServer.unregisterServer(serverInfo);
                return null;
            });
    }
    
    /**
     * Checks if the command source has permission to execute the command.
     *
     * @param invocation The command invocation.
     * @return True if the command source has permission, false otherwise.
     */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    private static final int ADMIN_JOIN_POLL_INTERVAL_SECONDS = 2;
    private static final int ADMIN_JOIN_POLL_MAX_ATTEMPTS = 45; // ~90s, matches a cold island boot

    /**
     * Admin variant of {@link #teleportToIsland}: starts (creating the island
     * first if the target has never played) an arbitrary player's island by
     * name and connects {@code admin} the moment it's ready — no confirmation
     * from the target, and the target does not need to be online. The server
     * runs with online-mode=false, so the UUID is derived the same
     * deterministic way the rest of the platform (islands, teams) already
     * does, rather than requiring a live Mojang/proxy lookup.
     *
     * @param admin      The admin being connected.
     * @param targetName The nickname of the island owner (may never have logged in).
     */
    public void adminJoinIsland(Player admin, String targetName) {
        UUID targetUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String lang = admin.getPlayerSettings().getLocale().getLanguage();
        admin.sendMessage(Component.text("⏳ Запускаю острів " + targetName + "...", NamedTextColor.YELLOW));
        apiClient.requestIslandStart(targetUuid, targetName);
        adminPollAndConnect(admin, targetUuid, targetName, 0);
    }

    private void adminPollAndConnect(Player admin, UUID targetUuid, String targetName, int attempt) {
        apiClient.getIslandDetails(targetUuid).thenAccept(res -> {
            if (res.isSuccess()) {
                try {
                    JsonObject island = JsonParser.parseString(res.body()).getAsJsonObject();
                    String status = island.get("status").getAsString();
                    boolean ready = island.has("minecraft_ready") && !island.get("minecraft_ready").isJsonNull()
                            && island.get("minecraft_ready").getAsBoolean();
                    if ("RUNNING".equalsIgnoreCase(status) && ready) {
                        String ip = island.get("internal_ip_address").getAsString();
                        int port = island.get("internal_port").getAsInt();
                        admin.sendMessage(Component.text("✔ Острів готовий, підключаю...", NamedTextColor.GREEN));
                        attemptSingleConnection(admin, targetUuid, ip, port);
                        return;
                    }
                } catch (Exception e) {
                    logger.error("[IslandAdmin] join: bad island data for {}: {}", targetName, e.getMessage());
                    admin.sendMessage(Component.text("✖ Некоректні дані острова.", NamedTextColor.RED));
                    return;
                }
            }
            adminScheduleNextPoll(admin, targetUuid, targetName, attempt);
        }).exceptionally(ex -> {
            adminScheduleNextPoll(admin, targetUuid, targetName, attempt);
            return null;
        });
    }

    private void adminScheduleNextPoll(Player admin, UUID targetUuid, String targetName, int attempt) {
        if (attempt + 1 >= ADMIN_JOIN_POLL_MAX_ATTEMPTS) {
            admin.sendMessage(Component.text("✖ Острів " + targetName + " не запустився за відведений час.", NamedTextColor.RED));
            return;
        }
        if (!admin.isActive()) return; // admin disconnected while waiting
        proxyServer.getScheduler().buildTask(plugin, () -> adminPollAndConnect(admin, targetUuid, targetName, attempt + 1))
                .delay(java.time.Duration.ofSeconds(ADMIN_JOIN_POLL_INTERVAL_SECONDS))
                .schedule();
    }

    /**
     * Teleports a player to another player's island.
     *
     * @param player           The player to teleport.
     * @param targetPlayerName The name of the player whose island to teleport to.
     */
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
                            attemptSingleConnection(player, targetPlayer.getUniqueId(), ip, port);
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
