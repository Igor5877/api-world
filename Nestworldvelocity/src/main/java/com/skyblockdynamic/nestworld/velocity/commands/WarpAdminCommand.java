package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * warp-admin <create|suspend|restore|delete> <player_name>
 *
 * Виконується AzLink від імені консолі при старті/завершенні підписки на варп.
 * Azuriom передає {player} (ім'я гравця) — команда сама розв'язує UUID:
 *   1. Якщо гравець онлайн на Velocity — беремо UUID напряму.
 *   2. Якщо офлайн — запит до Mojang API.
 *
 * Azuriom → AzLink → "warp-admin create {player}"
 *   → Velocity → resolve UUID → POST /api/v1/warps/{uuid}/create
 *              → WS → spawn_hub → IslandManager
 */
public class WarpAdminCommand implements SimpleCommand {

    private static final List<String> ACTIONS = List.of("create", "suspend", "restore", "delete");

    private final ApiClient apiClient;
    private final ProxyServer server;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WarpAdminCommand(ApiClient apiClient, ProxyServer server, Logger logger) {
        this.apiClient = apiClient;
        this.server = server;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length < 2) {
            invocation.source().sendMessage(Component.text(
                    "Використання: warp-admin <create|suspend|restore|delete> <player_name>",
                    NamedTextColor.RED));
            return;
        }

        String action = args[0].toLowerCase();
        if (!ACTIONS.contains(action)) {
            invocation.source().sendMessage(Component.text(
                    "Невідома дія: " + action + ". Доступні: " + ACTIONS, NamedTextColor.RED));
            return;
        }

        String playerName = args[1];
        logger.info("[WarpAdmin] {} for player name '{}'", action, playerName);

        resolveUuid(playerName).thenAccept(uuidOpt -> {
            if (uuidOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text(
                        "[WarpAdmin] Не вдалось знайти UUID для гравця: " + playerName, NamedTextColor.RED));
                logger.error("[WarpAdmin] Could not resolve UUID for '{}'", playerName);
                return;
            }

            UUID uuid = uuidOpt.get();
            logger.info("[WarpAdmin] Resolved '{}' → {}", playerName, uuid);

            var future = switch (action) {
                case "create"  -> apiClient.warpCreate(uuid);
                case "suspend" -> apiClient.warpSuspend(uuid);
                case "restore" -> apiClient.warpRestore(uuid);
                case "delete"  -> apiClient.warpDelete(uuid);
                default        -> throw new IllegalStateException("Unreachable");
            };

            future.thenAccept(response -> {
                if (response.isSuccess()) {
                    invocation.source().sendMessage(Component.text(
                            "[WarpAdmin] OK: warp " + action + " для " + playerName + " (" + uuid + ")",
                            NamedTextColor.GREEN));
                    logger.info("[WarpAdmin] Success: {} for {} ({})", action, playerName, uuid);
                } else {
                    invocation.source().sendMessage(Component.text(
                            "[WarpAdmin] Помилка API (" + response.statusCode() + "): " + response.body(),
                            NamedTextColor.RED));
                    logger.error("[WarpAdmin] API error {} for {}: {}", response.statusCode(), playerName, response.body());
                }
            });
        });
    }

    /**
     * Розв'язує UUID гравця за іменем:
     * 1. Онлайн-гравці Velocity (миттєво).
     * 2. GET /api/v1/warps/player-uuid/{name} — шукає в team_members по player_name.
     */
    private CompletableFuture<Optional<UUID>> resolveUuid(String playerName) {
        // 1. Онлайн-гравці
        Optional<Player> online = server.getPlayer(playerName);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(online.get().getUniqueId()));
        }

        // 2. Наш FastAPI (team_members.player_name → player_uuid) — повністю async
        String url = apiClient.getApiUrlBase() + "/warps/player-uuid/" + playerName;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(res -> {
                    if (res.statusCode() == 200) {
                        JsonObject obj = JsonParser.parseString(res.body()).getAsJsonObject();
                        UUID uuid = UUID.fromString(obj.get("player_uuid").getAsString());
                        logger.info("[WarpAdmin] Resolved '{}' → {}", playerName, uuid);
                        return Optional.of(uuid);
                    } else if (res.statusCode() == 404) {
                        logger.warn("[WarpAdmin] Player '{}' not found in API", playerName);
                    } else {
                        logger.error("[WarpAdmin] API {} for '{}': {}", res.statusCode(), playerName, res.body());
                    }
                    return Optional.<UUID>empty();
                })
                .exceptionally(ex -> {
                    logger.error("[WarpAdmin] UUID lookup error for '{}': {} — {}",
                            playerName, ex.getClass().getSimpleName(), ex.getMessage());
                    return Optional.empty();
                });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Тільки консоль або гравці з правом warp.admin
        return !(invocation.source() instanceof Player)
                || invocation.source().hasPermission("warp.admin");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) return ACTIONS;
        return List.of();
    }
}
