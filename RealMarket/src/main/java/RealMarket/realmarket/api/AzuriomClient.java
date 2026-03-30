package net.market.realmarket.api;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class AzuriomClient {
    private static final String URL = "https://nestworld.site/api/azlink";
    private static final String TOKEN = "qTuFiVRpG9QNvWxJVmidtu2vYz2j6079";
    private static final Map<UUID, Integer> IDS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static int getPlayerId(UUID uuid) {
        return IDS.getOrDefault(uuid, -1);
    }

    // Асинхронна синхронізація без циклу for
    public static void sync(UUID uuid, String name) {
        JsonObject root = new JsonObject();
        root.addProperty("server-id", 1);
        JsonArray players = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.addProperty("uuid", uuid.toString());
        players.add(p);
        root.add("players", players);

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(URL))
                .header("Azuriom-Link-Token", TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString())).build();

        // Відправляємо асинхронно
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body -> {
                    JsonObject resp = JsonParser.parseString(body).getAsJsonObject();
                    if (resp.has("users")) {
                        // Беремо ID відразу з першого користувача (users[0]), бо сайт відфільтрував запит
                        int id = resp.getAsJsonArray("users").get(0).getAsJsonObject().get("id").getAsInt();
                        IDS.put(uuid, id);
                    }
                }).exceptionally(ex -> { ex.printStackTrace(); return null; });
    }

    // Асинхронне отримання балансу
    public static CompletableFuture<Double> getBalAsync(int id) {
        if (id == -1) return CompletableFuture.completedFuture(-1.0);

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(URL + "/user/" + id))
                .header("Azuriom-Link-Token", TOKEN).GET().build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> res.statusCode() == 200 ?
                        JsonParser.parseString(res.body()).getAsJsonObject().get("money").getAsDouble() : -1.0);
    }

    // Асинхронне оновлення грошей
    public static CompletableFuture<Boolean> updateAsync(int id, double amt) {
        if (id == -1) return CompletableFuture.completedFuture(false);

        String act = amt >= 0 ? "add" : "remove";
        JsonObject body = new JsonObject();
        body.addProperty("amount", Math.abs(amt));

        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(URL + "/user/" + id + "/money/" + act))
                .header("Azuriom-Link-Token", TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> res.statusCode() == 200);
    }
}