package com.skyblockdynamic.nestworld.velocity.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Persistent Velocity→API WebSocket connection, client_id "proxy_control".
 *
 * Unlike WebSocketManager (one-shot, per-player, closes after the island
 * comes ready), this stays open for the plugin's whole lifetime and answers
 * idle_reaper's "can I stop island X" live check
 * (lifecycle_stability_plan.md §3): the API asks whether anyone is actually
 * connected to island-{ownerUuid}'s backend server before executing a
 * backstop stop, catching the race where a resume is already under way
 * while the island is still nominally FROZEN in the DB.
 *
 * Reconnect is a lazy periodic check (like the mod's own heartbeat-driven
 * reconnect in SkyBlockMod.java), not event-driven backoff — simple and
 * good enough for a connection that's mostly idle.
 */
public class ProxyControlConnection implements WebSocket.Listener {

    private static final String CLIENT_ID = "proxy_control";

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer server;
    private final Logger logger;
    private final PluginConfig config;
    private final Gson gson = new Gson();
    private final AtomicReference<WebSocket> webSocket = new AtomicReference<>();
    private final StringBuilder messageBuffer = new StringBuilder();

    public ProxyControlConnection(NestworldVelocityPlugin plugin, ProxyServer server, Logger logger, PluginConfig config) {
        this.plugin = plugin;
        this.server = server;
        this.logger = logger;
        this.config = config;
    }

    /** Opens the connection and schedules the periodic reconnect check. */
    public void start() {
        connect();
        server.getScheduler().buildTask(plugin, this::ensureConnected)
                .repeat(30, TimeUnit.SECONDS)
                .schedule();
    }

    private void ensureConnected() {
        WebSocket current = webSocket.get();
        if (current == null || current.isOutputClosed() || current.isInputClosed()) {
            connect();
        }
    }

    private void connect() {
        String httpUrl = config.getApiUrl();
        String wsUrl = httpUrl.replace("/api/v1", "/ws/" + CLIENT_ID).replaceFirst("http", "ws");

        HttpClient.Builder httpBuilder = HttpClient.newBuilder();
        WebSocket.Builder builder = httpBuilder.build().newWebSocketBuilder();
        String apiKey = config.getApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-Api-Key", apiKey);
        }
        builder.buildAsync(URI.create(wsUrl), this)
                .thenAccept(ws -> logger.info("[ProxyControl] Connected: {}", wsUrl))
                .exceptionally(ex -> {
                    logger.warn("[ProxyControl] Connection failed, will retry: {}", ex.getMessage());
                    return null;
                });
    }

    @Override
    public void onOpen(WebSocket ws) {
        webSocket.set(ws);
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        ws.request(1);
        messageBuffer.append(data);
        if (!last) return null;

        String message = messageBuffer.toString();
        messageBuffer.setLength(0);
        try {
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (json.has("type") && "island_stop_check_request".equals(json.get("type").getAsString())) {
                handleStopCheckRequest(ws, json);
            }
        } catch (Exception e) {
            logger.error("[ProxyControl] Error processing message: {}", e.getMessage(), e);
        }
        return null;
    }

    private void handleStopCheckRequest(WebSocket ws, JsonObject request) {
        String requestId = request.get("request_id").getAsString();

        int onlineCount;
        if (request.has("member_uuids") && request.get("member_uuids").isJsonArray()) {
            // Будь-хто з команди онлайн на проксі (спавн, чужий острів, в дорозі
            // на свій) — острів лишається FROZEN, а не зупиняється: гравець може
            // повернутись будь-якої миті, і resume миттєвий, а холодний старт ні.
            onlineCount = 0;
            for (var element : request.getAsJsonArray("member_uuids")) {
                try {
                    if (server.getPlayer(java.util.UUID.fromString(element.getAsString())).isPresent()) {
                        onlineCount++;
                    }
                } catch (IllegalArgumentException e) {
                    logger.warn("[ProxyControl] Invalid member UUID in stop check: {}", element);
                }
            }
        } else {
            // Старий API без member_uuids — лише гравці на бекенді острова.
            String ownerUuid = request.get("owner_uuid").getAsString();
            onlineCount = server.getServer("island-" + ownerUuid)
                    .map(s -> s.getPlayersConnected().size()).orElse(0);
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "island_stop_check_response");
        response.addProperty("request_id", requestId);
        response.addProperty("allow", onlineCount == 0);
        response.addProperty("online_count", onlineCount);
        ws.sendText(gson.toJson(response), true);
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        logger.warn("[ProxyControl] Connection closed ({}): {} — will reconnect on next check.", statusCode, reason);
        webSocket.compareAndSet(ws, null);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        logger.error("[ProxyControl] WebSocket error: {}", error.getMessage());
        webSocket.compareAndSet(ws, null);
    }
}
