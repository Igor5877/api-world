package com.real.market;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class MarketCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("mkt")
                        .then(Commands.literal("buy")
                                .then(Commands.argument("item", ItemArgument.item(context))  // ← context замість null
                                        .then(Commands.argument("qty", IntegerArgumentType.integer(1, 64))
                                                .executes(c -> {
                                                    // ... решта без змін
                                            Player p = c.getSource().getPlayerOrException();
                                            Item targetItem = ItemArgument.getItem(c, "item").getItem();
                                            int qty = IntegerArgumentType.getInteger(c, "qty");

                                            // Отримуємо дані ринку та рахуємо ціну
                                            var market = MarketData.get(c.getSource().getLevel());
                                            var node = market.stocks.computeIfAbsent(targetItem, i -> new MarketData.Entry(1000, 1000, 10.0));
                                            double totalCost = node.price(true) * qty;

                                            // 1. Синхронізуємо дані з Azuriom (отримуємо ID та Баланс)
                                            AzuriomClient.syncPlayer(p.getGameProfile().getName(), p.getUUID()).thenAccept(info -> {
                                                if (info == null) {
                                                    c.getSource().getServer().execute(() -> p.sendSystemMessage(Component.literal("§cПомилка: Сайт Azuriom недоступний!")));
                                                    return;
                                                }

                                                if (info.money() >= totalCost) {
                                                    // 2. Знімаємо гроші на сайті
                                                    AzuriomClient.updateMoney(info.id(), "remove", totalCost).thenAccept(success -> {
                                                        if (success) {
                                                            // 3. Видаємо предмет у головному потоці гри
                                                            c.getSource().getServer().execute(() -> {
                                                                p.addItem(new ItemStack(targetItem, qty));
                                                                node.stock -= qty;
                                                                market.setDirty();
                                                                p.sendSystemMessage(Component.literal("§aКуплено! З балансу знято: §e" + String.format("%.2f", totalCost) + " ₴"));
                                                            });
                                                        } else {
                                                            c.getSource().getServer().execute(() -> p.sendSystemMessage(Component.literal("§cПомилка транзакції на сайті!")));
                                                        }
                                                    });
                                                } else {
                                                    c.getSource().getServer().execute(() -> p.sendSystemMessage(Component.literal("§cНедостатньо коштів! Треба: " + String.format("%.2f", totalCost))));
                                                }
                                            });
                                            return 1;
                                        }))))

                // ПРОДАЖ: /mkt sell <кількість> (продає предмет у руці)
                .then(Commands.literal("sell")
                        .then(Commands.argument("qty", IntegerArgumentType.integer(1, 2304))
                                .executes(c -> {
                                    Player p = c.getSource().getPlayerOrException();
                                    int qty = IntegerArgumentType.getInteger(c, "qty");
                                    ItemStack stackInHand = p.getMainHandItem();

                                    if (stackInHand.isEmpty()) {
                                        p.sendSystemMessage(Component.literal("§cВізьміть предмет для продажу у руку!"));
                                        return 0;
                                    }

                                    Item itemToSell = stackInHand.getItem();
                                    int hasCount = p.getInventory().items.stream()
                                            .filter(s -> s.is(itemToSell))
                                            .mapToInt(ItemStack::getCount).sum();

                                    if (hasCount < qty) {
                                        p.sendSystemMessage(Component.literal("§cУ вас немає стільки предметів!"));
                                        return 0;
                                    }

                                    var market = MarketData.get(c.getSource().getLevel());
                                    var node = market.stocks.computeIfAbsent(itemToSell, i -> new MarketData.Entry(1000, 1000, 10.0));
                                    double profit = node.price(false) * qty;

                                    // 1. Отримуємо ID гравця з сайту
                                    AzuriomClient.syncPlayer(p.getGameProfile().getName(), p.getUUID()).thenAccept(info -> {
                                        if (info != null) {
                                            // 2. Додаємо гроші на сайті
                                            AzuriomClient.updateMoney(info.id(), "add", profit).thenAccept(success -> {
                                                if (success) {
                                                    // 3. Вилучаємо предмети у головному потоці гри
                                                    c.getSource().getServer().execute(() -> {
                                                        p.getInventory().clearOrCountMatchingItems(s -> s.is(itemToSell), qty, p.inventoryMenu.getCraftSlots());
                                                        node.stock += qty;
                                                        market.setDirty();
                                                        p.sendSystemMessage(Component.literal("§6Продано! На баланс нараховано: §e" + String.format("%.2f", profit) + " ₴"));
                                                    });
                                                }
                                            });
                                        } else {
                                            c.getSource().getServer().execute(() -> p.sendSystemMessage(Component.literal("§cПомилка зв'язку з сайтом!")));
                                        }
                                    });
                                    return 1;
                                })))

                // БАЛАНС: /mkt bal
                .then(Commands.literal("bal").executes(c -> {
                    Player p = c.getSource().getPlayerOrException();
                    AzuriomClient.syncPlayer(p.getGameProfile().getName(), p.getUUID()).thenAccept(info -> {
                        c.getSource().getServer().execute(() -> {
                            if (info != null) {
                                p.sendSystemMessage(Component.literal("§6Ваш баланс на сайті: §e" + String.format("%.2f", info.money()) + " ₴"));
                            } else {
                                p.sendSystemMessage(Component.literal("§cНе вдалося отримати дані з сайту."));
                            }
                        });
                    });
                    return 1;
                }))
        );
    }
}