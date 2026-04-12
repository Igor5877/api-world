package RealMarket.realmarket.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

public class MarketWebSocketClient {

    private final URI serverUri;
    private WebSocket webSocket;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public MarketWebSocketClient(URI serverUri) {
        this.serverUri = serverUri;
    }

    public void connect() {
        httpClient.newWebSocketBuilder()
            .buildAsync(serverUri, new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket ws) {
                    System.out.println("[RealMarket] WebSocket Connected.");
                    WebSocket.Listener.super.onOpen(ws);
                }

                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    handleMessage(data.toString());
                    return WebSocket.Listener.super.onText(ws, data, last);
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    System.out.println("[RealMarket] WebSocket Closed: " + reason);
                    return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    System.err.println("[RealMarket] WebSocket Error: " + error.getMessage());
                }
            })
            .thenAccept(ws -> webSocket = ws)
            .exceptionally(ex -> {
                System.err.println("[RealMarket] Failed to connect WebSocket to " + serverUri + ": " + ex.getMessage());
                return null;
            });
    }

    private void handleMessage(String raw) {
        try {
            JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            switch (type) {
                case "ping" -> {
                    if (webSocket != null) webSocket.sendText("{\"type\":\"pong\"}", true);
                }
                case "market_purchase" -> {
                    String itemId   = msg.get("item_id").getAsString();
                    int    qty      = msg.get("quantity").getAsInt();
                    int    pendingId = msg.has("pending_id") ? msg.get("pending_id").getAsInt() : -1;
                    System.out.println("[RealMarket] Purchase received: " + qty + "x " + itemId + " (pending_id=" + pendingId + ") — extracting from AE2");
                    MarketSyncManager.extractFromAE2(itemId, qty, pendingId);
                }
                default -> { /* ігноруємо невідомі повідомлення */ }
            }
        } catch (Exception e) {
            System.err.println("[RealMarket] Failed to parse WS message: " + e.getMessage());
        }
    }

    public boolean isOpen() {
        return webSocket != null && !webSocket.isInputClosed();
    }

    public void close() {
        if (webSocket != null) webSocket.abort();
    }
}
