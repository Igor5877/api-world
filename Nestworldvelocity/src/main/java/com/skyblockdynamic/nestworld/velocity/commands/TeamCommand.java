package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.UUID;

public class TeamCommand implements SimpleCommand {

    private final ApiClient apiClient;
    private final Logger logger;
    private final Gson gson = new Gson();

    public TeamCommand(NestworldVelocityPlugin plugin, ApiClient apiClient, Logger logger) {
        this.apiClient = apiClient;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            source.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) source;
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(player);
            return;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "create":
                handleCreate(player, args);
                break;
            case "accept":
                handleAccept(player, args);
                break;
            case "leave":
                handleLeave(player);
                break;
            case "rename":
                handleRename(player, args);
                break;
            default:
                sendHelp(player);
        }
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /team create <name>", NamedTextColor.RED));
            return;
        }
        String teamName = args[1];
        apiClient.createTeam(teamName, player.getUniqueId(), player.getUsername())
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        player.sendMessage(Component.text("Team '" + teamName + "' created successfully!", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Error creating team: " + response.body(), NamedTextColor.RED));
                    }
                });
    }

    private void handleAccept(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /team accept <team_name>", NamedTextColor.RED));
            return;
        }
        String teamName = args[1];
        apiClient.acceptInvite(teamName, player.getUniqueId())
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        player.sendMessage(Component.text("You have joined team '" + teamName + "'!", NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text("Error joining team: " + response.body(), NamedTextColor.RED));
                    }
                });
    }

    private void handleLeave(Player player) {
        apiClient.getTeam(player.getUniqueId()).thenAccept(response -> {
            if (response.isSuccess()) {
                JsonObject teamJson = gson.fromJson(response.body(), JsonObject.class);
                int teamId = teamJson.get("id").getAsInt();
                apiClient.leaveTeam(teamId, player.getUniqueId())
                        .thenAccept(leaveResponse -> {
                            if (leaveResponse.isSuccess()) {
                                player.sendMessage(Component.text("You have left your team.", NamedTextColor.GREEN));
                            } else {
                                player.sendMessage(Component.text("Error leaving team: " + leaveResponse.body(), NamedTextColor.RED));
                            }
                        });
            } else {
                player.sendMessage(Component.text("You are not in a team.", NamedTextColor.RED));
            }
        });
    }
    
    private void handleRename(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(Component.text("Usage: /team rename <new_name>", NamedTextColor.RED));
            return;
        }
        String newName = args[1];
        apiClient.getTeam(player.getUniqueId()).thenAccept(response -> {
            if (response.isSuccess()) {
                JsonObject teamJson = gson.fromJson(response.body(), JsonObject.class);
                int teamId = teamJson.get("id").getAsInt();
                apiClient.renameTeam(teamId, newName, player.getUniqueId())
                        .thenAccept(renameResponse -> {
                            if (renameResponse.isSuccess()) {
                                player.sendMessage(Component.text("Team renamed to '" + newName + "'!", NamedTextColor.GREEN));
                            } else {
                                player.sendMessage(Component.text("Error renaming team: " + renameResponse.body(), NamedTextColor.RED));
                            }
                        });
            } else {
                player.sendMessage(Component.text("You are not in a team.", NamedTextColor.RED));
            }
        });
    }

    private void sendHelp(Player player) {
        player.sendMessage(Component.text("--- Team Commands ---", NamedTextColor.YELLOW));
        player.sendMessage(Component.text("/team create <name>", NamedTextColor.AQUA));
        player.sendMessage(Component.text("/team accept <team_name>", NamedTextColor.AQUA));
        player.sendMessage(Component.text("/team leave", NamedTextColor.AQUA));
        player.sendMessage(Component.text("/team rename <new_name>", NamedTextColor.AQUA));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
