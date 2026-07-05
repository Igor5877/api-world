package com.skyblock.dynamic.teams.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * All team GUI actions from the client addon, multiplexed into one packet.
 * The server resolves the sender's team through the Nestworld API and replies
 * with ActionResultS2C / DataS2C.
 */
public class TeamActionC2S {

    public enum Action {
        INVITE,               // strArg = player name to invite
        KICK,                 // uuidArg = member to kick
        ACCEPT_INVITE,        // intArg = invite id
        DECLINE_INVITE,       // intArg = invite id
        ACCEPT_BY_TEAM_NAME,  // strArg = team name (legacy flow, works today)
        LEAVE,
        REQUEST_INVITES,
        REQUEST_TEAM_INFO,
        REQUEST_ALL_PROGRESS  // spawn/hub quest progress viewer
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

    public static TeamActionC2S simple(Action action) {
        return new TeamActionC2S(action, null, 0, null);
    }

    public Action getAction() {
        return action;
    }

    public String getStrArg() {
        return strArg;
    }

    public int getIntArg() {
        return intArg;
    }

    public UUID getUuidArg() {
        return uuidArg;
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
        ServerPlayer sender = ctx.get().getSender();
        if (sender != null) {
            ServerPacketHandlers.handle(this, sender);
        }
        ctx.get().setPacketHandled(true);
    }
}
