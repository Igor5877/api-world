package RealMarket.realmarket.client;

import net.minecraft.client.Minecraft;

/**
 * A safe bridge for client-side code execution.
 * Methods in this class should only be called via DistExecutor on the logical client.
 */
public class ClientHooks {
    public static void openTradeScreen(double balance, double price) {
        Minecraft.getInstance().tell(() -> {
            Minecraft.getInstance().setScreen(new TradeScreen(balance, price));
        });
    }
}
