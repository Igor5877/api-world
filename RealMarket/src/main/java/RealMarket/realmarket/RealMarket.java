package RealMarket.realmarket;

import RealMarket.realmarket.api.AzuriomClient;
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
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import java.util.Collections;
import java.util.Set;
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

    private static final Set<MarketLinkBlockEntity> ACTIVE_LINKS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static Set<MarketLinkBlockEntity> getActiveMarketLinks() {
        return Collections.unmodifiableSet(ACTIVE_LINKS);
    }

    public static void addActiveMarketLink(MarketLinkBlockEntity link) {
        ACTIVE_LINKS.add(link);
    }

    public static void removeActiveMarketLink(MarketLinkBlockEntity link) {
        ACTIVE_LINKS.remove(link);
    }

    public RealMarket(FMLJavaModLoadingContext context) {
        IEventBus bus = context.getModEventBus();
        ModMessages.register();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BLOCK_ENTITIES.register(bus);
        MinecraftForge.EVENT_BUS.register(this);
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
}
