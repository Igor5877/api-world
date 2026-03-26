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
    public static final ForgeConfigSpec SPEC;

    static {
        BUILDER.push("Azuriom Settings");
        SITE_URL = BUILDER.comment("URL вашого сайту (з /api/azlink на кінці)")
                .define("site_url", "https://fdfhhfhf/api/azlink");
        TOKEN = BUILDER.comment("Ваш Azuriom-Link-Token")
                .define("token", "ВАШ_ТОКЕН_ТУТ");
        SERVER_ID = BUILDER.comment("ID сервера в налаштуваннях AzLink")
                .define("server_id", 1);
        BUILDER.pop();
        // SPEC обов'язково будується після всіх define(), в кінці static блоку
        SPEC = BUILDER.build();
    }

    public static String getApiBaseUrl() {
        return SITE_URL.get();
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent.Loading event) {
    }
}