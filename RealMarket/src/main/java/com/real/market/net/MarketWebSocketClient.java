package com.real.market.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.real.market.RealMarket;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;

import java.net.URI;

public class MarketWebSocketClient extends WebSocketClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    public MarketWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        LOGGER.info("[RealMarket] WebSocket connected.");
    }

    @Override
    public void onMessage(String message) {
        LOGGER.info("[RealMarket] Received message: {}", message);
        try {
            JsonObject json = GSON.fromJson(message, JsonObject.class);
            if (!json.has("action")) return;
            String action = json.get("action").getAsString();

            if (action.equals("market_debt_process")) {
                if (json.has("debts")) {
                    JsonArray debts = json.getAsJsonArray("debts");
                    ServerLifecycleHooks.getCurrentServer().execute(() -> {
                        RealMarket.processDebts(debts);
                    });
                }
            } else if (action.equals("market_sync")) {
                if (json.has("island_id") && json.has("items")) {
                    UUID islandId = UUID.fromString(json.get("island_id").getAsString());
                    JsonArray items = json.getAsJsonArray("items");
                    ServerLifecycleHooks.getCurrentServer().execute(() -> {
                        RealMarket.updateHubCache(islandId, items);
                    });
                }
            }
        } catch (Exception e) {
            LOGGER.error("[RealMarket] Error parsing message", e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        LOGGER.warn("[RealMarket] WebSocket closed: {}. Reconnecting...", reason);
    }

    @Override
    public void onError(Exception ex) {
        LOGGER.error("[RealMarket] WebSocket error", ex);
    }

    public void sendSync(String islandId, JsonArray items) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "market_sync");
        json.addProperty("island_id", islandId);
        json.add("items", items);
        send(json.toString());
    }
}
