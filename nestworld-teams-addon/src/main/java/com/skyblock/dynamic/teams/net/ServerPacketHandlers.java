package com.skyblock.dynamic.teams.net;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.teams.NestworldTeamsAddon;
import com.skyblock.dynamic.teams.api.TeamApiClient;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Executes TeamActionC2S packets. Every action resolves the sender's team
 * through the API (source of truth), performs the call, and replies with a
 * translation-key message the client addon renders. Membership changes are
 * NOT applied to FTB Teams here — the API broadcasts TEAM_UPDATED over the
 * island WebSocket, which triggers TeamSyncService reconciliation.
 */
public final class ServerPacketHandlers {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ServerPacketHandlers() {
    }

    public static void handle(TeamActionC2S packet, ServerPlayer sender) {
        TeamApiClient api = NestworldTeamsAddon.API_CLIENT;
        UUID senderUuid = sender.getUUID();

        switch (packet.getAction()) {
            case INVITE -> withOwnedTeam(sender, (teamId, ownerUuid) -> {
                String name = packet.getStrArg().trim();
                if (name.isEmpty() || name.length() > 16) {
                    reply(sender, false, "nestworld_teams.msg.bad_name", name);
                    return;
                }
                // Offline-mode network: UUIDs are derived deterministically from the name.
                UUID invitedUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
                api.invitePlayer(teamId, invitedUuid, name, senderUuid).thenAccept(r -> {
                    if (r.isSuccess()) {
                        reply(sender, true, "nestworld_teams.msg.invite_sent", name);
                    } else if (r.isNotImplementedYet()) {
                        reply(sender, false, "nestworld_teams.msg.api_not_ready", "");
                    } else {
                        reply(sender, false, "nestworld_teams.msg.invite_failed", detail(r));
                    }
                });
            });

            case KICK -> withOwnedTeam(sender, (teamId, ownerUuid) ->
                    api.kickMember(teamId, packet.getUuidArg(), senderUuid).thenAccept(r -> {
                        if (r.isSuccess()) {
                            reply(sender, true, "nestworld_teams.msg.kicked", "");
                        } else if (r.isNotImplementedYet()) {
                            reply(sender, false, "nestworld_teams.msg.api_not_ready", "");
                        } else {
                            reply(sender, false, "nestworld_teams.msg.kick_failed", detail(r));
                        }
                    }));

            case ACCEPT_INVITE -> NestworldTeamsAddon.API_CLIENT
                    .acceptInvite(packet.getIntArg(), senderUuid).thenAccept(r -> {
                        if (r.isSuccess()) {
                            reply(sender, true, "nestworld_teams.msg.invite_accepted", "");
                        } else if (r.isNotImplementedYet()) {
                            reply(sender, false, "nestworld_teams.msg.api_not_ready", "");
                        } else {
                            reply(sender, false, "nestworld_teams.msg.error", detail(r));
                        }
                    });

            case DECLINE_INVITE -> api.declineInvite(packet.getIntArg(), senderUuid).thenAccept(r ->
                    reply(sender, r.isSuccess(), r.isSuccess()
                            ? "nestworld_teams.msg.invite_declined"
                            : "nestworld_teams.msg.error", detail(r)));

            case ACCEPT_BY_TEAM_NAME -> api.acceptInviteByTeamName(senderUuid, packet.getStrArg()).thenAccept(r ->
                    reply(sender, r.isSuccess(), r.isSuccess()
                            ? "nestworld_teams.msg.joined_team"
                            : "nestworld_teams.msg.join_failed", r.isSuccess() ? packet.getStrArg() : detail(r)));

            case LEAVE -> withTeam(sender, (teamId, ownerUuid) -> {
                if (ownerUuid.equals(senderUuid)) {
                    reply(sender, false, "nestworld_teams.msg.owner_cannot_leave", "");
                    return;
                }
                api.leaveTeam(teamId, senderUuid).thenAccept(r ->
                        reply(sender, r.isSuccess(), r.isSuccess()
                                ? "nestworld_teams.msg.left_team"
                                : "nestworld_teams.msg.error", detail(r)));
            });

            case REQUEST_INVITES -> api.getInvites(senderUuid).thenAccept(r ->
                    sendData(sender, DataS2C.DataType.INVITES_LIST, r.isSuccess() ? r.body() : "[]"));

            case REQUEST_TEAM_INFO -> api.getMyTeam(senderUuid).thenAccept(r ->
                    sendData(sender, DataS2C.DataType.TEAM_INFO, r.isSuccess() ? r.body() : "{}"));

            case REQUEST_ALL_PROGRESS -> api.getAllQuestProgress().thenAccept(r ->
                    sendData(sender, DataS2C.DataType.ALL_PROGRESS, r.isSuccess() ? r.body() : "[]"));
        }
    }

    /** Resolves the sender's team; the action runs only if the sender is the team owner. */
    private static void withOwnedTeam(ServerPlayer sender, BiConsumer<Integer, UUID> action) {
        withTeam(sender, (teamId, ownerUuid) -> {
            if (!ownerUuid.equals(sender.getUUID())) {
                reply(sender, false, "nestworld_teams.msg.not_owner", "");
                return;
            }
            action.accept(teamId, ownerUuid);
        });
    }

    private static void withTeam(ServerPlayer sender, BiConsumer<Integer, UUID> action) {
        NestworldTeamsAddon.API_CLIENT.getMyTeam(sender.getUUID()).thenAccept(r -> {
            if (!r.isSuccess()) {
                reply(sender, false, "nestworld_teams.msg.no_team", "");
                return;
            }
            try {
                JsonObject team = r.json();
                int teamId = team.get("id").getAsInt();
                UUID ownerUuid = UUID.fromString(team.get("owner_uuid").getAsString());
                action.accept(teamId, ownerUuid);
            } catch (Exception e) {
                LOGGER.error("Malformed my_team response for {}", sender.getUUID(), e);
                reply(sender, false, "nestworld_teams.msg.error", "bad response");
            }
        });
    }

    private static String detail(TeamApiClient.ApiResult r) {
        if (r.status() <= 0) {
            return "no connection";
        }
        try {
            JsonObject json = r.json();
            if (json.has("detail")) {
                return json.get("detail").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "HTTP " + r.status();
    }

    private static void reply(ServerPlayer player, boolean success, String key, String arg) {
        player.getServer().execute(() ->
                NetworkHandler.sendTo(player, new ActionResultS2C(success, key, arg)));
    }

    private static void sendData(ServerPlayer player, DataS2C.DataType type, String json) {
        player.getServer().execute(() ->
                NetworkHandler.sendTo(player, new DataS2C(type, json)));
    }
}
