package RealMarket.realmarket.network;

import RealMarket.realmarket.client.ClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Пакет SERVER → CLIENT.
 * Сервер зібрав баланс і весь список предметів SINK — надсилає клієнту щоб відкрити TradeScreen.
 */
public class PacketOpenTradeUI {

    public record ItemEntry(String itemId, double price, long quantity) {}

    private final double balance;
    private final UUID islandUuid;
    private final List<ItemEntry> items;

    public PacketOpenTradeUI(double balance, UUID islandUuid, List<ItemEntry> items) {
        this.balance = balance;
        this.islandUuid = islandUuid;
        this.items = items;
    }

    public static void encode(PacketOpenTradeUI msg, FriendlyByteBuf buf) {
        buf.writeDouble(msg.balance);
        buf.writeUUID(msg.islandUuid);
        buf.writeInt(msg.items.size());
        for (ItemEntry e : msg.items) {
            buf.writeUtf(e.itemId());
            buf.writeDouble(e.price());
            buf.writeLong(e.quantity());
        }
    }

    public static PacketOpenTradeUI decode(FriendlyByteBuf buf) {
        double balance = buf.readDouble();
        UUID islandUuid = buf.readUUID();
        int size = buf.readInt();
        List<ItemEntry> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            items.add(new ItemEntry(buf.readUtf(), buf.readDouble(), buf.readLong()));
        }
        return new PacketOpenTradeUI(balance, islandUuid, items);
    }

    public static void handle(PacketOpenTradeUI msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                    ClientHooks.openTradeScreen(msg.balance, msg.islandUuid, msg.items));
        });
        ctx.get().setPacketHandled(true);
    }
}
