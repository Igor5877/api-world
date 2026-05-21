package RealMarket.realmarket.block;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.api.LinkNetworkManager;
import RealMarket.realmarket.api.MarketSyncManager;
import RealMarket.realmarket.blockentity.MarketCableBlockEntity;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity.BlockMode;
import RealMarket.realmarket.network.ModMessages;
import RealMarket.realmarket.network.PacketOpenTradeUI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class TradeBlock extends Block {
    protected static final VoxelShape SHAPE = Shapes.box(0.06D, 0.0D, 0.06D, 0.94D, 0.88D, 0.94D);

    public TradeBlock(Properties p) {
        super(p);
    }

    @Override
    @SuppressWarnings("deprecation")
    @Nonnull
    public VoxelShape getShape(@Nonnull BlockState s, @Nonnull BlockGetter g, @Nonnull BlockPos p, @Nonnull CollisionContext c) {
        return SHAPE;
    }

    @Override
    @SuppressWarnings("deprecation")
    @Nonnull
    public InteractionResult use(@Nonnull BlockState state, @Nonnull Level world, @Nonnull BlockPos pos,
                                 @Nonnull Player player, @Nonnull InteractionHand hand, @Nonnull BlockHitResult hit) {
        if (!world.isClientSide && player instanceof ServerPlayer sp) {
            int id = AzuriomClient.getPlayerId(sp.getUUID());
            if (id == -1) {
                sp.displayClientMessage(Component.literal("§cСинхронізація ID... Спробуйте ще раз"), true);
                return InteractionResult.SUCCESS;
            }

            UUID linkedUuid = findLinkedSinkUuid(world, pos);
            if (linkedUuid == null) {
                sp.displayClientMessage(Component.literal("§c[Market] Немає підключеного SINK блоку поруч або в мережі!"), true);
                return InteractionResult.SUCCESS;
            }

            List<PacketOpenTradeUI.ItemEntry> entries = MarketSyncManager.getCachedInventory(linkedUuid)
                    .stream()
                    .map(i -> new PacketOpenTradeUI.ItemEntry(i.itemId(), i.price(), i.quantity()))
                    .collect(Collectors.toList());

            if (entries.isEmpty()) {
                sp.displayClientMessage(Component.literal("§e[Market] Інвентар порожній або ще не синхронізовано."), true);
                return InteractionResult.SUCCESS;
            }

            final UUID finalUuid = linkedUuid;
            AzuriomClient.getBalanceAsync(id, balance -> {
                var server = sp.getServer();
                if (server != null) {
                    server.execute(() ->
                            ModMessages.sendToPlayer(new PacketOpenTradeUI(balance, finalUuid, entries), sp)
                    );
                }
            });
        }
        return InteractionResult.SUCCESS;
    }

    private static UUID findLinkedSinkUuid(Level world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos nPos = pos.relative(dir);
            BlockEntity be = world.getBlockEntity(nPos);

            // 1. Якщо лінк стоїть впритул
            if (be instanceof MarketLinkBlockEntity link &&
                    link.getMode() == BlockMode.SINK &&
                    link.getLinkedIslandUuid() != null) {
                return link.getLinkedIslandUuid();
            }

            // 2. Якщо ми торкнулися кабелю — шукаємо лінк у всій мережі кабелів
            if (be instanceof MarketCableBlockEntity) {
                List<MarketLinkBlockEntity> connected = LinkNetworkManager.getConnectedLinks(world, nPos);
                for (MarketLinkBlockEntity l : connected) {
                    if (l.getMode() == BlockMode.SINK && l.getLinkedIslandUuid() != null) {
                        return l.getLinkedIslandUuid();
                    }
                }
            }
        }
        return null;
    }
}