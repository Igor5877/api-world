package RealMarket.realmarket.api;

import RealMarket.realmarket.RealMarket;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.config.ApiConfig;

import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
        scheduler.scheduleAtFixedRate(() -> {
            syncInventory();
            checkWebSocketConnection();
        }, 30, 30, TimeUnit.SECONDS);
    }

    private static void connectWebSocket() {
        if (wsClient != null && wsClient.isOpen()) return;
        try {
            String baseUrl = ApiConfig.getApiUrl()
                    .replace("http://", "ws://")
                    .replace("https://", "wss://");
            int apiIndex = baseUrl.indexOf("/api/");
            if (apiIndex > 0) baseUrl = baseUrl.substring(0, apiIndex);
            URI wsUri = new URI(baseUrl + "/ws/island_" + currentIslandUuid);
            System.out.println("[RealMarket] Connecting to WebSocket: " + wsUri);
            wsClient = new MarketWebSocketClient(wsUri);
            wsClient.connect();
        } catch (Exception e) {
            System.err.println("[RealMarket] Failed to initialize WebSocket: " + e.getMessage());
        }
    }

    private static void checkWebSocketConnection() {
        if (wsClient == null || !wsClient.isOpen()) {
            System.out.println("[RealMarket] WebSocket disconnected, reconnecting...");
            connectWebSocket();
        }
    }

    private static void syncInventory() {
        System.out.println("[RealMarket] Syncing AE2 inventory...");
        Map<String, JsonObject> aggregatedItems = new HashMap<>();
        double defaultPrice = 10.0;

        for (MarketLinkBlockEntity link : RealMarket.getActiveMarketLinks()) {
            IGrid grid = link.getGrid();
            if (grid == null) continue;

            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) continue;

            MEStorage storage = storageService.getInventory();
            if (storage == null) continue;

            var stacks = storage.getAvailableStacks();
            for (var keyEntry : stacks) {
                AEKey key = keyEntry.getKey();
                long amount = keyEntry.getLongValue();

                if (!(key instanceof AEItemKey itemKey)) continue;  // <- continue, не return

                ItemStack stack = itemKey.toStack(1);
                Item item = stack.getItem();
                ResourceLocation regName = BuiltInRegistries.ITEM.getKey(item);
                String itemId = regName != null ? regName.toString() : "minecraft:air";

                if (itemId.equals("minecraft:air")) continue;  // <- continue, не return

                String nbtStr = stack.hasTag() ? stack.getTag().toString() : null;
                String uniqueId = itemId + (nbtStr != null ? nbtStr : "");

                if (aggregatedItems.containsKey(uniqueId)) {
                    JsonObject existing = aggregatedItems.get(uniqueId);
                    existing.addProperty("quantity", existing.get("quantity").getAsLong() + amount);
                } else {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("island_uuid", currentIslandUuid.toString());
                    obj.addProperty("item_id", itemId);
                    if (nbtStr != null) obj.addProperty("item_nbt", nbtStr);
                    obj.addProperty("quantity", amount);
                    obj.addProperty("price", defaultPrice);
                    obj.addProperty("is_for_sale", true);
                    obj.addProperty("version", 1);
                    aggregatedItems.put(uniqueId, obj);
                }
            }
        }

        sendSyncRequest(aggregatedItems);
    }

    private static void sendSyncRequest(Map<String, JsonObject> aggregatedItems) {
        JsonObject root = new JsonObject();
        JsonArray itemsArray = new JsonArray();
        aggregatedItems.values().forEach(itemsArray::add);
        root.add("items", itemsArray);
        String payload = root.toString();

        try {
            String baseUrl = ApiConfig.getApiUrl();
            int apiIndex = baseUrl.indexOf("/api/");
            if (apiIndex > 0) baseUrl = baseUrl.substring(0, apiIndex);
            String url = baseUrl + "/api/v1/market/islands/" + currentIslandUuid + "/inventory/sync";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() != 200) {
                            System.err.println("[RealMarket] Sync failed: " + res.statusCode() + " " + res.body());
                        } else {
                            System.out.println("[RealMarket] Synced " + aggregatedItems.size() + " stacks.");
                        }
                    }).exceptionally(ex -> {
                        System.err.println("[RealMarket] Network error: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] Sync request error: " + e.getMessage());
        }
    }

    public static void shutdown() {
        scheduler.shutdown();
        if (wsClient != null) wsClient.close();
    }
}
