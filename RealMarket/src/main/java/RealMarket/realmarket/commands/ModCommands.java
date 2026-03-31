package RealMarket.realmarket.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import RealMarket.realmarket.RealMarket;
import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class ModCommands {
    public static void register(CommandDispatcher<CommandSourceStack> disp) {
        // --- ГРУПА КОМАНД /market ---
        disp.register(Commands.literal("market")
                .then(Commands.literal("balance").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    int id = AzuriomClient.getPlayerId(p.getUUID());

                    if (id == -1) {
                        p.sendSystemMessage(Component.literal("§cСинхронізація з API... Будь ласка, зачекайте."));
                        return 0;
                    }

                    AzuriomClient.getBalAsync(id).thenAccept(bal -> {
                        p.server.tell(new TickTask(0, () -> {
                            p.sendSystemMessage(Component.literal("§bБаланс: §6" + (bal < 0 ? "Помилка API" : bal + " Coins")));
                        }));
                    });
                    return 1;
                }))
                .then(Commands.literal("sell").then(Commands.argument("amt", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    int id = AzuriomClient.getPlayerId(p.getUUID());
                    int amt = IntegerArgumentType.getInteger(c, "amt");
                    ItemStack stack = p.getMainHandItem();

                    if (id == -1) {
                        p.sendSystemMessage(Component.literal("§cСинхронізація з API... Будь ласка, зачекайте."));
                        return 0;
                    }
                    if (stack.getCount() < amt) {
                        p.sendSystemMessage(Component.literal("§cУ вас немає стільки предметів у руці!"));
                        return 0;
                    }

                    // Ціна за продаж (власна ціна гравця)
                    double price = IslandManager.PRICES.getOrDefault(p.getUUID(), 5.0) * amt;

                    AzuriomClient.updateAsync(id, price).thenAccept(success -> {
                        p.server.tell(new TickTask(0, () -> {
                            if (success) {
                                stack.shrink(amt);
                                p.sendSystemMessage(Component.literal("§aПродано " + amt + " од. за §6" + price));
                            } else {
                                p.sendSystemMessage(Component.literal("§cПомилка API при продажу!"));
                            }
                        }));
                    });
                    return 1;
                })))
                // ВИПРАВЛЕНА КОМАНДА BUY (Тепер з вибором продавця)
                .then(Commands.literal("buy")
                        .then(Commands.argument("seller", EntityArgument.player()) // Вибираємо, у кого купуємо
                                .then(Commands.argument("amt", IntegerArgumentType.integer(1))
                                        .executes(c -> {
                                            ServerPlayer buyer = c.getSource().getPlayerOrException();
                                            ServerPlayer seller = EntityArgument.getPlayer(c, "seller");
                                            int amt = IntegerArgumentType.getInteger(c, "amt");
                                            int buyerId = AzuriomClient.getPlayerId(buyer.getUUID());

                                            if (buyerId == -1) {
                                                buyer.sendSystemMessage(Component.literal("§cСинхронізація з API... Будь ласка, зачекайте."));
                                                return 0;
                                            }

                                            // БЕРЕМО ЦІНУ ПРОДАВЦЯ (seller), а не покупця
                                            double unitPrice = IslandManager.PRICES.getOrDefault(seller.getUUID(), 10.0);
                                            double cost = unitPrice * amt;

                                            AzuriomClient.getBalAsync(buyerId).thenAccept(bal -> {
                                                buyer.server.tell(new TickTask(0, () -> {
                                                    if (bal < 0) {
                                                        buyer.sendSystemMessage(Component.literal("§cПомилка отримання балансу!"));
                                                        return;
                                                    }
                                                    if (bal < cost) {
                                                        buyer.sendSystemMessage(Component.literal("§cНедостатньо коштів!"));
                                                        buyer.sendSystemMessage(Component.literal("§7Баланс: §6" + bal + "§7, Потрібно: §c" + cost + " §7(Ціна " + seller.getName().getString() + ": " + unitPrice + ")"));
                                                        return;
                                                    }

                                                    AzuriomClient.updateAsync(buyerId, -cost).thenAccept(success -> {
                                                        buyer.server.tell(new TickTask(0, () -> {
                                                            if (success) {
                                                                buyer.sendSystemMessage(Component.literal("§aКуплено " + amt + " од. у " + seller.getName().getString() + " за §6" + cost));
                                                                ItemStack itemStack = new ItemStack(Items.DIAMOND, amt);
                                                                if (!buyer.getInventory().add(itemStack)) {
                                                                    buyer.drop(itemStack, false);
                                                                }
                                                            } else {
                                                                buyer.sendSystemMessage(Component.literal("§cПомилка API при купівлі!"));
                                                            }
                                                        }));
                                                    });
                                                }));
                                            });
                                            return 1;
                                        }))))
                .then(Commands.literal("getblock").requires(s -> s.hasPermission(2)).executes(c -> {
                    c.getSource().getPlayerOrException().addItem(new ItemStack(RealMarket.TRADE_ITEM.get()));
                    return 1;
                }))
        );

        // --- ГРУПА КОМАНД /island ---
        disp.register(Commands.literal("island")
                .then(Commands.literal("create").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    IslandManager.createIsland(p.serverLevel(), p.getId());
                    p.sendSystemMessage(Component.literal("§aТорговий острів сформовано!"));
                    return 1;
                }))
                .then(Commands.literal("visit").then(Commands.argument("target", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    ServerPlayer target = EntityArgument.getPlayer(c, "target");
                    var pos = IslandManager.getIslandCoords(target.getId());
                    p.teleportTo(pos.getX(), pos.getY() + 1, pos.getZ());
                    p.sendSystemMessage(Component.literal("§eВи на острові гравця " + target.getName().getString()));
                    return 1;
                })))
                .then(Commands.literal("setprice").then(Commands.argument("val", DoubleArgumentType.doubleArg(0)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    double val = DoubleArgumentType.getDouble(c, "val");
                    IslandManager.PRICES.put(p.getUUID(), val);
                    IslandManager.savePrices(); // Метод має бути реалізований в IslandManager
                    p.sendSystemMessage(Component.literal("§aЦіну на товари встановлено: §6" + val));
                    return 1;
                })))
        );
    }
}