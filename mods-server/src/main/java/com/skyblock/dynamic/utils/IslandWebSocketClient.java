package com.skyblock.dynamic.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.net.URI;
import java.util.UUID;

public class IslandWebSocketClient extends org.java_websocket.client.WebSocketClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private final String ownerUuid;

    public IslandWebSocketClient(URI serverUri, String ownerUuid) {
        super(serverUri);
        this.ownerUuid = ownerUuid;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        LOGGER.info("WebSocket connection opened for owner: {}", ownerUuid);
        // You could send an identification message here if the API requires it
        // send("{\"type\":\"IDENTIFY\",\"owner_uuid\":\"" + ownerUuid + "\"}");
    }

    @Override
    public void onMessage(String message) {
        LOGGER.info("Received WebSocket message: {}", message);
        try {
            JsonObject json = GSON.fromJson(message, JsonObject.class);
            if (json.has("event") && json.get("event").getAsString().equals("TEAM_UPDATED")) {
                if (json.has("payload")) {
                    JsonObject payload = json.getAsJsonObject("payload");
                    // Since this is on a network thread, we need to schedule the task on the main server thread
                    getServer().execute(() -> {
                        NestworldModsServer.ISLAND_PROVIDER.processTeamData(payload);
                    });
                }
            }
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Failed to parse WebSocket message as JSON: {}", message, e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        LOGGER.warn("WebSocket connection closed. Code: {}, Reason: {}, Remote: {}. Will attempt to reconnect...", code, reason, remote);
        // Implement reconnection logic here if needed
    }

    @Override
    public void onError(Exception ex) {
        LOGGER.error("WebSocket error", ex);
    }

    private net.minecraft.server.MinecraftServer getServer() {
        return net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
    }
}