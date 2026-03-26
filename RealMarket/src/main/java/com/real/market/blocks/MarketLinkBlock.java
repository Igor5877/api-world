package com.real.market.blocks;

import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class MarketLinkBlock extends Block implements EntityBlock {
    public MarketLinkBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarketLinkBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide && placer instanceof Player player) {
            UUID islandId = NestworldModsServer.ISLAND_PROVIDER.getCachedTeamId(player.getUUID());
            String currentIslandOwner = NestworldModsServer.ISLAND_PROVIDER.getCurrentServerOwnerUuid();
            
            boolean isOwner = false;
            if (currentIslandOwner != null && islandId != null) {
                isOwner = islandId.toString().equals(currentIslandOwner);
            }

            if (!isOwner) {
                player.sendSystemMessage(Component.literal("§cВи можете ставити цей блок тільки на своєму острові!"));
                level.destroyBlock(pos, true);
                return;
            }

            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MarketLinkBlockEntity marketBE) {
                marketBE.setIslandId(islandId);
            }
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            player.sendSystemMessage(Component.literal("§aMarket Link Block активний. Використовуйте /market setprice <ціна> тримаючи предмет у руці."));
        }
        return InteractionResult.SUCCESS;
    }
}
