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
        return requestIslandStart(playerUuid, playerName, null);
    }

    /**
     * Same as {@link #requestIslandStart(UUID, String)}, on behalf of an admin
     * (/islandadmin start|join): with adminName the API lets the start through
     * even while the island is in admin mode (HTTP 423 for everyone else).
     */
    public CompletableFuture<ApiResponse> requestIslandStart(UUID playerUuid, String playerName, String adminName) {
        String path = "/islands/start/" + playerUuid.toString() + "?player_name="
                + URLEncoder.encode(playerName, StandardCharsets.UTF_8)
                + (adminName != null ? "&admin_name=" + URLEncoder.encode(adminName, StandardCharsets.UTF_8) : "");
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
     * Gets the island's incident journal (crashes, hangs, auto-freeze/stop,
     * state mismatches — written by the API's health watchdog and idle
     * reaper). Used by /islandadmin so staff can see what happened without
     * opening the database.
     *
     * @param ownerUuid The UUID of the island owner (team owner or solo player).
     * @param limit Max number of events, newest first.
     * @return A CompletableFuture that completes with the API response (a JSON array).
     */
    public CompletableFuture<ApiResponse> getIslandEvents(UUID ownerUuid, int limit) {
        String path = "/islands/" + ownerUuid.toString() + "/events?limit=" + limit;
        HttpRequest request = baseRequest(apiUrlBase + path).GET().build();

        logger.debug("Requesting island events for {}: GET {}", ownerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.error("API request failed for getIslandEvents for {}: {}", ownerUuid, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Lists all islands (the full roster) for /islandadmin list.
     *
     * @param statusFilter Optional status to narrow the list to (e.g. "RUNNING"), or null for all.
     * @return A CompletableFuture that completes with the API response (a JSON array).
     */
    public CompletableFuture<ApiResponse> listIslands(String statusFilter) {
        String path = "/islands/admin/list" + (statusFilter != null
                ? "?status_filter=" + URLEncoder.encode(statusFilter, StandardCharsets.UTF_8) : "");
        HttpRequest request = baseRequest(apiUrlBase + path).GET().build();

        logger.debug("Requesting island list: GET {}", request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.error("API request failed for listIslands: {}", ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Live DB-vs-LXD consistency check for /islandadmin status.
     *
     * @param ownerUuid The UUID of the island owner (team owner or solo player).
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> getIslandDiagnostics(UUID ownerUuid) {
        String path = "/islands/" + ownerUuid.toString() + "/admin/diagnostics";
        HttpRequest request = baseRequest(apiUrlBase + path).GET().build();

        logger.debug("Requesting island diagnostics for {}: GET {}", ownerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.error("API request failed for getIslandDiagnostics for {}: {}", ownerUuid, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Transfers island ownership to an existing team member (/islandadmin set owner).
     *
     * @param ownerUuid    The current owner's UUID (path — identifies the island).
     * @param newOwnerUuid The UUID of the member to promote to owner.
     * @param adminName    Name of the admin performing the change, for the audit trail.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> setIslandOwner(UUID ownerUuid, UUID newOwnerUuid, String adminName) {
        String path = "/islands/" + ownerUuid.toString() + "/admin/owner";
        String jsonPayload = gson.toJson(Map.of("new_owner_uuid", newOwnerUuid.toString(), "admin_name", adminName));
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build();

        logger.info("Requesting owner change for island {} -> {}: POST {}", ownerUuid, newOwnerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.error("API request failed for setIslandOwner for {}: {}", ownerUuid, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Puts an island into admin mode (/islandadmin hold): the API stops
     * touching it on its own (watchdog, idle stop, updates, player starts).
     *
     * @param ownerUuid The UUID of the island owner.
     * @param adminName Who is working on the island.
     * @param minutes   How long the hold lasts (1..1440); it expires by itself.
     * @param reason    Optional note, may be null.
     */
    public CompletableFuture<ApiResponse> setAdminHold(UUID ownerUuid, String adminName, int minutes, String reason) {
        String path = "/islands/" + ownerUuid.toString() + "/admin/hold";
        Map<String, Object> body = new java.util.HashMap<>(Map.of("admin_name", adminName, "minutes", minutes));
        if (reason != null && !reason.isBlank()) body.put("reason", reason);
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
        logger.info("Requesting admin hold for island {} by {} ({} min)", ownerUuid, adminName, minutes);
        return send(request, "setAdminHold");
    }

    /** Takes an island out of admin mode (/islandadmin release). */
    public CompletableFuture<ApiResponse> releaseAdminHold(UUID ownerUuid, String adminName) {
        String path = "/islands/" + ownerUuid.toString() + "/admin/hold?admin_name="
                + URLEncoder.encode(adminName, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest(apiUrlBase + path).DELETE().build();
        logger.info("Releasing admin hold for island {} by {}", ownerUuid, adminName);
        return send(request, "releaseAdminHold");
    }

    /** Islands currently in admin mode (/islandadmin holds). */
    public CompletableFuture<ApiResponse> listAdminHolds() {
        return send(baseRequest(apiUrlBase + "/islands/admin/holds").GET().build(), "listAdminHolds");
    }

    private CompletableFuture<ApiResponse> send(HttpRequest request, String op) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.error("API request failed for {}: {}", op, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /** FastAPI error text ({"detail": "..."}) or the raw body. */
    public static String errorDetail(ApiResponse res) {
        try {
            com.google.gson.JsonElement detail = com.google.gson.JsonParser.parseString(res.body())
                    .getAsJsonObject().get("detail");
            if (detail != null && detail.isJsonPrimitive()) return detail.getAsString();
        } catch (Exception ignored) {
        }
        return res.body();
    }

    /**
     * Restarts a running island's container (/islandadmin restart).
     *
     * @param ownerUuid The UUID of the island owner.
     * @param adminName Name of the admin performing the restart, for the audit trail.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> restartIsland(UUID ownerUuid, String adminName) {
        String path = "/islands/" + ownerUuid.toString() + "/admin/restart?admin_name="
                + URLEncoder.encode(adminName, StandardCharsets.UTF_8);
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.noBody()).build();

        logger.info("Requesting island restart for {}: POST {}", ownerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.error("API request failed for restartIsland for {}: {}", ownerUuid, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Adds a player to a team without a pending invite (/islandadmin member add).
     *
     * @param teamId     The team to add the player to.
     * @param playerUuid The UUID of the player to add.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> addTeamMember(int teamId, UUID playerUuid) {
        String path = "/teams/" + teamId + "/members?player_uuid=" + playerUuid;
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.noBody()).build();

        logger.info("Requesting member add for team {}: POST {}", teamId, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.error("API request failed for addTeamMember for team {}: {}", teamId, ex.getMessage(), ex);
                    return new ApiResponse(ex.getMessage());
                });
    }

    /**
     * Removes a player from a team (/islandadmin member remove).
     *
     * @param teamId     The team to remove the player from.
     * @param playerUuid The UUID of the player to remove.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> removeTeamMember(int teamId, UUID playerUuid) {
        String path = "/teams/" + teamId + "/members/" + playerUuid;
        HttpRequest request = baseRequest(apiUrlBase + path).DELETE().build();

        logger.info("Requesting member remove for team {}: DELETE {}", teamId, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.error("API request failed for removeTeamMember for team {}: {}", teamId, ex.getMessage(), ex);
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
        return requestIslandStop(playerUuid, null);
    }

    /** Stop on behalf of an admin (/islandadmin stop) — passes an admin-mode hold. */
    public CompletableFuture<ApiResponse> requestIslandStop(UUID playerUuid, String adminName) {
        String path = "/islands/stop/" + playerUuid.toString()
                + (adminName != null ? "?admin_name=" + URLEncoder.encode(adminName, StandardCharsets.UTF_8) : "");
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
     * Повідомляє API, що silent Server List Ping не вдався перед підключенням
     * гравця, хоча острів звітував RUNNING/minecraft_ready — сигнал
     * потенційно мертвого resumed-процесу (lifecycle_stability_plan.md §1).
     *
     * @param ownerUuid The UUID of the island owner.
     * @return A CompletableFuture that completes with the API response.
     */
    public CompletableFuture<ApiResponse> notifyConnectionFailed(UUID ownerUuid) {
        String path = "/islands/" + ownerUuid.toString() + "/connection_failed";
        HttpRequest request = baseRequest(apiUrlBase + path).POST(HttpRequest.BodyPublishers.noBody()).build();

        logger.warn("Notifying API that connection ping failed for island of {}: POST {}", ownerUuid, request.uri());

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(httpResponse -> new ApiResponse(httpResponse.statusCode(), httpResponse.body()))
                .exceptionally(ex -> {
                    logger.warn("API request failed for notifyConnectionFailed for {}: {}", ownerUuid, ex.getMessage());
                    return new ApiResponse(ex.getMessage());
                });
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
}
