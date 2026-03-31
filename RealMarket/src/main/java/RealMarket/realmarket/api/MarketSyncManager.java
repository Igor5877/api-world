package RealMarket.realmarket.api;

import RealMarket.realmarket.config.ApiConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

public class MarketSyncManager {
    private static MarketWebSocketClient wsClient;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static UUID currentIslandUuid;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void init(UUID islandUuid) {
        currentIslandUuid = islandUuid;
        connectWebSocket();

        // Schedule inventory sync every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            syncInventory();
            checkWebSocketConnection();
        }, 30, 30, TimeUnit.SECONDS);
    }

    private static void connectWebSocket() {
        if (wsClient != null && wsClient.isOpen()) return;

        try {
            String baseUrl = ApiConfig.getApiUrl().replace("http://", "ws://").replace("https://", "wss://");

            // Remove /api/azlink or anything after domain for generic base URL
            int apiIndex = baseUrl.indexOf("/api/");
            if (apiIndex > 0) {
                baseUrl = baseUrl.substring(0, apiIndex);
            }

            URI wsUri = new URI(baseUrl + "/ws/island_" + currentIslandUuid.toString());
            System.out.println("[RealMarket] Connecting to WebSocket: " + wsUri);

            wsClient = new MarketWebSocketClient(wsUri);
            wsClient.connect();
        } catch (Exception e) {
            System.err.println("[RealMarket] Failed to initialize WebSocket: " + e.getMessage());
        }
    }

    private static void checkWebSocketConnection() {
        if (wsClient == null || !wsClient.isOpen()) {
            System.out.println("[RealMarket] WebSocket disconnected. Attempting to reconnect...");
            connectWebSocket();
        }
    }

    private static void syncInventory() {
        // Mock payload structure since AE2 is absent during build
        // A real implementation would query ME networks linked to Market Links
        System.out.println("[RealMarket] Syncing AE2 Inventory to backend...");

        String payload = "{\"items\": []}";

        try {
            String baseUrl = ApiConfig.getApiUrl();
            int apiIndex = baseUrl.indexOf("/api/");
            if (apiIndex > 0) {
                baseUrl = baseUrl.substring(0, apiIndex);
            }

            String url = baseUrl + "/api/v1/islands/" + currentIslandUuid + "/inventory/sync";

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() != 200) {
                        System.err.println("[RealMarket] Inventory sync failed: " + res.statusCode() + " " + res.body());
                    } else {
                        System.out.println("[RealMarket] Successfully synced inventory.");
                    }
                }).exceptionally(ex -> {
                    System.err.println("[RealMarket] Network error syncing inventory: " + ex.getMessage());
                    return null;
                });
        } catch (Exception e) {
            System.err.println("[RealMarket] Failed to format inventory sync: " + e.getMessage());
        }
    }

    public static void shutdown() {
        System.out.println("[RealMarket] Shutting down market synchronization...");
        scheduler.shutdown();
        if (wsClient != null) {
            wsClient.close();
        }
    }
}
