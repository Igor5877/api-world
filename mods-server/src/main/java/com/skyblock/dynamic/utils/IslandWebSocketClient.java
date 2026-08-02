package com.skyblock.dynamic.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import com.skyblock.dynamic.Config;
import com.skyblock.dynamic.nestworld.mods.NestworldModsServer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class IslandWebSocketClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private final URI serverUri;
    private final String ownerUuid;
    private WebSocket webSocket;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    // java.net WebSocket забороняє паралельні sendText — шикуємо їх у ланцюжок.
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public IslandWebSocketClient(URI serverUri, String ownerUuid) {
        this.serverUri = serverUri;
        this.ownerUuid = ownerUuid;
    }

    public void connect() {
        var builder = httpClient.newWebSocketBuilder();
        String key = Config.getApiKey();
        if (!key.isBlank()) builder.header("X-Api-Key", key);
        builder.buildAsync(serverUri, new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket ws) {
                    LOGGER.info("WebSocket connection opened for owner: {}", ownerUuid);
                    WebSocket.Listener.super.onOpen(ws);
                }

                @Override
                public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                    handleMessage(data.toString());
                    return WebSocket.Listener.super.onText(ws, data, last);
                }

                @Override
                public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                    LOGGER.warn("WebSocket closed. Code: {}, Reason: {}. Heartbeat loop will reconnect.", statusCode, reason);
                    return WebSocket.Listener.super.onClose(ws, statusCode, reason);
                }

                @Override
                public void onError(WebSocket ws, Throwable error) {
                    LOGGER.error("WebSocket error", error);
                }
            })
            .thenAccept(ws -> {
                webSocket = ws;
                LOGGER.info("WebSocket successfully connected to {}", serverUri);
            })
            .exceptionally(ex -> {
                LOGGER.error("Failed to connect WebSocket to {}", serverUri, ex);
                return null;
            });
    }

    private void handleMessage(String message) {
        LOGGER.info("Received WebSocket message: {}", message);
        try {
            JsonObject json = GSON.fromJson(message, JsonObject.class);
            if (json == null) return;

            if (json.has("event") && json.get("event").getAsString().equals("TEAM_UPDATED")) {
                if (json.has("payload")) {
                    JsonObject payload = json.getAsJsonObject("payload");
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer()
                        .execute(() -> NestworldModsServer.ISLAND_PROVIDER.processTeamData(payload));
                }
                return;
            }

            if (json.has("event") && json.get("event").getAsString().equals("TEAM_INVITE")) {
                if (json.has("payload")) {
                    handleTeamInvite(json.getAsJsonObject("payload"));
                }
                return;
            }

            String type = json.has("type") ? json.get("type").getAsString() : "";
            switch (type) {
                case "execute_command" -> handleExecuteCommand(json);
                case "pending_update" -> handlePendingUpdate(json);
                default -> { /* інші типи (market тощо) обробляються деінде */ }
            }
        } catch (JsonSyntaxException e) {
            LOGGER.warn("Failed to parse WebSocket message as JSON: {}", message, e);
        } catch (Exception e) {
            LOGGER.error("Failed to handle WebSocket message: {}", message, e);
        }
    }

    /**
     * The API pushes this when someone invites this connection's owner to a
     * team. Previously it was received (logged) and silently dropped — the
     * invited player only ever found out by manually running
     * {@code /nwteam invites}. Surface it in chat if they're online here.
     */
    private void handleTeamInvite(JsonObject payload) {
        String teamName = payload.has("team_name") ? payload.get("team_name").getAsString() : "?";
        String inviterName = payload.has("inviter_name") && !payload.get("inviter_name").isJsonNull()
                ? payload.get("inviter_name").getAsString() : "?";

        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(java.util.UUID.fromString(ownerUuid));
            if (player == null) {
                return; // they'll see it via /nwteam invites on next check
            }
            player.sendSystemMessage(Component.literal(inviterName + " запросив(-ла) тебе до команди '" + teamName + "'.")
                    .withStyle(ChatFormatting.GOLD));
            player.sendSystemMessage(Component.literal("Введи /nwteam invites, щоб прийняти або відхилити.")
                    .withStyle(ChatFormatting.YELLOW));
        });
    }

    /**
     * Команда від системи оновлень (напр. "ftbquests reload"). Виконується на
     * server thread; після виконання шлемо command_ack, щоб API прибрав її з
     * island_pending_commands.
     */
    private void handleExecuteCommand(JsonObject json) {
        if (!json.has("command")) return;
        String command = json.get("command").getAsString();
        long pendingId = json.has("pending_id") ? json.get("pending_id").getAsLong() : -1;

        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            LOGGER.warn("execute_command '{}' received but the server is not available yet.", command);
            return;
        }
        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                LOGGER.info("Executed update command: '{}'", command);
            } catch (Exception e) {
                // Ack надсилаємо все одно: інакше API повторюватиме биту команду вічно.
                LOGGER.error("Update command '{}' failed", command, e);
            }
            if (pendingId > 0) {
                JsonObject ack = new JsonObject();
                ack.addProperty("type", "command_ack");
                ack.addProperty("pending_id", pendingId);
                sendJson(ack);
            }
        });
    }

    /**
     * Повідомлення про оновлення: чат для всіх гравців, kick=true — кік
     * (для critical-оновлень, контейнер зупинить API).
     */
    private void handlePendingUpdate(JsonObject json) {
        String text = json.has("message") ? json.get("message").getAsString() : "Оновлення сервера.";
        boolean kick = json.has("kick") && json.get("kick").getAsBoolean();

        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        server.execute(() -> {
            if (kick) {
                List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
                for (ServerPlayer player : players) {
                    player.connection.disconnect(Component.literal(text));
                }
                LOGGER.info("pending_update: kicked {} player(s): {}", players.size(), text);
            } else {
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("[Оновлення] " + text).withStyle(ChatFormatting.GOLD), false);
                LOGGER.info("pending_update: broadcast to players: {}", text);
            }
        });
    }

    /**
     * Асинхронно надсилає JSON (безпечно викликати з server thread — ніколи
     * не блокує: відправки шикуються в ланцюжок).
     */
    public synchronized void sendJson(JsonObject obj) {
        WebSocket ws = webSocket;
        if (ws == null || ws.isOutputClosed()) return;
        String text = GSON.toJson(obj);
        sendChain = sendChain
            .handle((r, t) -> null) // помилка попередньої відправки не блокує наступні
            .thenCompose(v -> {
                try {
                    return ws.sendText(text, true);
                } catch (Exception e) {
                    LOGGER.warn("WebSocket sendText failed: {}", e.toString());
                    return CompletableFuture.completedFuture(null);
                }
            });
    }

    /**
     * Надсилає JSON і чекає до timeout — лише для shutdown-сигналу, коли
     * сервер все одно зупиняється.
     */
    public void sendJsonBlocking(JsonObject obj, long timeoutMillis) {
        CompletableFuture<?> pending;
        synchronized (this) {
            sendJson(obj); // через спільний ланцюжок — без конкурентних sendText
            pending = sendChain;
        }
        try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            LOGGER.warn("Blocking WebSocket send failed: {}", e.toString());
        }
    }

    public boolean isOpen() {
        return webSocket != null && !webSocket.isInputClosed();
    }

    public void close() {
        if (webSocket != null) {
            webSocket.abort();
        }
    }
}
