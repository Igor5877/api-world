package com.skyblock.sales;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("sales_addon")
public class SalesAddon {
    private static final Logger LOGGER = LogManager.getLogger();

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "sales_addon");
    public static final DeferredRegister<net.minecraft.world.item.Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, "sales_addon");
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "sales_addon");

    public static final RegistryObject<Block> SALES_TERMINAL_BLOCK = BLOCKS.register("sales_terminal", SalesTerminalBlock::new);
    public static final RegistryObject<net.minecraft.world.item.Item> SALES_TERMINAL_ITEM = ITEMS.register("sales_terminal",
            () -> new net.minecraft.world.item.BlockItem(SALES_TERMINAL_BLOCK.get(), new net.minecraft.world.item.Item.Properties()));
    public static final RegistryObject<BlockEntityType<SalesTerminalBlockEntity>> SALES_TERMINAL_BLOCK_ENTITY = BLOCK_ENTITIES.register("sales_terminal",
            () -> BlockEntityType.Builder.of(SalesTerminalBlockEntity::new, SALES_TERMINAL_BLOCK.get()).build(null));

    public static final RegistryObject<Block> SALES_RECEIVER_BLOCK = BLOCKS.register("sales_receiver", SalesReceiverBlock::new);
    public static final RegistryObject<net.minecraft.world.item.Item> SALES_RECEIVER_ITEM = ITEMS.register("sales_receiver",
            () -> new net.minecraft.world.item.BlockItem(SALES_RECEIVER_BLOCK.get(), new net.minecraft.world.item.Item.Properties()));
    public static final RegistryObject<BlockEntityType<SalesReceiverBlockEntity>> SALES_RECEIVER_BLOCK_ENTITY = BLOCK_ENTITIES.register("sales_receiver",
            () -> BlockEntityType.Builder.of(SalesReceiverBlockEntity::new, SALES_RECEIVER_BLOCK.get()).build(null));

    public static final RegistryObject<Block> SALES_VENDING_BLOCK = BLOCKS.register("sales_vending", SalesVendingBlock::new);
    public static final RegistryObject<net.minecraft.world.item.Item> SALES_VENDING_ITEM = ITEMS.register("sales_vending",
            () -> new net.minecraft.world.item.BlockItem(SALES_VENDING_BLOCK.get(), new net.minecraft.world.item.Item.Properties()));
    public static final RegistryObject<BlockEntityType<SalesVendingBlockEntity>> SALES_VENDING_BLOCK_ENTITY = BLOCK_ENTITIES.register("sales_vending",
            () -> BlockEntityType.Builder.of(SalesVendingBlockEntity::new, SALES_VENDING_BLOCK.get()).build(null));

    public static SalesWebSocketClient wsClient;

    public SalesAddon() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        SalesConfig.register(ModLoadingContext.get());

        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);

        modEventBus.addListener(this::setup);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("Sales Addon initialized!");
    }

    private void onServerStarting(net.minecraftforge.event.server.ServerStartingEvent event) {
        // Initialize WebSocket connection on server start
        try {
            String apiUrl = SalesConfig.COMMON.apiUrl.get();
            int islandId = SalesConfig.COMMON.islandId.get();

            // Convert http://.../api/v1/sales to ws://.../ws/islandId
            String wsUrl = apiUrl.replace("http://", "ws://").replace("https://", "wss://");
            wsUrl = wsUrl.substring(0, wsUrl.indexOf("/api")) + "/ws/" + islandId;

            wsClient = new SalesWebSocketClient(new java.net.URI(wsUrl));
            wsClient.connect();
            LOGGER.info("Connecting to Sales WebSocket: " + wsUrl);
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Sales WebSocket", e);
        }
    }
}
