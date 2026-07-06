package com.skyblock.dynamic.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.Config;
import com.skyblock.dynamic.SkyBlockMod;
import dev.ftb.mods.ftblibrary.snbt.SNBT;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.net.SyncIslandDataMessage;
import dev.ftb.mods.ftbquests.quest.IslandData;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Syncs FTB Quests progress between island servers and the API.
 *
 * The island is the source of truth: it uploads its progress snapshot
 * (world/ftbquests/{owner}.snbt) on player logout and on server stop.
 * The spawn/hub server downloads the snapshot when a player joins and
 * applies it in memory, so players see their island progress read-only.
 */
public final class QuestProgressSync {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    /** Minimum ms between uploads so a mass logout doesn't spam the API. */
    private static final long UPLOAD_DEBOUNCE_MS = 5000;
    private static final AtomicLong lastUploadAt = new AtomicLong(0);

    private QuestProgressSync() {
    }

    private static HttpRequest.Builder apiRequest(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                .header("Content-Type", "application/json");
        String key = Config.getApiKey();
        if (!key.isBlank()) b.header("X-Api-Key", key);
        return b;
    }

    private static Path snbtPath(MinecraftServer server, UUID islandId) {
        return server.getWorldPath(ServerQuestFile.FTBQUESTS_DATA).resolve(islandId + ".snbt");
    }

    // ── island side: upload ──────────────────────────────────────────

    /**
     * Uploads the island's quest progress snapshot to the API.
     *
     * Must be called on the server thread (it serializes live quest data).
     * The HTTP call itself is asynchronous. No-op on non-island servers.
     *
     * @param server The Minecraft server.
     * @param force  If true, the debounce window is ignored (server stop).
     */
    public static void uploadIslandProgress(MinecraftServer server, boolean force) {
        if (!SkyBlockMod.isIslandServer() || SkyBlockMod.getOwnerUuid() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastUploadAt.get() < UPLOAD_DEBOUNCE_MS) {
            return;
        }

        try {
            ServerQuestFile file = ServerQuestFile.INSTANCE;
            if (file == null) {
                return;
            }
            UUID owner = UUID.fromString(SkyBlockMod.getOwnerUuid());
            IslandData data = file.getNullableIslandData(owner);
            if (data == null) {
                LOGGER.debug("QuestProgressSync: No island data for owner {} yet, nothing to upload.", owner);
                return;
            }
            String snbt = String.join("\n", SNBT.writeLines(data.serializeNBT()));
            lastUploadAt.set(now);

            JsonObject payload = new JsonObject();
            payload.addProperty("snbt", snbt);
            String url = Config.getApiBaseUrl() + "islands/" + owner + "/quest-progress";
            HttpRequest request = apiRequest(url)
                    .PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                    .build();

            if (force) {
                // Server stop: send synchronously, otherwise the JVM exits
                // before the async request ever leaves the machine.
                try {
                    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() >= 300) {
                        LOGGER.warn("QuestProgressSync: Final upload for owner {} failed with HTTP {}.",
                                owner, response.statusCode());
                    } else {
                        LOGGER.info("QuestProgressSync: Final upload for owner {} done ({} bytes).",
                                owner, snbt.length());
                    }
                } catch (Exception e) {
                    LOGGER.warn("QuestProgressSync: Final upload for owner {} failed: {}", owner, e.toString());
                }
                return;
            }

            HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                            LOGGER.warn("QuestProgressSync: Upload for owner {} failed with HTTP {}.",
                                    owner, response.statusCode());
                        } else {
                            LOGGER.info("QuestProgressSync: Uploaded quest progress for owner {} ({} bytes).",
                                    owner, snbt.length());
                        }
                    })
                    .exceptionally(ex -> {
                        LOGGER.warn("QuestProgressSync: Upload for owner {} failed: {}", owner, ex.toString());
                        return null;
                    });
        } catch (Throwable t) {
            // Progress sync must never take the server down.
            LOGGER.error("QuestProgressSync: Unexpected error during upload.", t);
        }
    }

    // ── spawn side: fetch and apply ──────────────────────────────────

    /**
     * Fetches the player's island quest progress from the API and applies it.
     *
     * Runs fully asynchronously; the apply step is scheduled back on the
     * server thread. No-op on island servers (they own their data).
     *
     * @param server The Minecraft server.
     * @param player The player who just joined the spawn/hub.
     */
    public static void fetchAndApply(MinecraftServer server, ServerPlayer player) {
        if (SkyBlockMod.isIslandServer()) {
            return;
        }
        UUID playerUuid = player.getUUID();

        CompletableFuture
                .supplyAsync(() -> com.skyblock.dynamic.nestworld.mods.NestworldModsServer
                        .ISLAND_PROVIDER.getCachedTeamId(playerUuid))
                .thenCompose(owner -> {
                    if (owner == null) {
                        LOGGER.debug("QuestProgressSync: No island owner for player {}, skipping fetch.", playerUuid);
                        return CompletableFuture.completedFuture(null);
                    }
                    String url = Config.getApiBaseUrl() + "islands/" + owner + "/quest-progress";
                    HttpRequest request = apiRequest(url).GET().build();
                    return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                            .thenAccept(response -> {
                                String snbt = null;
                                if (response.statusCode() == 404) {
                                    LOGGER.debug("QuestProgressSync: No stored progress for owner {}.", owner);
                                } else if (response.statusCode() >= 300) {
                                    LOGGER.warn("QuestProgressSync: Fetch for owner {} failed with HTTP {}.",
                                            owner, response.statusCode());
                                } else {
                                    JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                                    snbt = json.get("snbt").getAsString();
                                }
                                // Always lock the hub-side data (read-only view),
                                // even when there is no snapshot to apply yet.
                                final String snbtToApply = snbt;
                                server.execute(() -> applyOnServerThread(server, player, owner, snbtToApply));
                            });
                })
                .exceptionally(ex -> {
                    LOGGER.warn("QuestProgressSync: Fetch for player {} failed: {}", playerUuid, ex.toString());
                    return null;
                });
    }

    /**
     * Writes the snapshot to disk, loads it into the live quest file, locks it
     * (the hub is a read-only view) and re-syncs the player's client.
     * Server thread only. A null snbt still locks the hub-side data.
     */
    private static void applyOnServerThread(MinecraftServer server, ServerPlayer player, UUID owner, String snbt) {
        try {
            ServerQuestFile file = ServerQuestFile.INSTANCE;
            if (file == null) {
                return;
            }
            IslandData data = file.getOrCreateIslandData(owner);

            if (snbt != null) {
                Path path = snbtPath(server, owner);
                Files.createDirectories(path.getParent());
                Files.writeString(path, snbt, StandardCharsets.UTF_8);

                SNBTCompoundTag nbt = SNBT.read(path);
                if (nbt == null) {
                    LOGGER.warn("QuestProgressSync: Stored snapshot for owner {} is not valid SNBT.", owner);
                } else {
                    data.deserializeNBT(nbt);
                }
            }

            // NOTE: no setLocked(true) here — the fork's "locked" flag blocks the
            // whole quest GUI client-side. Read-only is enforced server-side by
            // the fork's FTBQUESTS_READONLY env gate on the hub instead.

            if (player.connection != null) {
                new SyncIslandDataMessage(data, true).sendTo(player);
            }
            LOGGER.info("QuestProgressSync: Applied island quest progress for owner {} to hub view of player {}.",
                    owner, player.getGameProfile().getName());
        } catch (Throwable t) {
            LOGGER.error("QuestProgressSync: Failed to apply quest progress for owner {}.", owner, t);
        }
    }
}
