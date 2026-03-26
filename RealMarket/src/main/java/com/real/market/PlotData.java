package com.real.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlotData extends SavedData {
    public static class Plot {
        public String name;
        public UUID ownerTeam;
        public long expiration;
        public int x1, z1, x2, z2;

        public Plot(String name, UUID ownerTeam, long expiration, int x1, int z1, int x2, int z2) {
            this.name = name;
            this.ownerTeam = ownerTeam;
            this.expiration = expiration;
            this.x1 = x1;
            this.z1 = z1;
            this.x2 = x2;
            this.z2 = z2;
        }
    }

    public final Map<String, Plot> plots = new HashMap<>();

    @Override
    public @NotNull CompoundTag save(CompoundTag tag) {
        plots.forEach((name, plot) -> {
            CompoundTag pTag = new CompoundTag();
            pTag.putString("name", plot.name);
            pTag.putUUID("owner", plot.ownerTeam);
            pTag.putLong("exp", plot.expiration);
            pTag.putInt("x1", plot.x1);
            pTag.putInt("z1", plot.z1);
            pTag.putInt("x2", plot.x2);
            pTag.putInt("z2", plot.z2);
            tag.put(name, pTag);
        });
        return tag;
    }

    public static PlotData get(Level level) {
        return ((ServerLevel) level).getDataStorage()
                .computeIfAbsent(PlotData::load, PlotData::new, "realmarket_plots");
    }

    public static PlotData load(CompoundTag tag) {
        PlotData data = new PlotData();
        tag.getAllKeys().forEach(name -> {
            CompoundTag pTag = tag.getCompound(name);
            data.plots.put(name, new Plot(
                    pTag.getString("name"),
                    pTag.getUUID("owner"),
                    pTag.getLong("exp"),
                    pTag.getInt("x1"),
                    pTag.getInt("z1"),
                    pTag.getInt("x2"),
                    pTag.getInt("z2")
            ));
        });
        return data;
    }
}
