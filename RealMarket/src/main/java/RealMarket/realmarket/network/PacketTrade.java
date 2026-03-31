package RealMarket.realmarket.network;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            int id = AzuriomClient.getPlayerId(player.getUUID());
            if (id == -1) return;

            // Логіка КУПІВЛІ
            if (msg.isBuy) {
                double price = IslandManager.PRICES.getOrDefault(player.getUUID(), 10.0) * msg.amount;
                AzuriomClient.updateAsync(id, -price).thenAccept(success -> {
                    player.server.tell(new TickTask(0, () -> {
                        if (success) {
                            player.getInventory().add(new ItemStack(Items.DIAMOND, msg.amount));
                            player.sendSystemMessage(Component.literal("§aКуплено " + msg.amount + " од."));
                        } else {
                            player.sendSystemMessage(Component.literal("§cНедостатньо коштів!"));
                        }
                    }));
                });
            }
            // Логіка ПРОДАЖУ
            else {
                ItemStack stack = player.getMainHandItem();
                if (stack.getCount() >= msg.amount) {
                    double reward = IslandManager.PRICES.getOrDefault(player.getUUID(), 5.0) * msg.amount;
                    AzuriomClient.updateAsync(id, reward).thenAccept(success -> {
                        player.server.tell(new TickTask(0, () -> {
                            if (success) {
                                stack.shrink(msg.amount);
                                player.sendSystemMessage(Component.literal("§aПродано! Отримано §6" + reward));
                            }
                        }));
                    });
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
