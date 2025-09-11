package com.skyblock.dynamic.nestworld.mods;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.skyblock.dynamic.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

public class NestworldModsServer {

    //public static final TeamProvider TEAM_PROVIDER = new TeamProvider();
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

                                    // Update the IslandData with the new member list
                                    ServerQuestFile file = ServerQuestFile.INSTANCE;
                                    if (file != null) {
                                        IslandData islandData = file.getOrCreateIslandData(ownerUuid);
                                        islandData.setMembers(memberUuids);
                                    }

                                    return ownerUuid;
                                }
                            } catch (JsonSyntaxException | IllegalStateException e) {
                                // Log error
                            }
                        }
                        return null;
                    });
        }
    }
}
