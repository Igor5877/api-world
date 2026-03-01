package com.skyblock.sales;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import appeng.api.networking.security.IActionSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = "sales_addon", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SalesSyncManager {
    private static final Logger LOGGER = LogManager.getLogger();
    // Force HTTP/1.1 to prevent "Unsupported upgrade request" errors in Uvicorn
    private static final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private static final Gson gson = new Gson();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Config values are now loaded from SalesConfig

    private static SalesTerminalBlockEntity activeTerminal;
    private static final ConcurrentLinkedQueue<Runnable> mainThreadTasks = new ConcurrentLinkedQueue<>();

    public static void registerTerminal(SalesTerminalBlockEntity terminal) {
        activeTerminal = terminal;
    }

    public static void unregisterTerminal(SalesTerminalBlockEntity terminal) {
        if (activeTerminal == terminal) {
            activeTerminal = null;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Process tasks scheduled for the main thread
        while (!mainThreadTasks.isEmpty()) {
            Runnable task = mainThreadTasks.poll();
            if (task != null) {
                try {
                    task.run();
                } catch (Exception e) {
                    LOGGER.error("Error running main thread task", e);
                }
            }
        }

        // Periodic sync every 60 seconds (1200 ticks)
        if (event.getServer().getTickCount() % 1200 == 0) {
             triggerSync();
        }
    }

    private static void triggerSync() {
        if (activeTerminal == null || activeTerminal.getMainNode() == null) return;

        // 1. Capture State on Main Thread
        // Scan AE2 network (must be done on server thread)
        // Note: activeTerminal.getMainNode() already returns IGridNode!
        Map<String, Long> items = AE2Handler.scanNetwork(activeTerminal.getMainNode());

        // Get config values
        String apiUrl = SalesConfig.COMMON.apiUrl.get();
        int islandId = SalesConfig.COMMON.islandId.get();

        // 2. Offload Network I/O to Executor
        executor.submit(() -> {
            try {
                // Upload Sales Data
                JsonObject payload = new JsonObject();
                JsonArray itemsArray = new JsonArray();

                items.forEach((id, qty) -> {
                    JsonObject itemObj = new JsonObject();
                    itemObj.addProperty("item_id", id);
                    itemObj.addProperty("quantity", qty);
                    itemObj.addProperty("price", 10.0); // Placeholder price logic
                    itemsArray.add(itemObj);
                });

                payload.add("items", itemsArray);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl + "/sync/" + islandId))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOGGER.error("Failed to sync sales data: " + response.statusCode());
                } else {
                    LOGGER.debug("Sales data synced successfully.");
                }

                // Check for Pending Removals
                checkPendingRemovals(apiUrl, islandId);

            } catch (Exception e) {
                LOGGER.error("Error in sync executor", e);
            }
        });
    }

    private static void checkPendingRemovals(String apiUrl, int islandId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/pending/" + islandId))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonArray pending = gson.fromJson(response.body(), JsonArray.class);

                // Process each pending removal
                pending.forEach(element -> {
                    JsonObject obj = element.getAsJsonObject();
                    int txId = obj.get("transaction_id").getAsInt();
                    String itemId = obj.get("item_id").getAsString();
                    int qty = obj.get("quantity").getAsInt();

                    // Schedule extraction on Main Thread
                    mainThreadTasks.add(() -> {
                        if (activeTerminal != null && activeTerminal.getMainNode() != null) {
                            // Dummy source for now, ideally create a machine source
                            IActionSource source = null;

                            // Note: activeTerminal.getMainNode() already returns IGridNode!
                            long extracted = AE2Handler.extractItem(activeTerminal.getMainNode(), itemId, qty, source);

                            if (extracted >= qty) {
                                // Confirm to API (Async)
                                executor.submit(() -> confirmRemoval(apiUrl, txId));
                            } else {
                                LOGGER.warn("Could not extract full quantity for transaction " + txId + ". Requested: " + qty + ", Extracted: " + extracted);
                            }
                        }
                    });
                });
            }
        } catch (Exception e) {
            LOGGER.error("Error checking pending removals", e);
        }
    }

    private static void confirmRemoval(String apiUrl, int txId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/confirm_removal/" + txId))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOGGER.error("Error confirming removal", e);
        }
    }

    public static void executePurchase(net.minecraft.world.entity.player.Player buyer, int islandId, String itemId, int quantity) {
        String apiUrl = SalesConfig.COMMON.apiUrl.get();

        executor.submit(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("buyer_uuid", buyer.getUUID().toString());
                payload.addProperty("item_id", itemId);
                payload.addProperty("quantity", quantity);
                payload.addProperty("island_id", islandId);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl + "/purchase"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    mainThreadTasks.add(() -> {
                        if (buyer.level().getBlockEntity(buyer.blockPosition()) instanceof SalesVendingBlockEntity vending) {
                             vending.onPurchaseSuccess(buyer);
                        } else {
                             // Fallback if player moved away from block
                             net.minecraft.world.item.ItemStack itemStack = new net.minecraft.world.item.ItemStack(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(itemId)), quantity);
                             if (!buyer.getInventory().add(itemStack)) {
                                 buyer.drop(itemStack, false);
                             }
                             buyer.sendSystemMessage(net.minecraft.network.chat.Component.literal("Purchase successful! Item delivered."));
                        }
                    });
                } else {
                    mainThreadTasks.add(() -> buyer.sendSystemMessage(net.minecraft.network.chat.Component.literal("Purchase failed. Server returned: " + response.statusCode())));
                }
            } catch (Exception e) {
                LOGGER.error("Error executing purchase", e);
                mainThreadTasks.add(() -> buyer.sendSystemMessage(net.minecraft.network.chat.Component.literal("Purchase error: " + e.getMessage())));
            }
        });
    }
}
