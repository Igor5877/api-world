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
import RealMarket.realmarket.api.MarketSyncManager;

import javax.annotation.Nullable;
import java.util.UUID;

public class MarketLinkBlockEntity extends BlockEntity implements IInWorldGridNodeHost, IGridNodeListener<MarketLinkBlockEntity> {

    /**
     * SOURCE — на острові: читає AE2, пушить до API.
     * SINK   — на спавні: тягне з API, надає TradeBlock.
     */
    public enum BlockMode { SOURCE, SINK }

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, this);

    private BlockMode mode = BlockMode.SINK;
    /** SOURCE: UUID власного острова. */
    private UUID sourceIslandUuid;
    /** SINK: UUID острова продавця, отриманий через Memory Card. */
    private UUID linkedIslandUuid;

    public MarketLinkBlockEntity(BlockPos pPos, BlockState pBlockState) {
        super(RealMarket.MARKET_LINK_BE.get(), pPos, pBlockState);
        this.mainNode.setIdlePowerUsage(0.5d);
        this.mainNode.setInWorldNode(true);
        this.mainNode.setVisualRepresentation(RealMarket.MARKET_LINK_ITEM.get());
    }

    // ── AE2 grid ────────────────────────────────────────────────────────────

    @Override
    public void onSaveChanges(MarketLinkBlockEntity nodeOwner, IGridNode node) {
        this.setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide()) {
            this.mainNode.create(level, getBlockPos());
            if (mode == BlockMode.SOURCE && sourceIslandUuid != null) {
                MarketSyncManager.init(sourceIslandUuid);
            }
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

    // ── Getters / setters ───────────────────────────────────────────────────

    public BlockMode getMode() { return mode; }
    public void setMode(BlockMode mode) { this.mode = mode; this.setChanged(); }

    public UUID getSourceIslandUuid() { return sourceIslandUuid; }
    public void setSourceIslandUuid(UUID uuid) {
        this.sourceIslandUuid = uuid;
        this.setChanged();
        if (mode == BlockMode.SOURCE && uuid != null && level != null && !level.isClientSide()) {
            MarketSyncManager.init(uuid);
        }
    }

    public UUID getLinkedIslandUuid() { return linkedIslandUuid; }
    public void setLinkedIslandUuid(UUID uuid) { this.linkedIslandUuid = uuid; this.setChanged(); }

    /** Повертає актуальний UUID залежно від режиму. */
    @Nullable
    public UUID getActiveIslandUuid() {
        return mode == BlockMode.SOURCE ? sourceIslandUuid : linkedIslandUuid;
    }

    // ── NBT ─────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("mode", mode.name());
        if (sourceIslandUuid != null) tag.putUUID("sourceIslandUuid", sourceIslandUuid);
        if (linkedIslandUuid != null) tag.putUUID("linkedIslandUuid", linkedIslandUuid);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        try { mode = BlockMode.valueOf(tag.getString("mode")); } catch (Exception ignored) {}
        if (tag.hasUUID("sourceIslandUuid")) sourceIslandUuid = tag.getUUID("sourceIslandUuid");
        if (tag.hasUUID("linkedIslandUuid")) linkedIslandUuid = tag.getUUID("linkedIslandUuid");
    }
}
