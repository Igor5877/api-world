package RealMarket.realmarket.commands;

import com.mojang.brigadier.CommandDispatcher;
import RealMarket.realmarket.RealMarket;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public class ModCommands {
    public static void register(CommandDispatcher<CommandSourceStack> disp) {

        // --- ГРУПА КОМАНД /market ---
        disp.register(Commands.literal("market")
                // Команда видачі блоку (Тільки для Адмінів / ОП)
                .then(Commands.literal("getblock")
                        .requires(s -> s.hasPermission(2)) // Перевірка на OP статус
                        .executes(c -> {
                            ServerPlayer p = c.getSource().getPlayerOrException();

                            // Створюємо стак предметів на основі нашого зареєстрованого блоку
                            ItemStack tradeStack = new ItemStack(RealMarket.TRADE_ITEM.get());

                            // Додаємо в інвентар. Якщо інвентар повний - викидаємо на землю
                            if (!p.getInventory().add(tradeStack)) {
                                p.drop(tradeStack, false);
                            }

                            p.sendSystemMessage(Component.literal("§b[Market] §fВам видано §eТорговий Термінал§f."));
                            return 1;
                        }))
        );

        // --- ГРУПА КОМАНД /island ---
        disp.register(Commands.literal("island")
                // Створення острова
                .then(Commands.literal("create").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();

                    // Викликаємо метод генерації острова з нашого IslandManager
                    IslandManager.createIsland(p.serverLevel(), p.getId());

                    p.sendSystemMessage(Component.literal("§a[Island] §fВаш торговий острів успішно сформовано!"));
                    return 1;
                }))
                // Візит на острів іншого гравця
                .then(Commands.literal("visit")
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(c -> {
                                    ServerPlayer p = c.getSource().getPlayerOrException();
                                    ServerPlayer target = EntityArgument.getPlayer(c, "target");

                                    // Отримуємо координати острова гравця-цілі
                                    var pos = IslandManager.getIslandCoords(target.getId());

                                    // Телепортація гравця
                                    p.teleportTo(pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5);

                                    p.sendSystemMessage(Component.literal("§e[Travel] §fВи прибули на острів гравця §b" + target.getScoreboardName()));
                                    return 1;
                                })))
        );
    }
}