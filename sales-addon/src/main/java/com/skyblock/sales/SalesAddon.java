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
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, "sales_addon");

    public static final RegistryObject<Block> SALES_TERMINAL_BLOCK = BLOCKS.register("sales_terminal", SalesTerminalBlock::new);
    public static final RegistryObject<BlockEntityType<SalesTerminalBlockEntity>> SALES_TERMINAL_BLOCK_ENTITY = BLOCK_ENTITIES.register("sales_terminal",
            () -> BlockEntityType.Builder.of(SalesTerminalBlockEntity::new, SALES_TERMINAL_BLOCK.get()).build(null));

    public SalesAddon() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        SalesConfig.register(ModLoadingContext.get());

        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);

        modEventBus.addListener(this::setup);
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("Sales Addon initialized!");
    }
}
