package net.market.realmarket.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.market.realmarket.RealMarket;
import net.market.realmarket.api.AzuriomClient;
import net.market.realmarket.world.IslandManager;
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

                    if (id == -1) return 0;

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

                    if (id == -1 || stack.getCount() < amt) return 0;

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

                    if (id == -1) return 0;

                    double cost = IslandManager.PRICES.getOrDefault(p.getUUID(), 10.0) * amt;

                    // Асинхронна купівля (зняття грошей)
                    AzuriomClient.updateAsync(id, -cost).thenAccept(success -> {
                        p.server.tell(new TickTask(0, () -> {
                            if (success) {
                                p.sendSystemMessage(Component.literal("§aКуплено " + amt + " од. за §6" + cost));
                                // Тут можна додати видачу предмету гравцю
                            } else {
                                p.sendSystemMessage(Component.literal("§cНедостатньо коштів на балансі!"));
                            }
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
                    p.sendSystemMessage(Component.literal("§aЦіну на товари встановлено: §6" + val));
                    return 1;
                })))
        );
    }
}