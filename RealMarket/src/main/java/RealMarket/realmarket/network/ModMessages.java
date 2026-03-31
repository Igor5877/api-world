package RealMarket.realmarket.network;

import RealMarket.realmarket.RealMarket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public class ModMessages {
    private static SimpleChannel INSTANCE;
    private static int packetId = 0;
    private static int id() { return packetId++; }

    public static void register() {
        // Використовуємо новий спосіб створення каналу для Forge 47.x
        INSTANCE = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(RealMarket.MODID, "main"),
                () -> "1.0",
                s -> true,
                s -> true
        );

        // Реєструємо пакет торгівлі
        INSTANCE.messageBuilder(PacketTrade.class, id(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(PacketTrade::decode)
                .encoder(PacketTrade::encode)
                .consumerMainThread(PacketTrade::handle)
                .add();
    }

    // Відправка повідомлення на сервер (з клієнта)
    public static <MSG> void sendToServer(MSG message) {
        INSTANCE.sendToServer(message);
    }

    // Відправка повідомлення конкретному гравцю (з сервера)
    public static <MSG> void sendToPlayer(MSG message, ServerPlayer player) {
        INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}