package com.skyblock.dynamic;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Pattern;

@Mod.EventBusSubscriber(modid = SkyBlockMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD) // Fixed MODID reference
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // Define a pattern for basic URL validation (simplified)
    private static final Pattern URL_PATTERN = Pattern.compile("^https?://[a-zA-Z0-9.-]+(:[0-9]{1,5})?(/.*)?$");

    private static final ForgeConfigSpec.ConfigValue<String> API_BASE_URL = BUILDER
            .comment("The base URL for the SkyBlock API (e.g., http://localhost:8000/api/v1)")
            .define("apiBaseUrl", "http://localhost:8000/api/v1", Config::validateUrl);
    
    // You can add other config values here as needed, for example:
    // private static final ForgeConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS = BUILDER
    //         .comment("Timeout in seconds for API requests.")
    //         .defineInRange("requestTimeoutSeconds", 10, 1, 60);


    static final ForgeConfigSpec SPEC = BUILDER.build();

    private static String apiBaseUrl;
    // private static int requestTimeoutSeconds;

    private static boolean validateUrl(final Object obj) {
        if (obj instanceof final String urlString) {
            return !StringUtils.isBlank(urlString) && URL_PATTERN.matcher(urlString).matches();
        }
        return false;
    }
    
    public static String getApiBaseUrl() {
        if (apiBaseUrl == null) {
            // Attempt to load it if not baked yet (e.g. direct call before config event)
            // This is a fallback, ideally `bake` is called by the event system.
            apiBaseUrl = API_BASE_URL.get();
        }
        return apiBaseUrl;
    }

    // public static int getRequestTimeoutSeconds() {
    //     return requestTimeoutSeconds;
    // }

    // This method is called by Forge when the config is loaded or reloaded.
    // We "bake" the values into static fields for easy access.
    public static void bake() {
        apiBaseUrl = API_BASE_URL.get();
        // requestTimeoutSeconds = REQUEST_TIMEOUT_SECONDS.get();
        // Ensure trailing slash for base URL consistency
        if (apiBaseUrl != null && !apiBaseUrl.endsWith("/")) {
            apiBaseUrl += "/";
        }
    }

    // This is not strictly necessary if using the ModConfigEvent listener in the main mod class,
    // but can be kept if you prefer config loading logic centralized here.
    // If SkyBlockMod.onModConfigEvent calls Config.bake(), this specific listener might be redundant.
    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }

    @SubscribeEvent
    static void onReload(final ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            bake();
        }
    }
}
