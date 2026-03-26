package com.real.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class MarketData extends SavedData {
    public final Map<String, Double> balances = new HashMap<>();
    // UUID (Island/Team) -> Map of Item -> Price Info
    public final Map<UUID, Map<Item, Entry>> stocks = new HashMap<>();

    public static class Entry {
        public long stock, target;
        public double base;

        public Entry(long s, long t, double b) { this.stock = s; this.target = t; this.base = b; }

        public double price(boolean buy) {
            var ratio = (double) target / Math.max(1, stock);
            var p = base * Math.sqrt(ratio);
            return buy ? p : p * 0.85;
        }
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag) {
        var bTag = new CompoundTag();
        balances.forEach(bTag::putDouble);
        tag.put("Balances", bTag);

        var sTag = new CompoundTag();
        stocks.forEach((islandId, itemMap) -> {
            var islandTag = new CompoundTag();
            itemMap.forEach((item, data) -> {
                var key = ForgeRegistries.ITEMS.getKey(item);
                if (key == null) return;
                var iTag = new CompoundTag();
                iTag.putLong("s", data.stock);
                iTag.putLong("t", data.target);
                iTag.putDouble("b", data.base);
                islandTag.put(key.toString(), iTag);
            });
            sTag.put(islandId.toString(), islandTag);
        });
        tag.put("Stocks", sTag);
        return tag;
    }

    public static MarketData get(Level lvl) {
        return ((ServerLevel) lvl).getDataStorage()
                .computeIfAbsent(MarketData::load, MarketData::new, "realmarket");
    }

    public static MarketData load(CompoundTag tag) {
        var data = new MarketData();
        var bTag = tag.getCompound("Balances");
        bTag.getAllKeys().forEach(k -> data.balances.put(k, bTag.getDouble(k)));

        var sTag = tag.getCompound("Stocks");
        sTag.getAllKeys().forEach(islandUuidStr -> {
            UUID islandId = UUID.fromString(islandUuidStr);
            var islandTag = sTag.getCompound(islandUuidStr);
            Map<Item, Entry> itemMap = new HashMap<>();
            islandTag.getAllKeys().forEach(k -> {
                var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(k));
                if (item != null && item != Items.AIR) {
                    var iTag = islandTag.getCompound(k);
                    itemMap.put(item, new Entry(iTag.getLong("s"), iTag.getLong("t"), iTag.getDouble("b")));
                }
            });
            data.stocks.put(islandId, itemMap);
        });
        return data;
    }
}
