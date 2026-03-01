package com.skyblock.sales;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SalesWebSocketClient extends WebSocketClient {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final Gson gson = new Gson();

    public SalesWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        LOGGER.info("Connected to Sales Quantum Bridge WebSocket");
        // Optionally send auth token or server identity
    }

    @Override
    public void onMessage(String message) {
        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            if (json.has("event") && "INVENTORY_UPDATE".equals(json.get("event").getAsString())) {
                JsonArray itemsArray = json.getAsJsonArray("items");
                Map<String, Long> newInventory = new ConcurrentHashMap<>();

                itemsArray.forEach(elem -> {
                    JsonObject itemObj = elem.getAsJsonObject();
                    newInventory.put(
                        itemObj.get("item_id").getAsString(),
                        itemObj.get("quantity").getAsLong()
                    );
                });

                // Update the receiver block on Spawn
                SalesReceiverManager.updateAllReceivers(newInventory);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to parse websocket message: " + message, e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        LOGGER.info("Disconnected from Sales Quantum Bridge WebSocket. Reason: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        LOGGER.error("WebSocket Error in Sales Quantum Bridge", ex);
    }

    // Helper to send inventory updates (from Island side)
    public void sendInventoryUpdate(Map<String, Long> inventory) {
        if (!isOpen()) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("event", "INVENTORY_UPDATE");

        JsonArray itemsArray = new JsonArray();
        inventory.forEach((id, qty) -> {
            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("item_id", id);
            itemObj.addProperty("quantity", qty);
            itemsArray.add(itemObj);
        });

        payload.add("items", itemsArray);
        this.send(gson.toJson(payload));
    }
}
