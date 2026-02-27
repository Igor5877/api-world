package com.skyblock.sales;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = "sales_addon", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SalesSyncManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final Gson gson = new Gson();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Configurable via config file or dynamic lookup
    // TODO: Load from config
    private static String API_URL = "http://localhost:8000/api/v1/sales";
    private static int ISLAND_ID = 1; // Placeholder: Must be fetched from island context

    private static SalesTerminalBlockEntity activeTerminal;

    public static void registerTerminal(SalesTerminalBlockEntity terminal) {
        activeTerminal = terminal;
        syncSalesData();
        checkPendingRemovals(); // Check for sales made while offline
    }

    public static void unregisterTerminal(SalesTerminalBlockEntity terminal) {
        if (activeTerminal == terminal) {
            activeTerminal = null;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        // Periodic sync every 60 seconds (1200 ticks)
        if (event.getServer().getTickCount() % 1200 == 0) {
             syncSalesData();
             checkPendingRemovals();
        }
    }

    private static void syncSalesData() {
        if (activeTerminal == null || activeTerminal.getMainNode() == null) return;

        executor.submit(() -> {
            try {
                // Gather data from AE2 network
                Map<String, Long> items = AE2Handler.scanNetwork(activeTerminal.getMainNode());

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
                        .uri(URI.create(API_URL + "/sync/" + ISLAND_ID))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    LOGGER.error("Failed to sync sales data: " + response.statusCode());
                } else {
                    LOGGER.debug("Sales data synced successfully.");
                }

            } catch (Exception e) {
                LOGGER.error("Error syncing sales data", e);
            }
        });
    }

    private static void checkPendingRemovals() {
         if (activeTerminal == null || activeTerminal.getMainNode() == null) return;

         executor.submit(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(API_URL + "/pending/" + ISLAND_ID))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonArray pending = gson.fromJson(response.body(), JsonArray.class);
                    pending.forEach(element -> {
                        JsonObject obj = element.getAsJsonObject();
                        int txId = obj.get("transaction_id").getAsInt();
                        String itemId = obj.get("item_id").getAsString();
                        int qty = obj.get("quantity").getAsInt();

                        // Execute removal logic
                        // Note: AE2 interaction should ideally happen on the server thread if it modifies world state
                        // Extracting items usually modifies inventory NBT, so we must be careful.
                        // For safety, we should schedule this back to the main thread.

                        // Stub for now, would use:
                        // AE2Handler.extractItem(activeTerminal.getMainNode(), itemId, qty, ...);

                        long extracted = 0; // AE2Handler.extractItem(...)

                        // If successful extraction:
                        confirmRemoval(txId);
                    });
                }
            } catch (Exception e) {
                LOGGER.error("Error checking pending removals", e);
            }
        });
    }

    private static void confirmRemoval(int txId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL + "/confirm_removal/" + txId))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            LOGGER.error("Error confirming removal", e);
        }
    }
}
