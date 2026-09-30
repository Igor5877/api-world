package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.skyblockdynamic.nestworld.velocity.network.ApiResponse;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /islandadmin status|info|set|member|start|stop|restart|join|list
 *
 * Адмінська діагностика й керування островом через API — ніколи напряму
 * через DB/LXD/мод. Read-only (status/info/list) доступне за island.admin;
 * зміна persistent-даних (set/member) — island.admin.modify; lifecycle
 * (start/stop/restart/join) — island.admin.lifecycle. Наявність island.admin
 * саму по собі не дає права на дії з іншими рівнями.
 *
 * Audit trail для mutation/lifecycle-операцій — не окрема підсистема, а
 * той самий island_events журнал, який показує status (API-сторона пише
 * туди admin_owner_change/admin_restart).
 */
public class IslandAdminCommand implements SimpleCommand {

    private static final List<String> SUBCOMMANDS = List.of("status", "info", "set", "member", "start", "stop", "restart", "join", "list", "hold", "release", "holds");
    private static final List<String> SET_FIELDS = List.of("owner");
    private static final List<String> MEMBER_ACTIONS = List.of("add", "remove");

    private final ApiClient apiClient;
    private final ProxyServer server;
    private final Logger logger;
    private final PluginConfig config;
    private final MyIslandCommand myIslandCommand;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public IslandAdminCommand(ApiClient apiClient, ProxyServer server, Logger logger, PluginConfig config,
                              MyIslandCommand myIslandCommand) {
        this.apiClient = apiClient;
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.myIslandCommand = myIslandCommand;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            sendUsage(invocation);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status" -> runWithPermission(invocation, "island.admin", () -> handleStatus(invocation, args, true));
            case "info" -> runWithPermission(invocation, "island.admin", () -> handleStatus(invocation, args, false));
            case "list" -> runWithPermission(invocation, "island.admin", () -> handleList(invocation, args));
            case "set" -> runWithPermission(invocation, "island.admin.modify", () -> handleSet(invocation, args));
            case "member" -> runWithPermission(invocation, "island.admin.modify", () -> handleMember(invocation, args));
            case "start" -> runWithPermission(invocation, "island.admin.lifecycle", () -> handleStart(invocation, args));
            case "stop" -> runWithPermission(invocation, "island.admin.lifecycle", () -> handleStop(invocation, args));
            case "restart" -> runWithPermission(invocation, "island.admin.lifecycle", () -> handleRestart(invocation, args));
            case "join" -> runWithPermission(invocation, "island.admin.lifecycle", () -> handleJoin(invocation, args));
            case "hold" -> runWithPermission(invocation, "island.admin.lifecycle", () -> handleHold(invocation, args));
            case "release" -> runWithPermission(invocation, "island.admin.lifecycle", () -> handleRelease(invocation, args));
            case "holds" -> runWithPermission(invocation, "island.admin", () -> handleHolds(invocation));
            default -> sendUsage(invocation);
        }
    }

    private void sendUsage(Invocation invocation) {
        invocation.source().sendMessage(Component.text(
                "Використання: /islandadmin <status|info|set|member|start|stop|restart|join|list|hold|release|holds> <name> ...",
                NamedTextColor.RED));
    }

    /** island.admin сам по собі не дає прав на modify/lifecycle — перевірка на кожну підкоманду окремо. */
    private void runWithPermission(Invocation invocation, String node, Runnable action) {
        if (invocation.source() instanceof Player && !invocation.source().hasPermission(node)) {
            invocation.source().sendMessage(Component.text("✖ Permission denied.", NamedTextColor.RED));
            return;
        }
        action.run();
    }

    // ── status / info ───────────────────────────────────────────────

    private void handleStatus(Invocation invocation, String[] args, boolean withEvents) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text(
                    "Використання: /islandadmin " + args[0] + " <player_name>", NamedTextColor.RED));
            return;
        }
        String playerName = args[1];
        resolveUuid(playerName).thenAccept(uuidOpt -> {
            if (uuidOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text(
                        "✖ Player UUID not found: " + playerName, NamedTextColor.RED));
                return;
            }
            UUID uuid = uuidOpt.get();

            CompletableFuture<ApiResponse> detailsFuture = apiClient.getIslandDetails(uuid);
            CompletableFuture<ApiResponse> diagFuture = apiClient.getIslandDiagnostics(uuid);
            CompletableFuture<ApiResponse> eventsFuture = withEvents
                    ? apiClient.getIslandEvents(uuid, 5)
                    : CompletableFuture.completedFuture(null);

            detailsFuture.thenCombine(diagFuture, (detailsRes, diagRes) -> new ApiResponse[]{detailsRes, diagRes})
                    .thenCombine(eventsFuture, (pair, eventsRes) -> {
                        ApiResponse detailsRes = pair[0];
                        ApiResponse diagRes = pair[1];

                        if (!detailsRes.isSuccess()) {
                            invocation.source().sendMessage(Component.text(
                                    "✖ Island not found for " + playerName + " (" + uuid + ").", NamedTextColor.RED));
                            return null;
                        }
                        JsonObject island = JsonParser.parseString(detailsRes.body()).getAsJsonObject();
                        JsonObject diag = diagRes != null && diagRes.isSuccess()
                                ? JsonParser.parseString(diagRes.body()).getAsJsonObject() : null;

                        JsonArray events = null;
                        if (withEvents) {
                            if (eventsRes != null && eventsRes.isSuccess()) {
                                events = JsonParser.parseString(eventsRes.body()).getAsJsonArray();
                            }
                        }

                        sendStatusBlock(invocation, playerName, uuid, island, diag, events);
                        return null;
                    })
                    .exceptionally(ex -> {
                        logger.error("[IslandAdmin] status lookup failed for {}: {}", playerName, ex.getMessage(), ex);
                        invocation.source().sendMessage(Component.text("✖ Operation failed.", NamedTextColor.RED));
                        return null;
                    });
        });
    }

    private void sendStatusBlock(Invocation invocation, String playerName, UUID uuid, JsonObject island,
                                  JsonObject diag, JsonArray events) {
        String dbStatus = getStringOrNull(island, "status");
        boolean ready = island.has("minecraft_ready") && !island.get("minecraft_ready").isJsonNull()
                && island.get("minecraft_ready").getAsBoolean();
        String ip = getStringOrNull(island, "internal_ip_address");
        String container = getStringOrNull(island, "container_name");
        String heartbeatStr = getStringOrNull(island, "last_heartbeat_at");
        Double tps = getDoubleOrNull(island, "last_tps");
        Double mspt = getDoubleOrNull(island, "last_mspt");

        Long heartbeatAgeSec = heartbeatStr == null ? null : ageSeconds(heartbeatStr);
        boolean heartbeatStale = heartbeatAgeSec == null || heartbeatAgeSec > config.getIslandAdminHeartbeatStaleSeconds();

        boolean consistent = diag == null || !diag.has("consistent") || diag.get("consistent").getAsBoolean();
        String liveContainerStatus = diag != null ? getStringOrNull(diag, "live_container_status") : null;

        String lastEventType = events != null && events.size() > 0
                ? getStringOrNull(events.get(0).getAsJsonObject(), "event_type") : null;
        boolean crashed = "crashed".equalsIgnoreCase(lastEventType) || "hung".equalsIgnoreCase(lastEventType);

        Aggregate agg = computeAggregate(dbStatus, ready, heartbeatAgeSec, heartbeatStale, consistent, crashed, tps);

        int onlineNow = server.getServer("island-" + uuid)
                .map(s -> s.getPlayersConnected().size()).orElse(0);

        invocation.source().sendMessage(Component.text(
                "════ Острів " + playerName + " (" + uuid + ") ════", NamedTextColor.GOLD));
        invocation.source().sendMessage(Component.text("Статус: ", NamedTextColor.GRAY)
                .append(Component.text(agg.label(), agg.color())));

        invocation.source().sendMessage(Component.text("Контейнер: ", NamedTextColor.GRAY)
                .append(Component.text(container == null ? "?" : container, NamedTextColor.WHITE))
                .append(Component.text("   IP: ", NamedTextColor.GRAY))
                .append(Component.text(ip == null ? "—" : ip, NamedTextColor.WHITE)));
        if (liveContainerStatus != null) {
            invocation.source().sendMessage(Component.text("  Live LXD state: ", NamedTextColor.GRAY)
                    .append(Component.text(liveContainerStatus, consistent ? NamedTextColor.GREEN : NamedTextColor.RED))
                    .append(Component.text(consistent ? "" : "  ⚠ DB/container inconsistent", NamedTextColor.RED)));
        }

        invocation.source().sendMessage(Component.text("DB статус: ", NamedTextColor.GRAY)
                .append(Component.text(dbStatus == null ? "?" : dbStatus, NamedTextColor.WHITE)));

        invocation.source().sendMessage(Component.text("Minecraft ready: ", NamedTextColor.GRAY)
                .append(Component.text(ready ? "так" : "ні", ready ? NamedTextColor.GREEN : NamedTextColor.YELLOW))
                .append(Component.text("   Гравців онлайн: ", NamedTextColor.GRAY))
                .append(Component.text(String.valueOf(onlineNow), NamedTextColor.WHITE)));

        invocation.source().sendMessage(Component.text("Heartbeat: ", NamedTextColor.GRAY)
                .append(Component.text(heartbeatAgeSec == null ? "ще не було" : heartbeatAgeSec + "с тому",
                        heartbeatStale ? NamedTextColor.YELLOW : NamedTextColor.WHITE))
                .append(Component.text("   TPS: ", NamedTextColor.GRAY))
                .append(Component.text(heartbeatStale || tps == null ? "--" : String.valueOf(tps), NamedTextColor.WHITE))
                .append(Component.text("   MSPT: ", NamedTextColor.GRAY))
                .append(Component.text(heartbeatStale || mspt == null ? "--" : String.valueOf(mspt), NamedTextColor.WHITE)));

        if (events != null) {
            sendEventsBlock(invocation, events);
        }
    }

    private void sendEventsBlock(Invocation invocation, JsonArray events) {
        if (events.isEmpty()) {
            invocation.source().sendMessage(Component.text("Останні події: немає записів.", NamedTextColor.DARK_GRAY));
            return;
        }
        invocation.source().sendMessage(Component.text("Останні події (найновіші спершу):", NamedTextColor.GOLD));
        for (JsonElement el : events) {
            JsonObject event = el.getAsJsonObject();
            String type = getStringOrNull(event, "event_type");
            String details = getStringOrNull(event, "details");
            String createdAt = getStringOrNull(event, "created_at");

            NamedTextColor typeColor = switch (type == null ? "" : type) {
                case "crashed", "hung" -> NamedTextColor.RED;
                case "state_mismatch", "auto_frozen_idle", "auto_stopped_idle" -> NamedTextColor.YELLOW;
                case "restarted", "stopping", "admin_restart", "admin_owner_change" -> NamedTextColor.AQUA;
                default -> NamedTextColor.WHITE;
            };

            invocation.source().sendMessage(Component.text("  [" + createdAt + "] ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(type == null ? "?" : type, typeColor))
                    .append(Component.text(details == null || details.isEmpty() ? "" : ": " + details,
                            NamedTextColor.GRAY)));
        }
    }

    private record Aggregate(String label, NamedTextColor color) {}

    private Aggregate computeAggregate(String dbStatus, boolean ready, Long heartbeatAgeSec, boolean heartbeatStale,
                                        boolean consistent, boolean crashed, Double tps) {
        if (dbStatus == null) return new Aggregate("🟣 UNKNOWN", NamedTextColor.LIGHT_PURPLE);

        if (crashed && heartbeatStale) return new Aggregate("🔴 CRASHED", NamedTextColor.RED);

        if (dbStatus.equals("STOPPED") || dbStatus.startsWith("ERROR")) {
            return new Aggregate("⚪ OFFLINE", NamedTextColor.GRAY);
        }
        if (dbStatus.startsWith("PENDING") || dbStatus.equals("CREATING") || dbStatus.equals("UPDATING")) {
            return new Aggregate("🟣 UNKNOWN (перехідний стан)", NamedTextColor.LIGHT_PURPLE);
        }
        if (dbStatus.equals("RUNNING") || dbStatus.equals("FROZEN")) {
            if (!ready) return new Aggregate("🟡 DEGRADED (MC ще не готовий)", NamedTextColor.YELLOW);
            if (heartbeatAgeSec == null) return new Aggregate("🟣 UNKNOWN (немає heartbeat)", NamedTextColor.LIGHT_PURPLE);
            if (heartbeatStale) return new Aggregate("🟠 STALE", NamedTextColor.GOLD);
            if (!consistent) return new Aggregate("🟡 DEGRADED (DB/container inconsistency)", NamedTextColor.YELLOW);
            if (tps != null && tps < 15.0) return new Aggregate("🟡 DEGRADED (низький TPS)", NamedTextColor.YELLOW);
            return new Aggregate("🟢 HEALTHY", NamedTextColor.GREEN);
        }
        return new Aggregate("🟣 UNKNOWN", NamedTextColor.LIGHT_PURPLE);
    }

    // ── set owner ────────────────────────────────────────────────────

    private void handleSet(Invocation invocation, String[] args) {
        if (args.length < 4) {
            invocation.source().sendMessage(Component.text(
                    "Використання: /islandadmin set <island> <field> <value>", NamedTextColor.RED));
            return;
        }
        String islandOwnerName = args[1];
        String field = args[2].toLowerCase();
        String value = args[3];

        if (!SET_FIELDS.contains(field)) {
            invocation.source().sendMessage(Component.text("✖ Invalid field.", NamedTextColor.RED));
            return;
        }

        String adminName = invocation.source() instanceof Player p ? p.getUsername() : "console";

        resolveUuid(islandOwnerName).thenAccept(ownerOpt -> {
            if (ownerOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text("✖ Island not found.", NamedTextColor.RED));
                return;
            }
            UUID ownerUuid = ownerOpt.get();

            resolveUuid(value).thenAccept(newOwnerOpt -> {
                if (newOwnerOpt.isEmpty()) {
                    invocation.source().sendMessage(Component.text(
                            "✖ Player UUID not found: " + value, NamedTextColor.RED));
                    return;
                }
                UUID newOwnerUuid = newOwnerOpt.get();

                apiClient.setIslandOwner(ownerUuid, newOwnerUuid, adminName).thenAccept(res -> {
                    if (!res.isSuccess()) {
                        invocation.source().sendMessage(Component.text(
                                "✖ Operation failed: " + describeError(res), NamedTextColor.RED));
                        return;
                    }
                    // Read-after-write: re-fetch and confirm the change actually applied.
                    apiClient.getIslandDetails(newOwnerUuid).thenAccept(verify -> {
                        if (verify.isSuccess()) {
                            invocation.source().sendMessage(Component.text(
                                    "✔ Owner changed successfully → " + value, NamedTextColor.GREEN));
                        } else {
                            invocation.source().sendMessage(Component.text(
                                    "⚠ Operation accepted by API, but new state could not be verified.",
                                    NamedTextColor.YELLOW));
                        }
                    });
                });
            });
        });
    }

    // ── member add/remove ───────────────────────────────────────────

    private void handleMember(Invocation invocation, String[] args) {
        if (args.length < 4 || !MEMBER_ACTIONS.contains(args[1].toLowerCase())) {
            invocation.source().sendMessage(Component.text(
                    "Використання: /islandadmin member <add|remove> <island> <player>", NamedTextColor.RED));
            return;
        }
        String action = args[1].toLowerCase();
        String islandOwnerName = args[2];
        String targetName = args[3];

        resolveUuid(islandOwnerName).thenAccept(ownerOpt -> {
            if (ownerOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text("✖ Island not found.", NamedTextColor.RED));
                return;
            }
            apiClient.getIslandDetails(ownerOpt.get()).thenAccept(detailsRes -> {
                if (!detailsRes.isSuccess()) {
                    invocation.source().sendMessage(Component.text("✖ Island not found.", NamedTextColor.RED));
                    return;
                }
                JsonObject island = JsonParser.parseString(detailsRes.body()).getAsJsonObject();
                if (!island.has("team_id") || island.get("team_id").isJsonNull()) {
                    invocation.source().sendMessage(Component.text(
                            "✖ Operation failed: island has no team (legacy solo island).", NamedTextColor.RED));
                    return;
                }
                int teamId = island.get("team_id").getAsInt();

                resolveUuid(targetName).thenAccept(targetOpt -> {
                    if (targetOpt.isEmpty()) {
                        invocation.source().sendMessage(Component.text(
                                "✖ Player UUID not found: " + targetName, NamedTextColor.RED));
                        return;
                    }
                    UUID targetUuid = targetOpt.get();
                    CompletableFuture<ApiResponse> future = action.equals("add")
                            ? apiClient.addTeamMember(teamId, targetUuid)
                            : apiClient.removeTeamMember(teamId, targetUuid);

                    future.thenAccept(res -> {
                        if (res.isSuccess()) {
                            invocation.source().sendMessage(Component.text(
                                    "✔ Member " + action + " OK: " + targetName, NamedTextColor.GREEN));
                        } else {
                            invocation.source().sendMessage(Component.text(
                                    "✖ Operation failed: " + describeError(res), NamedTextColor.RED));
                        }
                    });
                });
            });
        });
    }

    // ── lifecycle ────────────────────────────────────────────────────

    private void handleStart(Invocation invocation, String[] args) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Використання: /islandadmin start <name>", NamedTextColor.RED));
            return;
        }
        String playerName = args[1];
        resolveUuid(playerName).thenAccept(uuidOpt -> {
            if (uuidOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text("✖ Player UUID not found: " + playerName, NamedTextColor.RED));
                return;
            }
            apiClient.requestIslandStart(uuidOpt.get(), playerName, adminName(invocation)).thenAccept(res ->
                    reportLifecycleResult(invocation, "start", res));
        });
    }

    private void handleStop(Invocation invocation, String[] args) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Використання: /islandadmin stop <name>", NamedTextColor.RED));
            return;
        }
        String playerName = args[1];
        resolveUuid(playerName).thenAccept(uuidOpt -> {
            if (uuidOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text("✖ Player UUID not found: " + playerName, NamedTextColor.RED));
                return;
            }
            apiClient.requestIslandStop(uuidOpt.get(), adminName(invocation)).thenAccept(res ->
                    reportLifecycleResult(invocation, "stop", res));
        });
    }

    private void handleRestart(Invocation invocation, String[] args) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Використання: /islandadmin restart <name> [confirm]", NamedTextColor.RED));
            return;
        }
        String playerName = args[1];
        boolean confirmed = args.length >= 3 && "confirm".equalsIgnoreCase(args[2]);
        if (!confirmed) {
            invocation.source().sendMessage(Component.text(
                    "⚠ This will restart island " + playerName + ".", NamedTextColor.YELLOW));
            invocation.source().sendMessage(Component.text(
                    "Run: /islandadmin restart " + playerName + " confirm", NamedTextColor.AQUA));
            return;
        }
        String adminName = invocation.source() instanceof Player p ? p.getUsername() : "console";
        resolveUuid(playerName).thenAccept(uuidOpt -> {
            if (uuidOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text("✖ Player UUID not found: " + playerName, NamedTextColor.RED));
                return;
            }
            apiClient.restartIsland(uuidOpt.get(), adminName).thenAccept(res ->
                    reportLifecycleResult(invocation, "restart", res));
        });
    }

    private void handleJoin(Invocation invocation, String[] args) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Використання: /islandadmin join <name>", NamedTextColor.RED));
            return;
        }
        if (!(invocation.source() instanceof Player admin)) {
            invocation.source().sendMessage(Component.text("✖ Only players can join an island in-game.", NamedTextColor.RED));
            return;
        }
        myIslandCommand.adminJoinIsland(admin, args[1]);
    }

    // ── hold / release / holds (адмін-режим) ─────────────────────────
    // Поки острів на hold, API його сам не чіпає: watchdog не вбиває за мовчання,
    // idle-reaper не зупиняє, оновлення відкладаються, гравці й лаунчер не можуть
    // запустити/зупинити (423). Адмінські start/stop/join/restart проходять.

    private static String adminName(Invocation invocation) {
        return invocation.source() instanceof Player p ? p.getUsername() : "console";
    }

    private void handleHold(Invocation invocation, String[] args) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text(
                    "Використання: /islandadmin hold <name> [хвилини=120] [причина...]", NamedTextColor.RED));
            return;
        }
        String playerName = args[1];
        int minutes = 120;
        int reasonFrom = 2;
        if (args.length >= 3 && args[2].matches("\\d+")) {
            minutes = Integer.parseInt(args[2]);
            reasonFrom = 3;
        }
        if (minutes < 1 || minutes > 24 * 60) {
            invocation.source().sendMessage(Component.text("✖ Хвилини: від 1 до 1440.", NamedTextColor.RED));
            return;
        }
        String reason = args.length > reasonFrom
                ? String.join(" ", java.util.Arrays.copyOfRange(args, reasonFrom, args.length)) : null;
        int finalMinutes = minutes;
        resolveUuid(playerName).thenAccept(uuidOpt -> {
            if (uuidOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text("✖ Player UUID not found: " + playerName, NamedTextColor.RED));
                return;
            }
            apiClient.setAdminHold(uuidOpt.get(), adminName(invocation), finalMinutes, reason).thenAccept(res -> {
                if (res.isSuccess()) {
                    invocation.source().sendMessage(Component.text(
                            "🔧 Острів " + playerName + " у режимі адміна на " + finalMinutes
                                    + " хв — API його не чіпає. Зняти: /islandadmin release " + playerName,
                            NamedTextColor.GREEN));
                } else {
                    invocation.source().sendMessage(Component.text("✖ Operation failed: " + describeError(res), NamedTextColor.RED));
                }
            });
        });
    }

    private void handleRelease(Invocation invocation, String[] args) {
        if (args.length < 2) {
            invocation.source().sendMessage(Component.text("Використання: /islandadmin release <name>", NamedTextColor.RED));
            return;
        }
        String playerName = args[1];
        resolveUuid(playerName).thenAccept(uuidOpt -> {
            if (uuidOpt.isEmpty()) {
                invocation.source().sendMessage(Component.text("✖ Player UUID not found: " + playerName, NamedTextColor.RED));
                return;
            }
            apiClient.releaseAdminHold(uuidOpt.get(), adminName(invocation)).thenAccept(res -> {
                if (res.isSuccess()) {
                    invocation.source().sendMessage(Component.text(
                            "✔ Режим адміна знято з " + playerName + " — API знову керує островом.", NamedTextColor.GREEN));
                } else {
                    invocation.source().sendMessage(Component.text("✖ Operation failed: " + describeError(res), NamedTextColor.RED));
                }
            });
        });
    }

    private void handleHolds(Invocation invocation) {
        apiClient.listAdminHolds().thenAccept(res -> {
            if (!res.isSuccess()) {
                invocation.source().sendMessage(Component.text("✖ Operation failed: " + describeError(res), NamedTextColor.RED));
                return;
            }
            JsonArray holds = JsonParser.parseString(res.body()).getAsJsonArray();
            if (holds.isEmpty()) {
                invocation.source().sendMessage(Component.text("Жоден острів не в режимі адміна.", NamedTextColor.GRAY));
                return;
            }
            invocation.source().sendMessage(Component.text("════ Режим адміна (" + holds.size() + ") ════", NamedTextColor.GOLD));
            for (JsonElement el : holds) {
                JsonObject h = el.getAsJsonObject();
                String until = getStringOrNull(h, "admin_hold_until");
                Long age = until == null ? null : ageSeconds(until);
                String left = age == null ? "?" : Math.max(0, -age / 60) + " хв";
                String reason = getStringOrNull(h, "admin_hold_reason");
                invocation.source().sendMessage(Component.text("  🔧 " + getStringOrNull(h, "container_name") + " ", NamedTextColor.WHITE)
                        .append(Component.text("[" + getStringOrNull(h, "status") + "]", NamedTextColor.GRAY))
                        .append(Component.text("  " + getStringOrNull(h, "admin_hold_by") + ", ще " + left, NamedTextColor.AQUA))
                        .append(Component.text(reason == null ? "" : "  — " + reason, NamedTextColor.DARK_GRAY)));
            }
        }).exceptionally(ex -> {
            logger.error("[IslandAdmin] holds failed: {}", ex.getMessage(), ex);
            invocation.source().sendMessage(Component.text("✖ Operation failed.", NamedTextColor.RED));
            return null;
        });
    }

    // ── list ─────────────────────────────────────────────────────────

    private void handleList(Invocation invocation, String[] args) {
        String statusFilter = args.length >= 2 ? args[1].toUpperCase() : null;
        apiClient.listIslands(statusFilter).thenAccept(res -> {
            if (!res.isSuccess()) {
                invocation.source().sendMessage(Component.text("✖ Operation failed: " + describeError(res), NamedTextColor.RED));
                return;
            }
            JsonArray islands = JsonParser.parseString(res.body()).getAsJsonArray();
            if (islands.isEmpty()) {
                invocation.source().sendMessage(Component.text("Островів не знайдено.", NamedTextColor.GRAY));
                return;
            }
            invocation.source().sendMessage(Component.text(
                    "════ Острови (" + islands.size() + ") ════", NamedTextColor.GOLD));
            for (JsonElement el : islands) {
                JsonObject island = el.getAsJsonObject();
                String name = getStringOrNull(island, "team_name");
                String dbStatus = getStringOrNull(island, "status");
                boolean ready = island.has("minecraft_ready") && !island.get("minecraft_ready").isJsonNull()
                        && island.get("minecraft_ready").getAsBoolean();
                Object online = island.has("online_players") && !island.get("online_players").isJsonNull()
                        ? island.get("online_players").getAsInt() : "—";
                String version = getStringOrNull(island, "current_version");
                String holdBy = getStringOrNull(island, "admin_hold_by");

                NamedTextColor statusColor = switch (dbStatus == null ? "" : dbStatus) {
                    case "RUNNING" -> ready ? NamedTextColor.GREEN : NamedTextColor.YELLOW;
                    case "FROZEN" -> NamedTextColor.AQUA;
                    case "STOPPED" -> NamedTextColor.GRAY;
                    default -> dbStatus != null && dbStatus.startsWith("ERROR") ? NamedTextColor.RED : NamedTextColor.LIGHT_PURPLE;
                };

                invocation.source().sendMessage(Component.text("  " + (name == null ? "?" : name) + " ", NamedTextColor.WHITE)
                        .append(Component.text("[" + dbStatus + "]", statusColor))
                        .append(Component.text("  онлайн: " + online, NamedTextColor.GRAY))
                        .append(Component.text(version == null ? "" : "  " + version, NamedTextColor.DARK_GRAY))
                        .append(Component.text(holdBy == null ? "" : "  🔧 " + holdBy, NamedTextColor.AQUA)));
            }
        }).exceptionally(ex -> {
            logger.error("[IslandAdmin] list failed: {}", ex.getMessage(), ex);
            invocation.source().sendMessage(Component.text("✖ Operation failed.", NamedTextColor.RED));
            return null;
        });
    }

    private void reportLifecycleResult(Invocation invocation, String op, ApiResponse res) {
        if (res.isSuccess()) {
            invocation.source().sendMessage(Component.text("✔ " + op + " accepted.", NamedTextColor.GREEN));
        } else if (res.statusCode() == 0) {
            invocation.source().sendMessage(Component.text("⚠ API request timed out.", NamedTextColor.YELLOW));
        } else {
            invocation.source().sendMessage(Component.text("✖ Operation failed: " + describeError(res), NamedTextColor.RED));
        }
    }

    /** Фіксований набір повідомлень — без Java stack trace для звичайного адміністратора. */
    private String describeError(ApiResponse res) {
        return switch (res.statusCode()) {
            case 401, 403 -> "Permission denied.";
            case 404 -> "Island not found.";
            case 409 -> res.body();
            case 423 -> ApiClient.errorDetail(res);
            case 0 -> "API unavailable.";
            default -> "Operation failed (" + res.statusCode() + ").";
        };
    }

    // ── shared helpers ───────────────────────────────────────────────

    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static Double getDoubleOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try { return obj.get(key).getAsDouble(); } catch (Exception e) { return null; }
    }

    /** Age in whole seconds of an ISO-ish timestamp string, or null if unparseable. */
    private Long ageSeconds(String isoTimestamp) {
        try {
            Instant instant;
            try {
                instant = Instant.parse(isoTimestamp);
            } catch (DateTimeParseException e) {
                instant = LocalDateTime.parse(isoTimestamp).toInstant(ZoneOffset.UTC);
            }
            return Duration.between(instant, Instant.now()).getSeconds();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Same UUID-resolution shape as WarpAdminCommand: online players resolve
     * instantly; offline players resolve via the API's team_members lookup.
     */
    private CompletableFuture<Optional<UUID>> resolveUuid(String playerName) {
        Optional<Player> online = server.getPlayer(playerName);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(online.get().getUniqueId()));
        }

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
                        return Optional.of(UUID.fromString(obj.get("player_uuid").getAsString()));
                    }
                    if (res.statusCode() != 404) {
                        logger.error("[IslandAdmin] UUID lookup API {} for '{}': {}", res.statusCode(), playerName, res.body());
                    }
                    return Optional.<UUID>empty();
                })
                .exceptionally(ex -> {
                    logger.error("[IslandAdmin] UUID lookup error for '{}': {}", playerName, ex.getMessage());
                    return Optional.empty();
                });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // Базовий доступ до самої команди — конкретні підкоманди перевіряються
        // окремо в execute(), щоб дати точне повідомлення "Permission denied."
        return true;
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) return SUBCOMMANDS;

        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            return switch (sub) {
                case "member" -> MEMBER_ACTIONS;
                case "list" -> List.of("RUNNING", "STOPPED", "FROZEN");
                case "status", "info", "set", "start", "stop", "restart", "join", "hold", "release" ->
                        server.getAllPlayers().stream().map(Player::getUsername).toList();
                default -> List.of();
            };
        }
        if (args.length == 3) {
            return switch (sub) {
                case "set" -> SET_FIELDS;
                case "member" -> server.getAllPlayers().stream().map(Player::getUsername).toList();
                case "restart" -> List.of("confirm");
                case "hold" -> List.of("30", "60", "120");
                default -> List.of();
            };
        }
        if (args.length == 4 && sub.equals("member")) {
            return server.getAllPlayers().stream().map(Player::getUsername).toList();
        }
        return List.of();
    }
}
