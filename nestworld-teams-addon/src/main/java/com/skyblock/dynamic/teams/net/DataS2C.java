package com.skyblock.dynamic.teams.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * JSON payloads for the client addon (invite list, team info, all-teams quest
 * progress for the spawn viewer). Kept as raw JSON so the packet format stays
 * stable while the API evolves.
 */
public class DataS2C {

    public enum DataType {
        INVITES_LIST,
        TEAM_INFO,
        ALL_PROGRESS
    }

    /** Custom payload packets are capped at 1 MiB; leave headroom for headers. */
    public static final int MAX_JSON_BYTES = 900_000;

    private final DataType type;
    private final String json;

    public DataS2C(DataType type, String json) {
        this.type = type;
        this.json = json == null ? "{}" : json;
    }

    public DataType getType() {
        return type;
    }

    public String getJson() {
        return json;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(type);
        buf.writeUtf(json.length() > MAX_JSON_BYTES ? "{\"error\":\"payload_too_large\"}" : json, MAX_JSON_BYTES);
    }

    public static DataS2C decode(FriendlyByteBuf buf) {
        return new DataS2C(buf.readEnum(DataType.class), buf.readUtf(MAX_JSON_BYTES));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // Display is implemented in the client addon; nothing to do server-side.
        ctx.get().setPacketHandled(true);
    }
}
