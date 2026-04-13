package com.skyblockdynamic.nestworld.velocity.commands;

import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * /warp-admin <create|suspend|restore|delete> <player_uuid>
 *
 * Виконується AzLink від імені консолі при старті/завершенні підписки на варп.
 * Звичайні гравці не мають доступу (hasPermission перевіряє warp.admin).
 *
 * Azuriom → AzLink → /warp-admin create <uuid>
 *                  → Velocity → POST /api/v1/warps/{uuid}/create
 *                             → WS → spawn_hub → IslandManager
 */
public class WarpAdminCommand implements SimpleCommand {

    private static final List<String> ACTIONS = List.of("create", "suspend", "restore", "delete");

    private final ApiClient apiClient;
    private final Logger logger;

    public WarpAdminCommand(ApiClient apiClient, Logger logger) {
        this.apiClient = apiClient;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length < 2) {
            invocation.source().sendMessage(Component.text(
                    "Використання: /warp-admin <create|suspend|restore|delete> <player_uuid>",
                    NamedTextColor.RED));
            return;
        }

        String action = args[0].toLowerCase();
        if (!ACTIONS.contains(action)) {
            invocation.source().sendMessage(Component.text(
                    "Невідома дія: " + action + ". Доступні: " + ACTIONS, NamedTextColor.RED));
            return;
        }

        UUID playerUuid;
        try {
            playerUuid = UUID.fromString(args[1]);
        } catch (IllegalArgumentException e) {
            invocation.source().sendMessage(Component.text(
                    "Невалідний UUID: " + args[1], NamedTextColor.RED));
            return;
        }

        logger.info("[WarpAdmin] Command: {} for {}", action, playerUuid);

        var future = switch (action) {
            case "create"  -> apiClient.warpCreate(playerUuid);
            case "suspend" -> apiClient.warpSuspend(playerUuid);
            case "restore" -> apiClient.warpRestore(playerUuid);
            case "delete"  -> apiClient.warpDelete(playerUuid);
            default        -> throw new IllegalStateException("Unreachable");
        };

        future.thenAccept(response -> {
            if (response.isSuccess()) {
                invocation.source().sendMessage(Component.text(
                        "[WarpAdmin] OK: warp " + action + " для " + playerUuid, NamedTextColor.GREEN));
                logger.info("[WarpAdmin] Success: {} for {}", action, playerUuid);
            } else {
                invocation.source().sendMessage(Component.text(
                        "[WarpAdmin] Помилка API (" + response.statusCode() + "): " + response.body(),
                        NamedTextColor.RED));
                logger.error("[WarpAdmin] API error {}: {} for {}", response.statusCode(), response.body(), playerUuid);
            }
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Тільки консоль або гравці з правом warp.admin (для AzLink це консоль)
        return !(invocation.source() instanceof com.velocitypowered.api.proxy.Player)
                || invocation.source().hasPermission("warp.admin");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) return ACTIONS;
        return List.of();
    }
}
