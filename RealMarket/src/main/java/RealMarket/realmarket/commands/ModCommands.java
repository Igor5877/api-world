package RealMarket.realmarket.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import RealMarket.realmarket.RealMarket;
import RealMarket.realmarket.api.MarketSyncManager;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity.BlockMode;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.UUID;

public class ModCommands {
    public static void register(CommandDispatcher<CommandSourceStack> disp) {

        // --- /market ---
        disp.register(Commands.literal("market")
                .then(Commands.literal("getblock")
                        .requires(s -> s.hasPermission(2))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            ItemStack tradeStack = new ItemStack(RealMarket.TRADE_ITEM.get());
                            if (!p.getInventory().add(tradeStack)) p.drop(tradeStack, false);
                            p.sendSystemMessage(Component.literal("§b[Market] §fВам видано §eТорговий Термінал§f."));
                            return 1;
                        }))

                .then(Commands.literal("getlink")
                        .requires(s -> s.hasPermission(2))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            ItemStack linkStack = new ItemStack(RealMarket.MARKET_LINK_ITEM.get());
                            if (!p.getInventory().add(linkStack)) p.drop(linkStack, false);
                            p.sendSystemMessage(Component.literal("§b[Market] §fВам видано §eMarket Link блок§f."));
                            return 1;
                        }))
                .then(Commands.literal("getcable")
                        .requires(s -> s.hasPermission(2))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            ItemStack cableStack = new ItemStack(RealMarket.MARKET_CABLE_ITEM.get());
                            if (!p.getInventory().add(cableStack)) p.drop(cableStack, false);
                            p.sendSystemMessage(Component.literal("§b[Market] §fВам видано §eMarket Cable§f."));
                            return 1;
                        }))

                // --- /market debug --- (тільки для ОП)
                .then(Commands.literal("debug")
                        .requires(s -> s.hasPermission(2))

                        // /market debug source <x> <y> <z> <island_uuid>
                        // Встановлює блок в SOURCE режим з UUID острова
                        .then(Commands.literal("source")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("uuid", StringArgumentType.word())
                                                .executes(c -> {
                                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                                    BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
                                                    String uuidStr = StringArgumentType.getString(c, "uuid");

                                                    UUID uuid;
                                                    try { uuid = UUID.fromString(uuidStr); }
                                                    catch (Exception e) {
                                                        p.sendSystemMessage(Component.literal("§c[Market] Невалідний UUID: " + uuidStr));
                                                        return 0;
                                                    }

                                                    BlockEntity be = p.serverLevel().getBlockEntity(pos);
                                                    if (!(be instanceof MarketLinkBlockEntity link)) {
                                                        p.sendSystemMessage(Component.literal("§c[Market] Немає MarketLinkBlock на " + pos.toShortString()));
                                                        return 0;
                                                    }

                                                    link.setMode(BlockMode.SOURCE);
                                                    link.setSourceIslandUuid(uuid); // також викликає MarketSyncManager.init()
                                                    p.sendSystemMessage(Component.literal(
                                                            "§a[Market] §fБлок на §e" + pos.toShortString() +
                                                            " §f→ §6SOURCE §f| UUID: §b" + uuid));
                                                    return 1;
                                                }))))

                        // /market debug sink <x> <y> <z> <island_uuid>
                        // Встановлює блок в SINK режим, лінкує до UUID острова
                        .then(Commands.literal("sink")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .then(Commands.argument("uuid", StringArgumentType.word())
                                                .executes(c -> {
                                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                                    BlockPos pos = BlockPosArgument.getBlockPos(c, "pos");
                                                    String uuidStr = StringArgumentType.getString(c, "uuid");

                                                    UUID uuid;
                                                    try { uuid = UUID.fromString(uuidStr); }
                                                    catch (Exception e) {
                                                        p.sendSystemMessage(Component.literal("§c[Market] Невалідний UUID: " + uuidStr));
                                                        return 0;
                                                    }

                                                    BlockEntity be = p.serverLevel().getBlockEntity(pos);
                                                    if (!(be instanceof MarketLinkBlockEntity link)) {
                                                        p.sendSystemMessage(Component.literal("§c[Market] Немає MarketLinkBlock на " + pos.toShortString()));
                                                        return 0;
                                                    }

                                                    link.setMode(BlockMode.SINK);
                                                    link.setLinkedIslandUuid(uuid);
                                                    p.sendSystemMessage(Component.literal(
                                                            "§a[Market] §fБлок на §e" + pos.toShortString() +
                                                            " §f→ §9SINK §f| Лінк: §b" + uuid));
                                                    return 1;
                                                }))))

                        // /market debug sync
                        // Примусово запускає синхронізацію прямо зараз
                        .then(Commands.literal("sync")
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    MarketSyncManager.triggerSync();
                                    p.sendSystemMessage(Component.literal("§a[Market] §fСинхронізацію запущено."));
                                    return 1;
                                }))

                        // /market debug status
                        // Показує стан всіх активних MarketLink блоків
                        .then(Commands.literal("status")
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    var links = RealMarket.getActiveMarketLinks();
                                    if (links.isEmpty()) {
                                        p.sendSystemMessage(Component.literal("§e[Market] §fАктивних MarketLink блоків немає."));
                                        return 1;
                                    }
                                    p.sendSystemMessage(Component.literal("§a[Market] §fАктивні блоки (" + links.size() + "):"));
                                    for (var link : links) {
                                        String mode = link.getMode() == BlockMode.SOURCE ? "§6SOURCE" : "§9SINK";
                                        UUID uuid = link.getActiveIslandUuid();
                                        String uuidStr = uuid != null ? "§b" + uuid : "§cне встановлено";
                                        BlockPos pos = link.getBlockPos();
                                        p.sendSystemMessage(Component.literal(
                                                "  §7" + pos.toShortString() + " §f→ " + mode + " §f| " + uuidStr));
                                    }
                                    return 1;
                                }))
                )
        );

        // --- /island ---
        disp.register(Commands.literal("island")
                // /island create — створює платформу для себе
                .then(Commands.literal("create")
                        .requires(s -> s.hasPermission(2))
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();
                            IslandManager.createIsland(p.serverLevel(), p.getUUID());
                            p.sendSystemMessage(Component.literal("§a[Island] §fПлатформу успішно сформовано!"));
                            return 1;
                        })
                        // /island create <player> — адмін створює для іншого гравця
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(c, "target");
                                    IslandManager.createIsland(p.serverLevel(), target.getUUID());
                                    p.sendSystemMessage(Component.literal("§a[Island] §fПлатформу створено для §b" + target.getScoreboardName()));
                                    return 1;
                                })))

                // /island visit <player> — телепорт на платформу гравця
                .then(Commands.literal("visit")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(c, "target");
                                    var pos = IslandManager.getIslandCoords(target.getUUID());
                                    p.teleportTo(pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5);
                                    p.sendSystemMessage(Component.literal("§e[Island] §fВи на платформі §b" + target.getScoreboardName()));
                                    return 1;
                                })))

                // /island suspend <player> — зберігає і видаляє платформу (блокування/завершення підписки)
                .then(Commands.literal("suspend")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(c, "target");
                                    IslandManager.suspendPlatform(p.serverLevel(), target.getUUID());
                                    p.sendSystemMessage(Component.literal("§e[Island] §fПлатформу §b" + target.getScoreboardName() + " §fзбережено та видалено."));
                                    return 1;
                                })))

                // /island restore <player> — відновлює платформу зі збереженого файлу
                .then(Commands.literal("restore")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(c, "target");
                                    boolean ok = IslandManager.restorePlatform(p.serverLevel(), target.getUUID());
                                    if (ok) {
                                        p.sendSystemMessage(Component.literal("§a[Island] §fПлатформу §b" + target.getScoreboardName() + " §fвідновлено!"));
                                    } else {
                                        p.sendSystemMessage(Component.literal("§c[Island] §fНемає збереженої платформи для §b" + target.getScoreboardName()));
                                    }
                                    return ok ? 1 : 0;
                                })))

                // /island delete <player> — остаточно видаляє збережені дані (після 30 днів)
                .then(Commands.literal("delete")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(c, "target");
                                    boolean ok = IslandManager.deleteSavedPlatform(target.getUUID());
                                    p.sendSystemMessage(ok
                                            ? Component.literal("§a[Island] §fДані платформи §b" + target.getScoreboardName() + " §fвидалено.")
                                            : Component.literal("§c[Island] §fНемає збережених даних для §b" + target.getScoreboardName()));
                                    return ok ? 1 : 0;
                                })))
        );
    }
}
