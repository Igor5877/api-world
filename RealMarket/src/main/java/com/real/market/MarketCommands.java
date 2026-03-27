package com.real.market;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.google.gson.JsonObject;
import com.real.market.blocks.MarketLinkBlockEntity;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import dev.ftb.mods.ftbchunks.data.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.data.ClaimedChunk;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MarketCommands {
    private static PlotData.Plot getPlotAtPos(BlockPos pos, Level level) {
        PlotData plotData = PlotData.get(level);
        for (PlotData.Plot plot : plotData.plots.values()) {
            if (pos.getX() >= plot.x1 && pos.getX() <= plot.x2 && pos.getZ() >= plot.z1 && pos.getZ() <= plot.z2) {
                return plot;
            }
        }
        return null;
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("market")
                .then(Commands.literal("visit")
                        .then(Commands.argument("island", StringArgumentType.string())
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    String islandName = StringArgumentType.getString(c, "island");
                                    PlotData data = PlotData.get(p.level());
                                    PlotData.Plot plot = data.plots.get(islandName);

                                    if (plot == null) {
                                        c.getSource().sendFailure(Component.literal("§cМагазин не знайдено!"));
                                        return 0;
                                    }

                                    p.teleportTo((plot.x1 + plot.x2) / 2.0, p.getY(), (plot.z1 + plot.z2) / 2.0);
                                    c.getSource().sendSuccess(() -> Component.literal("§aТелепортовано до магазину: " + islandName), true);
                                    return 1;
                                })
                        )
                )
                .then(Commands.literal("plot")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .then(Commands.argument("owner_team_uuid", StringArgumentType.string())
                                                .executes(c -> {
                                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                                    String name = StringArgumentType.getString(c, "name");
                                                    UUID owner;
                                                    try {
                                                        owner = UUID.fromString(StringArgumentType.getString(c, "owner_team_uuid"));
                                                    } catch (IllegalArgumentException e) {
                                                        c.getSource().sendFailure(Component.literal("§cНеправильний формат UUID!"));
                                                        return 0;
                                                    }
                                                    BlockPos pos = p.blockPosition();

                                                    PlotData data = PlotData.get(p.level());
                                                    int x1 = pos.getX() - 10, z1 = pos.getZ() - 10, x2 = pos.getX() + 10, z2 = pos.getZ() + 10;
                                                    data.plots.put(name, new PlotData.Plot(name, owner, System.currentTimeMillis() + 2592000000L, x1, z1, x2, z2));
                                                    data.setDirty();

                                                    FTBChunksAPI.api().getManager().getOrCreateTeam(owner).ifPresent(team -> {
                                                        for (int x = x1 >> 4; x <= x2 >> 4; x++) {
                                                            for (int z = z1 >> 4; z <= z2 >> 4; z++) {
                                                                ClaimedChunk chunk = FTBChunksAPI.api().getManager().getChunk(new ChunkPos(x, z));
                                                                if (chunk == null) {
                                                                    FTBChunksAPI.api().getManager().claimChunk(team, new dev.ftb.mods.ftblibrary.math.ChunkDimPos(p.level().dimension(), x, z), false);
                                                                }
                                                            }
                                                        }
                                                    });

                                                    c.getSource().sendSuccess(() -> Component.literal("§aПлот створено!"), true);
                                                    return 1;
                                                })
                                        )
                                )
                        )
                        .then(Commands.literal("renew")
                                .then(Commands.argument("island_uuid", StringArgumentType.string())
                                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                                .executes(c -> {
                                                    UUID islandId;
                                                    try {
                                                        islandId = UUID.fromString(StringArgumentType.getString(c, "island_uuid"));
                                                    } catch (IllegalArgumentException e) {
                                                        c.getSource().sendFailure(Component.literal("§cНеправильний формат UUID!"));
                                                        return 0;
                                                    }
                                                    int days = IntegerArgumentType.getInteger(c, "days");
                                                    PlotData data = PlotData.get(c.getSource().getLevel());

                                                    for (PlotData.Plot plot : data.plots.values()) {
                                                        if (plot.ownerTeam.equals(islandId)) {
                                                            plot.expiration = Math.max(plot.expiration, System.currentTimeMillis()) + (long) days * 86400000L;
                                                            data.setDirty();
                                                            c.getSource().sendSuccess(() -> Component.literal("§aОренду плоту " + plot.name + " продовжено на " + days + " днів."), true);
                                                            return 1;
                                                        }
                                                    }
                                                    c.getSource().sendFailure(Component.literal("§cПлот для острова " + islandId + " не знайдено."));
                                                    return 0;
                                                })
                                        )
                                )
                        )
                )
                .then(Commands.literal("setprice")
                        .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.0))
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    double price = DoubleArgumentType.getDouble(c, "price");
                                    ItemStack stack = p.getMainHandItem();

                                    if (stack.isEmpty()) {
                                        c.getSource().sendFailure(Component.literal("§cВізьміть предмет у руку!"));
                                        return 0;
                                    }

                                    BlockPos playerPos = p.blockPosition();
                                    MarketLinkBlockEntity foundBE = null;
                                    for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-5, -5, -5), playerPos.offset(5, 5, 5))) {
                                        BlockEntity be = p.level().getBlockEntity(pos);
                                        if (be instanceof MarketLinkBlockEntity marketBE) {
                                            foundBE = marketBE;
                                            break;
                                        }
                                    }

                                    if (foundBE == null) {
                                        c.getSource().sendFailure(Component.literal("§cПоряд не знайдено Market Link Block!"));
                                        return 0;
                                    }

                                    UUID playerIsland = NestworldModsServer.ISLAND_PROVIDER.getCachedTeamId(p.getUUID());
                                    if (playerIsland == null || !playerIsland.equals(foundBE.getIslandId())) {
                                        c.getSource().sendFailure(Component.literal("§cВи можете налаштовувати ціни тільки на власному Market Link!"));
                                        return 0;
                                    }

                                    MarketData data = MarketData.get(p.level());
                                    Item item = stack.getItem();
                                    Map<Item, MarketData.Entry> islandStocks = data.stocks.computeIfAbsent(playerIsland, id -> new HashMap<>());
                                    MarketData.Entry entry = islandStocks.get(item);
                                    if (entry == null) {
                                        entry = new MarketData.Entry(1000, 1000, price);
                                        islandStocks.put(item, entry);
                                    } else {
                                        entry.base = price;
                                    }
                                    data.setDirty();

                                    c.getSource().sendSuccess(() -> Component.literal("§aЦіна для " + item.getDescriptionId() + " встановлена: §e" + price), true);
                                    return 1;
                                })
                        )
                )
        );

        dispatcher.register(Commands.literal("mkt")
                        .then(Commands.literal("buy")
                                .then(Commands.argument("item", ItemArgument.item(context))
                                        .then(Commands.argument("qty", IntegerArgumentType.integer(1, 64))
                                                .executes(c -> {
                                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                                    Item targetItem = ItemArgument.getItem(c, "item").getItem();
                                                    int qty = IntegerArgumentType.getInteger(c, "qty");

                                                    var market = MarketData.get(c.getSource().getLevel());
                                                    PlotData.Plot plot = getPlotAtPos(p.blockPosition(), p.level());
                                                    if (plot == null) {
                                                        c.getSource().sendFailure(Component.literal("§cВи не перебуваєте в жодному магазині!"));
                                                        return 0;
                                                    }

                                                    if (plot.expiration < System.currentTimeMillis()) {
                                                        c.getSource().sendFailure(Component.literal("§cЦей магазин тимчасово заморожений (закінчився термін оренди)."));
                                                        return 0;
                                                    }
                                                    UUID islandId = plot.ownerTeam;

                                                    Map<Item, MarketData.Entry> islandStocks = market.stocks.get(islandId);
                                                    if (islandStocks == null || !islandStocks.containsKey(targetItem)) {
                                                        final double defaultTotalCost = 10.0 * qty;
                                                        AzuriomClient.syncPlayer(p.getGameProfile().getName(), p.getUUID()).thenAccept(info -> {
                                                            if (info == null) {
                                                                c.getSource().getServer().execute(() -> c.getSource().sendFailure(Component.literal("§cПомилка: Сайт Azuriom недоступний!")));
                                                                return;
                                                            }

                                                            if (info.money() >= defaultTotalCost) {
                                                                AzuriomClient.updateMoney(info.id(), "remove", defaultTotalCost).thenAccept(success -> {
                                                                    if (success) {
                                                                        c.getSource().getServer().execute(() -> {
                                                                            p.addItem(new ItemStack(targetItem, qty));
                                                                            market.setDirty();
                                                                            c.getSource().sendSuccess(() -> Component.literal("§aКуплено! З балансу знято: §e" + String.format("%.2f", defaultTotalCost) + " ₴"), true);

                                                                            if (RealMarket.wsClient != null && RealMarket.wsClient.isOpen()) {
                                                                                JsonObject debtJson = new JsonObject();
                                                                                debtJson.addProperty("action", "market_debt_create");
                                                                            debtJson.addProperty("seller_island_id", islandId.toString());
                                                                                debtJson.addProperty("item_id", ForgeRegistries.ITEMS.getKey(targetItem).toString());
                                                                                debtJson.addProperty("amount", qty);
                                                                                RealMarket.wsClient.send(debtJson.toString());
                                                                            }
                                                                        });
                                                                    } else {
                                                                        c.getSource().getServer().execute(() -> p.sendSystemMessage(Component.literal("§cПомилка транзакції на сайті!")));
                                                                    }
                                                                });
                                                            } else {
                                                                c.getSource().getServer().execute(() -> c.getSource().sendFailure(Component.literal("§cНедостатньо коштів! Треба: " + String.format("%.2f", defaultTotalCost))));
                                                            }
                                                        });
                                                        return 1;
                                                    }

                                                    MarketData.Entry node = islandStocks.get(targetItem);
                                                    final double finalTotalCost = node.price(true) * qty;

                                                    AzuriomClient.syncPlayer(p.getGameProfile().getName(), p.getUUID()).thenAccept(info -> {
                                                        if (info == null) {
                                                            c.getSource().getServer().execute(() -> c.getSource().sendFailure(Component.literal("§cПомилка: Сайт Azuriom недоступний!")));
                                                            return;
                                                        }

                                                        if (info.money() >= finalTotalCost) {
                                                            AzuriomClient.updateMoney(info.id(), "remove", finalTotalCost).thenAccept(success -> {
                                                                if (success) {
                                                                    c.getSource().getServer().execute(() -> {
                                                                        p.addItem(new ItemStack(targetItem, qty));
                                                                        node.stock -= qty;
                                                                        market.setDirty();
                                                                        c.getSource().sendSuccess(() -> Component.literal("§aКуплено! З балансу знято: §e" + String.format("%.2f", finalTotalCost) + " ₴"), true);

                                                                        if (RealMarket.wsClient != null && RealMarket.wsClient.isOpen()) {
                                                                            JsonObject debtJson = new JsonObject();
                                                                            debtJson.addProperty("action", "market_debt_create");
                                                                            debtJson.addProperty("seller_island_id", islandId.toString());
                                                                            debtJson.addProperty("item_id", ForgeRegistries.ITEMS.getKey(targetItem).toString());
                                                                            debtJson.addProperty("amount", qty);
                                                                            RealMarket.wsClient.send(debtJson.toString());
                                                                        }
                                                                    });
                                                                } else {
                                                                    c.getSource().getServer().execute(() -> p.sendSystemMessage(Component.literal("§cПомилка транзакції на сайті!")));
                                                                }
                                                            });
                                                        } else {
                                                            c.getSource().getServer().execute(() -> c.getSource().sendFailure(Component.literal("§cНедостатньо коштів! Треба: " + String.format("%.2f", finalTotalCost))));
                                                        }
                                                    });
                                                    return 1;
                                                }))))

                .then(Commands.literal("sell")
                        .then(Commands.argument("qty", IntegerArgumentType.integer(1, 2304))
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    int qty = IntegerArgumentType.getInteger(c, "qty");
                                    ItemStack stackInHand = p.getMainHandItem();

                                    if (stackInHand.isEmpty()) {
                                        c.getSource().sendFailure(Component.literal("§cВізьміть предмет для продажу у руку!"));
                                        return 0;
                                    }

                                    Item itemToSell = stackInHand.getItem();
                                    int hasCount = p.getInventory().items.stream()
                                            .filter(s -> s.is(itemToSell))
                                            .mapToInt(ItemStack::getCount).sum();

                                    if (hasCount < qty) {
                                        c.getSource().sendFailure(Component.literal("§cУ вас немає стільки предметів!"));
                                        return 0;
                                    }

                                    var market = MarketData.get(c.getSource().getLevel());
                                    PlotData.Plot plot = getPlotAtPos(p.blockPosition(), p.level());
                                    if (plot == null) {
                                        c.getSource().sendFailure(Component.literal("§cВи не перебуваєте в жодному магазині!"));
                                        return 0;
                                    }

                                    if (plot.expiration < System.currentTimeMillis()) {
                                        c.getSource().sendFailure(Component.literal("§cЦей магазин тимчасово заморожений (закінчився термін оренди)."));
                                        return 0;
                                    }
                                    UUID islandId = plot.ownerTeam;

                                    Map<Item, MarketData.Entry> islandStocks = market.stocks.get(islandId);
                                    if (islandStocks == null || !islandStocks.containsKey(itemToSell)) {
                                        c.getSource().sendFailure(Component.literal("§cЦей магазин не приймає цей предмет!"));
                                        return 0;
                                    }

                                    MarketData.Entry node = islandStocks.get(itemToSell);
                                    final double finalProfit = node.price(false) * qty;

                                    AzuriomClient.syncPlayer(p.getGameProfile().getName(), p.getUUID()).thenAccept(info -> {
                                        if (info != null) {
                                            AzuriomClient.updateMoney(info.id(), "add", finalProfit).thenAccept(success -> {
                                                if (success) {
                                                    c.getSource().getServer().execute(() -> {
                                                        p.getInventory().clearOrCountMatchingItems(s -> s.is(itemToSell), qty, p.inventoryMenu.getCraftSlots());
                                                        node.stock += qty;
                                                        market.setDirty();
                                                        c.getSource().sendSuccess(() -> Component.literal("§6Продано! На баланс нараховано: §e" + String.format("%.2f", finalProfit) + " ₴"), true);
                                                    });
                                                }
                                            });
                                        } else {
                                            c.getSource().getServer().execute(() -> c.getSource().sendFailure(Component.literal("§cПомилка зв'язку з сайтом!")));
                                        }
                                    });
                                    return 1;
                                })))

                .then(Commands.literal("bal").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    AzuriomClient.syncPlayer(p.getGameProfile().getName(), p.getUUID()).thenAccept(info -> {
                        c.getSource().getServer().execute(() -> {
                            if (info != null) {
                                c.getSource().sendSuccess(() -> Component.literal("§6Ваш баланс на сайті: §e" + String.format("%.2f", info.money()) + " ₴"), true);
                            } else {
                                c.getSource().sendFailure(Component.literal("§cНе вдалося отримати дані з сайту."));
                            }
                        });
                    });
                    return 1;
                }))
        );
    }
}
