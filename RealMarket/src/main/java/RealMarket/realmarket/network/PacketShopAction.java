package RealMarket.realmarket.network;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.api.MarketSyncManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class PacketShopAction {
    private final int type;       // 0: Buy, 1: Sell
    private final int amount;
    private final String itemId;  // item registry name, наприклад "minecraft:diamond"
    private final UUID islandUuid;

    public PacketShopAction(int type, int amount, String itemId, UUID islandUuid) {
        this.type = type;
        this.amount = amount;
        this.itemId = itemId;
        this.islandUuid = islandUuid;
    }

    public static void encode(PacketShopAction msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.type);
        buf.writeInt(msg.amount);
        buf.writeUtf(msg.itemId);
        buf.writeUUID(msg.islandUuid);
    }

    public static PacketShopAction decode(FriendlyByteBuf buf) {
        return new PacketShopAction(buf.readInt(), buf.readInt(), buf.readUtf(), buf.readUUID());
    }

    public static void handle(PacketShopAction msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            int apiId = AzuriomClient.getPlayerId(player.getUUID());
            if (apiId == -1) {
                player.sendSystemMessage(Component.literal("§c[Error] Ваш ID не синхронізовано з сайтом!"));
                return;
            }

            switch (msg.type) {
                case 0 -> handleBuy(player, apiId, msg.amount, msg.itemId, msg.islandUuid);
                case 1 -> handleSell(player, apiId, msg.amount, msg.itemId, msg.islandUuid);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    private static void handleBuy(ServerPlayer p, int apiId, int amt, String itemId, UUID islandUuid) {
        // Знаходимо ціну з SINK кешу
        List<MarketSyncManager.CachedItem> cached = MarketSyncManager.getCachedInventory(islandUuid);
        MarketSyncManager.CachedItem entry = cached.stream()
                .filter(i -> i.itemId().equals(itemId) && i.isForSale())
                .findFirst().orElse(null);

        if (entry == null) {
            p.sendSystemMessage(Component.literal("§c[Shop] Предмет більше не доступний!"));
            return;
        }

        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.tryParse(itemId));
        if (item == Items.AIR) {
            p.sendSystemMessage(Component.literal("§c[Shop] Невідомий предмет: " + itemId));
            return;
        }

        if (entry.quantity() < amt) {
            p.sendSystemMessage(Component.literal("§c[Shop] Недостатньо предметів! Є тільки: §f" + entry.quantity()));
            return;
        }

        double totalCost = entry.price() * amt;

        // Крок 1: перевіряємо баланс перед бронюванням
        AzuriomClient.getBalAsync(apiId).thenAccept(currentBalance -> {
            p.server.tell(new TickTask(0, () -> {
                if (currentBalance < totalCost) {
                    p.sendSystemMessage(Component.literal("§c[Shop] Недостатньо коштів! Треба: §6" + totalCost + " §c(Ваш: " + currentBalance + ")"));
                    return;
                }

                // Крок 2: бронюємо через API (записує транзакцію + pending extraction)
                // Продавець отримає гроші тільки після підтвердження extraction
                MarketSyncManager.reservePurchaseAsync(islandUuid, itemId, amt, apiId, pendingId -> {
                    if (pendingId == null) {
                        p.server.tell(new TickTask(0, () ->
                            p.sendSystemMessage(Component.literal("§c[Shop] Предмет вже продано або недоступний!"))
                        ));
                        return;
                    }

                    // Крок 3: знімаємо гроші з покупця
                    AzuriomClient.updateAsync(apiId, -totalCost).thenAccept(success -> {
                        if (success) {
                            // Крок 4: видаємо предмет покупцю
                            p.server.tell(new TickTask(0, () -> {
                                ItemStack reward = new ItemStack(item, amt);
                                if (!p.getInventory().add(reward)) {
                                    p.drop(reward, false);
                                }
                                p.sendSystemMessage(Component.literal("§a[Shop] Ви придбали §f" + amt + "x " + itemId + " §aза §6" + totalCost + " Coins"));
                                MarketSyncManager.invalidateCache(islandUuid);
                            }));
                            // Продавець отримає гроші коли острів підтвердить extraction через WS
                        } else {
                            // Оплата провалилась — скасовуємо бронювання
                            MarketSyncManager.cancelPurchaseAsync(islandUuid, pendingId);
                            p.server.tell(new TickTask(0, () ->
                                p.sendSystemMessage(Component.literal("§c[API] Помилка під час транзакції. Гроші не знято."))
                            ));
                        }
                    });
                });
            }));
        });
    }

    private static void handleSell(ServerPlayer p, int apiId, int amt, String itemId, UUID islandUuid) {
        ItemStack handStack = p.getMainHandItem();

        if (handStack.isEmpty() || handStack.getCount() < amt) {
            p.sendSystemMessage(Component.literal("§c[Shop] У вас недостатньо предметів у руці!"));
            return;
        }

        // Ціна продажу = 50% від ціни купівлі
        List<MarketSyncManager.CachedItem> cached = MarketSyncManager.getCachedInventory(islandUuid);
        double unitPrice = cached.stream()
                .filter(i -> i.itemId().equals(itemId))
                .findFirst()
                .map(i -> i.price() * 0.5)
                .orElse(5.0);

        double reward = unitPrice * amt;

        AzuriomClient.updateAsync(apiId, reward).thenAccept(success -> {
            if (success) {
                p.server.tell(new TickTask(0, () -> {
                    handStack.shrink(amt);
                    p.sendSystemMessage(Component.literal("§6[Shop] Продано §f" + amt + "x " + itemId + " §6за §e" + reward + " Coins"));
                }));
            } else {
                p.sendSystemMessage(Component.literal("§c[API] Сайт відхилив транзакцію. Спробуйте пізніше."));
            }
        });
    }
}
