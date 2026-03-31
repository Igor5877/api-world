package RealMarket.realmarket.block;

import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public class MarketLinkBlock extends Block implements EntityBlock {

    public MarketLinkBlock(Properties p) {
        super(p);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarketLinkBlockEntity(pos, state);
    }
}
