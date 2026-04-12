package RealMarket.realmarket.client;

import RealMarket.realmarket.network.PacketOpenTradeUI;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.UUID;

public class ClientHooks {
    public static void openTradeScreen(double balance, UUID islandUuid, List<PacketOpenTradeUI.ItemEntry> items) {
        Minecraft.getInstance().tell(() ->
                Minecraft.getInstance().setScreen(new TradeScreen(balance, islandUuid, items)));
    }
}
