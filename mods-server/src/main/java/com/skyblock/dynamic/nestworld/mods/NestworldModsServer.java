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
<<<<<<< Updated upstream

public class NestworldModsServer {

    private static final Logger LOGGER = LogUtils.getLogger();
=======
import java.util.concurrent.TimeUnit;

public class NestworldModsServer {

>>>>>>> Stashed changes
    public static final IslandProvider ISLAND_PROVIDER = new IslandProvider();
    private static final Logger LOGGER = LogUtils.getLogger();

    public static class IslandProvider {
        private final Map<UUID, UUID> islandCache = new ConcurrentHashMap<>();
        private final Map<UUID, CompletableFuture<UUID>> pendingRequests = new ConcurrentHashMap<>();
        private final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final Gson gson = new Gson();

        public UUID getCachedTeamId(UUID playerUuid) {
            if (islandCache.containsKey(playerUuid)) {
                return islandCache.get(playerUuid);
            }

            CompletableFuture<UUID> future = pendingRequests.get(playerUuid);
            if (future == null) {
                // Not in cache and no request pending, so start one and wait for it.
                future = refreshAndGetTeamId(playerUuid);
            }

            try {
                // Block and wait for the future to complete, but with a reasonable timeout.
                return future.get(1000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOGGER.error("Couldn't get team ID for player {} in time", playerUuid, e);
                return null;
            }
        }

        public CompletableFuture<Void> sendReady(UUID ownerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "islands/" + ownerUuid.toString() + "/ready";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
<<<<<<< Updated upstream
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
=======
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                            LOGGER.warn("Non-successful response {} for ready signal for owner {}", response.statusCode(), ownerUuid);
>>>>>>> Stashed changes
                        }
                    });
        }

<<<<<<< Updated upstream
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

=======
>>>>>>> Stashed changes
        public CompletableFuture<Void> sendFreeze(UUID ownerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "islands/" + ownerUuid.toString() + "/freeze";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
<<<<<<< Updated upstream
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
=======
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                             LOGGER.warn("Non-successful response {} for freeze signal for owner {}", response.statusCode(), ownerUuid);
                        }
                    });
        }

        public CompletableFuture<UUID> refreshAndGetTeamId(UUID playerUuid) {
            return pendingRequests.computeIfAbsent(playerUuid, k -> {
                String apiUrl = Config.getApiBaseUrl() + "teams/my_team/";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl + playerUuid.toString()))
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
                                        memberUuids.add(ownerUuid); // Owner is always a member

                                        if (teamJson.has("members") && teamJson.get("members").isJsonArray()) {
                                            teamJson.get("members").getAsJsonArray().forEach(memberElement -> {
                                                JsonObject memberObj = memberElement.getAsJsonObject();
                                                if (memberObj.has("player_uuid")) {
                                                    UUID memberUuid = UUID.fromString(memberObj.get("player_uuid").getAsString());
                                                    if (!memberUuids.contains(memberUuid)) {
                                                        memberUuids.add(memberUuid);
                                                    }
                                                }
                                            });
                                        }
                                        
                                        // Update cache for all members
                                        for (UUID memberUuid : memberUuids) {
                                            islandCache.put(memberUuid, ownerUuid);
                                        }

                                        com.skyblock.dynamic.utils.QuestTeamBridge.getInstance().syncTeamData(ownerUuid, memberUuids);
                                        LOGGER.info("Successfully refreshed team data for owner {}", ownerUuid);
                                        return ownerUuid;
                                    }
                                } catch (JsonSyntaxException | IllegalStateException e) {
                                    LOGGER.error("Failed to parse team data for player {}", playerUuid, e);
                                }
                            } else {
                                LOGGER.warn("Failed to fetch team data for player {}: HTTP {}", playerUuid, response.statusCode());
                            }
                            return null;
                        })
                        .whenComplete((result, throwable) -> {
                            if (throwable != null) {
                                LOGGER.error("Exception during team data refresh for player {}", playerUuid, throwable);
                            }
                            pendingRequests.remove(playerUuid);
                        });
            });
>>>>>>> Stashed changes
        }
    }
}