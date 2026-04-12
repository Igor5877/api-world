package RealMarket.realmarket.network;

import RealMarket.realmarket.client.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Пакет SERVER → CLIENT.
 * Сервер зібрав баланс і ціну — надсилає клієнту щоб відкрити TradeScreen.
 */
public class PacketOpenTradeUI {
    private final double balance;
    private final double price;

    public PacketOpenTradeUI(double balance, double price) {
        this.balance = balance;
        this.price = price;
    }

    public static void encode(PacketOpenTradeUI msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.balance);
        buf.writeDouble(msg.price);
    }

    public static PacketOpenTradeUI decode(FriendlyByteBuf buf) {
        return new PacketOpenTradeUI(buf.readDouble(), buf.readDouble());
    }

    public static void handle(PacketOpenTradeUI msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientHooks.openTradeScreen(msg.balance, msg.price));
        });
        ctx.get().setPacketHandled(true);
    }
}
