package RealMarket.realmarket.block;

import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.UUID;

import RealMarket.realmarket.api.MarketIslandApi;

public class MarketLinkBlock extends Block implements EntityBlock {

    public MarketLinkBlock(Properties p) {
        super(p);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarketLinkBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (!level.isClientSide && placer instanceof Player player) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof MarketLinkBlockEntity marketLinkBlockEntity) {
                // Get the island UUID via the bridged API
                UUID islandUuid = MarketIslandApi.getIslandUuid(player.getUUID());

                // If we don't have it, set it to the player's own UUID as a final fallback
                if (islandUuid == null) {
                     islandUuid = player.getUUID();
                }

                marketLinkBlockEntity.setIslandUuid(islandUuid);
            }
        }
    }
}
