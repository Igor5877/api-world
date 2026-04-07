package RealMarket.realmarket.api;

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
                    String message = data.toString();
                    if (message.contains("\"type\": \"ping\"") || message.contains("\"type\":\"ping\"")) {
                        ws.sendText("{\"type\": \"pong\"}", true);
                    }
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
            .thenAccept(ws -> {
                webSocket = ws;
            })
            .exceptionally(ex -> {
                System.err.println("[RealMarket] Failed to connect WebSocket to " + serverUri + ": " + ex.getMessage());
                return null;
            });
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
