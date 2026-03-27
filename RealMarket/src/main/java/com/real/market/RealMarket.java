package com.real.market;

import com.real.market.blocks.MarketLinkBlock;
import com.real.market.blocks.MarketLinkBlockEntity;
import com.real.market.net.MarketSyncTask;
import com.real.market.net.MarketWebSocketClient;
import com.real.market.Config;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
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
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Mod("realmarket")
public class RealMarket {
    public static final String MODID = "realmarket";
    private static final Logger LOGGER = LogUtils.getLogger();
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

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.real.market.Config.SPEC, "realmarket-common.toml");

        MinecraftForge.EVENT_BUS.addListener(this::onCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
    }

    private void onServerStarted(ServerStartedEvent event) {
        String apiUrl = com.skyblock.dynamic.Config.getApiBaseUrl();
        if (apiUrl != null) {
            if (!apiUrl.endsWith("/")) {
                apiUrl += "/";
            }
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
            if (tickCounter >= 3600) { // ~3 mins
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
                    // Snapshot AE2 stacks on the main thread
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
        String sellerIslandIdStr = debt.has("seller_island_id") ? debt.get("seller_island_id").getAsString() : null;
        long amount = debt.get("amount").getAsLong();

        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        if (item == null) return;

        boolean resolved = false;
        for (MarketLinkBlockEntity be : TRACKED_BEs) {
            // Verify that this Market Link belongs to the seller
            if (sellerIslandIdStr != null && !be.getIslandId().toString().equals(sellerIslandIdStr)) {
                continue;
            }

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
                // Retry in 1 minute (1200 ticks)
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

    public static void updateHubCache(UUID islandId, JsonArray items) {
        Level level = ServerLifecycleHooks.getCurrentServer().getLevel(Level.OVERWORLD);
        if (level == null) return;
        MarketData data = MarketData.get(level);
        Map<Item, MarketData.Entry> islandStocks = data.stocks.computeIfAbsent(islandId, id -> new HashMap<>());
        islandStocks.clear();
        for (JsonElement el : items) {
            JsonObject obj = el.getAsJsonObject();
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(obj.get("item_id").getAsString()));
            if (item != null && item != Items.AIR) {
                double price = obj.get("price").getAsDouble();
                long amount = obj.get("amount").getAsLong();
                islandStocks.put(item, new MarketData.Entry(amount, 1000, price));
            }
        }
        data.setDirty();
        LOGGER.info("[RealMarket] Updated Hub cache for island {}", islandId);
    }

    private void onCommands(RegisterCommandsEvent event) {
        LOGGER.info("[RealMarket] Registering commands...");
        MarketCommands.register(event.getDispatcher(), event.getBuildContext());
    }
}