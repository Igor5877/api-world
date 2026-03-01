package com.skyblock.sales;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class SalesVendingBlock extends Block implements EntityBlock {

    public SalesVendingBlock() {
        // A physical "Showcase" block that players interact with to buy items
        super(Properties.of().mapColor(MapColor.GOLD).strength(2.0f));
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof SalesVendingBlockEntity vending) {
                // For MVP: Right-clicking triggers a purchase of 1 item configured in the vending block
                vending.triggerPurchase(player);
            }
        }
        return InteractionResult.SUCCESS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SalesVendingBlockEntity(pos, state);
    }
}
