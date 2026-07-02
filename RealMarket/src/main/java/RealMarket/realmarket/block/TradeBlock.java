package RealMarket.realmarket.block;

import RealMarket.realmarket.api.*;
import RealMarket.realmarket.blockentity.*;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity.BlockMode;
import RealMarket.realmarket.network.*;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import javax.annotation.Nonnull;
import java.util.*;
import java.util.stream.Collectors;

public class TradeBlock extends Block implements EntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    protected static final VoxelShape SHAPE = box(0, 0, 0, 16, 14.2, 16);

    public TradeBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING, c.getHorizontalDirection().getOpposite()); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING); }
    @Nonnull @Override public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) { return SHAPE; }
    @Override public BlockEntity newBlockEntity(BlockPos p, BlockState s) { return null; }

    @Override @Nonnull
    public InteractionResult use(BlockState s, Level world, BlockPos pos, Player p, InteractionHand h, BlockHitResult hit) {
        if (world.isClientSide || !(p instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;

        int id = AzuriomClient.getPlayerId(sp.getUUID());
        if (id == -1) { sp.displayClientMessage(Component.literal("§cСинхронізація..."), true); return InteractionResult.SUCCESS; }

        UUID island = findSinkInNetwork(world, pos);
        if (island == null) { sp.displayClientMessage(Component.literal("§c[Market] Не підключено до мережі SINK!"), true); return InteractionResult.SUCCESS; }

        List<PacketOpenTradeUI.ItemEntry> entries = MarketSyncManager.getCachedInventory(island).stream()
                .map(i -> new PacketOpenTradeUI.ItemEntry(i.itemId(), i.price(), i.quantity())).collect(Collectors.toList());

        if (entries.isEmpty()) { sp.displayClientMessage(Component.literal("§e[Market] Порожньо або очікування синхронізації..."), true); return InteractionResult.SUCCESS; }

        AzuriomClient.getBalanceAsync(id, bal -> sp.getServer().execute(() -> ModMessages.sendToPlayer(new PacketOpenTradeUI(bal, island, entries), sp)));
        return InteractionResult.SUCCESS;
    }

    private UUID findSinkInNetwork(Level world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos np = pos.relative(d);
            BlockEntity be = world.getBlockEntity(np);
            if (be instanceof MarketLinkBlockEntity l && l.getMode() == BlockMode.SINK) return l.getLinkedIslandUuid();
            if (be instanceof MarketCableBlockEntity) {
                for (MarketLinkBlockEntity l : LinkNetworkManager.getConnectedLinks(world, np)) {
                    if (l.getMode() == BlockMode.SINK && l.getLinkedIslandUuid() != null) return l.getLinkedIslandUuid();
                }
            }
        }
        return null;
    }
}