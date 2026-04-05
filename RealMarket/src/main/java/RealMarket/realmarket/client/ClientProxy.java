package RealMarket.realmarket.client;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class ClientProxy {
    public static void openTradeScreen(Player player) {
        int id = AzuriomClient.getPlayerId(player.getUUID());

        // Отримуємо ціну острова (якщо вона є в пам'яті)
        double price = IslandManager.PRICES.getOrDefault(player.getUUID(), 10.0);

        if (id != -1) {
            // Асинхронний запит балансу через Azuriom
            AzuriomClient.getBalAsync(id).thenAccept(bal -> {
                // Повертаємося в потік Minecraft для відкриття GUI
                Minecraft.getInstance().tell(() -> {
                    Minecraft.getInstance().setScreen(new TradeScreen(bal, price));
                });
            });
        } else {
            // Швидке повідомлення над інвентарем, якщо ID ще не підтягнувся
            player.displayClientMessage(Component.literal("§cСинхронізація ID... Спробуйте ще раз"), true);
        }
    }
}
