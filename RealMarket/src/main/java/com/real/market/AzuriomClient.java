package com.real.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AzuriomClient {
    private static final HttpClient client = HttpClient.newHttpClient();

    public static CompletableFuture<AzUserInfo> syncPlayer(String name, UUID uuid) {
        //  Read config values on the calling (main) thread, BEFORE going async
        final String siteUrl = Config.SITE_URL.get();
        final String token   = Config.TOKEN.get();
        final int    serverId = Config.SERVER_ID.get();

        return CompletableFuture.supplyAsync(() -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("server-id", serverId);   // use local variables
            payload.addProperty("maxPlayers", 100);
            payload.addProperty("full", true);

            JsonArray players = new JsonArray();
            JsonObject pObj = new JsonObject();
            pObj.addProperty("name", name);
            pObj.addProperty("uuid", uuid.toString());
            players.add(pObj);
            payload.add("players", players);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(siteUrl))             // use local variable
                    .header("Azuriom-Link-Token", token)  // use local variable
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            try {
                HttpResponse<String> res = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    System.out.println("[RealMarket] API response: " + res.body());
                    var json = JsonParser.parseString(res.body()).getAsJsonObject();

                    // Відповідь може бути об'єктом користувача (AzAuth) або містити список (AzLink)
                    if (json.has("money")) {
                        return new AzUserInfo(
                                json.get("id").getAsInt(),
                                json.get("money").getAsDouble()
                        );
                    } else if (json.has("users")) {
                        var users = json.get("users").getAsJsonArray();
                        if (users.size() > 0) {
                            var pData = users.get(0).getAsJsonObject();
                            return new AzUserInfo(
                                    pData.get("id").getAsInt(),
                                    pData.get("money").getAsDouble()
                            );
                        }
                    }
                } else {
                    System.err.println("[RealMarket] HTTP error: " + res.statusCode() + " body: " + res.body());
                }
            } catch (Exception e) {
            System.err.println("[RealMarket] syncPlayer error: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
            return null;
        });
    }

    public static CompletableFuture<Boolean> updateMoney(int azId, String action, double amount) {
        //  Same pattern: snapshot config values before going async
        final String siteUrl = Config.SITE_URL.get();
        final String token   = Config.TOKEN.get();

        return CompletableFuture.supplyAsync(() -> {
            JsonObject payload = new JsonObject();
            payload.addProperty("amount", amount);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(siteUrl + "/user/" + azId + "/money/" + action))
                    .header("Azuriom-Link-Token", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();

            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString())
                        .statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
    }

    public record AzUserInfo(int id, double money) {}
}