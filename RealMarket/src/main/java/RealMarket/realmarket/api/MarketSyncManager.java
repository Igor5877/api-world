package RealMarket.realmarket.api;

import RealMarket.realmarket.RealMarket;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.config.ApiConfig;
import RealMarket.realmarket.world.IslandManager;

import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.KeyCounter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
        System.out.println("[RealMarket] Syncing AE2 Inventory to backend...");

        // Use a Map to aggregate items across multiple Market Links (in case they are on different networks, though usually it's one network)
        Map<String, JsonObject> aggregatedItems = new HashMap<>();

        // Arbitrary owner UUID lookup for pricing if possible, using null or default price if not tied specifically
        double defaultPrice = 10.0;

        for (MarketLinkBlockEntity link : RealMarket.getActiveMarketLinks()) {
            IGrid grid = link.getGrid();
            if (grid == null) continue;

            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) continue;

            MEStorage inventory = storageService.getInventory();
            if (inventory == null) continue;

            KeyCounter counter = new KeyCounter();
            inventory.getAvailableStacks(counter);

            for (Map.Entry<AEKey, Long> entry : counter) {
                AEKey key = entry.getKey();
                long amount = entry.getValue();

                if (key instanceof AEItemKey itemKey) {
                    Item item = itemKey.getItem();
                    ResourceLocation regName = BuiltInRegistries.ITEM.getKey(item);
                    String itemId = regName != null ? regName.toString() : "minecraft:air";

                    if (itemId.equals("minecraft:air")) continue;

                    String nbtStr = null;
                    if (itemKey.hasTag()) {
                        nbtStr = itemKey.getTag().toString();
                    }

                    // Generate a unique identifier for aggregation based on ID and NBT
                    String uniqueId = itemId + (nbtStr != null ? nbtStr : "");

                    if (aggregatedItems.containsKey(uniqueId)) {
                        JsonObject existing = aggregatedItems.get(uniqueId);
                        existing.addProperty("quantity", existing.get("quantity").getAsLong() + amount);
                    } else {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("island_uuid", currentIslandUuid.toString());
                        obj.addProperty("item_id", itemId);
                        if (nbtStr != null) {
                            obj.addProperty("item_nbt", nbtStr);
                        }
                        obj.addProperty("quantity", amount);
                        // Pricing could be more complex, fetching from IslandManager
                        obj.addProperty("price", defaultPrice);
                        obj.addProperty("is_for_sale", true);
                        obj.addProperty("version", 1);

                        aggregatedItems.put(uniqueId, obj);
                    }
                }
            }
        }

        JsonObject root = new JsonObject();
        JsonArray itemsArray = new JsonArray();
        for (JsonObject itemObj : aggregatedItems.values()) {
            itemsArray.add(itemObj);
        }
        root.add("items", itemsArray);
        String payload = root.toString();

        try {
            String baseUrl = ApiConfig.getApiUrl();
            int apiIndex = baseUrl.indexOf("/api/");
            if (apiIndex > 0) {
                baseUrl = baseUrl.substring(0, apiIndex);
            }

            String url = baseUrl + "/api/v1/market/islands/" + currentIslandUuid + "/inventory/sync";

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
                        System.out.println("[RealMarket] Successfully synced inventory (" + aggregatedItems.size() + " unique item stacks).");
                    }
                }).exceptionally(ex -> {
                    System.err.println("[RealMarket] Network error syncing inventory: " + ex.getMessage());
                    return null;
                });
        } catch (Exception e) {
            System.err.println("[RealMarket] Failed to format inventory sync request: " + e.getMessage());
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
