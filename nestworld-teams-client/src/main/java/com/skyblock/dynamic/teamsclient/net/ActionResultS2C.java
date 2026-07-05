package com.skyblock.dynamic.teamsclient.net;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Mirror of the server addon's ActionResultS2C: shows the result of a team
 * action in chat, resolved through this mod's lang files.
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

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(success);
        buf.writeUtf(messageKey, 128);
        buf.writeUtf(arg, 128);
    }

    public static ActionResultS2C decode(FriendlyByteBuf buf) {
        return new ActionResultS2C(buf.readBoolean(), buf.readUtf(128), buf.readUtf(128));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                Component message = arg.isEmpty()
                        ? Component.translatable(messageKey)
                        : Component.translatable(messageKey, arg);
                mc.player.displayClientMessage(
                        message.copy().withStyle(success ? ChatFormatting.GREEN : ChatFormatting.RED), false);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
