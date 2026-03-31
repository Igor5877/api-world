package RealMarket.realmarket.api;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

public class MarketWebSocketClient extends WebSocketClient {

    public MarketWebSocketClient(URI serverUri) {
        super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        System.out.println("[RealMarket] WebSocket Connected.");
    }

    @Override
    public void onMessage(String message) {
        if (message.contains("\"type\": \"ping\"") || message.contains("\"type\":\"ping\"")) {
            this.send("{\"type\": \"pong\"}");
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("[RealMarket] WebSocket Closed: " + reason);
    }

    @Override
    public void onError(Exception ex) {
        System.err.println("[RealMarket] WebSocket Error: " + ex.getMessage());
    }
}
