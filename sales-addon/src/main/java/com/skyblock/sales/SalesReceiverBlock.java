package com.skyblock.sales;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

public class SalesReceiverBlock extends Block implements EntityBlock {

    public SalesReceiverBlock() {
        // A visual block for Spawn that acts as the receiver/drive emulator
        super(Properties.of().mapColor(MapColor.COLOR_BLUE).strength(2.0f));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SalesReceiverBlockEntity(pos, state);
    }
}
