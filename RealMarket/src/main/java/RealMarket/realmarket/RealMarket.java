package RealMarket.realmarket;

import RealMarket.realmarket.api.AzuriomClient;
import RealMarket.realmarket.block.TradeBlock;
import RealMarket.realmarket.commands.ModCommands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
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

@Mod(RealMarket.MODID)
public class RealMarket {
    public static final String MODID = "realmarket";
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final RegistryObject<Block> TRADE_BLOCK = BLOCKS.register("trade_station",
            () -> new TradeBlock(BlockBehaviour.Properties.of().strength(-1f).noOcclusion().dynamicShape()));

    public static final RegistryObject<Item> TRADE_ITEM = ITEMS.register("trade_station",
            () -> new BlockItem(TRADE_BLOCK.get(), new Item.Properties()));

    public RealMarket() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        MinecraftForge.EVENT_BUS.register(this);
        
        RealMarket.realmarket.world.IslandManager.loadPrices();
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Синхронізуємо гравця з сайтом, щоб отримати його числовий ID
            AzuriomClient.sync(player.getUUID(), player.getName().getString());
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ModCommands.register(event.getDispatcher());
    }
}