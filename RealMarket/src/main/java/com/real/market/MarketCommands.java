package com.real.market;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.real.market.blocks.MarketLinkBlockEntity;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

public class MarketCommands {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
        dispatcher.register(Commands.literal("market")
                .then(Commands.literal("visit")
                        .then(Commands.argument("island", StringArgumentType.string())
                                .executes(c -> {
                                    Player p = c.getSource().getPlayerOrException();
                                    String islandName = StringArgumentType.getString(c, "island");
                                    PlotData data = PlotData.get(p.level());
                                    PlotData.Plot plot = data.plots.get(islandName);

                                    if (plot == null) {
                                        p.sendSystemMessage(Component.literal("§cМагазин не знайдено!"));
                                        return 0;
                                    }

                                    p.teleportTo((plot.x1 + plot.x2) / 2.0, p.getY(), (plot.z1 + plot.z2) / 2.0);
                                    p.sendSystemMessage(Component.literal("§aТелепортовано до магазину: " + islandName));
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
                                                    Player p = c.getSource().getPlayerOrException();
                                                    String name = StringArgumentType.getString(c, "name");
                                                    UUID owner = UUID.fromString(StringArgumentType.getString(c, "owner_team_uuid"));
                                                    BlockPos pos = p.blockPosition();

                                                    PlotData data = PlotData.get(p.level());
                                                    int x1 = pos.getX() - 10, z1 = pos.getZ() - 10,
                                                        x2 = pos.getX() + 10, z2 = pos.getZ() + 10;
                                                    data.plots.put(name, new PlotData.Plot(
                                                            name, owner,
                                                            System.currentTimeMillis() + 2592000000L,
                                                            x1, z1, x2, z2));
                                                    data.setDirty();

                                                    // getPersonalData(UUID) — існує в ClaimedChunkManager 2001.3.1
                                                    ChunkTeamData teamData = FTBChunksAPI.api()
                                                            .getManager()
                                                            .getPersonalData(owner);

                                                    if (teamData != null) {
                                                        for (int x = x1 >> 4; x <= x2 >> 4; x++) {
                                                            for (int z = z1 >> 4; z <= z2 >> 4; z++) {
                                                                ChunkDimPos dimPos = new ChunkDimPos(
                                                                        p.level().dimension(), x, z);
                                                                ClaimedChunk chunk = FTBChunksAPI.api()
                                                                        .getManager()
                                                                        .getChunk(dimPos);
                                                                if (chunk == null) {
                                                                    // claim(CommandSourceStack, ChunkDimPos, boolean)
                                                                    // boolean = forceLoad (false = звичайний клейм)
                                                                    teamData.claim(c.getSource(), dimPos, false);
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        p.sendSystemMessage(Component.literal(
                                                                "§eУвага: не знайдено дані команди для UUID " + owner
                                                                + ". Чанки не заклеймовано."));
                                                    }

                                                    p.sendSystemMessage(Component.literal("§aПлот створено!"));
                                                    return 1;
                                                })
                                        )
                                )
                        )
                )
                .then(Commands.literal("setprice")
                        .then(Commands.argument("price", DoubleArgumentType.doubleArg(0.0))
                                .executes(c -> {
                                    Player p = c.getSource().getPlayerOrException();
                                    double price = DoubleArgumentType.getDouble(c, "price");
                                    ItemStack stack = p.getMainHandItem();

                                    if (stack.isEmpty()) {
                                        p.sendSystemMessage(Component.literal("§cВізьміть предмет у руку!"));
                                        return 0;
                                    }

                                    BlockPos playerPos = p.blockPosition();
                                    MarketLinkBlockEntity foundBE = null;
                                    for (BlockPos bPos : BlockPos.betweenClosed(
                                            playerPos.offset(-5, -5, -5),
                                            playerPos.offset(5, 5, 5))) {
                                        BlockEntity be = p.level().getBlockEntity(bPos);
                                        if (be instanceof MarketLinkBlockEntity marketBE) {
                                            foundBE = marketBE;
                                            break;
                                        }
                                    }

                                    if (foundBE == null) {
                                        p.sendSystemMessage(Component.literal("§cПоряд не знайдено Market Link Block!"));
                                        return 0;
                                    }

                                    MarketData data = MarketData.get(p.level());
                                    Item item = stack.getItem();
                                    MarketData.Entry entry = data.stocks.get(item);
                                    if (entry == null) {
                                        entry = new MarketData.Entry(0, 100, price);
                                        data.stocks.put(item, entry);
                                    } else {
                                        entry.base = price;
                                    }
                                    data.setDirty();

                                    p.sendSystemMessage(Component.literal(
                                            "§aЦіна для " + item.getDescriptionId() + " встановлена: §e" + price));
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
                                            Player p = c.getSource().getPlayerOrException();
                                            Item targetItem = ItemArgument.getItem(c, "item").getItem();
                                            int qty = IntegerArgumentType.getInteger(c, "qty");

                                            var market = MarketData.get(c.getSource().getLevel());
                                            var node = market.stocks.computeIfAbsent(
                                                    targetItem,
                                                    i -> new MarketData.Entry(1000, 1000, 10.0));
                                            double totalCost = node.price(true) * qty;

                                            AzuriomClient.syncPlayer(
                                                    p.getGameProfile().getName(), p.getUUID()
                                            ).thenAccept(info -> {
                                                if (info == null) {
                                                    c.getSource().getServer().execute(() ->
                                                            p.sendSystemMessage(Component.literal(
                                                                    "§cПомилка: Сайт Azuriom недоступний!")));
                                                    return;
                                                }

                                                if (info.money() >= totalCost) {
                                                    AzuriomClient.updateMoney(info.id(), "remove", totalCost)
                                                            .thenAccept(success -> {
                                                                if (success) {
                                                                    c.getSource().getServer().execute(() -> {
                                                                        p.addItem(new ItemStack(targetItem, qty));
                                                                        node.stock -= qty;
                                                                        market.setDirty();
                                                                        p.sendSystemMessage(Component.literal(
                                                                                "§aКуплено! З балансу знято: §e"
                                                                                + String.format("%.2f", totalCost) + " ₴"));

                                                                        if (RealMarket.wsClient != null
                                                                                && RealMarket.wsClient.isOpen()) {
                                                                            JsonObject debtJson = new JsonObject();
                                                                            debtJson.addProperty("action", "market_debt_create");
                                                                            debtJson.addProperty("item_id",
                                                                                    ForgeRegistries.ITEMS
                                                                                            .getKey(targetItem)
                                                                                            .toString());
                                                                            debtJson.addProperty("amount", qty);
                                                                            RealMarket.wsClient.send(debtJson.toString());
                                                                        }
                                                                    });
                                                                } else {
                                                                    c.getSource().getServer().execute(() ->
                                                                            p.sendSystemMessage(Component.literal(
                                                                                    "§cПомилка транзакції на сайті!")));
                                                                }
                                                            });
                                                } else {
                                                    c.getSource().getServer().execute(() ->
                                                            p.sendSystemMessage(Component.literal(
                                                                    "§cНедостатньо коштів! Треба: "
                                                                    + String.format("%.2f", totalCost))));
                                                }
                                            });
                                            return 1;
                                        }))))
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
                                    var node = market.stocks.computeIfAbsent(
                                            itemToSell,
                                            i -> new MarketData.Entry(1000, 1000, 10.0));
                                    double profit = node.price(false) * qty;

                                    AzuriomClient.syncPlayer(
                                            p.getGameProfile().getName(), p.getUUID()
                                    ).thenAccept(info -> {
                                        if (info != null) {
                                            AzuriomClient.updateMoney(info.id(), "add", profit)
                                                    .thenAccept(success -> {
                                                        if (success) {
                                                            c.getSource().getServer().execute(() -> {
                                                                p.getInventory().clearOrCountMatchingItems(
                                                                        s -> s.is(itemToSell), qty,
                                                                        p.inventoryMenu.getCraftSlots());
                                                                node.stock += qty;
                                                                market.setDirty();
                                                                p.sendSystemMessage(Component.literal(
                                                                        "§6Продано! На баланс нараховано: §e"
                                                                        + String.format("%.2f", profit) + " ₴"));
                                                            });
                                                        }
                                                    });
                                        } else {
                                            c.getSource().getServer().execute(() ->
                                                    p.sendSystemMessage(Component.literal(
                                                            "§cПомилка зв'язку з сайтом!")));
                                        }
                                    });
                                    return 1;
                                })))
                .then(Commands.literal("bal").executes(c -> {
                    Player p = c.getSource().getPlayerOrException();
                    AzuriomClient.syncPlayer(
                            p.getGameProfile().getName(), p.getUUID()
                    ).thenAccept(info -> {
                        c.getSource().getServer().execute(() -> {
                            if (info != null) {
                                p.sendSystemMessage(Component.literal(
                                        "§6Ваш баланс на сайті: §e"
                                        + String.format("%.2f", info.money()) + " ₴"));
                            } else {
                                p.sendSystemMessage(Component.literal(
                                        "§cНе вдалося отримати дані з сайту."));
                            }
                        });
                    });
                    return 1;
                }))
        );
    }
}