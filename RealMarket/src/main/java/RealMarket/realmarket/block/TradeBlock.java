package RealMarket.realmarket.block;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.client.TradeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class TradeBlock extends Block {
    public TradeBlock(Properties p) { super(p); }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide) {
            int id = AzuriomClient.getPlayerId(player.getUUID());
            if (id == -1) {
                player.sendSystemMessage(Component.literal("§cСинхронізація... зачекайте."));
                return InteractionResult.SUCCESS;
            }

            // Викликаємо баланс асинхронно
            AzuriomClient.getBalAsync(id).thenAccept(bal -> {
                // Повертаємося в головний потік Minecraft для відкриття Screen
                Minecraft.getInstance().tell(() -> {
                    Minecraft.getInstance().setScreen(new TradeScreen(bal));
                });
            });
        }
        return InteractionResult.SUCCESS;
    }
}