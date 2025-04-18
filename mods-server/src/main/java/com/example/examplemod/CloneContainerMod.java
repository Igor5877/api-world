package com.cody.clone;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

@Mod("clonecontainermod")
@EventBusSubscriber
public class CloneContainerMod {
    public CloneContainerMod() {
        // Ініціалізація при старті сервера
    }

    @SubscribeEvent
    public static void onCommandRegister(RegisterCommandsEvent event) {
        CloneCommand.register(event.getDispatcher());
        BazaCommand.register(event.getDispatcher());
    }
}
