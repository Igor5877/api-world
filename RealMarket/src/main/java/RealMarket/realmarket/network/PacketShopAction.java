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

public class PacketShopAction {
    private final int type;   // 0: Buy, 1: Sell, 2: Set Price
    private final double value; // Кількість для обміну або значення нової ціни

    public PacketShopAction(int type, double value) {
        this.type = type;
        this.value = value;
    }

    // Запис даних у мережевий буфер
    public static void encode(PacketShopAction msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.type);
        buf.writeDouble(msg.value);
    }

    // Читання даних з мережевого буфера
    public static PacketShopAction decode(FriendlyByteBuf buf) {
        return new PacketShopAction(buf.readInt(), buf.readDouble());
    }

    // Обробка пакету на сервері
    public static void handle(PacketShopAction msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            int apiId = AzuriomClient.getPlayerId(player.getUUID());
            if (apiId == -1) {
                player.sendSystemMessage(Component.literal("§c[Error] Ваш ID не синхронізовано з сайтом!"));
                return;
            }

            // Вибір дії залежно від типу
            switch (msg.type) {
                case 0 -> handleBuy(player, apiId, (int) msg.value);
                case 1 -> handleSell(player, apiId, (int) msg.value);
                case 2 -> handleSetPrice(player, msg.value);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * ЛОГІКА КУПІВЛІ (Зняття грошей з сайту -> Видача предметів в грі)
     */
    private static void handleBuy(ServerPlayer p, int apiId, int amt) {
        double unitPrice = IslandManager.PRICES.getOrDefault(p.getUUID(), 10.0);
        double totalCost = unitPrice * amt;

        // 1. Асинхронно перевіряємо баланс на сайті
        AzuriomClient.getBalAsync(apiId).thenAccept(currentBalance -> {
            p.server.tell(new TickTask(0, () -> {
                if (currentBalance < totalCost) {
                    p.sendSystemMessage(Component.literal("§c[Shop] Недостатньо коштів! Треба: §6" + totalCost + " §c(Ваш: " + currentBalance + ")"));
                    return;
                }

                // 2. Якщо грошей вистачає - асинхронно знімаємо їх
                AzuriomClient.updateAsync(apiId, -totalCost).thenAccept(success -> {
                    if (success) {
                        // 3. Повертаємось в потік Minecraft для видачі предметів
                        p.server.tell(new TickTask(0, () -> {
                            ItemStack reward = new ItemStack(Items.DIAMOND, amt);
                            if (!p.getInventory().add(reward)) {
                                p.drop(reward, false);
                            }
                            p.sendSystemMessage(Component.literal("§a[Shop] Ви придбали §f" + amt + " од. §aза §6" + totalCost + " Coins"));
                        }));
                    } else {
                        p.sendSystemMessage(Component.literal("§c[API] Помилка під час транзакції. Гроші не знято."));
                    }
                });
            }));
        });
    }

    /**
     * ЛОГІКА ПРОДАЖУ (Вилучення предметів в грі -> Нарахування грошей на сайт)
     */
    private static void handleSell(ServerPlayer p, int apiId, int amt) {
        ItemStack handStack = p.getMainHandItem();

        // 1. Перевірка наявності предметів у руці (Жорстка серверна перевірка)
        if (handStack.isEmpty() || handStack.getCount() < amt) {
            p.sendSystemMessage(Component.literal("§c[Shop] У вас недостатньо предметів у руці!"));
            return;
        }

        double unitPrice = IslandManager.PRICES.getOrDefault(p.getUUID(), 5.0);
        double reward = unitPrice * amt;

        // 2. Асинхронно нараховуємо гроші на сайт
        AzuriomClient.updateAsync(apiId, reward).thenAccept(success -> {
            if (success) {
                // 3. Якщо сайт підтвердив - забираємо предмети
                p.server.tell(new TickTask(0, () -> {
                    handStack.shrink(amt);
                    p.sendSystemMessage(Component.literal("§6[Shop] Продано! Ви отримали §e" + reward + " Coins §6на баланс сайту."));
                }));
            } else {
                p.sendSystemMessage(Component.literal("§c[API] Сайт відхилив транзакцію. Спробуйте пізніше."));
            }
        });
    }

    /**
     * ЛОГІКА НАЛАШТУВАННЯ ЦІНИ
     */
    private static void handleSetPrice(ServerPlayer p, double newPrice) {
        if (newPrice < 0) return;

        // Зберігаємо ціну для UUID гравця
        IslandManager.PRICES.put(p.getUUID(), newPrice);
        IslandManager.savePrices(); // Метод в IslandManager для збереження в JSON/Config

        p.sendSystemMessage(Component.literal("§b[Config] §fЦіна вашого острова встановлена на: §a" + newPrice));
    }
}