package RealMarket.realmarket;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.api.MarketSyncManager;
import RealMarket.realmarket.block.MarketLinkBlock;
import RealMarket.realmarket.block.TradeBlock;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;
import RealMarket.realmarket.commands.ModCommands;
import RealMarket.realmarket.network.ModMessages;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(RealMarket.MODID)
public class RealMarket {
    public static final String MODID = "realmarket";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);

    public static final RegistryObject<Block> TRADE_BLOCK = BLOCKS.register("trade_station",
            () -> new TradeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .strength(-1f, 3600000.0F)
                    .noOcclusion()
                    .dynamicShape()));

    public static final RegistryObject<Item> TRADE_ITEM = ITEMS.register("trade_station",
            () -> new BlockItem(TRADE_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<Block> MARKET_LINK_BLOCK = BLOCKS.register("market_link",
            () -> new MarketLinkBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLUE)
                    .strength(2f, 6f)
                    .noOcclusion()));

    public static final RegistryObject<Item> MARKET_LINK_ITEM = ITEMS.register("market_link",
            () -> new BlockItem(MARKET_LINK_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<MarketLinkBlockEntity>> MARKET_LINK_BE =
            BLOCK_ENTITIES.register("market_link",
                    () -> BlockEntityType.Builder
                            .of(MarketLinkBlockEntity::new, MARKET_LINK_BLOCK.get())
                            .build(null));

    // Використовуємо BlockPos як ключ для уникнення дублікатів
    private static final ConcurrentHashMap<net.minecraft.core.BlockPos, MarketLinkBlockEntity> ACTIVE_LINKS =
            new ConcurrentHashMap<>();

    public static java.util.Collection<MarketLinkBlockEntity> getActiveMarketLinks() {
        return ACTIVE_LINKS.values();
    }

    public static void addActiveMarketLink(MarketLinkBlockEntity link) {
        ACTIVE_LINKS.put(link.getBlockPos(), link);
    }

    public static void removeActiveMarketLink(MarketLinkBlockEntity link) {
        ACTIVE_LINKS.remove(link.getBlockPos(), link);
    }

    public RealMarket(FMLJavaModLoadingContext context) {
        IEventBus bus = context.getModEventBus();
        ModMessages.register();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        
        MinecraftForge.EVENT_BUS.register(this);
        IslandManager.initPaths();
        IslandManager.loadPrices();
        IslandManager.loadSlots();
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AzuriomClient.sync(player.getUUID(), player.getName().getString());
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        String uuidStr = System.getenv("ISLAND_UUID");
        if (uuidStr == null || uuidStr.isEmpty()) {
            uuidStr = "00000000-0000-0000-0000-000000000001";
        }
        System.out.println("[RealMarket] Server starting... Initializing Market Sync Manager for Island: " + uuidStr);
        MarketSyncManager.init(UUID.fromString(uuidStr));
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        System.out.println("[RealMarket] Server stopping... Shutting down Market Sync Manager.");
        MarketSyncManager.shutdown();
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel)) return;
        if (!(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)) return;

        // Адміни можуть ламати
        if (player.hasPermissions(2)) return;

        if (!event.getState().is(MARKET_LINK_BLOCK.get())) return;

        var be = event.getLevel().getBlockEntity(event.getPos());
        if (be instanceof MarketLinkBlockEntity link) {
            if (link.getActiveIslandUuid() != null) {
                event.setResult(Event.Result.DENY);
                event.setCanceled(true);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§c[Market] Цей блок захищений і не може бути зламаний!"));
            }
        }
    }
}