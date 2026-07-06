package com.skyblock.dynamic.teams.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.Config;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the Nestworld API team endpoints.
 * Base URL and X-Api-Key are taken from the nestworld-mods-server config
 * (skyblock-common.toml) so there is a single place to configure them.
 *
 * Endpoints marked [FUTURE] are specified in api/API_TEAMS_TODO.md and return
 * 404 until the API implements them — callers surface that as a friendly
 * "not available yet" message instead of crashing.
 */
public class TeamApiClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record ApiResult(int status, String body) {
        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        public boolean isNotImplementedYet() {
            return status == 404 || status == 405;
        }

        public JsonObject json() {
            try {
                return GSON.fromJson(body, JsonObject.class);
            } catch (Exception e) {
                return new JsonObject();
            }
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(Config.getApiBaseUrl() + path))
                .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                .header("Content-Type", "application/json");
        String key = Config.getApiKey();
        if (!key.isBlank()) {
            b.header("X-Api-Key", key);
        }
        return b;
    }

    private CompletableFuture<ApiResult> send(HttpRequest req) {
        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(r -> new ApiResult(r.statusCode(), r.body()))
                .exceptionally(ex -> {
                    LOGGER.error("API request {} failed", req.uri(), ex);
                    return new ApiResult(-1, "");
                });
    }

    // ---- Existing endpoints ----

    public CompletableFuture<ApiResult> getMyTeam(UUID playerUuid) {
        return send(request("teams/my_team/" + playerUuid).GET().build());
    }

    public CompletableFuture<ApiResult> leaveTeam(int teamId, UUID playerUuid) {
        return send(request("teams/" + teamId + "/leave?player_uuid=" + playerUuid)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    /**
     * Вступ у команду без API-запрошення: викликається, коли гравець прийняв
     * запрошення в GUI FTB Teams (згода вже відбулась там). Старий соло-острів
     * гравця видаляється — так само, як при прийнятті API-запрошення.
     */
    public CompletableFuture<ApiResult> forceJoin(int teamId, UUID playerUuid) {
        return send(request("teams/" + teamId + "/members?player_uuid=" + playerUuid)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    public CompletableFuture<ApiResult> renameTeam(int teamId, UUID playerUuid, String newName) {
        JsonObject body = new JsonObject();
        body.addProperty("name", newName);
        return send(request("teams/" + teamId + "/rename?player_uuid=" + playerUuid)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build());
    }

    public CompletableFuture<ApiResult> acceptInviteByTeamName(UUID playerUuid, String teamName) {
        JsonObject body = new JsonObject();
        body.addProperty("team_name", teamName);
        return send(request("teams/accept_invite?player_uuid=" + playerUuid)
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build());
    }

    // ---- [FUTURE] invite system (api/API_TEAMS_TODO.md §1) ----

    public CompletableFuture<ApiResult> invitePlayer(int teamId, UUID invitedUuid, String invitedName, UUID inviterUuid) {
        JsonObject body = new JsonObject();
        body.addProperty("invited_uuid", invitedUuid.toString());
        body.addProperty("invited_name", invitedName);
        body.addProperty("inviter_uuid", inviterUuid.toString());
        return send(request("teams/" + teamId + "/invite")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build());
    }

    public CompletableFuture<ApiResult> getInvites(UUID playerUuid) {
        return send(request("teams/invites/" + playerUuid).GET().build());
    }

    public CompletableFuture<ApiResult> acceptInvite(int inviteId, UUID playerUuid) {
        return send(request("teams/invites/" + inviteId + "/accept?player_uuid=" + playerUuid)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    public CompletableFuture<ApiResult> declineInvite(int inviteId, UUID playerUuid) {
        return send(request("teams/invites/" + inviteId + "?player_uuid=" + playerUuid)
                .DELETE().build());
    }

    public CompletableFuture<ApiResult> kickMember(int teamId, UUID targetUuid, UUID requesterUuid) {
        return send(request("teams/" + teamId + "/members/" + targetUuid + "?requester_uuid=" + requesterUuid)
                .DELETE().build());
    }

    // ---- [FUTURE] quest progress (api/API_TEAMS_TODO.md §2) ----

    public CompletableFuture<ApiResult> putQuestProgress(int teamId, String progressJson) {
        return send(request("quests/progress/" + teamId)
                .PUT(HttpRequest.BodyPublishers.ofString(progressJson)).build());
    }

    public CompletableFuture<ApiResult> getAllQuestProgress() {
        return send(request("quests/progress").GET().build());
    }

    public CompletableFuture<ApiResult> getQuestProgress(int teamId) {
        return send(request("quests/progress/" + teamId).GET().build());
    }
}
