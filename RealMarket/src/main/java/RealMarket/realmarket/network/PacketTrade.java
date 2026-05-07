package RealMarket.realmarket.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public class PacketTrade {
    private final int amount;
    private final boolean isBuy; // true - купівля, false - продаж

    public PacketTrade(int amount, boolean isBuy) {
        this.amount = amount;
        this.isBuy = isBuy;
    }

    public static void encode(PacketTrade msg, FriendlyByteBuf buffer) {
        buffer.writeInt(msg.amount);
        buffer.writeBoolean(msg.isBuy);
    }

    public static PacketTrade decode(FriendlyByteBuf buffer) {
        return new PacketTrade(buffer.readInt(), buffer.readBoolean());
    }

    public static void handle(PacketTrade msg, Supplier<NetworkEvent.Context> ctx) {
        // Deprecated: PacketTrade is no longer registered. Use PacketShopAction instead.
        ctx.get().setPacketHandled(true);
    }
}
