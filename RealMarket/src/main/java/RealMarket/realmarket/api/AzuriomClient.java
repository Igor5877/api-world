package RealMarket.realmarket.api;

import com.google.gson.*;
import RealMarket.realmarket.config.ApiConfig;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AzuriomClient {
    private static final Map<UUID, Integer> IDS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // Ініціалізація конфіга при завантаженні класу
    static {
        System.out.println("[RealMarket] Loading API configuration...");
        String token = ApiConfig.getToken();
        String url = ApiConfig.getApiUrl();
        int serverId = ApiConfig.getServerId();
        System.out.println("[RealMarket] API Config initialized - URL: " + url + ", Server ID: " + serverId);
    }

    public static int getPlayerId(UUID uuid) {
        return IDS.getOrDefault(uuid, -1);
    }

    // Асинхронна синхронізація без циклу for
    public static void sync(UUID uuid, String name) {
        String token = ApiConfig.getToken();
        if (token == null || token.isEmpty()) {
            System.err.println("API token not configured. Please set token in config/realmarket-api.toml");
            return;
        }
        
        String url = ApiConfig.getApiUrl();
        int serverId = ApiConfig.getServerId();
        
        JsonObject root = new JsonObject();
        root.addProperty("server-id", serverId);
        JsonArray players = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.addProperty("uuid", uuid.toString());
        players.add(p);
        root.add("players", players);

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .header("Azuriom-Link-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString())).build();

        // Відправляємо асинхронно
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() != 200) {
                        System.err.println("[RealMarket] API Error: HTTP " + res.statusCode() + " - " + res.body());
                        return;
                    }
                    try {
                        JsonElement parsed = JsonParser.parseString(res.body().trim());
                        if (parsed.isJsonObject()) {
                            JsonObject resp = parsed.getAsJsonObject();
                            if (resp.has("users") && resp.get("users").isJsonArray()) {
                                JsonArray users = resp.getAsJsonArray("users");
                                if (users.size() > 0 && users.get(0).isJsonObject() && users.get(0).getAsJsonObject().has("id")) {
                                    // Беремо ID відразу з першого користувача (users[0]), бо сайт відфільтрував запит
                                    int id = users.get(0).getAsJsonObject().get("id").getAsInt();
                                    IDS.put(uuid, id);
                                    System.out.println("[RealMarket] Synced player " + name + " -> ID " + id);
                                }
                            }
                        }
                    } catch (JsonSyntaxException | IllegalStateException e) {
                        System.err.println("[RealMarket] Failed to parse JSON response: " + res.body());
                    }
                }).exceptionally(ex -> {
                    System.err.println("[RealMarket] Network exception during sync: " + ex.getMessage());
                    return null;
                });
    }

    // Асинхронне отримання балансу
    public static CompletableFuture<Double> getBalAsync(int id) {
        if (id == -1) return CompletableFuture.completedFuture(-1.0);

        String token = ApiConfig.getToken();
        if (token == null || token.isEmpty()) {
            return CompletableFuture.completedFuture(-1.0);
        }
        
        String url = ApiConfig.getApiUrl();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url + "/user/" + id))
                .header("Azuriom-Link-Token", token).GET().build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    if (res.statusCode() == 200) {
                        try {
                            JsonElement parsed = JsonParser.parseString(res.body());
                            if (parsed.isJsonObject() && parsed.getAsJsonObject().has("money")) {
                                return parsed.getAsJsonObject().get("money").getAsDouble();
                            }
                        } catch (Exception e) {
                            System.err.println("[RealMarket] JSON parsing error in getBalAsync: " + e.getMessage());
                        }
                    } else {
                        System.err.println("[RealMarket] API Error (balance): HTTP " + res.statusCode());
                    }
                    return -1.0;
                }).exceptionally(ex -> {
                    System.err.println("[RealMarket] Network exception in getBalAsync: " + ex.getMessage());
                    return -1.0;
                });
    }

    // Асинхронне оновлення грошей
    public static CompletableFuture<Boolean> updateAsync(int id, double amt) {
        if (id == -1) return CompletableFuture.completedFuture(false);

        String token = ApiConfig.getToken();
        if (token == null || token.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        
        String url = ApiConfig.getApiUrl();
        String act = amt >= 0 ? "add" : "remove";
        JsonObject body = new JsonObject();
        body.addProperty("amount", Math.abs(amt));

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url + "/user/" + id + "/money/" + act))
                .header("Azuriom-Link-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    if (res.statusCode() == 200) {
                        return true;
                    }
                    System.err.println("[RealMarket] API Error (update money): HTTP " + res.statusCode() + " - " + res.body());
                    return false;
                }).exceptionally(ex -> {
                    System.err.println("[RealMarket] Network exception in updateAsync: " + ex.getMessage());
                    return false;
                });
    }
}