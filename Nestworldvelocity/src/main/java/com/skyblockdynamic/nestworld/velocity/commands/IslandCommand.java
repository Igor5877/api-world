package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class IslandCommand implements SimpleCommand {

    private final ApiClient apiClient;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final ScheduledExecutorService pollingExecutor;

    public IslandCommand(NestworldVelocityPlugin plugin, ApiClient apiClient, Logger logger) {
        this.apiClient = apiClient;
        this.logger = logger;
        this.pollingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "island-creation-poller");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void execute(final Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            source.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) source;
        String[] args = invocation.arguments();

        if (args.length == 0) {
            // In the future, this could show island help or info.
            player.sendMessage(Component.text("Usage: /island create", NamedTextColor.RED));
            return;
        }

        String subCommand = args[0].toLowerCase();
        if ("create".equals(subCommand)) {
            handleCreate(player);
        } else {
            player.sendMessage(Component.text("Unknown subcommand. Usage: /island create", NamedTextColor.RED));
        }
    }

    private void handleCreate(Player player) {
        player.sendMessage(Component.text("Requesting island creation...", NamedTextColor.YELLOW));

        // The createTeam method handles solo island creation.
        // The team name parameter is not used by the /create_solo endpoint.
        apiClient.createTeam(player.getUsername(), player.getUniqueId(), player.getUsername())
            .thenAcceptAsync(response -> {
                int statusCode = response.statusCode();
                logger.info("Initial island creation API response for {}: {} - {}", player.getUsername(), statusCode, response.body());

                if (statusCode == 201 || statusCode == 200 || statusCode == 202) { // Created, OK, Accepted
                    player.sendMessage(Component.text("Request accepted! Polling for creation status...", NamedTextColor.GREEN));
                    pollIslandStatus(player, new AtomicInteger(0));
                } else if (statusCode == 409) { // Conflict
                    player.sendMessage(Component.text("You already have an island!", NamedTextColor.RED));
                } else {
                    player.sendMessage(Component.text("Error requesting island creation. Code: " + statusCode, NamedTextColor.RED));
                    logger.error("API Error on island creation for {}. Status: {}. Body: {}", player.getUsername(), statusCode, response.body());
                }
            }, pollingExecutor)
            .exceptionally(e -> {
                player.sendMessage(Component.text("Failed to communicate with the island service.", NamedTextColor.RED));
                logger.error("Exception during island creation request for {}: {}", player.getUsername(), e.getMessage(), e);
                return null;
            });
    }

    private void pollIslandStatus(Player player, AtomicInteger attempts) {
        final int MAX_POLLING_ATTEMPTS = 15;
        final int POLLING_INTERVAL_SECONDS = 2;

        if (attempts.getAndIncrement() >= MAX_POLLING_ATTEMPTS) {
            player.sendMessage(Component.text("Island creation is taking longer than expected. Please try connecting with /myisland later.", NamedTextColor.YELLOW));
            logger.warn("Max polling attempts reached for player {}", player.getUniqueId());
            return;
        }

        logger.info("Polling island status for {} (Attempt {})", player.getUsername(), attempts.get());

        apiClient.getIslandDetails(player.getUniqueId())
            .thenAcceptAsync(response -> {
                int statusCode = response.statusCode();
                if (statusCode == 200) {
                    try {
                        JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);
                        String status = jsonResponse.has("status") ? jsonResponse.get("status").getAsString() : "UNKNOWN";

                        switch (status.toUpperCase()) {
                            case "STOPPED":
                                player.sendMessage(Component.text("Your island has been created successfully!", NamedTextColor.GREEN));
                                player.sendMessage(Component.text("You can now connect to it using /myisland.", NamedTextColor.AQUA));
                                break;
                            case "PENDING_CREATION":
                            case "CREATING":
                                logger.info("Island for {} is still being created (Status: {}). Polling again in {}s.", player.getUsername(), status, POLLING_INTERVAL_SECONDS);
                                pollingExecutor.schedule(() -> pollIslandStatus(player, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                                break;
                            case "ERROR_CREATE":
                            case "ERROR":
                                player.sendMessage(Component.text("There was an error creating your island. Please contact an administrator.", NamedTextColor.RED));
                                logger.error("Island creation failed for {} with status: {}. API Response: {}", player.getUsername(), status, response.body());
                                break;
                            default:
                                logger.warn("Unknown island status '{}' for {}. Polling again.", status, player.getUsername());
                                pollingExecutor.schedule(() -> pollIslandStatus(player, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                                break;
                        }
                    } catch (Exception e) {
                        logger.error("Error parsing island status response for {}: {}", player.getUsername(), response.body(), e);
                        pollingExecutor.schedule(() -> pollIslandStatus(player, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                    }
                } else if (statusCode == 404) {
                    logger.warn("Island for {} not found yet (404). Polling again.", player.getUsername());
                    pollingExecutor.schedule(() -> pollIslandStatus(player, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                } else {
                    logger.error("API error while polling island status for {}. Status: {}. Body: {}", player.getUsername(), statusCode, response.body());
                    pollingExecutor.schedule(() -> pollIslandStatus(player, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                }
            }, pollingExecutor)
            .exceptionally(e -> {
                logger.error("Exception during island status polling for {}: {}", player.getUsername(), e.getMessage(), e);
                if (attempts.get() < MAX_POLLING_ATTEMPTS) {
                    pollingExecutor.schedule(() -> pollIslandStatus(player, attempts), POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
                }
                return null;
            });
    }
}
