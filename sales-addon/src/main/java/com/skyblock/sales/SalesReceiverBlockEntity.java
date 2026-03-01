package com.skyblock.sales;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.storage.MEStorage;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.IStorageMounts;
import appeng.api.util.AECableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * BlockEntity for the receiver at Spawn.
 * Connects to AE2 and provides the virtual storage.
 */
public class SalesReceiverBlockEntity extends BlockEntity implements IInWorldGridNodeHost, IStorageProvider {

    private final IManagedGridNode mainNode;
    private final VirtualMEStorage virtualStorage;

    public SalesReceiverBlockEntity(BlockPos pos, BlockState state) {
        super(SalesAddon.SALES_RECEIVER_BLOCK_ENTITY.get(), pos, state);

        this.virtualStorage = new VirtualMEStorage();

        // Need an IGridNodeListener
        IGridNodeListener<SalesReceiverBlockEntity> listener = new IGridNodeListener<>() {
            public void onSaveChanges(SalesReceiverBlockEntity nodeOwner, IGridNode node) {
                setChanged();
            }
        };

        this.mainNode = GridHelper.createManagedNode(this, listener);
        this.mainNode.setTagName("Quantum Receiver");
        this.mainNode.setVisualRepresentation(SalesAddon.SALES_RECEIVER_BLOCK.get().asItem());
        this.mainNode.setIdlePowerUsage(1.0);
        this.mainNode.setInWorldNode(true);

        // Tells the node to query this block entity for IStorageProvider
        this.mainNode.addService(IStorageProvider.class, this);
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        // Mount our virtual storage to the AE2 network
        mounts.mount(this.virtualStorage);
    }

    // Updates the internal virtual inventory, to be called when WebSocket receives data
    public void updateRemoteInventory(java.util.Map<String, Long> newData) {
        this.virtualStorage.updateInventory(newData);
        // Force the grid to update its caches so it notices the new items immediately
        if (this.mainNode.isReady()) {
            // Note: Depending on the specific AE2 1.20 API, firing an event might be needed.
            // Often MEStorage implementations that change asynchronously must notify the grid.
            // This is a placeholder for the notification logic if required.
        }
    }

    // --- Standard Node Host Boilerplate ---

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return this.mainNode.getNode();
    }

    @Nullable
    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (this.mainNode != null) {
            this.mainNode.destroy();
            SalesReceiverManager.unregisterReceiver(this);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
             this.mainNode.create(this.level, this.getBlockPos());
             SalesReceiverManager.registerReceiver(this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.mainNode != null) {
            this.mainNode.destroy();
        }
    }
}
