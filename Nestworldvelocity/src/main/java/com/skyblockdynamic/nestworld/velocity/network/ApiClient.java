package com.skyblockdynamic.nestworld.velocity.network;

import com.google.gson.Gson;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A client for interacting with the SkyBlock API.
 */
public class ApiClient {

    private final HttpClient httpClient;
    private final Logger logger;
    private final String apiUrlBase;
    private final String apiKey;
    private final Duration requestTimeout;
    private final Gson gson = new Gson();

    /**
     * Constructs a new ApiClient.
     *
     * @param logger The logger.
     * @param config The plugin configuration.
     */
    public String getApiUrlBase() { return apiUrlBase; }

    private HttpRequest.Builder baseRequest(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(this.requestTimeout);
        if (apiKey != null && !apiKey.isBlank()) {
            b.header("X-Api-Key", apiKey);
        }
        return b;
    }

    public ApiClient(Logger logger, PluginConfig config) {
        this.logger = logger;
        this.apiUrlBase = config.getApiUrl();
        this.apiKey = config.getApiKey();
        this.requestTimeout = Duration.ofSeconds(config.getApiRequestTimeoutSeconds());

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        if (this.apiUrlBase == null || this.apiUrlBase.isBlank()) {
            logger.error("API URL is not configured! API calls will likely fail.");
        }
    }

    /**
     * Gets the details of a player's island.
     *
     * @param playerUuid The UUID of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> getIslandDetails(UUID playerUuid) {
        String path = "/islands/" + playerUuid.toString();
        HttpRequest request = baseRequest(apiUrlBase + path).GET().build();

        logger.debug("Requesting island details for {}: GET {}", playerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> {
                    String body = httpResponse.body() != null ? httpResponse.body() : "";
                    logger.debug("API Response for getIslandDetails for {}: Status Code {}, Body: {}",
                            playerUuid, httpResponse.statusCode(), body.substring(0, Math.min(body.length(), 500)));
                    return new ApiResponse(httpResponse.statusCode(), body);
                })
                .exceptionally(ex -> {
                    logger.error("API request failed for getIslandDetails for {}: {}", playerUuid, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Requests to start a player's island.
     *
     * @param playerUuid The UUID of the player.
     * @param playerName The name of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> requestIslandStart(UUID playerUuid, String playerName) {
        String path = "/islands/start/" + playerUuid.toString() + "?player_name="
                + URLEncoder.encode(playerName, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.noBody()).build();

        logger.info("Requesting island start for {}: POST {}", playerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> {
                    logger.info("API Response for requestIslandStart for {}: Status Code {}", playerUuid, httpResponse.statusCode());
                    logger.debug("API Response Body: {}", httpResponse.body());
                    return new ApiResponse(httpResponse.statusCode(), httpResponse.body());
                })
                .exceptionally(ex -> {
                    logger.error("API request failed for requestIslandStart for {}: {}", playerUuid, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Requests to stop a player's island.
     *
     * @param playerUuid The UUID of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> requestIslandStop(UUID playerUuid) {
        String path = "/islands/stop/" + playerUuid.toString();
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.noBody()).build();

        logger.info("Requesting island stop for {}: POST {}", playerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> {
                    logger.info("API Response for requestIslandStop for {}: Status Code {}", playerUuid, httpResponse.statusCode());
                    logger.debug("API Response Body: {}", httpResponse.body().substring(0, Math.min(httpResponse.body().length(), 500)));
                    return new ApiResponse(httpResponse.statusCode(), httpResponse.body());
                })
                .exceptionally(ex -> {
                    logger.error("API request failed for requestIslandStop for {}: {}", playerUuid, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Повідомляє API, що останній гравець покинув острів.
     * Тригер для update worker: якщо острів чекає оновлення (WAITING),
     * воно буде застосоване після зупинки сервера.
     *
     * @param ownerUuid The UUID of the island owner.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> notifyPlayerLeft(UUID ownerUuid) {
        String path = "/islands/" + ownerUuid.toString() + "/player_left";
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.noBody()).build();

        logger.info("Notifying API that the last player left island of {}: POST {}", ownerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> {
                    logger.debug("API Response for notifyPlayerLeft for {}: Status Code {}", ownerUuid, httpResponse.statusCode());
                    return new ApiResponse(httpResponse.statusCode(), httpResponse.body());
                })
                .exceptionally(ex -> {
                    logger.warn("API request failed for notifyPlayerLeft for {}: {}", ownerUuid, ex.getMessage());
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Creates a solo island for a player.
     *
     * @param playerUuid The UUID of the player.
     * @param playerName The name of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> createSoloIsland(UUID playerUuid, String playerName) {
        String path = "/teams/create_solo";
        String jsonPayload = gson.toJson(Map.of("player_uuid", playerUuid.toString(), "player_name", playerName));
        
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }

    /**
     * Creates a team.
     *
     * @param teamName  The name of the team.
     * @param ownerUuid The UUID of the team owner.
     * @param ownerName The name of the team owner.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> createTeam(String teamName, UUID ownerUuid, String ownerName) {
        String path = "/teams/create_solo";
        // The API endpoint expects the player_info to be a nested dictionary.
        String jsonPayload = gson.toJson(Map.of("player_info", Map.of("player_uuid", ownerUuid.toString(), "player_name", ownerName)));
        
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }

    /**
     * Accepts a team invitation.
     *
     * @param teamName   The name of the team.
     * @param playerUuid The UUID of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> acceptInvite(String teamName, UUID playerUuid) {
        String path = "/teams/accept_invite?player_uuid=" + playerUuid.toString();
        String jsonPayload = gson.toJson(Map.of("team_name", teamName));
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }

    /**
     * Leaves a team.
     *
     * @param teamId     The ID of the team.
     * @param playerUuid The UUID of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> leaveTeam(int teamId, UUID playerUuid) {
        String path = "/teams/" + teamId + "/leave?player_uuid=" + playerUuid.toString();
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.noBody()).build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }

    /**
     * Gets a player's team.
     *
     * @param playerUuid The UUID of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> getTeam(UUID playerUuid) {
        String path = "/teams/my_team/" + playerUuid.toString();
        HttpRequest request = baseRequest(apiUrlBase + path).GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }
    
    // ── Warp platform commands ───────────────────────────────────────────────

    /**
     * Надсилає команду створення варп-платформи для гравця.
     */
    public CompletableFuture<ApiResponse> warpCreate(UUID playerUuid) {
        return postWarpCommand("create", playerUuid);
    }

