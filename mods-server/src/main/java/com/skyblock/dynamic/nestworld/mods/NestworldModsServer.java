package com.skyblock.dynamic.nestworld.mods;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.skyblock.dynamic.Config;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class NestworldModsServer {

    private static final Logger LOGGER = LogUtils.getLogger();
    public static final IslandProvider ISLAND_PROVIDER = new IslandProvider();

    public static class IslandProvider {
        private final Map<UUID, UUID> islandCache = new ConcurrentHashMap<>();
        private final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final Gson gson = new Gson();

        public UUID getCachedTeamId(UUID playerUuid) {
            return islandCache.get(playerUuid);
        }

        public CompletableFuture<UUID> refreshAndGetTeamId(UUID playerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "teams/my_team/";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            try {
                                JsonObject teamJson = gson.fromJson(response.body(), JsonObject.class);
                                if (teamJson.has("owner_uuid")) {
                                    UUID ownerUuid = UUID.fromString(teamJson.get("owner_uuid").getAsString());

                                    List<UUID> memberUuids = new ArrayList<>();
                                    if (teamJson.has("members") && teamJson.get("members").isJsonArray()) {
                                        teamJson.get("members").getAsJsonArray().forEach(memberElement -> {
                                            JsonObject memberObj = memberElement.getAsJsonObject();
                                            if (memberObj.has("player_uuid")) {
                                                UUID memberUuid = UUID.fromString(memberObj.get("player_uuid").getAsString());
                                                memberUuids.add(memberUuid);
                                                islandCache.put(memberUuid, ownerUuid);
                                            }
                                        });
                                    }
                                    
                                    islandCache.put(ownerUuid, ownerUuid);

                                    com.skyblock.dynamic.utils.QuestTeamBridge.getInstance().syncTeamData(ownerUuid, memberUuids);
                                    return ownerUuid;
                                }
                            } catch (JsonSyntaxException | IllegalStateException e) {
                                LOGGER.error("Failed to parse team data for player {}", playerUuid, e);
                            }
                        } else {
                            LOGGER.warn("Failed to get team data for player {}. Status: {}, Body: {}", playerUuid, response.statusCode(), response.body());
                        }
                        return null;
                    });
        }

        public CompletableFuture<Void> sendReady(UUID ownerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "islands/" + ownerUuid.toString() + "/ready";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                    .build();
            
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        LOGGER.error("Failed to send 'island ready' signal for owner UUID: {}. Status: {}, Body: {}", ownerUuid, response.statusCode(), response.body());
                    } else {
                        LOGGER.info("Successfully sent 'island ready' signal for owner UUID: {}", ownerUuid);
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Exception while sending 'island ready' signal for owner UUID: {}", ownerUuid, ex);
                    return null;
                });
        }

        public CompletableFuture<Void> sendFreeze(UUID ownerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "islands/" + ownerUuid.toString() + "/freeze";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 300) {
                        LOGGER.error("Failed to send 'island freeze' signal for owner UUID: {}. Status: {}, Body: {}", ownerUuid, response.statusCode(), response.body());
                    } else {
                        LOGGER.info("Successfully sent 'island freeze' signal for owner UUID: {}", ownerUuid);
                    }
                })
                .exceptionally(ex -> {
                    LOGGER.error("Exception while sending 'island freeze' signal for owner UUID: {}", ownerUuid, ex);
                    return null;
                });
        }
    }
}