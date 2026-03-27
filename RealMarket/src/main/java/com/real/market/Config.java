package com.real.market;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = RealMarket.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> SITE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> TOKEN;
    public static final ForgeConfigSpec.ConfigValue<Integer> SERVER_ID;

    static {
        BUILDER.push("Azuriom Settings");
        SITE_URL = BUILDER.comment("URL сайту Nestworld (з /api/azlink на кінці)")
                .define("site_url", "https://nestworld.site/api/azlink");
        TOKEN = BUILDER.comment("Ваш Nestworld API токен")
                .define("token", "nestworld_token_here");
        SERVER_ID = BUILDER.comment("ID сервера в налаштуваннях AzLink")
                .define("server_id", 1);
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();
    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
        // Цей метод можна лишити порожнім, він просто допомагає Forge
        // синхронізувати завантаження.
    }
}
