package RealMarket.realmarket;

import RealMarket.realmarket.config.ApiConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Ініціалізація конфіга мода при завантаженні
 */
@Mod.EventBusSubscriber(modid = "realmarket", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class RealMarketClientInitializer {
    
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        System.out.println("[RealMarket] Initializing RealMarket mod API configuration...");
        // Завантажуємо конфіг API при запуску мода
        ApiConfig.getToken();
        ApiConfig.getApiUrl();
        ApiConfig.getServerId();
        System.out.println("[RealMarket] RealMarket mod API configuration loaded!");
    }
}
