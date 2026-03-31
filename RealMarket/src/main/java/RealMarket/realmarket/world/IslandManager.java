package RealMarket.realmarket.world;

import RealMarket.realmarket.RealMarket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import java.util.HashMap;
import java.util.UUID;

public class IslandManager {
    public static final HashMap<UUID, Double> PRICES = new HashMap<>();
    private static final int GRID_DISTANCE = 1000;

    public static BlockPos getIslandCoords(int entityId) {
        return new BlockPos(entityId * GRID_DISTANCE, 100, entityId * GRID_DISTANCE);
    }

    public static void createIsland(ServerLevel world, int id) {
        BlockPos center = getIslandCoords(id);

        // Генерація платформи 5x5
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.setBlockAndUpdate(center.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }

        // Встановлення торгового блоку
        world.setBlockAndUpdate(center.above(), RealMarket.TRADE_BLOCK.get().defaultBlockState());
    }
}