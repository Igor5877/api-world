package RealMarket.realmarket.blockentity;

import RealMarket.realmarket.RealMarket;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nonnull;

public class MarketCableBlockEntity extends BlockEntity {
    public MarketCableBlockEntity(@Nonnull BlockPos p, @Nonnull BlockState s) {
        super(RealMarket.MARKET_CABLE_BE.get(), p, s);
    }

    @Override
    public void onLoad() {
        super.onLoad();
    }
}