package com.skyblock.dynamic.nestworld.mods;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.skyblock.dynamic.Config;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.utils.QuestTeamBridge;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
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
            LOGGER.info("Attempting to refresh team data for player {}...", playerUuid);
            Path cachePath = ServerLifecycleHooks.getCurrentServer().getServerDirectory().toPath().resolve("world").resolve("serverconfig").resolve("cached_team_data.json");

            try {
                String apiUrl = Config.getApiBaseUrl() + "teams/my_team/";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl + playerUuid.toString()))
                        .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .GET()
                        .build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    LOGGER.info("Successfully fetched team data from API for player {}.", playerUuid);
                    JsonObject teamJson = gson.fromJson(response.body(), JsonObject.class);
                    
                    try (FileWriter writer = new FileWriter(cachePath.toFile())) {
                        gson.toJson(teamJson, writer);
                        LOGGER.info("Successfully cached team data to {}.", cachePath);
                    } catch (IOException e) {
                        LOGGER.error("Failed to write team data to cache file: {}", cachePath, e);
                    }
                    
                    return processTeamData(teamJson);
                } else {
                    LOGGER.warn("Failed to fetch team data for player {} from API, HTTP {}. Attempting to use cache.", playerUuid, response.statusCode());
                    return loadTeamDataFromCache(cachePath);
                }
            } catch (Exception e) {
                LOGGER.error("Exception fetching team data from API for player {}. Attempting to use cache.", playerUuid, e);
                return loadTeamDataFromCache(cachePath);
            }
        }

        private UUID loadTeamDataFromCache(Path cachePath) {
            if (Files.exists(cachePath)) {
                try (FileReader reader = new FileReader(cachePath.toFile())) {
                    JsonObject teamJson = gson.fromJson(reader, JsonObject.class);
                    LOGGER.info("Successfully loaded team data from cache file: {}", cachePath);
                    return processTeamData(teamJson);
                } catch (IOException | JsonSyntaxException e) {
                    LOGGER.error("Failed to read or parse cached team data from {}.", cachePath, e);
                }
            } else {
                LOGGER.warn("Team data cache file not found at {}. Unable to proceed.", cachePath);
            }
            return null;
        }

        public UUID processTeamData(JsonObject teamJson) {
            if (teamJson != null && teamJson.has("owner_uuid")) {
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
                
                com.skyblock.dynamic.utils.QuestTeamBridge.getInstance().syncTeamData(ownerUuid, memberUuids);
                LOGGER.info("Successfully processed and synced team data for owner {}", ownerUuid);
                return ownerUuid;
            }
            LOGGER.warn("processTeamData called with invalid or incomplete team JSON.");
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