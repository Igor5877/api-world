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
            double price = IslandManager.PRICES.getOrDefault(player.getUUID(), 10.0);

            // On client, we don't know the exact balance yet, so we can display a default or loading value.
            // The actual check happens on the server during the trade packet processing.
            RealMarket.realmarket.client.ClientHooks.openTradeScreen(0.0, price);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }
}