    /**
     * Надсилає команду призупинення варп-платформи (підписка закінчилась).
     */
    public CompletableFuture<ApiResponse> warpSuspend(UUID playerUuid) {
        return postWarpCommand("suspend", playerUuid);
    }

    /**
     * Надсилає команду відновлення варп-платформи (підписка поновлена).
     */
    public CompletableFuture<ApiResponse> warpRestore(UUID playerUuid) {
        return postWarpCommand("restore", playerUuid);
    }

    /**
     * Надсилає команду остаточного видалення збережених даних платформи.
     */
    public CompletableFuture<ApiResponse> warpDelete(UUID playerUuid) {
        return postWarpCommand("delete", playerUuid);
    }

    private CompletableFuture<ApiResponse> postWarpCommand(String action, UUID playerUuid) {
        String path = "/warps/" + playerUuid.toString() + "/" + action;
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.noBody()).build();

        logger.info("[WarpAdmin] POST {} for {}", path, playerUuid);

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> {
                    logger.info("[WarpAdmin] {} → status {}", action, httpResponse.statusCode());
                    return new ApiResponse(httpResponse.statusCode(), httpResponse.body());
                })
                .exceptionally(ex -> {
                    logger.error("[WarpAdmin] {} request failed: {}", action, ex.getMessage());
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Renames a team.
     *
     * @param teamId     The ID of the team.
     * @param newName    The new name for the team.
     * @param playerUuid The UUID of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> renameTeam(int teamId, String newName, UUID playerUuid) {
        String path = "/teams/" + teamId + "/rename?player_uuid=" + playerUuid.toString();
        String jsonPayload = gson.toJson(Map.of("name", newName));
        HttpRequest request = baseRequest(apiUrlBase + path).method("PATCH", HttpRequest.BodyPublishers.ofString(jsonPayload)).build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }
}
