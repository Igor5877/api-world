package com.real.market;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.real.market.blocks.MarketLinkBlock;
import com.real.market.blocks.MarketLinkBlockEntity;
import com.real.market.net.MarketSyncTask;
import com.real.market.net.MarketWebSocketClient;
import com.real.market.Config;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks; // ДОДАНО

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID; // ДОДАНО
import java.util.concurrent.CompletableFuture;

@Mod("realmarket")
public class RealMarket {
    public static final String MODID = "realmarket";
    public static MarketWebSocketClient wsClient;
    private int tickCounter = 0;

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, MODID);

    public static final RegistryObject<Block> MARKET_LINK = BLOCKS.register("market_link", () -> new MarketLinkBlock(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)));
    public static final RegistryObject<BlockEntityType<MarketLinkBlockEntity>> MARKET_LINK_BE = BLOCK_ENTITIES.register("market_link", () -> BlockEntityType.Builder.of(MarketLinkBlockEntity::new, MARKET_LINK.get()).build(null));

    public RealMarket() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);

        // ВАЖЛИВО: Перевірте, щоб у файлі Config.java поле SPEC було PUBLIC
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "realmarket-common.toml");

        MinecraftForge.EVENT_BUS.addListener(this::onCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerStarted(ServerStartedEvent event) {
        String apiUrl = Config.getApiBaseUrl();
        if (apiUrl != null) {
            String wsUrl = apiUrl.replace("http", "ws") + "market/ws";
            try {
                wsClient = new MarketWebSocketClient(new URI(wsUrl));
                wsClient.connect();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        syncAll(event.getServer().getLevel(Level.OVERWORLD));
        if (wsClient != null) {
            wsClient.close();
        }
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tickCounter++;
            if (tickCounter >= 3600) { 
                tickCounter = 0;
                syncAll(event.getServer().getLevel(Level.OVERWORLD));
            }
        }
    }

    public static final Set<MarketLinkBlockEntity> TRACKED_BEs = new HashSet<>();

    private void syncAll(Level level) {
        if (level == null || wsClient == null || !wsClient.isOpen()) return;
        MarketData marketData = MarketData.get(level);
        for (MarketLinkBlockEntity be : TRACKED_BEs) {
            if (!be.isRemoved()) {
                UUID islandId = be.getIslandId();
                if (islandId == null) continue;
                
                MEStorage inventory = be.getInventory();
                if (inventory != null) {
                    appeng.api.stacks.KeyCounter stacks = inventory.getAvailableStacks();
                    CompletableFuture.runAsync(new MarketSyncTask(islandId, stacks, wsClient, marketData));
                }
            }
        }
    }

    public static void processDebts(JsonArray debts) {
        for (JsonElement element : debts) {
            processDebt(element.getAsJsonObject(), 0);
        }
    }

    private static void processDebt(JsonObject debt, int retryCount) {
        String debtId = debt.get("debt_id").getAsString();
        String itemId = debt.get("item_id").getAsString();
        long amount = debt.get("amount").getAsLong();

        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (item == null) return;

        boolean resolved = false;
        for (MarketLinkBlockEntity be : TRACKED_BEs) {
            MEStorage inventory = be.getInventory();
            if (inventory != null) {
                AEItemKey key = AEItemKey.of(item);
                long extracted = inventory.extract(key, amount, Actionable.MODULATE, null);
                if (extracted >= amount) {
                    sendDebtResolved(debtId, "success");
                    resolved = true;
                    break;
                } else if (extracted > 0) {
                    inventory.insert(key, extracted, Actionable.MODULATE, null);
                }
            }
        }

        if (!resolved) {
            if (retryCount < 3) {
                ServerLifecycleHooks.getCurrentServer().execute(() -> {
                    final int nextRetry = retryCount + 1;
                    CompletableFuture.delayedExecutor(1, java.util.concurrent.TimeUnit.MINUTES).execute(() -> {
                        ServerLifecycleHooks.getCurrentServer().execute(() -> processDebt(debt, nextRetry));
                    });
                });
            } else {
                sendDebtResolved(debtId, "failed_insufficient_items");
            }
        }
    }

    private static void sendDebtResolved(String debtId, String status) {
        if (wsClient != null && wsClient.isOpen()) {
            JsonObject json = new JsonObject();
            json.addProperty("action", "market_debt_resolved");
            json.addProperty("debt_id", debtId);
            json.addProperty("status", status);
            wsClient.send(json.toString());
        }
    }

    private void onCommands(RegisterCommandsEvent event) {
        MarketCommands.register(event.getDispatcher(), event.getBuildContext());
    }
}