package com.skyblock.dynamic.nestworld.mods;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.skyblock.dynamic.Config;
import com.mojang.logging.LogUtils;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraftforge.server.ServerLifecycleHooks;
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

    public static final IslandProvider ISLAND_PROVIDER = new IslandProvider();
    private static final Logger LOGGER = LogUtils.getLogger();

    public static class IslandProvider {
        private final Map<UUID, UUID> islandCache = new ConcurrentHashMap<>();
        private final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final Gson gson = new Gson();

        public UUID getCachedTeamId(UUID playerUuid) {
            // If the team ID is in the cache, return it.
            if (islandCache.containsKey(playerUuid)) {
                return islandCache.get(playerUuid);
            }
            // If not, fetch it synchronously. This will block, but it's necessary for FTB Quests.
            return refreshAndGetTeamId(playerUuid);
        }

        public CompletableFuture<Void> sendReady(UUID ownerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "islands/" + ownerUuid.toString() + "/ready";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                            LOGGER.warn("Non-successful response {} for ready signal for owner {}", response.statusCode(), ownerUuid);
                        }
                    });
        }

        public CompletableFuture<Void> sendFreeze(UUID ownerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "islands/" + ownerUuid.toString() + "/freeze";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                             LOGGER.warn("Non-successful response {} for freeze signal for owner {}", response.statusCode(), ownerUuid);
                        }
                    });
        }

        public UUID refreshAndGetTeamId(UUID playerUuid) {
            LOGGER.info("Fetching team data for player {} synchronously...", playerUuid);
            String apiUrl = Config.getApiBaseUrl() + "teams/my_team/";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + playerUuid.toString()))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject teamJson = gson.fromJson(response.body(), JsonObject.class);
                    if (teamJson.has("owner_uuid")) {
                        UUID ownerUuid = UUID.fromString(teamJson.get("owner_uuid").getAsString());
                        List<UUID> memberUuids = new ArrayList<>();
                        memberUuids.add(ownerUuid);

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

                        for (UUID memberUuid : memberUuids) {
                            islandCache.put(memberUuid, ownerUuid);
                        }
                        
                        // It's safe to call this directly now because this whole method is on the main thread.
                        com.skyblock.dynamic.utils.QuestTeamBridge.getInstance().syncTeamData(ownerUuid, memberUuids);
                        LOGGER.info("Successfully refreshed and synced team data for owner {}", ownerUuid);
                        return ownerUuid;
                    }
                } else {
                    LOGGER.warn("Failed to fetch team data for player {}: HTTP {}", playerUuid, response.statusCode());
                }
            } catch (Exception e) {
                LOGGER.error("Exception during synchronous team data refresh for player {}", playerUuid, e);
            }

            return null;
        }

        public boolean isThisAnIslandServer() {
            return com.skyblock.dynamic.SkyBlockMod.isIslandServer();
        }

        public String getCurrentServerOwnerUuid() {
            return com.skyblock.dynamic.SkyBlockMod.getOwnerUuid();
        }
    }
}