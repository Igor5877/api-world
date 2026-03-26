package com.real.market.net;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.real.market.MarketData;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;

public class MarketSyncTask implements Runnable {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final UUID islandId;
    private final KeyCounter availableStacks;
    private final MarketWebSocketClient client;
    private final MarketData marketData;

    public MarketSyncTask(UUID islandId, KeyCounter availableStacks, MarketWebSocketClient client, MarketData marketData) {
        this.islandId = islandId;
        this.availableStacks = availableStacks;
        this.client = client;
        this.marketData = marketData;
    }

    @Override
    public void run() {
        if (client == null || !client.isOpen()) return;

        JsonArray items = new JsonArray();
        Map<Item, MarketData.Entry> islandStocks = marketData.stocks.get(islandId);
        if (islandStocks == null) return;

        availableStacks.forEach((key, amount) -> {
            if (key instanceof AEItemKey itemKey) {
                Item item = itemKey.getItem();
                MarketData.Entry entry = islandStocks.get(item);
                if (entry != null) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("item_id", ForgeRegistries.ITEMS.getKey(item).toString());
                    obj.addProperty("amount", amount);
                    obj.addProperty("price", entry.base);
                    if (itemKey.hasTag()) {
                        obj.addProperty("nbt", itemKey.getTag().toString());
                    }
                    items.add(obj);
                }
            }
        });

        client.sendSync(islandId.toString(), items);
        LOGGER.debug("[RealMarket] Synced {} items for island {}", items.size(), islandId);
    }
}
