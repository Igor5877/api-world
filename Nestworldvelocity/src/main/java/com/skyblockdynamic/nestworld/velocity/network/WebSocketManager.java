package com.skyblockdynamic.nestworld.velocity.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.net.URI;
import java.util.UUID;
import java.util.function.Consumer;

public class WebSocketManager extends WebSocketClient {

    private final Logger logger;
    private final Player player;
    private final ProxyServer proxyServer;
    private final Consumer<JsonObject> onIslandReady;

    public WebSocketManager(URI serverUri, Logger logger, Player player, ProxyServer proxyServer, Consumer<JsonObject> onIslandReady) {
        super(serverUri);
        this.logger = logger;
        this.player = player;
        this.proxyServer = proxyServer;
        this.onIslandReady = onIslandReady;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.info("WebSocket connection opened for player {}", player.getUsername());
        player.sendMessage(Component.text("Connected to the island service. Waiting for status updates...", NamedTextColor.GRAY));
    }

    @Override
    public void onMessage(String message) {
        logger.info("WebSocket message received for {}: {}", player.getUsername(), message);
        try {
            JsonObject islandData = JsonParser.parseString(message).getAsJsonObject();
            String status = islandData.get("status").getAsString();
            boolean minecraftReady = islandData.has("minecraft_ready") && islandData.get("minecraft_ready").getAsBoolean();

            if ("RUNNING".equalsIgnoreCase(status) && minecraftReady) {
                logger.info("Island for {} is ready! Triggering connection logic.", player.getUsername());
                onIslandReady.accept(islandData);
                this.close();
            } else {
                // You can add more detailed messages for other statuses if you want
                // For example, PENDING_START, RUNNING (but not ready), etc.
                String readableStatus = status.toLowerCase().replace('_', ' ');
                player.sendMessage(Component.text("Island status: " + readableStatus + "...", NamedTextColor.YELLOW));
            }
        } catch (Exception e) {
            logger.error("Error parsing WebSocket message for {}: {}", player.getUsername(), message, e);
            player.sendMessage(Component.text("Error processing status update from the server.", NamedTextColor.RED));
            this.close();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("WebSocket connection closed for {}. Code: {}, Reason: {}", player.getUsername(), code, reason);
    }

    @Override
    public void onError(Exception ex) {
        logger.error("WebSocket error for player {}: {}", player.getUsername(), ex.getMessage());
        player.sendMessage(Component.text("An error occurred with the island service connection.", NamedTextColor.RED));
    }
}
