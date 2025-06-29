package com.skyblock.dynamic.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.skyblock.dynamic.SkyBlockMod;
import com.skyblock.dynamic.Config; 

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public class IslandCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds())) // Use configured timeout
            .build();
    private static final ScheduledExecutorService POLLING_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "island-status-poller");
        t.setDaemon(true); // Allow JVM to exit if this is the only thread running
        return t;
    });

    public static void shutdownExecutor() {
        if (POLLING_EXECUTOR != null && !POLLING_EXECUTOR.isShutdown()) {
            LOGGER.info("Shutting down IslandCommand POLLING_EXECUTOR.");
            POLLING_EXECUTOR.shutdown();
            try {
                if (!POLLING_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("IslandCommand POLLING_EXECUTOR did not terminate in 5 seconds, forcing shutdown.");
                    POLLING_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOGGER.error("Interrupted while waiting for IslandCommand POLLING_EXECUTOR to terminate.", e);
                POLLING_EXECUTOR.shutdownNow();
                Thread.currentThread().interrupt(); // Preserve interrupt status
            }
        }
    }

    // Polling parameters
    private static final int POLLING_INTERVAL_SECONDS = 2;
    private static final int MAX_POLLING_ATTEMPTS = 15; // 15 attempts * 2 seconds = 30 seconds max polling time

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("island")
            .then(Commands.literal("create")
                .executes(context -> {
                    // Check context before executing
                    if (SkyBlockMod.isIslandServer()) {
                        context.getSource().sendFailure(Component.literal("Ця команда недоступна на сервері острова."));
                        return 0; // Command failed or is disallowed
                    }
                    try {
                        return createIsland(context.getSource());
                    } catch (Exception e) {
                        LOGGER.error("Error executing /island create command", e);
                        context.getSource().sendFailure(Component.literal("Виникла неочікувана помилка. Перевірте логи сервера."));
                        return 0;
                    }
                })
            )
            // Potentially add other subcommands like /island visit <player>, /island home etc. later
        );
    }

    private static int createIsland(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID playerUuid = player.getUUID();
        String playerName = player.getName().getString();

        // Prepare JSON payload first
        JsonObject jsonPayload = new JsonObject();
        jsonPayload.addProperty("player_uuid", playerUuid.toString());
        jsonPayload.addProperty("player_name", playerName);

        String apiUrl = Config.getApiBaseUrl() + "islands/"; 
        LOGGER.info("Attempting to create island for {} (UUID: {}) via API: {}", playerName, playerUuid, apiUrl);

        HttpRequest createRequest = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonPayload)))
                .build();
        
        source.sendSuccess(() -> Component.literal("Запит на створення острова прийнято. Перевіряємо статус..."), true);

        HTTP_CLIENT.sendAsync(createRequest, HttpResponse.BodyHandlers.ofString())
            .thenAcceptAsync(response -> {
                int statusCode = response.statusCode();
                String responseBody = response.body();
                LOGGER.info("Initial island creation API response for {}: {} - {}", playerName, statusCode, responseBody);

                if (statusCode == 202 || statusCode == 200 || statusCode == 201) { // Accepted, OK, Created
                    // Start polling for island status
                    pollIslandStatus(player, playerUuid, new AtomicInteger(0));
                } else if (statusCode == 409) { // Conflict - island already exists
                     player.sendSystemMessage(Component.literal("У тебе вже є острів!"));
                     LOGGER.warn("Конфлікт викликів API для гравця {} (острів, ймовірно, вже існує): {} - {}", playerName, statusCode, responseBody);
                } else {
                    player.sendSystemMessage(Component.literal("Помилка під час запиту на створення острова. Код: " + statusCode));
                    LOGGER.error("Помилка API під час запиту на створення острова для {}. Статус: {}. Відповідь: {}", playerName, statusCode, responseBody);
                }
            }, POLLING_EXECUTOR) // Ensure response handling is off the Netty threads
            .exceptionally(e -> {
                player.sendSystemMessage(Component.literal("Помилка зв'язку з сервісом островів. Спробуйте пізніше."));
                LOGGER.error("Виняток під час запиту на створення острова для {}: {}", playerName, e.getMessage(), e);
                return null;
            });

        return 1; // Command initiated
    }

    private static void pollIslandStatus(ServerPlayer player, UUID playerUuid, AtomicInteger attempts) {
        if (attempts.getAndIncrement() >= MAX_POLLING_ATTEMPTS) {
            player.sendSystemMessage(Component.literal("Створення острова займає більше часу, ніж очікувалося.")); // TODO: Add /island status command
            LOGGER.warn("Max polling attempts reached for player {}", playerUuid);
            return;
        }

        String statusApiUrl = Config.getApiBaseUrl() + "islands/" + playerUuid.toString();
        HttpRequest statusRequest = HttpRequest.newBuilder()
                .uri(URI.create(statusApiUrl))
                .timeout(Duration.ofSeconds(Config.getApiRequestTimeoutSeconds()))
                .GET()
                .build();

        LOGGER.info("Polling island status for {} (Attempt {}): {}", player.getName().getString(), attempts.get(), statusApiUrl);

        HTTP_CLIENT.sendAsync(statusRequest, HttpResponse.BodyHandlers.ofString())
            .thenAcceptAsync(response -> {
                int statusCode = response.statusCode();
                String responseBody = response.body();
                LOGGER.debug("Island status API response for {}: {} - {}", player.getName().getString(), statusCode, responseBody);

                if (statusCode == 200) {
                    try {
                        JsonObject jsonResponse = GSON.fromJson(responseBody, JsonObject.class);
                        String status = jsonResponse.has("status") ? jsonResponse.get("status").getAsString() : "UNKNOWN";

                        switch (status.toUpperCase()) {
                            case "STOPPED":
                                player.sendSystemMessage(Component.literal("Ваший острів створений!"));
                                LOGGER.info("Острів для {} успішно створений та зупинений (status: STOPPED).", player.getName().getString());
                                break;
                            case "PENDING_CREATION":
                            case "CREATING":
                                //player.sendSystemMessage(Component.literal("Острів ще створюється... (" + status + ")"));
                                LOGGER.info("Острів для {} все ще створюється (status: {}). Повторна перевірка через {} сек.", player.getName().getString(), status, POLLING_INTERVAL_SECONDS);
                                POLLING_EXECUTOR.schedule(() -> pollIslandStatus(player, playerUuid, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                                break;
                            case "ERROR_CREATE":
                            case "ERROR":
                                player.sendSystemMessage(Component.literal("Помилка під час створення вашого острова на сервері (status: " + status + "). Зверніться до адміністрації."));
                                LOGGER.error("Помилка створення острова для {} (status: {}). Відповідь API: {}", player.getName().getString(), status, responseBody);
                                break;
                            default:
                                player.sendSystemMessage(Component.literal("Невідомий статус острова: " + status + ". Спробуйте пізніше."));
                                LOGGER.warn("Невідомий статус острова '{}' для гравця {}. Відповідь API: {}", status, player.getName().getString(), responseBody);
                                POLLING_EXECUTOR.schedule(() -> pollIslandStatus(player, playerUuid, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS); // Retry on unknown status as well
                                break;
                        }
                    } catch (Exception e) {
                        player.sendSystemMessage(Component.literal("Помилка обробки відповіді від сервісу островів."));
                        LOGGER.error("Помилка парсингу JSON відповіді статусу для {}: {}", player.getName().getString(), responseBody, e);
                        // Optionally retry or stop
                        POLLING_EXECUTOR.schedule(() -> pollIslandStatus(player, playerUuid, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                    }
                } else if (statusCode == 404) {
                    LOGGER.warn("Острів для {} ще не знайдено (404). Можливо, ще не створений в API. Повторна перевірка через {} сек. (Attempt {})", player.getName().getString(), POLLING_INTERVAL_SECONDS, attempts.get());
                     // Send a less alarming message for 404 during polling as it might just be eventual consistency
                    if (attempts.get() == 1) { // Only send on first 404 after initial request
                        player.sendSystemMessage(Component.literal("Очікуємо на інформацію про острів від API..."));
                    }
                    POLLING_EXECUTOR.schedule(() -> pollIslandStatus(player, playerUuid, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                } else {
                    player.sendSystemMessage(Component.literal("Помилка перевірки статусу острова. Код: " + statusCode));
                    LOGGER.error("Помилка API під час перевірки статусу острова для {}. Статус: {}. Відповідь: {}", player.getName().getString(), statusCode, responseBody);
                    // Decide if to retry or stop based on status code
                    if (statusCode >= 500 && statusCode < 600) { // Server-side errors
                         POLLING_EXECUTOR.schedule(() -> pollIslandStatus(player, playerUuid, attempts), POLLING_INTERVAL_SECONDS * 2, TimeUnit.SECONDS); // Longer delay for server errors
                    } else {
                        // For client-side errors (4xx, excluding 404 handled above), usually stop.
                        LOGGER.warn("Припинення спроб перевірки статусу для {} через помилку клієнта (код: {}).", player.getName().getString(), statusCode);
                    }
                }
            }, POLLING_EXECUTOR) // Ensure response handling is off the Netty threads
            .exceptionally(e -> {
                // Network errors or other issues during polling
                LOGGER.error("Виняток під час перевірки статусу острова для {}: {}", player.getName().getString(), e.getMessage(), e);
                // Decide if to retry or inform player
                if (attempts.get() < MAX_POLLING_ATTEMPTS) {
                     player.sendSystemMessage(Component.literal("Помилка зв'язку під час перевірки статусу. Повторна спроба..."));
                    POLLING_EXECUTOR.schedule(() -> pollIslandStatus(player, playerUuid, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                } else {
                    player.sendSystemMessage(Component.literal("Не вдалося перевірити статус острова після кількох спроб."));
                }
                return null;
            });
    }
}
