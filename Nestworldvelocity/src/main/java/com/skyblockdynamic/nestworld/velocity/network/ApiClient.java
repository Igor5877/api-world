package com.skyblockdynamic.nestworld.velocity.network;

import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.skyblockdynamic.nestworld.velocity.network.ApiResponse;

// This record can be shared or defined commonly if mod and plugin share a library
// For now, defined separately.
// public record ApiResponse(int statusCode, String body, String errorMessage, boolean isSuccess) {

//     public ApiResponse(int statusCode, String body) {
//         this(statusCode, body, null, statusCode >= 200 && statusCode < 300);
//     }
//     public ApiResponse(String errorMessage) {
//         this(0, null, errorMessage, false); // 0 for client-side/network errors
//     }
// }

public class ApiClient {

    private final HttpClient httpClient;
    private final Logger logger;
    private final String apiUrlBase;
    private final Duration requestTimeout;

    public ApiClient(Logger logger, PluginConfig config) {
        this.logger = logger;
        this.apiUrlBase = config.getApiUrl();
        this.requestTimeout = Duration.ofSeconds(config.getApiRequestTimeoutSeconds());
        
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)) // General connect timeout
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

    public CompletableFuture<ApiResponse> requestIslandStart(UUID playerUuid) {
        String path = "/islands/" + playerUuid.toString() + "/start";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrlBase + path))
                .header("Content-Type", "application/json") // Content-Type might not be strictly needed for an empty POST
                .POST(HttpRequest.BodyPublishers.noBody()) // Empty body for start request
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
        String path = "/islands/" + playerUuid.toString() + "/stop";
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
}
