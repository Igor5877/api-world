package RealMarket.realmarket.block;

import RealMarket.realmarket.RealMarket;
import RealMarket.realmarket.blockentity.MarketCableBlockEntity;
import appeng.api.networking.IInWorldGridNodeHost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nonnull;
import java.util.Map;

@SuppressWarnings("deprecation")
public class MarketCableBlock extends DirectionalBlock implements EntityBlock {
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;
    public static final BooleanProperty UP = BlockStateProperties.UP;
    public static final BooleanProperty DOWN = BlockStateProperties.DOWN;

    private static final Map<Direction, BooleanProperty> PROPS = Map.of(
            Direction.NORTH, NORTH, Direction.SOUTH, SOUTH,
            Direction.EAST, EAST, Direction.WEST, WEST,
            Direction.UP, UP, Direction.DOWN, DOWN
    );

    private static final VoxelShape[] SHAPES = new VoxelShape[64];

    public MarketCableBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(NORTH, false).setValue(SOUTH, false).setValue(EAST, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
        runCache();
    }

    private void runCache() {
        VoxelShape core = box(6, 6, 6, 10, 10, 10);
        VoxelShape[] sides = {
                box(6, 0, 6, 10, 6, 10), box(6, 10, 6, 10, 16, 10),
                box(6, 6, 0, 10, 10, 6), box(6, 6, 10, 10, 10, 16),
                box(0, 6, 6, 6, 10, 10), box(10, 6, 6, 16, 10, 10)
        };
        for (int i = 0; i < 64; i++) {
            VoxelShape s = core;
            for (int b = 0; b < 6; b++) if ((i & (1 << b)) != 0) s = Shapes.or(s, sides[b]);
            SHAPES[i] = s;
        }
    }

    @Override
    @Nonnull
    public VoxelShape getShape(@Nonnull BlockState s, @Nonnull BlockGetter l, @Nonnull BlockPos p, @Nonnull CollisionContext c) {
        int i = 0;
        if (s.getValue(DOWN)) i |= 1; if (s.getValue(UP)) i |= 2;
        if (s.getValue(NORTH)) i |= 4; if (s.getValue(SOUTH)) i |= 8;
        if (s.getValue(WEST)) i |= 16; if (s.getValue(EAST)) i |= 32;
        return SHAPES[i];
    }

    private boolean canConnect(BlockGetter level, BlockPos pos, Direction dir) {
        BlockPos target = pos.relative(dir);
        BlockState state = level.getBlockState(target);
        if (state.is(this) || state.is(RealMarket.TRADE_BLOCK.get()) || state.is(RealMarket.MARKET_LINK_BLOCK.get())) return true;
        return level.getBlockEntity(target) instanceof IInWorldGridNodeHost;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = defaultBlockState().setValue(FACING, ctx.getNearestLookingDirection().getOpposite());
        for (Direction d : Direction.values()) {
            state = state.setValue(PROPS.get(d), canConnect(ctx.getLevel(), ctx.getClickedPos(), d));
        }
        return state;
    }

    @Override
    @Nonnull
    public BlockState updateShape(BlockState s, @Nonnull Direction d, @Nonnull BlockState adj, @Nonnull LevelAccessor level, @Nonnull BlockPos p, @Nonnull BlockPos ap) {
        return s.setValue(PROPS.get(d), canConnect(level, p, d));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Override
    public BlockEntity newBlockEntity(@Nonnull BlockPos p, @Nonnull BlockState s) {
        return new MarketCableBlockEntity(p, s);
    }
}