package com.skyblockdynamic.nestworld.velocity.network;

import com.google.gson.Gson;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final Duration requestTimeout;
    private final Gson gson = new Gson();

    /**
     * Constructs a new ApiClient.
     *
     * @param logger The logger.
     * @param config The plugin configuration.
     */
    public ApiClient(Logger logger, PluginConfig config) {
        this.logger = logger;
        this.apiUrlBase = config.getApiUrl();
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .GET()
                .timeout(this.requestTimeout)
                .build();

        logger.debug("Requesting island details for {}: GET {}", playerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> {
                    logger.debug("API Response for getIslandDetails for {}: Status Code {}, Body: {}",
                            playerUuid, httpResponse.statusCode(), httpResponse.body().substring(0, Math.min(httpResponse.body().length(), 500)));
                    return new ApiResponse(httpResponse.statusCode(), httpResponse.body());
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
        String path = "/islands/start/" + playerUuid.toString() + "?player_name=" + playerName;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(this.requestTimeout)
                .build();

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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(this.requestTimeout)
                .build();

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
     * Creates a solo island for a player.
     *
     * @param playerUuid The UUID of the player.
     * @param playerName The name of the player.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> createSoloIsland(UUID playerUuid, String playerName) {
        String path = "/teams/create_solo";
        String jsonPayload = gson.toJson(Map.of("player_uuid", playerUuid.toString(), "player_name", playerName));
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(this.requestTimeout)
                .build();

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
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(this.requestTimeout)
                .build();

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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(this.requestTimeout)
                .build();
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(this.requestTimeout)
                .build();
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .GET()
                .timeout(this.requestTimeout)
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
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
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(this.requestTimeout)
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }
}
