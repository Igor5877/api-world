package com.nguyendevs.freesia.forge;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.util.Collection;
import java.util.UUID;

final class TrackerProtocol {
    record Scan(int callback, UUID target) {}

    static Scan decodeScan(FriendlyByteBuf payload) {
        if (payload == null || payload.readableBytes() < 18 || payload.readableBytes() > 26) return null;
        try {
            if (payload.readVarInt() != 1) return null;
            Scan scan = new Scan(payload.readVarInt(), payload.readUUID());
            return payload.isReadable() ? null : scan;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    static FriendlyByteBuf reply(int callback, Collection<UUID> viewers) {
        FriendlyByteBuf result = new FriendlyByteBuf(Unpooled.buffer());
        result.writeVarInt(0);
        result.writeVarInt(callback);
        result.writeVarInt(viewers.size());
        viewers.forEach(result::writeUUID);
        return result;
    }

    static FriendlyByteBuf update(UUID target, UUID watcher) {
        FriendlyByteBuf result = new FriendlyByteBuf(Unpooled.buffer());
        result.writeVarInt(2);
        result.writeUUID(target);
        result.writeUUID(watcher);
        return result;
    }
}
