package com.skyblock.sales;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Represents a specific item for sale from a specific island.
 */
public class SalesVendingBlockEntity extends BlockEntity {

    // Configuration: What this block sells.
    private String targetItemId = "minecraft:diamond";
    private int targetIslandId = 1;
    private int pricePerItem = 10;

    public SalesVendingBlockEntity(BlockPos pos, BlockState state) {
        super(SalesAddon.SALES_VENDING_BLOCK_ENTITY.get(), pos, state);
    }

    public void setTargetItem(String itemId) {
        this.targetItemId = itemId;
        this.setChanged();
    }

    @Override
    public void saveAdditional(net.minecraft.nbt.CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("TargetItemId", this.targetItemId);
        tag.putInt("TargetIslandId", this.targetIslandId);
        tag.putInt("PricePerItem", this.pricePerItem);
    }

    @Override
    public void load(net.minecraft.nbt.CompoundTag tag) {
        super.load(tag);
        if (tag.contains("TargetItemId")) {
            this.targetItemId = tag.getString("TargetItemId");
        }
        if (tag.contains("TargetIslandId")) {
            this.targetIslandId = tag.getInt("TargetIslandId");
        }
        if (tag.contains("PricePerItem")) {
            this.pricePerItem = tag.getInt("PricePerItem");
        }
    }

    public void triggerPurchase(Player buyer) {
        // 1. Check if the player has enough currency (Skip for MVP, assume yes)
        // int balance = EconomySystem.getBalance(buyer);
        // if (balance < pricePerItem) { buyer.sendSystemMessage(Component.literal("Not enough money")); return; }

        // 2. We could check local AE2 network to see if the item is physically available right now
        //    via the Receiver block, OR we just trust the API and use Liability Shift.
        //    We use the API Liability Shift for maximum reliability.

        buyer.sendSystemMessage(Component.literal("Initiating purchase of " + targetItemId + "..."));

        // Call API
        SalesSyncManager.executePurchase(buyer, targetIslandId, targetItemId, 1);
    }

    // Method called by SalesSyncManager when API returns SUCCESS
    public void onPurchaseSuccess(Player buyer) {
        // 1. Deduct currency (Skip for MVP)

        // 2. Give item to player
        ItemStack itemStack = new ItemStack(ForgeRegistries.ITEMS.getValue(new ResourceLocation(targetItemId)), 1);
        boolean added = buyer.getInventory().add(itemStack);
        if (!added) {
            buyer.drop(itemStack, false);
        }

        buyer.sendSystemMessage(Component.literal("Purchase successful! Item delivered."));
    }

    public void onPurchaseFail(Player buyer, String reason) {
        buyer.sendSystemMessage(Component.literal("Purchase failed: " + reason));
    }
}
