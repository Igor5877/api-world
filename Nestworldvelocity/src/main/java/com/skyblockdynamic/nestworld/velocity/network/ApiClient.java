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

public class ApiClient {

    private final HttpClient httpClient;
    private final Logger logger;
    private final String apiUrlBase;
    private final Duration requestTimeout;
    private final Gson gson = new Gson();

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

    public CompletableFuture<ApiResponse> requestIslandStart(UUID playerUuid, String playerName) {
        String path = "/islands/start/" + playerUuid.toString();
        String jsonPayload = gson.toJson(Map.of("player_name", playerName));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
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

    public CompletableFuture<ApiResponse> createTeam(String teamName, UUID ownerUuid, String ownerName) {
        String path = "/teams/create_solo";
        String jsonPayload = gson.toJson(Map.of("player_uuid", ownerUuid.toString(), "player_name", ownerName));
        
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

    public CompletableFuture<ApiResponse> acceptInvite(String teamName, UUID playerUuid) {
        String path = "/teams/accept_invite";
        String jsonPayload = gson.toJson(Map.of("team_name", teamName));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .header("X-Player-UUID", playerUuid.toString())
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(this.requestTimeout)
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }

    public CompletableFuture<ApiResponse> leaveTeam(int teamId, UUID playerUuid) {
        String path = "/teams/" + teamId + "/leave";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .header("X-Player-UUID", playerUuid.toString())
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(this.requestTimeout)
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }

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
    
    public CompletableFuture<ApiResponse> renameTeam(int teamId, String newName, UUID playerUuid) {
        String path = "/teams/" + teamId + "/rename";
        String jsonPayload = gson.toJson(Map.of("name", newName));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json")
                .header("X-Player-UUID", playerUuid.toString())
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonPayload))
                .timeout(this.requestTimeout)
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> new ApiResponse(ex.getMessage()));
    }
}
