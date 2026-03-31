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

                    // Асинхронне отримання балансу
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

                    double price = IslandManager.PRICES.getOrDefault(p.getUUID(), 5.0) * amt;

                    // Асинхронний продаж (додавання грошей)
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
                .then(Commands.literal("buy").then(Commands.argument("amt", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    int id = AzuriomClient.getPlayerId(p.getUUID());
                    int amt = IntegerArgumentType.getInteger(c, "amt");

                    if (id == -1) {
                        p.sendSystemMessage(Component.literal("§cСинхронізація з API... Будь ласка, зачекайте."));
                        return 0;
                    }

                    double cost = IslandManager.PRICES.getOrDefault(p.getUUID(), 10.0) * amt;

                    // Спочатку перевіряємо баланс
                    AzuriomClient.getBalAsync(id).thenAccept(bal -> {
                        p.server.tell(new TickTask(0, () -> {
                            if (bal < 0) {
                                p.sendSystemMessage(Component.literal("§cПомилка отримання балансу з API!"));
                                return;
                            }
                            if (bal < cost) {
                                p.sendSystemMessage(Component.literal("§cНедостатньо коштів! Ваш баланс: §6" + bal + " Coins§c, потрібно: §6" + cost + " Coins"));
                                return;
                            }

                            // Асинхронна купівля (зняття грошей)
                            AzuriomClient.updateAsync(id, -cost).thenAccept(success -> {
                                p.server.tell(new TickTask(0, () -> {
                                    if (success) {
                                        p.sendSystemMessage(Component.literal("§aКуплено " + amt + " од. за §6" + cost));
                                        net.minecraft.world.item.ItemStack itemStack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, amt); // Наразі захардкоджено алмази, як тимчасове рішення, поки не буде інтеграції з AE2 або вибору товару
                                        if (!p.getInventory().add(itemStack)) {
                                            p.drop(itemStack, false);
                                        }
                                    } else {
                                        p.sendSystemMessage(Component.literal("§cПомилка API при купівлі!"));
                                    }
                                }));
                            });
                        }));
                    });
                    return 1;
                })))
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
                    IslandManager.savePrices();
                    p.sendSystemMessage(Component.literal("§aЦіну на товари встановлено: §6" + val));
                    return 1;
                })))
        );
    }
}