package RealMarket.realmarket.api;

import RealMarket.realmarket.config.ApiConfig;
import RealMarket.realmarket.world.IslandManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * WebSocket клієнт спавн-сервера.
 * Підключається до API як "spawn_hub" і отримує команди керування варп-платформами.
 *
 * Підтримувані типи повідомлень від API:
 *   island_create  — створити платформу для UUID
 *   island_suspend — зберегти і видалити платформу
 *   island_restore — відновити платформу зі збереженого файлу
 *   island_delete  — остаточно видалити збережені дані
 *   ping           — відповідає pong
 */
public class HubCommandClient {

    private static volatile WebSocket webSocket;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HubCommandClient");
                t.setDaemon(true);
                return t;
            });

    private static volatile boolean initialized = false;

    /** Ініціалізує клієнт і запускає watchdog перепідключення. Викликається один раз. */
    public static void init() {
        if (initialized) return;
        initialized = true;
        connect();
        // Watchdog: перепідключатись кожні 30 сек якщо WS впав
        scheduler.scheduleAtFixedRate(() -> {
            if (webSocket == null || webSocket.isInputClosed()) {
                System.out.println("[HubClient] Reconnecting WebSocket...");
                connect();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    private static void connect() {
        try {
            String baseUrl = ApiConfig.getApiWorldUrl()
                    .replace("http://", "ws://")
                    .replace("https://", "wss://");
            URI wsUri = new URI(baseUrl + "/ws/spawn_hub");
            System.out.println("[HubClient] Connecting to " + wsUri);

            HTTP.newWebSocketBuilder()
                    .buildAsync(wsUri, new WebSocket.Listener() {

                        @Override
                        public void onOpen(WebSocket ws) {
                            webSocket = ws;
                            System.out.println("[HubClient] Connected.");
                            WebSocket.Listener.super.onOpen(ws);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            handleMessage(data.toString());
                            return WebSocket.Listener.super.onText(ws, data, last);
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            System.out.println("[HubClient] Closed (" + statusCode + "): " + reason);
                            webSocket = null;
                            return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            System.err.println("[HubClient] Error: " + error.getMessage());
                            webSocket = null;
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("[HubClient] Failed to connect: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[HubClient] connect() exception: " + e.getMessage());
        }
    }

    private static void handleMessage(String raw) {
        try {
            JsonObject msg = JsonParser.parseString(raw).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            switch (type) {
                case "ping" -> {
                    WebSocket ws = webSocket;
                    if (ws != null) ws.sendText("{\"type\":\"pong\"}", true);
                }
                case "island_create" -> {
                    UUID uuid = UUID.fromString(msg.get("uuid").getAsString());
                    int pendingId = msg.has("pending_id") ? msg.get("pending_id").getAsInt() : -1;
                    runOnServerThread(server -> {
                        ServerLevel level = server.getLevel(Level.OVERWORLD);
                        if (level != null) {
                            IslandManager.createIsland(level, uuid);
                            System.out.println("[HubClient] island_create done for " + uuid);
                        } else {
                            System.err.println("[HubClient] island_create: Overworld not available");
                        }
                        confirmCommand(pendingId);
                    });
                }
                case "island_suspend" -> {
                    UUID uuid = UUID.fromString(msg.get("uuid").getAsString());
                    int pendingId = msg.has("pending_id") ? msg.get("pending_id").getAsInt() : -1;
                    runOnServerThread(server -> {
                        ServerLevel level = server.getLevel(Level.OVERWORLD);
                        if (level != null) {
                            IslandManager.suspendPlatform(level, uuid);
                            System.out.println("[HubClient] island_suspend done for " + uuid);
                        }
                        confirmCommand(pendingId);
                    });
                }
                case "island_restore" -> {
                    UUID uuid = UUID.fromString(msg.get("uuid").getAsString());
                    int pendingId = msg.has("pending_id") ? msg.get("pending_id").getAsInt() : -1;
                    runOnServerThread(server -> {
                        ServerLevel level = server.getLevel(Level.OVERWORLD);
                        if (level != null) {
                            boolean ok = IslandManager.restorePlatform(level, uuid);
                            System.out.println("[HubClient] island_restore " + (ok ? "done" : "failed") + " for " + uuid);
                        }
                        confirmCommand(pendingId);
                    });
                }
                case "island_delete" -> {
                    UUID uuid = UUID.fromString(msg.get("uuid").getAsString());
                    int pendingId = msg.has("pending_id") ? msg.get("pending_id").getAsInt() : -1;
                    // Не потребує ServerLevel — лише видаляє файл
                    boolean ok = IslandManager.deleteSavedPlatform(uuid);
                    System.out.println("[HubClient] island_delete " + (ok ? "done" : "no file") + " for " + uuid);
                    confirmCommand(pendingId);
                }
                default -> { /* ігноруємо невідомі повідомлення */ }
            }
        } catch (Exception e) {
            System.err.println("[HubClient] handleMessage error: " + e.getMessage());
        }
    }

    /** Підтверджує виконання команди — видаляє pending запис в API. */
    private static void confirmCommand(int pendingId) {
        if (pendingId <= 0) return;
        try {
            String url = ApiConfig.getApiWorldUrl() + "/api/v1/warps/confirm/" + pendingId;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> {
                        if (res.statusCode() == 200)
                            System.out.println("[HubClient] Confirmed warp command pending_id=" + pendingId);
                        else
                            System.err.println("[HubClient] Confirm failed: " + res.statusCode());
                    }).exceptionally(ex -> {
                        System.err.println("[HubClient] Confirm error: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("[HubClient] confirmCommand error: " + e.getMessage());
        }
    }

    /**
     * Виконує дію у головному потоці сервера (thread-safe для Minecraft API).
     */
    private static void runOnServerThread(Consumer<MinecraftServer> action) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> action.accept(server));
        } else {
            System.err.println("[HubClient] MinecraftServer not available (server not started yet?)");
        }
    }

    public static void shutdown() {
        scheduler.shutdown();
        WebSocket ws = webSocket;
        if (ws != null) ws.abort();
    }
}
