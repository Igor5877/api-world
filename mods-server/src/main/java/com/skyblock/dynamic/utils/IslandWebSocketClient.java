package com.skyblock.dynamic.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

public class IslandWebSocketClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final URI serverUri;
    private final String ownerUuid;
    private WebSocket webSocket;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public IslandWebSocketClient(URI serverUri, String ownerUuid) {
        this.serverUri = serverUri;
        this.ownerUuid = ownerUuid;
    }

    public void connect() {
        httpClient.newWebSocketBuilder()
            .buildAsync(serverUri, new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket ws) {
                    LOGGER.info("WebSocket connection opened for owner: {}", ownerUuid);
                    WebSocket.Listener.super.onOpen(ws);
                }

                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    handleMessage(data.toString());
                    return WebSocket.Listener.super.onText(ws, data, last);
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    LOGGER.warn("WebSocket closed. Code: {}, Reason: {}. Will attempt to reconnect...", statusCode, reason);
                    return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    LOGGER.error("WebSocket error", error);
                }
            })
            .thenAccept(ws -> {
                webSocket = ws;
                LOGGER.info("WebSocket successfully connected to {}", serverUri);
            })
            .exceptionally(ex -> {
                LOGGER.error("Failed to connect WebSocket to {}", serverUri, ex);
                return null;
            });
    }

    private void handleMessage(String message) {
        LOGGER.info("Received WebSocket message: {}", message);
        try {
            JsonObject json = GSON.fromJson(message, JsonObject.class);
            if (json.has("event") && json.get("event").getAsString().equals("TEAM_UPDATED")) {
                if (json.has("payload")) {
                    JsonObject payload = json.getAsJsonObject("payload");
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()
                        .execute(() -> NestworldModsServer.ISLAND_PROVIDER.processTeamData(payload));
                }
            }
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Failed to parse WebSocket message as JSON: {}", message, e);
        }
    }

    public boolean isOpen() {
        return webSocket != null && !webSocket.isInputClosed();
    }

    public void close() {
        if (webSocket != null) {
            webSocket.abort();
        }
    }
}
