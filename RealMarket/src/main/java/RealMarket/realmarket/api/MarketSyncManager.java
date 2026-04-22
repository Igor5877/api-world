package RealMarket.realmarket.api;

import RealMarket.realmarket.RealMarket;
import RealMarket.realmarket.api.MarketIslandApi;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity.BlockMode;
import RealMarket.realmarket.config.ApiConfig;

import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageHelper;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.core.registries.BuiltInRegistries;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class MarketSyncManager {

    // Кеш інвентарів для SINK блоків: island_uuid → список предметів
    public record CachedItem(String itemId, String itemNbt, long quantity, double price, boolean isForSale, int sellerAzuriomId) {}
    private static final Map<UUID, List<CachedItem>> sinkCache = new ConcurrentHashMap<>();

    private static MarketWebSocketClient wsClient;
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static UUID currentIslandUuid;
    /** Azuriom ID власника острова (продавця). -1 якщо ще не відомий. */
    private static volatile int sellerAzuriomId = -1;

    /** Черга pending extractions що прийшли до того як AE2 була готова. */
    public record PendingExtraction(String itemId, int quantity, int pendingId) {}
    private static final List<PendingExtraction> extractionQueue = new CopyOnWriteArrayList<>();
    /** true = перший sync пройшов, AE2 готова до extraction */
    private static volatile boolean ae2Ready = false;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static boolean initialized = false;

    /** Викликається на острові (SOURCE блок) — встановлює UUID і стартує всі задачі. */
    public static void init(UUID islandUuid) {
        currentIslandUuid = islandUuid;
        connectWebSocket();
        startScheduler();
    }

    /** Викликається на спавні (SINK блок) — стартує fetch задачу і hub WS для команд. */
    public static void initSink() {
        startScheduler();
        HubCommandClient.init();
    }

    private static void startScheduler() {
        if (!initialized) {
            initialized = true;
            scheduler.scheduleAtFixedRate(() -> {
                syncSourceBlocks();
                fetchSinkInventories();
                if (currentIslandUuid != null) checkWebSocketConnection();
            }, 10, 30, TimeUnit.SECONDS);
        }
    }

    /** Примусова негайна синхронізація (для дев-команд). */
    public static void triggerSync() {
        scheduler.submit(() -> {
            syncSourceBlocks();
            fetchSinkInventories();
        });
    }

    // ── SOURCE: пуш AE2 → API ────────────────────────────────────────────────

    /**
     * Намагається знайти Azuriom ID власника острова серед онлайн-гравців.
     * Порівнює island UUID кожного гравця через MarketIslandApi.
     * Зберігає результат — повторний виклик повертає кешоване значення.
     */
    private static void tryResolveSellerAzuriomId() {
        if (sellerAzuriomId != -1 || currentIslandUuid == null) return;
        for (MarketLinkBlockEntity link : RealMarket.getActiveMarketLinks()) {
            if (link.getMode() != BlockMode.SOURCE) continue;
            if (!(link.getLevel() instanceof net.minecraft.server.level.ServerLevel sl)) continue;
            for (net.minecraft.server.level.ServerPlayer player : sl.getServer().getPlayerList().getPlayers()) {
                UUID pIsland = MarketIslandApi.getIslandUuid(player.getUUID());
                if (currentIslandUuid.equals(pIsland)) {
                    int id = AzuriomClient.getPlayerId(player.getUUID());
                    if (id != -1) {
                        sellerAzuriomId = id;
                        System.out.println("[RealMarket] Seller Azuriom ID resolved: " + id + " for island " + currentIslandUuid);
                    }
                    return;
                }
            }
        }
    }

    private static void syncSourceBlocks() {
        // Якщо AE2 ще не готова — перевіримо чи є активний grid і drain черги ДО читання інвентаря
        if (!ae2Ready) {
            boolean hasActiveGrid = false;
            for (MarketLinkBlockEntity link : RealMarket.getActiveMarketLinks()) {
                if (link.getMode() == BlockMode.SOURCE && link.getGrid() != null) {
                    hasActiveGrid = true;
                    break;
                }
            }
            if (hasActiveGrid) {
                ae2Ready = true;
                List<PendingExtraction> queued = new ArrayList<>(extractionQueue);
                extractionQueue.clear();
                if (!queued.isEmpty()) {
                    System.out.println("[RealMarket] AE2 ready — draining " + queued.size() + " queued extractions before sync");
                    for (PendingExtraction p : queued) {
                        extractFromAE2(p.itemId(), p.quantity(), p.pendingId());
                    }
                } else {
                    System.out.println("[RealMarket] AE2 ready — no queued extractions");
                }
            } else {
                System.out.println("[RealMarket] Syncing SOURCE blocks...");
                return; // Нема active grid — ще рано синхронізувати
            }
        }

        tryResolveSellerAzuriomId();
        System.out.println("[RealMarket] Syncing SOURCE blocks...");
        Map<String, JsonObject> aggregatedItems = new HashMap<>();
        double defaultPrice = 10.0;

        for (MarketLinkBlockEntity link : RealMarket.getActiveMarketLinks()) {
            if (link.getMode() != BlockMode.SOURCE) continue;

            IGrid grid = link.getGrid();
            if (grid == null) continue;

            IStorageService storageService = grid.getService(IStorageService.class);
            if (storageService == null) continue;

            MEStorage storage = storageService.getInventory();
            if (storage == null) continue;

            UUID islandUuid = link.getSourceIslandUuid();
            if (islandUuid == null) islandUuid = currentIslandUuid;

            for (var keyEntry : storage.getAvailableStacks()) {
                AEKey key = keyEntry.getKey();
                long amount = keyEntry.getLongValue();

                if (!(key instanceof AEItemKey itemKey)) continue;

                ItemStack stack = itemKey.toStack(1);
                Item item = stack.getItem();
                ResourceLocation regName = BuiltInRegistries.ITEM.getKey(item);
                String itemId = regName != null ? regName.toString() : "minecraft:air";
                if (itemId.equals("minecraft:air")) continue;

                String nbtStr = stack.hasTag() ? stack.getTag().toString() : null;
                String uniqueId = itemId + (nbtStr != null ? nbtStr : "");

                if (aggregatedItems.containsKey(uniqueId)) {
                    JsonObject existing = aggregatedItems.get(uniqueId);
                    existing.addProperty("quantity", existing.get("quantity").getAsLong() + amount);
                } else {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("island_uuid", islandUuid.toString());
                    obj.addProperty("item_id", itemId);
                    if (nbtStr != null) obj.addProperty("item_nbt", nbtStr);
                    obj.addProperty("quantity", amount);
                    obj.addProperty("price", defaultPrice);
                    obj.addProperty("is_for_sale", true);
                    obj.addProperty("version", 1);
                    if (sellerAzuriomId != -1) obj.addProperty("seller_azuriom_id", sellerAzuriomId);
                    aggregatedItems.put(uniqueId, obj);
                }
            }
        }

        if (!aggregatedItems.isEmpty()) {
            sendSyncRequest(aggregatedItems, islandUuidForSync());
        }
    }

    private static UUID islandUuidForSync() {
        for (MarketLinkBlockEntity link : RealMarket.getActiveMarketLinks()) {
            if (link.getMode() == BlockMode.SOURCE && link.getSourceIslandUuid() != null)
                return link.getSourceIslandUuid();
        }
        return currentIslandUuid;
    }

    private static void sendSyncRequest(Map<String, JsonObject> aggregatedItems, UUID islandUuid) {
        JsonObject root = new JsonObject();
        JsonArray itemsArray = new JsonArray();
        aggregatedItems.values().forEach(itemsArray::add);
        root.add("items", itemsArray);

        try {
            String url = apiBase() + "/api/v1/market/islands/" + islandUuid + "/inventory/sync";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                    .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() != 200)
                            System.err.println("[RealMarket] Sync failed: " + res.statusCode() + " " + res.body());
                        else
                            System.out.println("[RealMarket] Synced " + aggregatedItems.size() + " stacks.");
                    }).exceptionally(ex -> {
                        System.err.println("[RealMarket] Sync network error: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] Sync request error: " + e.getMessage());
        }
    }

    // ── SINK: тягнемо інвентар з API → кеш ──────────────────────────────────

    private static void fetchSinkInventories() {
        Set<UUID> toFetch = new HashSet<>();
        for (MarketLinkBlockEntity link : RealMarket.getActiveMarketLinks()) {
            if (link.getMode() == BlockMode.SINK && link.getLinkedIslandUuid() != null)
                toFetch.add(link.getLinkedIslandUuid());
        }
        for (UUID targetUuid : toFetch) {
            fetchInventoryFromApi(targetUuid);
        }
    }

    private static void fetchInventoryFromApi(UUID islandUuid) {
        try {
            String url = apiBase() + "/api/v1/market/islands/" + islandUuid + "/inventory";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() != 200) {
                            System.err.println("[RealMarket] Fetch inventory failed: " + res.statusCode());
                            return;
                        }
                        try {
                            List<CachedItem> items = new ArrayList<>();
                            JsonArray arr = JsonParser.parseString(res.body()).getAsJsonArray();
                            for (JsonElement el : arr) {
                                JsonObject obj = el.getAsJsonObject();
                                items.add(new CachedItem(
                                        obj.get("item_id").getAsString(),
                                        obj.has("item_nbt") && !obj.get("item_nbt").isJsonNull()
                                                ? obj.get("item_nbt").getAsString() : null,
                                        obj.get("quantity").getAsLong(),
                                        obj.get("price").getAsDouble(),
                                        obj.get("is_for_sale").getAsBoolean(),
                                        obj.has("seller_azuriom_id") && !obj.get("seller_azuriom_id").isJsonNull()
                                                ? obj.get("seller_azuriom_id").getAsInt() : -1
                                ));
                            }
                            sinkCache.put(islandUuid, items);
                            System.out.println("[RealMarket] Fetched " + items.size() + " items for island " + islandUuid);
                        } catch (Exception e) {
                            System.err.println("[RealMarket] Failed to parse inventory: " + e.getMessage());
                        }
                    }).exceptionally(ex -> {
                        System.err.println("[RealMarket] Fetch network error: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] Fetch request error: " + e.getMessage());
        }
    }

    /** Повертає кешований інвентар для SINK блоку. Викликається TradeBlock. */
    public static List<CachedItem> getCachedInventory(UUID islandUuid) {
        return sinkCache.getOrDefault(islandUuid, Collections.emptyList());
    }

    /**
     * Резервує покупку через API і повертає pending_id через callback.
     * Callback отримує null якщо API відхилив (conflict / помилка).
     */
    public static void reservePurchaseAsync(UUID islandUuid, String itemId, int quantity, int buyerAzuriomId,
                                            java.util.function.Consumer<Integer> callback) {
        try {
            String url = apiBase() + "/api/v1/market/islands/" + islandUuid + "/purchase";
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            body.addProperty("item_id", itemId);
            body.addProperty("quantity", quantity);
            if (buyerAzuriomId != -1) body.addProperty("buyer_azuriom_id", buyerAzuriomId);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() == 200) {
                            try {
                                com.google.gson.JsonObject resp = com.google.gson.JsonParser
                                        .parseString(res.body()).getAsJsonObject();
                                // API повертає pending_id з WS-повідомлення; але сам purchase
                                // не повертає pending_id напряму — читаємо його якщо є,
                                // інакше шукаємо у стандартному полі
                                int pendingId = resp.has("pending_id") ? resp.get("pending_id").getAsInt() : -1;
                                System.out.println("[RealMarket] Purchase reserved: " + quantity + "x " + itemId
                                        + " pending_id=" + pendingId);
                                callback.accept(pendingId != -1 ? pendingId : null);
                            } catch (Exception e) {
                                System.err.println("[RealMarket] Parse reserve response error: " + e.getMessage());
                                callback.accept(null);
                            }
                        } else {
                            System.err.println("[RealMarket] Purchase reserve failed: " + res.statusCode() + " " + res.body());
                            callback.accept(null);
                        }
                    }).exceptionally(ex -> {
                        System.err.println("[RealMarket] Purchase request error: " + ex.getMessage());
                        callback.accept(null);
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] reservePurchaseAsync error: " + e.getMessage());
            callback.accept(null);
        }
    }

    /** Скасовує резервування покупки (коли оплата провалилась). */
    public static void cancelPurchaseAsync(UUID islandUuid, int pendingId) {
        try {
            String url = apiBase() + "/api/v1/market/islands/" + islandUuid + "/purchase/" + pendingId + "/cancel";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() == 200)
                            System.out.println("[RealMarket] Purchase cancelled: pending_id=" + pendingId);
                        else
                            System.err.println("[RealMarket] Cancel failed: " + res.statusCode() + " " + res.body());
                    }).exceptionally(ex -> {
                        System.err.println("[RealMarket] Cancel error: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] cancelPurchaseAsync error: " + e.getMessage());
        }
    }

    /**
     * Витягує предмети з AE2 мережі острова після купівлі на спавні.
     * Викликається з WebSocket повідомлення "market_purchase".
     */
    public static void extractFromAE2(String itemId, int quantity, int pendingId) {
        if (!ae2Ready) {
            System.out.println("[RealMarket] AE2 not ready yet — queuing extraction: " + quantity + "x " + itemId + " (pending_id=" + pendingId + ")");
            extractionQueue.add(new PendingExtraction(itemId, quantity, pendingId));
            return;
        }

        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == null) {
            System.err.println("[RealMarket] extractFromAE2: unknown item " + itemId);
            return;
        }

        AEItemKey key = AEItemKey.of(item);
        long totalExtracted = 0;

        for (MarketLinkBlockEntity link : RealMarket.getActiveMarketLinks()) {
            if (link.getMode() != BlockMode.SOURCE) continue;
            IGrid grid = link.getGrid();
            if (grid == null) continue;

            IStorageService storage = grid.getService(IStorageService.class);
            IEnergyService energy   = grid.getService(IEnergyService.class);
            if (storage == null || energy == null) continue;

            long toExtract = quantity - totalExtracted;
            if (toExtract <= 0) break;

            long extracted = StorageHelper.poweredExtraction(
                    energy,
                    storage.getInventory(),
                    key,
                    toExtract,
                    IActionSource.empty()
            );
            totalExtracted += extracted;

            if (totalExtracted >= quantity) break;
        }

        if (totalExtracted > 0) {
            System.out.println("[RealMarket] Extracted " + totalExtracted + "x " + itemId + " from AE2 (requested " + quantity + ")");
            if (pendingId > 0) confirmExtraction(pendingId);
        } else {
            // Предметів ще нема в AE2 — продавець в боргу. Зберігаємо pending,
            // API повторно надішле WS коли з'являться предмети при наступному sync.
            System.err.println("[RealMarket] extractFromAE2: could not extract " + quantity + "x " + itemId + " — debt recorded, will retry on next sync");
            if (pendingId > 0) failExtraction(pendingId);
        }
    }

    /** Підтверджує extraction — видаляє pending запис в API і кредитує продавця. */
    private static void confirmExtraction(int pendingId) {
        if (currentIslandUuid == null) return;
        try {
            String url = apiBase() + "/api/v1/market/islands/" + currentIslandUuid + "/extraction/" + pendingId + "/confirm";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() == 200)
                            System.out.println("[RealMarket] Extraction confirmed: pending_id=" + pendingId);
                        else
                            System.err.println("[RealMarket] Confirm failed: " + res.statusCode());
                    }).exceptionally(ex -> {
                        System.err.println("[RealMarket] Confirm error: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] confirmExtraction error: " + e.getMessage());
        }
    }

    /** Повідомляє API що extraction не вдався (борг продавця) — pending залишається. */
    private static void failExtraction(int pendingId) {
        if (currentIslandUuid == null) return;
        try {
            String url = apiBase() + "/api/v1/market/islands/" + currentIslandUuid + "/extraction/" + pendingId + "/fail";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() == 200)
                            System.out.println("[RealMarket] Extraction fail reported: pending_id=" + pendingId);
                        else
                            System.err.println("[RealMarket] Fail report error: " + res.statusCode());
                    }).exceptionally(ex -> {
                        System.err.println("[RealMarket] failExtraction error: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] failExtraction error: " + e.getMessage());
        }
    }

    public record PurchaseResult(int pendingId, double totalCost) {}

    /**
     * Повна покупка в одному API-запиті: перевірка балансу + зняття грошей + резервування.
     * Замінює окремі виклики getBalAsync + updateAsync + reservePurchaseAsync.
     */
    public static void executePurchaseAsync(UUID islandUuid, String itemId, int quantity,
                                             int buyerAzuriomId, java.util.function.Consumer<PurchaseResult> callback) {
        try {
            String url = apiBase() + "/api/v1/market/islands/" + islandUuid + "/purchase/execute";
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            body.addProperty("item_id", itemId);
            body.addProperty("quantity", quantity);
            body.addProperty("buyer_azuriom_id", buyerAzuriomId);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() == 200) {
                            try {
                                com.google.gson.JsonObject resp = com.google.gson.JsonParser
                                        .parseString(res.body()).getAsJsonObject();
                                int pendingId = resp.has("pending_id") ? resp.get("pending_id").getAsInt() : -1;
                                double totalCost = resp.has("total_cost") ? resp.get("total_cost").getAsDouble() : 0.0;
                                callback.accept(new PurchaseResult(pendingId, totalCost));
                            } catch (Exception e) {
                                System.err.println("[RealMarket] executePurchase parse error: " + e.getMessage());
                                callback.accept(null);
                            }
                        } else {
                            System.err.println("[RealMarket] executePurchase failed: " + res.statusCode() + " " + res.body());
                            callback.accept(null);
                        }
                    }).exceptionally(ex -> {
                        System.err.println("[RealMarket] executePurchase error: " + ex.getMessage());
                        callback.accept(null);
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] executePurchaseAsync error: " + e.getMessage());
            callback.accept(null);
        }
    }

    /** Продаж предметів з інвентаря гравця: API кредитує продавця і записує транзакцію. */
    public static void sellItemAsync(UUID islandUuid, String itemId, int quantity,
                                      int sellerAzuriomId, double unitPrice,
                                      java.util.function.Consumer<Boolean> callback) {
        try {
            String url = apiBase() + "/api/v1/market/islands/" + islandUuid + "/sell";
            com.google.gson.JsonObject body = new com.google.gson.JsonObject();
            body.addProperty("item_id", itemId);
            body.addProperty("quantity", quantity);
            body.addProperty("seller_azuriom_id", sellerAzuriomId);
            body.addProperty("unit_price", unitPrice);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> callback.accept(res.statusCode() == 200))
                    .exceptionally(ex -> {
                        System.err.println("[RealMarket] sellItem error: " + ex.getMessage());
                        callback.accept(false);
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[RealMarket] sellItemAsync error: " + e.getMessage());
            callback.accept(false);
        }
    }

    /** Примусово оновити кеш для конкретного острова (наприклад після купівлі). */
    public static void invalidateCache(UUID islandUuid) {
        sinkCache.remove(islandUuid);
        fetchInventoryFromApi(islandUuid);
    }

    // ── WebSocket ────────────────────────────────────────────────────────────

    private static void connectWebSocket() {
        if (wsClient != null && wsClient.isOpen()) return;
        try {
            String baseUrl = ApiConfig.getApiWorldUrl()
                    .replace("http://", "ws://")
                    .replace("https://", "wss://");
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

    public static void shutdown() {
        scheduler.shutdown();
        if (wsClient != null) wsClient.close();
    }

    // ── Утиліти ──────────────────────────────────────────────────────────────

    private static String apiBase() {
        return ApiConfig.getApiWorldUrl();
    }
}
