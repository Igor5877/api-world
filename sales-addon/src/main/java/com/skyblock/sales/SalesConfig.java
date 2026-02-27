package com.skyblock.sales;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

public class SalesConfig {
    public static final Common COMMON;
    public static final ForgeConfigSpec COMMON_SPEC;

    static {
        final Pair<Common, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(Common::new);
        COMMON_SPEC = specPair.getRight();
        COMMON = specPair.getLeft();
    }

    public static class Common {
        public final ForgeConfigSpec.ConfigValue<String> apiUrl;
        public final ForgeConfigSpec.IntValue islandId;

        public Common(ForgeConfigSpec.Builder builder) {
            builder.push("sales");
            apiUrl = builder
                    .comment("The API URL for sales synchronization")
                    .define("apiUrl", "http://localhost:8000/api/v1/sales");
            islandId = builder
                    .comment("The ID of this island (must be unique)")
                    .defineInRange("islandId", 1, 1, Integer.MAX_VALUE);
            builder.pop();
        }
    }

    public static void register(ModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, COMMON_SPEC);
    }
}
