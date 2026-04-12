package RealMarket.realmarket.network;

import RealMarket.realmarket.RealMarket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModMessages {
    private static SimpleChannel INSTANCE;
    private static int packetId = 0;
    private static int id() { return packetId++; }

    public static void register() {
        // Створюємо канал
        INSTANCE = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(RealMarket.MODID, "main"),
                () -> "1.0",
                s -> true,
                s -> true
        );

        INSTANCE.messageBuilder(PacketShopAction.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(PacketShopAction::decode)
                .encoder(PacketShopAction::encode)
                .consumerMainThread(PacketShopAction::handle)
                .add();

        INSTANCE.messageBuilder(PacketOpenTradeUI.class, id(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(PacketOpenTradeUI::decode)
                .encoder(PacketOpenTradeUI::encode)
                .consumerMainThread(PacketOpenTradeUI::handle)
                .add();

        System.out.println("[RealMarket] Network packets registered successfully!");
    }

    public static <MSG> void sendToServer(MSG message) {
        INSTANCE.sendToServer(message);
    }

    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}