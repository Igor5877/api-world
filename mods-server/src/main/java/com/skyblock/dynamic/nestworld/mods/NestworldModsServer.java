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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Provides server-side functionality for Nestworld mods.
 */
public class NestworldModsServer {

    /** The island provider. */
    public static final IslandProvider ISLAND_PROVIDER = new IslandProvider();
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Provides island-related functionality.
     */
    public static class IslandProvider {
        /**
         * How long a cached player→team-owner mapping is trusted before we
         * re-fetch it. Without a TTL, a player's team was cached forever
         * (until this JVM restarts) — so once cached, joining/leaving a team
         * later never took effect for this island's session: FTB Quests kept
         * treating them as their old team indefinitely.
         */
        private static final long TEAM_CACHE_TTL_MS = 60_000L;
        private final Map<UUID, UUID> islandCache = new ConcurrentHashMap<>();
        private final Map<UUID, Long> islandCacheTimestamps = new ConcurrentHashMap<>();
        /** Players with a refresh already in flight — avoids piling up
         *  duplicate background HTTP calls if getCachedTeamId is hit
         *  repeatedly (e.g. quest checks) while the cache is stale. */
        private final Set<UUID> refreshInFlight = ConcurrentHashMap.newKeySet();
        private final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        private final Gson gson = new Gson();

        private HttpRequest.Builder apiRequest(String url) {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                    .header("Content-Type", "application/json");
            String key = Config.getApiKey();
            if (!key.isBlank()) b.header("X-Api-Key", key);
            return b;
        }

        /**
         * Gets the cached team ID for a player.
         *
         * @param playerUuid The UUID of the player.
         * @return The team ID, or null if not found.
         */
        public UUID getCachedTeamId(UUID playerUuid) {
            // If the team ID is cached and still fresh, return it (FRESH).
            Long cachedAt = islandCacheTimestamps.get(playerUuid);
            UUID cached = islandCache.get(playerUuid);
            if (cachedAt != null && (System.currentTimeMillis() - cachedAt) < TEAM_CACHE_TTL_MS) {
                return cached;
            }
            // STALE or UNKNOWN — never block the caller (often the Minecraft
            // server thread, e.g. quest permission checks) on HTTP here.
            // Refresh happens in the background; this call returns whatever
            // is cached right now (possibly null on first-ever lookup).
            triggerAsyncRefresh(playerUuid);
            return cached;
        }

        /**
         * Kicks off a background refresh for a player's team, if one isn't
         * already in flight. Never blocks the calling thread.
         *
         * @param playerUuid The UUID of the player.
         */
        private void triggerAsyncRefresh(UUID playerUuid) {
            if (!refreshInFlight.add(playerUuid)) return; // already refreshing
            CompletableFuture.runAsync(() -> {
                try {
                    refreshAndGetTeamId(playerUuid);
                } finally {
                    refreshInFlight.remove(playerUuid);
                }
            });
        }

        /**
         * Sends a "ready" signal to the API for the specified island owner.
         * Retries on transient failure — this is a one-shot signal with no
         * other recovery path (mods-server spec §11).
         *
         * @param ownerUuid The UUID of the island owner.
         * @return A CompletableFuture that completes when the signal has been sent.
         */
        public CompletableFuture<Void> sendReady(UUID ownerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "islands/" + ownerUuid.toString() + "/ready";
            return postWithRetry(apiUrl, "ready signal for " + ownerUuid, 5, 2_000L);
        }

        /**
         * Sends a "freeze" signal to the API for the specified island owner.
         * Retries on transient failure — with idle_reaper no longer
         * auto-freezing (lifecycle_stability_plan.md §3), this is the only
         * path that ever freezes an island, with no backstop if it fails.
         *
         * @param ownerUuid The UUID of the island owner.
         * @return A CompletableFuture that completes when the signal has been sent.
         */
        public CompletableFuture<Void> sendFreeze(UUID ownerUuid) {
            String apiUrl = Config.getApiBaseUrl() + "islands/" + ownerUuid.toString() + "/freeze";
            return postWithRetry(apiUrl, "freeze signal for " + ownerUuid, 5, 2_000L);
        }

        /**
         * POSTs with no body, retrying transient failures (timeout,
         * connection error, 5xx) with capped exponential backoff. Never
         * retries 4xx — the same request body would just fail the same way
         * again (mods-server spec §13-14).
         *
         * @param url The full request URL.
         * @param label Human-readable label for log messages.
         * @param maxAttempts Maximum number of attempts before giving up.
         * @param initialBackoffMs Delay before the first retry; doubles each attempt, capped at 30s.
         * @return A CompletableFuture that completes when the request succeeds, or fails after the final attempt.
         */
        private CompletableFuture<Void> postWithRetry(String url, String label, int maxAttempts, long initialBackoffMs) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            attemptPost(url, label, 1, maxAttempts, initialBackoffMs, result);
            return result;
        }

