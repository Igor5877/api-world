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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.skyblock.dynamic.SkyBlockMod;
import com.skyblock.dynamic.Config; // Assuming Config.java will hold API URL

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

public class IslandCommand {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("island")
            .then(Commands.literal("create")
                .executes(context -> {
                    try {
                        return createIsland(context.getSource());
                    } catch (Exception e) {
                        LOGGER.error("Error executing /island create command", e);
                        context.getSource().sendFailure(Component.literal("An unexpected error occurred. See server logs."));
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

        // TODO: Get API URL from Config
        String apiUrl = Config.getApiBaseUrl() + "islands/"; // Removed leading slash from "islands/"
        // String apiUrl = "http://localhost:8000/api/v1/islands/"; // Placeholder

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonPayload)))
                .build();
        
        // Send initial "requesting" message to player
        source.sendSuccess(() -> Component.literal("Запит на створення острова для " + playerName + "..."), true);

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenAccept(response -> { // Changed to thenAccept as we don't need to return the response itself
                int statusCode = response.statusCode();
                if (statusCode == 202 || statusCode == 200 || statusCode == 201) { // Accepted, OK, Created
                    player.sendSystemMessage(Component.literal("Ваший острів створений"));
                    LOGGER.info("Виклик API для створення острова для гравця {} успішний. Статус: {}. Відповідь: {}", playerName, statusCode, response.body());
                } else if (statusCode == 409) { // Conflict - island already exists
                     player.sendSystemMessage(Component.literal("У тебе вже є острів!"));
                     LOGGER.warn("Конфлікт викликів API для гравця {} (острів, ймовірно, вже існує): {} - {}", playerName, statusCode, response.body());
                } else {
                    // Generic error for other client/server errors
                    player.sendSystemMessage(Component.literal("Виникла внутрішня помилка 32. Зверніться до адміністрації."));
                    LOGGER.error("Виклик API гравця {} не вдався під час створення острова. Статус: {}. Відповідь: {}", playerName, statusCode, response.body());
                }
            })
            .exceptionally(e -> {
                // Network errors or other issues before getting a response
                player.sendSystemMessage(Component.literal("Виникла внутрішня помилка 32. Зверніться до адміністрації."));
                LOGGER.error("Виняток під час виклику API для створення острова для гравця:", playerName, e.getMessage());
                return null; // Void for exceptionally
            });

        return 1; // Command initiated (asynchronously)
    }
}
