package RealMarket.realmarket.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import RealMarket.realmarket.client.TradeScreen;

import java.util.function.Supplier;

public class PacketTradeResponse {
    private final boolean success;
    private final String message;
    private final double newBalance;

    public PacketTradeResponse(boolean success, String message, double newBalance) {
        this.success = success;
        this.message = message;
        this.newBalance = newBalance;
    }

    public static void encode(PacketTradeResponse msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.message);
        buf.writeDouble(msg.newBalance);
    }

    public static PacketTradeResponse decode(FriendlyByteBuf buf) {
        return new PacketTradeResponse(buf.readBoolean(), buf.readUtf(), buf.readDouble());
    }

    public static void handle(PacketTradeResponse msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(msg.message), true);
            }
            if (mc.screen instanceof TradeScreen ts) {
                ts.updateBalance(msg.newBalance);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
