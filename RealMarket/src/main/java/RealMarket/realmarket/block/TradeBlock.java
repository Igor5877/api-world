package RealMarket.realmarket.block;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

public class TradeBlock extends Block {
    // Форма блоку
    protected static final VoxelShape SHAPE = Shapes.box(0.06D, 0.0D, 0.06D, 0.94D, 0.88D, 0.94D);

    public TradeBlock(Properties p) {
        super(p);
    }

    @SuppressWarnings("deprecation") // Прибирає попередження про застарілий метод Mojang
    @Override
    public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) {
        return SHAPE;
    }

    @SuppressWarnings("deprecation") // Прибирає попередження для методу use
    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide) {
            int id = AzuriomClient.getPlayerId(player.getUUID());

            // Отримуємо ціну острова (якщо вона є в пам'яті)
            double price = IslandManager.PRICES.getOrDefault(player.getUUID(), 10.0);

            if (id != -1) {
                // Асинхронний запит балансу через Azuriom
                AzuriomClient.getBalAsync(id).thenAccept(bal -> {
                    // Безпечний виклик клієнтського коду через DistExecutor (лише на клієнті)
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                        RealMarket.realmarket.client.ClientHooks.openTradeScreen(bal, price);
                    });
                });
            } else {
                // Швидке повідомлення над інвентарем, якщо ID ще не підтягнувся
                player.displayClientMessage(Component.literal("§cСинхронізація ID... Спробуйте ще раз"), true);
            }
        }
        return InteractionResult.SUCCESS;
    }
}