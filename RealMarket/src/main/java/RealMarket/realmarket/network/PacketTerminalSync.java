package RealMarket.realmarket.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class PacketTerminalSync {
    private final List<UUID> connectedIslands;

    public PacketTerminalSync(List<UUID> ids) {
        this.connectedIslands = ids;
    }

    public static void encode(PacketTerminalSync msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.connectedIslands.size());
        msg.connectedIslands.forEach(buf::writeUUID);
    }

    public static PacketTerminalSync decode(FriendlyByteBuf buf) {
        int size = buf.readInt();
        List<UUID> list = new ArrayList<>();
        for (int i = 0; i < size; i++) list.add(buf.readUUID());
        return new PacketTerminalSync(list);
    }

    public static void handle(PacketTerminalSync msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Використовуємо msg для уникнення попередження
            if (msg.connectedIslands != null) {
                // Тут логіка оновлення UI або списку доступних островів
                System.out.println("[Market] Synced " + msg.connectedIslands.size() + " network nodes.");
            }
        });
        ctx.get().setPacketHandled(true);
    }
}