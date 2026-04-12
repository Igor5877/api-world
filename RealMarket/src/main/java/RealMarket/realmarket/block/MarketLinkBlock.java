package RealMarket.realmarket.block;

import RealMarket.realmarket.api.MarketIslandApi;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity.BlockMode;
import appeng.api.implementations.items.IMemoryCard;
import appeng.api.implementations.items.MemoryCardMessages;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.UUID;

public class MarketLinkBlock extends Block implements EntityBlock {

    public MarketLinkBlock(Properties p) {
        super(p);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MarketLinkBlockEntity(pos, state);
    }

    /**
     * При розміщенні блок автоматично визначає режим:
     * - SOURCE: якщо mods-server зареєстрував провайдер острова (ми на острові)
     * - SINK:   якщо провайдер повернув null (ми на спавні)
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof MarketLinkBlockEntity entity) {

            UUID islandUuid = MarketIslandApi.getIslandUuid(player.getUUID());

            if (islandUuid != null) {
                // Острів: SOURCE режим
                entity.setMode(BlockMode.SOURCE);
                entity.setSourceIslandUuid(islandUuid);
                player.displayClientMessage(
                        Component.literal("§a[MarketLink] §fРежим: §6SOURCE §f| Острів: §b" + islandUuid), true);
            } else {
                // Спавн: SINK режим — чекає на Memory Card
                entity.setMode(BlockMode.SINK);
                player.displayClientMessage(
                        Component.literal("§a[MarketLink] §fРежим: §9SINK §f| Використай Memory Card острова для зв'язку"), true);
            }
        }
    }

    /**
     * ПКМ з AE2 Memory Card:
     *
     * SOURCE блок → зберігає island_uuid на карту (як "скопіювати налаштування")
     * SINK блок   → читає island_uuid з карти і зв'язується з островом
     *
     * ПКМ без предмета → показує статус
     */
    @SuppressWarnings("deprecation")
    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer)) return InteractionResult.PASS;
        if (!(world.getBlockEntity(pos) instanceof MarketLinkBlockEntity entity)) return InteractionResult.PASS;

        ItemStack held = player.getItemInHand(hand);

        // ── Memory Card взаємодія ────────────────────────────────────────────
        if (held.getItem() instanceof IMemoryCard memCard) {
            if (entity.getMode() == BlockMode.SOURCE) {
                UUID uuid = entity.getSourceIslandUuid();
                if (uuid == null) {
                    player.displayClientMessage(
                            Component.literal("§c[MarketLink] UUID острова ще не встановлено!"), true);
                    return InteractionResult.SUCCESS;
                }
                // Зберігаємо UUID на карту
                CompoundTag tag = new CompoundTag();
                tag.putUUID("island_uuid", uuid);
                memCard.setMemoryCardContents(held, "realmarket:market_link", tag);
                memCard.notifyUser(player, MemoryCardMessages.SETTINGS_SAVED);

            } else { // SINK
                CompoundTag tag = memCard.getData(held);
                if (tag != null && tag.hasUUID("island_uuid")) {
                    UUID linkedUuid = tag.getUUID("island_uuid");
                    entity.setLinkedIslandUuid(linkedUuid);
                    memCard.notifyUser(player, MemoryCardMessages.SETTINGS_LOADED);
                    player.displayClientMessage(
                            Component.literal("§a[MarketLink] §fПов'язано з островом: §b" + linkedUuid), true);
                } else {
                    memCard.notifyUser(player, MemoryCardMessages.INVALID_MACHINE);
                    player.displayClientMessage(
                            Component.literal("§c[MarketLink] Карта порожня! Спочатку скопіюй налаштування з SOURCE блоку."), true);
                }
            }
            return InteractionResult.SUCCESS;
        }

        // ── Статус без предмета ──────────────────────────────────────────────
        if (held.isEmpty()) {
            String modeStr = entity.getMode() == BlockMode.SOURCE ? "§6SOURCE" : "§9SINK";
            UUID active = entity.getActiveIslandUuid();
            String uuidStr = active != null ? "§b" + active : "§cне встановлено";
            player.displayClientMessage(
                    Component.literal("§a[MarketLink] §fРежим: " + modeStr + " §f| UUID: " + uuidStr), true);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }
}