        private void attemptPost(String url, String label, int attempt, int maxAttempts, long backoffMs, CompletableFuture<Void> result) {
            HttpRequest request = apiRequest(url).POST(HttpRequest.BodyPublishers.noBody()).build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((response, ex) -> {
                        boolean success = ex == null && response.statusCode() < 300;
                        if (success) {
                            result.complete(null);
                            return;
                        }
                        boolean retryable = ex != null || (response != null && response.statusCode() >= 500);
                        if (!retryable || attempt >= maxAttempts) {
                            LOGGER.error("{} failed{}: {}", label, attempt > 1 ? " after " + attempt + " attempts" : "",
                                    ex != null ? ex.getMessage() : "HTTP " + response.statusCode());
                            result.completeExceptionally(ex != null ? ex : new java.io.IOException("HTTP " + response.statusCode()));
                            return;
                        }
                        LOGGER.warn("{} attempt {} failed ({}), retrying in {}ms", label, attempt,
                                ex != null ? ex.getMessage() : "HTTP " + response.statusCode(), backoffMs);
                        CompletableFuture.delayedExecutor(backoffMs, TimeUnit.MILLISECONDS)
                                .execute(() -> attemptPost(url, label, attempt + 1, maxAttempts, Math.min(backoffMs * 2, 30_000L), result));
                    });
        }

        /**
         * Refreshes the team ID for a player and returns it.
         *
         * @param playerUuid The UUID of the player.
         * @return The team ID, or null if not found.
         */
        public UUID refreshAndGetTeamId(UUID playerUuid) {
            LOGGER.info("Attempting to refresh team data for player {}...", playerUuid);
            
            Path serverBasePath = java.nio.file.Paths.get("").toAbsolutePath();
            try {
                java.io.File serverDir = ServerLifecycleHooks.getCurrentServer().getFile("");
                if (serverDir != null) {
                    serverBasePath = serverDir.toPath();
                }
            } catch (NoSuchMethodError | Exception e) {
                LOGGER.warn("Could not get server directory via getFile(\"\"). Using current working directory instead: " + serverBasePath);
            }
            
            Path cachePath = serverBasePath.resolve("world").resolve("serverconfig").resolve("cached_team_data.json");

            try {
                String apiUrl = Config.getApiBaseUrl() + "teams/my_team/";
                HttpRequest request = apiRequest(apiUrl + playerUuid.toString()).GET().build();
                
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    LOGGER.info("Successfully fetched team data from API for player {}.", playerUuid);
                    JsonObject teamJson = gson.fromJson(response.body(), JsonObject.class);
                    
                    // Write to a .tmp file and atomically rename over the real
                    // one — a crash mid-write must never leave a partially
                    // written cache file behind (mods-server spec §33).
                    Path tmpPath = cachePath.resolveSibling(cachePath.getFileName() + ".tmp");
                    try (FileWriter writer = new FileWriter(tmpPath.toFile())) {
                        gson.toJson(teamJson, writer);
                        Files.move(tmpPath, cachePath,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
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

        /**
         * Loads team data from the cache.
         *
         * @param cachePath The path to the cache file.
         * @return The team ID, or null if not found.
         */
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

        /**
         * Processes team data from a JSON object.
         *
         * @param teamJson The JSON object containing the team data.
         * @return The team ID, or null if the data is invalid.
         */
        public UUID processTeamData(JsonObject teamJson) {
            if (teamJson != null && teamJson.has("owner_uuid")) {
                UUID ownerUuid = UUID.fromString(teamJson.get("owner_uuid").getAsString());
                Set<UUID> memberUuidSet = new LinkedHashSet<>();
                memberUuidSet.add(ownerUuid);

                if (teamJson.has("members") && teamJson.get("members").isJsonArray()) {
                    teamJson.get("members").getAsJsonArray().forEach(memberElement -> {
                        JsonObject memberObj = memberElement.getAsJsonObject();
                        if (memberObj.has("player_uuid")) {
                            UUID memberUuid = UUID.fromString(memberObj.get("player_uuid").getAsString());
                            memberUuidSet.add(memberUuid);
                        }
                    });
                }
                List<UUID> memberUuids = new ArrayList<>(memberUuidSet);

                long now = System.currentTimeMillis();
                for (UUID memberUuid : memberUuids) {
                    islandCache.put(memberUuid, ownerUuid);
                    islandCacheTimestamps.put(memberUuid, now);
                }

                com.skyblock.dynamic.utils.QuestTeamBridge.getInstance().syncTeamData(ownerUuid, memberUuids);

                int teamId = teamJson.has("id") ? teamJson.get("id").getAsInt() : -1;
                String teamName = teamJson.has("name") ? teamJson.get("name").getAsString() : null;
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new com.skyblock.dynamic.events.TeamDataUpdatedEvent(teamId, teamName, ownerUuid, memberUuids));

                LOGGER.info("Successfully processed and synced team data for owner {}", ownerUuid);
                return ownerUuid;
            }
            LOGGER.warn("processTeamData called with invalid or incomplete team JSON.");
            return null;
        }

        /**
         * Checks if the current server is an island server.
         *
         * @return True if the server is an island server, false otherwise.
         */
        public boolean isThisAnIslandServer() {
            return com.skyblock.dynamic.SkyBlockMod.isIslandServer();
        }

        /**
         * Gets the UUID of the current server's owner.
         *
         * @return The UUID of the current server's owner.
         */
        public String getCurrentServerOwnerUuid() {
            return com.skyblock.dynamic.SkyBlockMod.getOwnerUuid();
        }
    }
}
