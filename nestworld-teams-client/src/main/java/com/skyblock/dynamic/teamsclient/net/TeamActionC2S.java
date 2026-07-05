package com.skyblock.dynamic.teamsclient.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Byte-for-byte mirror of the server addon's TeamActionC2S
 * (nestworld-teams-addon). Keep the Action enum order and field encoding
 * identical on both sides.
 */
public class TeamActionC2S {

    public enum Action {
        INVITE,
        KICK,
        ACCEPT_INVITE,
        DECLINE_INVITE,
        ACCEPT_BY_TEAM_NAME,
        LEAVE,
        REQUEST_INVITES,
        REQUEST_TEAM_INFO,
        REQUEST_ALL_PROGRESS
    }

    private final Action action;
    private final String strArg;
    private final int intArg;
    private final UUID uuidArg;

    public TeamActionC2S(Action action, String strArg, int intArg, UUID uuidArg) {
        this.action = action;
        this.strArg = strArg == null ? "" : strArg;
        this.intArg = intArg;
        this.uuidArg = uuidArg == null ? new UUID(0, 0) : uuidArg;
    }

    public static TeamActionC2S invite(String playerName) {
        return new TeamActionC2S(Action.INVITE, playerName, 0, null);
    }

    public static TeamActionC2S kick(UUID member) {
        return new TeamActionC2S(Action.KICK, null, 0, member);
    }

    public static TeamActionC2S acceptInvite(int inviteId) {
        return new TeamActionC2S(Action.ACCEPT_INVITE, null, inviteId, null);
    }

    public static TeamActionC2S declineInvite(int inviteId) {
        return new TeamActionC2S(Action.DECLINE_INVITE, null, inviteId, null);
    }

    public static TeamActionC2S acceptByTeamName(String teamName) {
        return new TeamActionC2S(Action.ACCEPT_BY_TEAM_NAME, teamName, 0, null);
    }

    public static TeamActionC2S simple(Action action) {
        return new TeamActionC2S(action, null, 0, null);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
        buf.writeUtf(strArg, 64);
        buf.writeVarInt(intArg);
        buf.writeUUID(uuidArg);
    }

    public static TeamActionC2S decode(FriendlyByteBuf buf) {
        return new TeamActionC2S(buf.readEnum(Action.class), buf.readUtf(64), buf.readVarInt(), buf.readUUID());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // Client never receives this packet.
        ctx.get().setPacketHandled(true);
    }
}
