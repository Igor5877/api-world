package RealMarket.realmarket.block;

import RealMarket.realmarket.api.MarketIslandApi;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity.BlockMode;
import appeng.api.implementations.items.IMemoryCard;
import appeng.api.implementations.items.MemoryCardMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class MarketLinkBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public MarketLinkBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext c) {
        return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState rotate(BlockState s, Rotation r) {
        return s.setValue(FACING, r.rotate(s.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState s, Mirror m) {
        return s.rotate(m.getRotation(s.getValue(FACING)));
    }

    @Nullable @Override
    public BlockEntity newBlockEntity(@Nonnull BlockPos p, @Nonnull BlockState s) {
        return new MarketLinkBlockEntity(p, s);
    }

    @Override
    public void setPlacedBy(@Nonnull Level world, @Nonnull BlockPos pos, @Nonnull BlockState s, @Nullable LivingEntity placer, @Nonnull ItemStack stack) {
        super.setPlacedBy(world, pos, s, placer, stack);
        if (!world.isClientSide && placer instanceof Player p && world.getBlockEntity(pos) instanceof MarketLinkBlockEntity be) {
            UUID island = MarketIslandApi.getIslandUuid(p.getUUID());
            if (island != null) {
                be.setMode(BlockMode.SOURCE);
                be.setSourceIslandUuid(island);
                p.displayClientMessage(Component.literal("§a[MarketLink] §fРежим: §6SOURCE §f| Острів: §b" + island), true);
            } else {
                be.setMode(BlockMode.SINK);
                p.displayClientMessage(Component.literal("§a[MarketLink] §fРежим: §9SINK §f| Використай Memory Card"), true);
            }
        }
    }

    @Override @Nonnull
    public InteractionResult use(BlockState s, Level world, BlockPos pos, Player p, InteractionHand h, BlockHitResult hit) {
        if (world.isClientSide) return InteractionResult.SUCCESS;
        if (!(p instanceof ServerPlayer sp) || !(world.getBlockEntity(pos) instanceof MarketLinkBlockEntity be)) return InteractionResult.PASS;

        ItemStack held = p.getItemInHand(h);
        if (held.getItem() instanceof IMemoryCard mc) {
            if (be.getMode() == BlockMode.SOURCE) {
                UUID uuid = be.getSourceIslandUuid();
                if (uuid == null) {
                    p.displayClientMessage(Component.literal("§c[MarketLink] UUID не встановлено!"), true);
                    return InteractionResult.SUCCESS;
                }
                CompoundTag tag = new CompoundTag();
                tag.putUUID("island_uuid", uuid);
                mc.setMemoryCardContents(held, "realmarket:market_link", tag);
                mc.notifyUser(p, MemoryCardMessages.SETTINGS_SAVED);
            } else {
                CompoundTag tag = mc.getData(held);
                if (tag != null && tag.hasUUID("island_uuid")) {
                    UUID linked = tag.getUUID("island_uuid");
                    be.setLinkedIslandUuid(linked);
                    mc.notifyUser(p, MemoryCardMessages.SETTINGS_LOADED);
                    p.displayClientMessage(Component.literal("§a[MarketLink] §fЗв'язано: §b" + linked), true);
                } else {
                    mc.notifyUser(p, MemoryCardMessages.INVALID_MACHINE);
                    p.displayClientMessage(Component.literal("§c[MarketLink] Карта порожня!"), true);
                }
            }
            return InteractionResult.SUCCESS;
        }

        if (held.isEmpty()) {
            String m = be.getMode() == BlockMode.SOURCE ? "§6SOURCE" : "§9SINK";
            UUID active = be.getActiveIslandUuid();
            p.displayClientMessage(Component.literal("§a[MarketLink] §fРежим: " + m + " §f| UUID: §b" + (active != null ? active : "§cнемає")), true);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}