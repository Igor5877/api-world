package RealMarket.realmarket.blockentity;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.GridHelper;
import appeng.api.util.AECableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import RealMarket.realmarket.RealMarket;

import javax.annotation.Nullable;
import java.util.UUID;

public class MarketLinkBlockEntity extends BlockEntity implements IInWorldGridNodeHost, IGridNodeListener<MarketLinkBlockEntity> {

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, this);
    private UUID islandUuid;

    public MarketLinkBlockEntity(BlockPos pPos, BlockState pBlockState) {
        super(RealMarket.MARKET_LINK_BE.get(), pPos, pBlockState);
        this.mainNode.setIdlePowerUsage(0.5d); // Example power usage
        this.mainNode.setInWorldNode(true);
        this.mainNode.setVisualRepresentation(RealMarket.MARKET_LINK_ITEM.get());
    }

    @Override
    public void onSaveChanges(MarketLinkBlockEntity nodeOwner, IGridNode node) {
        this.setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            this.mainNode.create(level, getBlockPos());
        }
        RealMarket.addActiveMarketLink(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            this.mainNode.destroy();
        }
        RealMarket.removeActiveMarketLink(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null && !level.isClientSide()) {
            this.mainNode.destroy();
        }
        RealMarket.removeActiveMarketLink(this);
    }

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return this.mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    public IGrid getGrid() {
        if (this.mainNode.getNode() != null) {
            return this.mainNode.getNode().getGrid();
        }
        return null;
    }

    public UUID getIslandUuid() {
        return islandUuid;
    }

    public void setIslandUuid(UUID islandUuid) {
        this.islandUuid = islandUuid;
        this.setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.islandUuid != null) {
            tag.putUUID("islandUuid", this.islandUuid);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("islandUuid")) {
            this.islandUuid = tag.getUUID("islandUuid");
        }
    }
}
