package RealMarket.realmarket.world;

import RealMarket.realmarket.RealMarket;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import net.minecraftforge.fml.loading.FMLPaths;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class IslandManager {

    // Залишаємо для сумісності з PacketTrade
    public static final HashMap<UUID, Double> PRICES = new HashMap<>();
    private static File PRICES_FILE;
    private static File SLOTS_FILE;
    private static File WARPS_DIR;

    public static void loadPrices() {
        if (!PRICES_FILE.exists()) return;
        try (FileReader reader = new FileReader(PRICES_FILE)) {
            Type type = new TypeToken<HashMap<String, Double>>() {}.getType();
            HashMap<String, Double> raw = GSON.fromJson(reader, type);
            if (raw == null) return;
            raw.forEach((k, v) -> PRICES.put(UUID.fromString(k), v));
        } catch (IOException e) { e.printStackTrace(); }
    }

    public static void savePrices() {
        Map<String, Double> snapshot = new HashMap<>();
        PRICES.forEach((k, v) -> snapshot.put(k.toString(), v));
        CompletableFuture.runAsync(() -> {
            try (FileWriter w = new FileWriter(PRICES_FILE)) { GSON.toJson(snapshot, w); }
            catch (IOException e) { e.printStackTrace(); }
        });
    }

    private static final int GRID_DISTANCE = 1000;
    private static final int WARP_Y        = 100;
    /** Радіус збереження платформи (горизонтально) */
    public static final int SAVE_RADIUS_H  = 8;
    /** Висота збереження (вгору від Y платформи) */
    public static final int SAVE_RADIUS_V  = 15;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Ініціалізує шляхи через FMLPaths — викликати після завантаження Forge. */
    public static void initPaths() {
        java.nio.file.Path configDir = FMLPaths.CONFIGDIR.get();
        PRICES_FILE = configDir.resolve("realmarket-prices.json").toFile();
        SLOTS_FILE  = configDir.resolve("realmarket-slots.json").toFile();
        WARPS_DIR   = configDir.resolve("realmarket-warps").toFile();
    }

    /** UUID гравця → номер слоту (стабільний між рестартами) */
    private static final HashMap<UUID, Integer> SLOTS = new HashMap<>();
    private static int nextSlot = 0;

    // ── Слоти ────────────────────────────────────────────────────────────────

    public static void loadSlots() {
        if (!SLOTS_FILE.exists()) return;
        try (FileReader r = new FileReader(SLOTS_FILE)) {
            Type type = new TypeToken<HashMap<String, Integer>>() {}.getType();
            HashMap<String, Integer> raw = GSON.fromJson(r, type);
            if (raw == null) return;
            for (var e : raw.entrySet()) {
                SLOTS.put(UUID.fromString(e.getKey()), e.getValue());
            }
            nextSlot = SLOTS.values().stream().mapToInt(i -> i).max().orElse(-1) + 1;
            System.out.println("[IslandManager] Завантажено " + SLOTS.size() + " слотів.");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void saveSlots() {
        Map<String, Integer> snapshot = new HashMap<>();
        SLOTS.forEach((k, v) -> snapshot.put(k.toString(), v));
        CompletableFuture.runAsync(() -> {
            try (FileWriter w = new FileWriter(SLOTS_FILE)) { GSON.toJson(snapshot, w); }
            catch (IOException e) { e.printStackTrace(); }
        });
    }

    /** Повертає стабільні координати центру платформи для гравця. */
    public static BlockPos getIslandCoords(UUID playerUuid) {
        boolean isNew = !SLOTS.containsKey(playerUuid);
        int slot = SLOTS.computeIfAbsent(playerUuid, k -> nextSlot++);
        if (isNew) saveSlots(); // зберігаємо ПІСЛЯ того як запис вже в Map
        return new BlockPos(slot * GRID_DISTANCE, WARP_Y, 0);
    }

    // ── Створення платформи ───────────────────────────────────────────────────

    /**
     * Генерує 5×5 платформу на спавні та ставить MarketLink SINK блок в центрі.
     * Прив'язується до UUID гравця — координати стабільні між рестартами.
     */
    public static void createIsland(ServerLevel world, UUID playerUuid) {
        BlockPos center = getIslandCoords(playerUuid);

        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.setBlockAndUpdate(center.offset(x, 0, z), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }

        // MarketLink SINK блок — центр платформи (на 1 вище)
        BlockPos linkPos = center.above();
        world.setBlockAndUpdate(linkPos, RealMarket.MARKET_LINK_BLOCK.get().defaultBlockState());

        // Налаштовуємо блок як SINK прив'язаний до UUID острова гравця
        if (world.getBlockEntity(linkPos) instanceof MarketLinkBlockEntity link) {
            link.setMode(MarketLinkBlockEntity.BlockMode.SINK);
            link.setLinkedIslandUuid(playerUuid);
        }

        System.out.println("[IslandManager] Платформа створена для " + playerUuid + " в " + center.toShortString());
    }

    // ── Збереження / видалення / відновлення ─────────────────────────────────

    /**
     * Зберігає всі блоки в регіоні навколо платформи у файл.
     * Включає BlockEntity NBT (скрині, написи тощо).
     */
    public static void savePlatform(ServerLevel world, UUID playerUuid) {
        WARPS_DIR.mkdirs();
        BlockPos center = getIslandCoords(playerUuid);
        JsonArray blocks = new JsonArray();

        for (int dx = -SAVE_RADIUS_H; dx <= SAVE_RADIUS_H; dx++) {
            for (int dy = 0; dy <= SAVE_RADIUS_V; dy++) {
                for (int dz = -SAVE_RADIUS_H; dz <= SAVE_RADIUS_H; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;

                    JsonObject entry = new JsonObject();
                    entry.addProperty("dx", dx);
                    entry.addProperty("dy", dy);
                    entry.addProperty("dz", dz);
                    entry.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());

                    // Властивості блоку (напрямок, варіант тощо)
                    JsonObject props = new JsonObject();
                    for (Property<?> prop : state.getProperties()) {
                        props.addProperty(prop.getName(), state.getValue(prop).toString());
                    }
                    entry.add("properties", props);

                    // NBT блок-ентіті (скриня, знак тощо)
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be != null) {
                        CompoundTag nbt = be.saveWithFullMetadata();
                        entry.addProperty("be_nbt", nbt.toString());
                    }

                    blocks.add(entry);
                }
            }
        }

        JsonObject data = new JsonObject();
        data.addProperty("center_x", center.getX());
        data.addProperty("center_y", center.getY());
        data.addProperty("center_z", center.getZ());
        data.add("blocks", blocks);

        File file = new File(WARPS_DIR, playerUuid + ".json");
        int blockCount = blocks.size();
        CompletableFuture.runAsync(() -> {
            try (FileWriter w = new FileWriter(file)) {
                GSON.toJson(data, w);
                System.out.println("[IslandManager] Платформу збережено: " + blockCount + " блоків → " + file.getName());
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Зберігає платформу і видаляє всі її блоки зі світу.
     * Дані зберігаються 30 днів (або до вайпу).
     */
    public static void suspendPlatform(ServerLevel world, UUID playerUuid) {
        savePlatform(world, playerUuid);

        BlockPos center = getIslandCoords(playerUuid);
        for (int dx = -SAVE_RADIUS_H; dx <= SAVE_RADIUS_H; dx++) {
            for (int dy = 0; dy <= SAVE_RADIUS_V; dy++) {
                for (int dz = -SAVE_RADIUS_H; dz <= SAVE_RADIUS_H; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!world.getBlockState(pos).isAir()) {
                        world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        System.out.println("[IslandManager] Платформу призупинено для " + playerUuid);
    }

    /**
     * Відновлює платформу зі збереженого файлу.
     * @return true якщо відновлено успішно
     */
    public static boolean restorePlatform(ServerLevel world, UUID playerUuid) {
        File file = new File(WARPS_DIR, playerUuid + ".json");
        if (!file.exists()) {
            System.err.println("[IslandManager] Немає збереженої платформи для " + playerUuid);
            return false;
        }

        try (FileReader r = new FileReader(file)) {
            JsonObject data = JsonParser.parseReader(r).getAsJsonObject();
            int cx = data.get("center_x").getAsInt();
            int cy = data.get("center_y").getAsInt();
            int cz = data.get("center_z").getAsInt();
            BlockPos center = new BlockPos(cx, cy, cz);

            JsonArray blocks = data.getAsJsonArray("blocks");
            for (JsonElement el : blocks) {
                JsonObject entry = el.getAsJsonObject();
                int dx = entry.get("dx").getAsInt();
                int dy = entry.get("dy").getAsInt();
                int dz = entry.get("dz").getAsInt();
                BlockPos pos = center.offset(dx, dy, dz);

                String blockId = entry.get("block").getAsString();
                Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.tryParse(blockId));
                if (block == Blocks.AIR) continue;

                // Відновлюємо BlockState з властивостями
                BlockState state = applyProperties(block.defaultBlockState(),
                        entry.getAsJsonObject("properties"), block);
                world.setBlockAndUpdate(pos, state);

                // Відновлюємо BlockEntity NBT
                if (entry.has("be_nbt")) {
                    BlockEntity be = world.getBlockEntity(pos);
                    if (be != null) {
                        try {
                            CompoundTag nbt = net.minecraft.nbt.TagParser.parseTag(entry.get("be_nbt").getAsString());
                            be.load(nbt);
                            be.setChanged();
                        } catch (Exception ignored) {}
                    }
                }
            }

            System.out.println("[IslandManager] Платформу відновлено для " + playerUuid + " (" + blocks.size() + " блоків)");
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Видаляє збережений файл платформи (після 30 днів або вайпу). */
    public static boolean deleteSavedPlatform(UUID playerUuid) {
        File file = new File(WARPS_DIR, playerUuid + ".json");
        if (file.exists()) {
            file.delete();
            System.out.println("[IslandManager] Збережену платформу видалено для " + playerUuid);
            return true;
        }
        return false;
    }

    public static boolean hasSavedPlatform(UUID playerUuid) {
        return new File(WARPS_DIR, playerUuid + ".json").exists();
    }

    // ── Утиліти ──────────────────────────────────────────────────────────────

    private static BlockState applyProperties(BlockState state, JsonObject props, Block block) {
        for (Property<?> prop : block.getStateDefinition().getProperties()) {
            JsonElement val = props.get(prop.getName());
            if (val == null) continue;
            state = applyPropertyUnchecked(state, prop, val.getAsString());
        }
        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyPropertyUnchecked(BlockState state, Property prop, String val) {
        Optional<?> parsed = prop.getValue(val);
        if (parsed.isEmpty()) return state;
        try {
            return state.setValue(prop, (Comparable) parsed.get());
        } catch (Exception e) {
            return state;
        }
    }
}
