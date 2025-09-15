package com.skyblockdynamic.nestworld.velocity.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

public class WebSocketManager implements WebSocket.Listener {

    private final Logger logger;
    private final Player player;
    private final ProxyServer proxyServer;
    private final Consumer<JsonObject> onIslandReady;
    private final CountDownLatch latch = new CountDownLatch(1);
    private WebSocket webSocket;
    private final URI uri;

    public WebSocketManager(URI uri, Logger logger, Player player, ProxyServer proxyServer, Consumer<JsonObject> onIslandReady) {
        this.uri = uri;
        this.logger = logger;
        this.player = player;
        this.proxyServer = proxyServer;
        this.onIslandReady = onIslandReady;
    }

    public void connect() {
        HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(uri, this);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        webSocket.request(1);
        logger.info("WebSocket connection opened for player " + player.getUsername());
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        try {
            String message = data.toString();
            logger.info("Received WebSocket message for player " + player.getUsername() + ": " + message);
            JsonObject jsonObject = JsonParser.parseString(message).getAsJsonObject();
            if (jsonObject.has("status") && "RUNNING".equalsIgnoreCase(jsonObject.get("status").getAsString()) &&
                jsonObject.has("minecraft_ready") && jsonObject.get("minecraft_ready").getAsBoolean()) {
                onIslandReady.accept(jsonObject);
                close();
            }
        } catch (Exception e) {
            logger.error("Error processing WebSocket message for player " + player.getUsername(), e);
        }
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        logger.info("WebSocket connection closed for player " + player.getUsername() + " with status code " + statusCode + " and reason: " + reason);
        latch.countDown();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        logger.error("WebSocket error for player " + player.getUsername(), error);
        latch.countDown();
    }

    public void close() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closing connection");
        }
    }
}
