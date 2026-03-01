package com.skyblock.sales;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A virtual ME storage that represents the inventory of an offline/remote island.
 * It does not hold physical items. It reads from a cache updated via WebSocket/API.
 */
public class VirtualMEStorage implements MEStorage {

    // Cache of item ResourceLocation to Quantity. Updated by WebSocket.
    private final Map<String, Long> virtualInventory = new ConcurrentHashMap<>();

    public void updateInventory(Map<String, Long> newInventory) {
        virtualInventory.clear();
        virtualInventory.putAll(newInventory);
    }

    @Override
    public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
        return false; // We don't want AE2 to prioritize inserting here unless necessary
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        // For phase 2: If we want players on spawn to insert items into their island via this terminal.
        // If mode == SIMULATE, return amount (we can accept anything).
        // If mode == MODULATE, we must trigger an API call to send the item to the island's DB,
        // and return the amount we successfully 'swallowed'.
        // For MVP: Read-only.
        return 0;
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey)) return 0;

        String id = ForgeRegistries.ITEMS.getKey(itemKey.getItem()).toString();
        long available = virtualInventory.getOrDefault(id, 0L);

        if (available <= 0) return 0;

        long toExtract = Math.min(available, amount);

        if (mode == Actionable.MODULATE) {
            // Liability Shift Logic triggered!
            // When AE2 extracts an item from this virtual drive, we MUST fulfill the extraction locally,
            // but we also must tell the API "Hey, deduct this from the island DB".

            // 1. Locally reduce cache immediately to prevent double-extracting in the same tick
            virtualInventory.put(id, available - toExtract);

            // 2. Fire API request to register the Transaction (Pending Removal for Island)
            // SalesSyncManager.queueRemoteExtraction(islandId, id, toExtract);
        }

        return toExtract;
    }

    @Override
    public KeyCounter getAvailableStacks() {
        KeyCounter counter = new KeyCounter();

        for (Map.Entry<String, Long> entry : virtualInventory.entrySet()) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(entry.getKey()));
            if (item != null && entry.getValue() > 0) {
                AEItemKey key = AEItemKey.of(item);
                if (key != null) {
                    counter.add(key, entry.getValue());
                }
            }
        }

        return counter;
    }

    @Override
    public Component getDescription() {
        return Component.literal("Quantum Bridge Receiver");
    }
}
