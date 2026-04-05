package RealMarket.realmarket.world;

import RealMarket.realmarket.RealMarket;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.UUID;

public class IslandManager {
    public static final HashMap<UUID, Double> PRICES = new HashMap<>();
    private static final int GRID_DISTANCE = 1000;
    private static final File PRICES_FILE = new File("config/realmarket-prices.json");
    private static final Gson GSON = new Gson();

    public static void loadPrices() {
        if (!PRICES_FILE.exists()) return;
        try (FileReader reader = new FileReader(PRICES_FILE)) {
            Type type = new TypeToken<HashMap<UUID, Double>>(){}.getType();
            HashMap<UUID, Double> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                PRICES.putAll(loaded);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void savePrices() {
        try (FileWriter writer = new FileWriter(PRICES_FILE)) {
            GSON.toJson(PRICES, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

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