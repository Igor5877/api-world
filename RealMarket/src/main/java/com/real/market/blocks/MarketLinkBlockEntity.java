package com.real.market.blocks;

import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import com.real.market.RealMarket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class MarketLinkBlockEntity extends BlockEntity implements IInWorldGridNodeHost, IActionHost {
    private UUID islandId;
    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, (node, owner) -> {
    })
            .setFlags(GridFlags.REQUIRE_CHANNEL)
            .setVisualRepresentation(RealMarket.MARKET_LINK.get());

    public MarketLinkBlockEntity(BlockPos pos, BlockState state) {
        super(RealMarket.MARKET_LINK_BE.get(), pos, state);
    }

    public void setIslandId(UUID islandId) {
        this.islandId = islandId;
        setChanged();
    }

    public UUID getIslandId() {
        return islandId;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!level.isClientSide) {
            RealMarket.TRACKED_BEs.add(this);
            mainNode.create(level, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide) {
            RealMarket.TRACKED_BEs.remove(this);
            mainNode.destroy();
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("islandId")) {
            this.islandId = tag.getUUID("islandId");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (islandId != null) {
            tag.putUUID("islandId", islandId);
        }
    }

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return mainNode.getNode();
    }

    @Override
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    public MEStorage getInventory() {
        IGridNode node = mainNode.getNode();
        if (node != null && node.isActive()) {
            IGrid grid = node.getGrid();
            // IStorageGrid не існує в AE2 15.x — використовуємо IStorageService
            IStorageService storageService = grid.getService(IStorageService.class);
            return storageService.getInventory();
        }
        return null;
    }
}