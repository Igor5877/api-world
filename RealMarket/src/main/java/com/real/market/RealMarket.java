package com.real.market;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod("realmarket")
public class RealMarket {
    public RealMarket() {
        // Реєструємо конфіг негайно
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "realmarket-common.toml");

        // Додаємо слухача команд
        MinecraftForge.EVENT_BUS.addListener(this::onCommands);
    }

    private void onCommands(RegisterCommandsEvent event) {
        MarketCommands.register(event.getDispatcher(), event.getBuildContext());
    }
}