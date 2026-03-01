package com.skyblock.sales;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper class to interact with the AE2 Grid.
 */
public class AE2Handler {

    /**
     * Scans the ME network connected to the given node for all items.
     * @param node The grid node to scan from.
     * @return A map of ItemKey (as string) to Quantity.
     */
    public static Map<String, Long> scanNetwork(IGridNode node) {
        Map<String, Long> inventory = new HashMap<>();
        if (node == null) return inventory;

        IGrid grid = node.getGrid();
        IStorageService storageService = grid.getService(IStorageService.class);
        MEStorage inventoryStorage = storageService.getInventory();

        KeyCounter keyCounter = inventoryStorage.getAvailableStacks();

        // entry is Map.Entry<AEKey, Long>
        for (var entry : keyCounter) {
            if (entry.getKey() instanceof AEItemKey itemKey) {
                // Use robust registry name instead of toString()
                String id = ForgeRegistries.ITEMS.getKey(itemKey.getItem()).toString();
                inventory.put(id, entry.getValue());
            }
        }
        return inventory;
    }

    /**
     * Extracts an item from the ME network.
     * @param node The grid node to extract from.
     * @param itemId The item ID (registry name).
     * @param quantity The amount to extract.
     * @param source The action source (machine/player).
     * @return The amount actually extracted.
     */
    public static long extractItem(IGridNode node, String itemId, long quantity, IActionSource source) {
        if (node == null) return 0;

        IGrid grid = node.getGrid();
        IStorageService storageService = grid.getService(IStorageService.class);
        MEStorage inventoryStorage = storageService.getInventory();

        // This is a simplified lookup. In a real scenario, you'd need to parse the itemId back to an Item.
        // For MVP, we iterate to find the matching key.
        AEItemKey targetKey = null;
        KeyCounter keyCounter = inventoryStorage.getAvailableStacks();
        for (var entry : keyCounter) {
             if (entry.getKey() instanceof AEItemKey itemKey) {
                 if (ForgeRegistries.ITEMS.getKey(itemKey.getItem()).toString().equals(itemId)) {
                     targetKey = itemKey;
                     break;
                 }
             }
        }

        if (targetKey != null) {
            return inventoryStorage.extract(targetKey, quantity, Actionable.MODULATE, source);
        }
        return 0;
    }
}
