package RealMarket.realmarket;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.block.TradeBlock;
import RealMarket.realmarket.commands.ModCommands;
import RealMarket.realmarket.world.IslandManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import RealMarket.realmarket.api.MarketSyncManager;
import RealMarket.realmarket.block.MarketLinkBlock;
import RealMarket.realmarket.blockentity.MarketLinkBlockEntity;

import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod(RealMarket.MODID)
public class RealMarket {
    public static final String MODID = "realmarket";

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);

    // Реєстрація нашого торгового блоку
    public static final RegistryObject<Block> TRADE_BLOCK = BLOCKS.register("trade_station",
            () -> new TradeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .instrument(NoteBlockInstrument.BASEDRUM)
                    .strength(-1f, 3600000.0F)
                    .noOcclusion()
                    .dynamicShape()
            ));

    public static final RegistryObject<Item> TRADE_ITEM = ITEMS.register("trade_station",
            () -> new BlockItem(TRADE_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<Block> MARKET_LINK_BLOCK = BLOCKS.register("market_link",
            () -> new MarketLinkBlock(BlockBehaviour.Properties.of().strength(1.5f).dynamicShape()));

    public static final RegistryObject<Item> MARKET_LINK_ITEM = ITEMS.register("market_link",
            () -> new BlockItem(MARKET_LINK_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<MarketLinkBlockEntity>> MARKET_LINK_BE = BLOCK_ENTITIES.register("market_link",
            () -> BlockEntityType.Builder.of(MarketLinkBlockEntity::new, MARKET_LINK_BLOCK.get()).build(null));

    // Store active links on the server
    private static final Set<MarketLinkBlockEntity> activeMarketLinks = ConcurrentHashMap.newKeySet();

    public static void addActiveMarketLink(MarketLinkBlockEntity link) {
        activeMarketLinks.add(link);
    }

    public static void removeActiveMarketLink(MarketLinkBlockEntity link) {
        activeMarketLinks.remove(link);
    }

    public static Set<MarketLinkBlockEntity> getActiveMarketLinks() {
        return activeMarketLinks;
    }

    // ВИПРАВЛЕНИЙ КОНСТРУКТОР: приймає context і реєструє всі блоки
    public RealMarket(FMLJavaModLoadingContext context) {
        IEventBus bus = context.getModEventBus();

        // 1. Реєструємо мережу (з вашої другої версії)
        ModMessages.register();

        // 2. Реєструємо всі типи об'єктів
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);

        // 3. Підписуємось на події Forge
        MinecraftForge.EVENT_BUS.register(this);

        // 4. Завантажуємо ціни
        IslandManager.loadPrices();
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
        // We need an island UUID to identify the server to the backend.
        // For real integration, you'd fetch this from NestworldModsServer provider or env variable.
        // E.g., UUID islandUuid = NestworldModsServer.ISLAND_PROVIDER.getIslandContext().getUuid();
        // Since we are mocking, we fetch an arbitrary UUID or rely on config if needed.
        // In this implementation, we will assume a known UUID for testing.
        String uuidStr = System.getenv("ISLAND_UUID");
        if (uuidStr == null || uuidStr.isEmpty()) {
            // Default placeholder UUID for the island
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
}