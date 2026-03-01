package com.skyblock.sales;

import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.util.AECableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

// Implementing IInWorldGridNodeHost allows AE2 cables to discover and connect to this block entity
public class SalesTerminalBlockEntity extends BlockEntity implements IInWorldGridNodeHost {

    private final IManagedGridNode mainNode;

    public SalesTerminalBlockEntity(BlockPos pos, BlockState state) {
        super(SalesAddon.SALES_TERMINAL_BLOCK_ENTITY.get(), pos, state);

        // Initialize the managed node
        this.mainNode = GridHelper.createManagedNode(this, new IGridNodeListener<SalesTerminalBlockEntity>() {
            public void onSecurityBreak(SalesTerminalBlockEntity nodeOwner, IGridNode node) {
                // Handle security break if needed
            }

            public void onSaveChanges(SalesTerminalBlockEntity nodeOwner, IGridNode node) {
                setChanged();
            }
        });

        // Configure the node
        this.mainNode.setTagName("Sales Terminal");
        this.mainNode.setVisualRepresentation(SalesAddon.SALES_TERMINAL_BLOCK.get().asItem());
        this.mainNode.setIdlePowerUsage(5.0);
        this.mainNode.setInWorldNode(true); // Crucial for in-world connection
    }

    // Implements IInWorldGridNodeHost.getGridNode
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
            SalesSyncManager.unregisterTerminal(this);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
             // Create the node in the world once the block entity is loaded
             this.mainNode.create(this.level, this.getBlockPos());
             SalesSyncManager.registerTerminal(this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (this.mainNode != null) {
            this.mainNode.destroy();
        }
    }

    public IGridNode getMainNode() {
        return this.mainNode.getNode();
    }
}
