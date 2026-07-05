package com.skyblock.dynamic.teams.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Result of a TeamActionC2S: a translation key (resolved by the client addon's
 * lang files) plus one optional argument. On a dedicated server this class has
 * no client handling — the client addon owns the display logic via the shared
 * channel, so the handler here is a no-op that only exists to satisfy the
 * channel registration symmetry.
 */
public class ActionResultS2C {
    private final boolean success;
    private final String messageKey;
    private final String arg;

    public ActionResultS2C(boolean success, String messageKey, String arg) {
        this.success = success;
        this.messageKey = messageKey;
        this.arg = arg == null ? "" : arg;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getArg() {
        return arg;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(success);
        buf.writeUtf(messageKey, 128);
        buf.writeUtf(arg, 128);
    }

    public static ActionResultS2C decode(FriendlyByteBuf buf) {
        return new ActionResultS2C(buf.readBoolean(), buf.readUtf(128), buf.readUtf(128));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        // Display is implemented in the client addon; nothing to do server-side.
        ctx.get().setPacketHandled(true);
    }
}
