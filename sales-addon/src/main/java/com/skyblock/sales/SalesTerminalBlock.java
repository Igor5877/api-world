package com.skyblock.sales;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

public class SalesTerminalBlock extends Block implements EntityBlock {

    public SalesTerminalBlock() {
        super(Properties.of().mapColor(MapColor.METAL).strength(2.0f));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SalesTerminalBlockEntity(pos, state);
    }
}
