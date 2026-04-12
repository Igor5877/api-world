package RealMarket.realmarket.block;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.api.MarketSyncManager;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity.BlockMode;
import RealMarket.realmarket.network.ModMessages;
import RealMarket.realmarket.network.PacketOpenTradeUI;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;
import java.util.UUID;

public class TradeBlock extends Block {
    // Форма блоку
    protected static final VoxelShape SHAPE = Shapes.box(0.06D, 0.0D, 0.06D, 0.94D, 0.88D, 0.94D);

    public TradeBlock(Properties p) {
        super(p);
    }

    @SuppressWarnings("deprecation") // Прибирає попередження про застарілий метод Mojang
    @Override
    public VoxelShape getShape(BlockState s, BlockGetter g, BlockPos p, CollisionContext c) {
        return SHAPE;
    }

    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!world.isClientSide && player instanceof ServerPlayer serverPlayer) {
            int id = AzuriomClient.getPlayerId(player.getUUID());
            if (id == -1) {
                serverPlayer.displayClientMessage(Component.literal("§cСинхронізація ID... Спробуйте ще раз"), true);
                return InteractionResult.SUCCESS;
            }

            // Шукаємо сусідній SINK MarketLinkBlock (з'єднаний через Memory Card)
            UUID linkedIslandUuid = findLinkedSinkUuid(world, pos);
            double price;
            if (linkedIslandUuid != null) {
                // Використовуємо ціну з кешу SINK блоку (перший предмет як приклад)
                var items = MarketSyncManager.getCachedInventory(linkedIslandUuid);
                price = items.isEmpty() ? 10.0 : items.get(0).price();
            } else {
                // Fallback: стара поведінка (немає підключеного SINK блоку)
                price = IslandManager.PRICES.getOrDefault(player.getUUID(), 10.0);
            }

            final double finalPrice = price;
            AzuriomClient.getBalAsync(id).thenAccept(bal ->
                    ModMessages.sendToPlayer(new PacketOpenTradeUI(bal, finalPrice), serverPlayer)
            );
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Шукає сусідній (в 6 напрямках) MarketLinkBlockEntity у режимі SINK
     * з встановленим linkedIslandUuid.
     */
    private static UUID findLinkedSinkUuid(Level world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockEntity be = world.getBlockEntity(pos.relative(dir));
            if (be instanceof MarketLinkBlockEntity link
                    && link.getMode() == BlockMode.SINK
                    && link.getLinkedIslandUuid() != null) {
                return link.getLinkedIslandUuid();
            }
        }
        return null;
    }
}