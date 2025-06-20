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

        source.sendSuccess(() -> Component.literal("Requesting island creation for " + playerName + "..."), true);

        // Prepare JSON payload
        JsonObject jsonPayload = new JsonObject();
        jsonPayload.addProperty("player_uuid", playerUuid.toString());
        jsonPayload.addProperty("player_name", playerName);

        // TODO: Get API URL from Config
        String apiUrl = Config.getApiBaseUrl() + "/islands/"; 
        // String apiUrl = "http://localhost:8000/api/v1/islands/"; // Placeholder

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonPayload)))
                .build();
        
        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() == 202 || response.statusCode() == 200 || response.statusCode() == 201) { // 202 Accepted, 200 OK, 201 Created
                    // Example: {"id":1,"player_uuid":"...","container_name":"sky-playeruuid","status":"PENDING_CREATION",...}
                    // We can parse more details if needed, for now, a generic success message.
                    player.sendSystemMessage(Component.literal("Island creation initiated! You will be notified once it's ready."));
                    LOGGER.info("API call successful for player {}: {}", playerName, response.body());
                } else if (response.statusCode() == 409) { // Conflict - island already exists
                     player.sendSystemMessage(Component.literal("You already have an island!"));
                     LOGGER.warn("API call conflict for player {} (island likely already exists): {} - {}", playerName, response.statusCode(), response.body());
                }
                else {
                    player.sendSystemMessage(Component.literal("Error creating island. Status: " + response.statusCode()));
                    LOGGER.error("API call failed for player {}: {} - {}", playerName, response.statusCode(), response.body());
                }
                return response;
            })
            .exceptionally(e -> {
                player.sendSystemMessage(Component.literal("Failed to send island creation request. Please try again later."));
                LOGGER.error("Exception during API call for player {}:", playerName, e);
                return null;
            });

        return 1; // Command executed successfully (asynchronously)
    }
}
