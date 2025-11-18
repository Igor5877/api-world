package com.skyblockdynamic.nestworld.velocity.commands;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.metrics.MetricsManager;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.UUID;

/**
 * The /team command.
 */
public class TeamCommand implements SimpleCommand {

    private final ApiClient apiClient;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final com.skyblockdynamic.nestworld.velocity.locale.LocaleManager localeManager;
    private final MetricsManager metricsManager;

    /**
     * Constructs a new TeamCommand.
     *
     * @param plugin        The plugin instance.
     * @param apiClient     The API client.
     * @param logger        The logger.
     * @param localeManager The locale manager.
     */
    public TeamCommand(NestworldVelocityPlugin plugin, ApiClient apiClient, Logger logger, com.skyblockdynamic.nestworld.velocity.locale.LocaleManager localeManager, MetricsManager metricsManager) {
        this.apiClient = apiClient;
        this.logger = logger;
        this.localeManager = localeManager;
        this.metricsManager = metricsManager;
    }

    /**
     * Executes the /team command.
     *
     * @param invocation The command invocation.
     */
    @Override
    public void execute(Invocation invocation) {
        MetricsManager.command_usage.labels("team").inc();
        io.prometheus.client.Histogram.Timer timer = MetricsManager.command_latency.labels("team").startTimer();

        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            source.sendMessage(localeManager.getComponent("en", "command.player_only", NamedTextColor.RED));
            timer.observeDuration();
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
            case "info":
                handleInfo(player);
                break;
            default:
                sendHelp(player);
        }
        timer.observeDuration();
    }

    /**
     * Handles the /team create subcommand.
     *
     * @param player The player who executed the command.
     * @param args   The command arguments.
     */
    private void handleCreate(Player player, String[] args) {
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        if (args.length < 2) {
            player.sendMessage(localeManager.getComponent(lang, "team.create.usage", NamedTextColor.RED));
            return;
        }
        String teamName = args[1];
        apiClient.createTeam(teamName, player.getUniqueId(), player.getUsername())
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        player.sendMessage(Component.text(localeManager.getMessage(lang, "team.create.success").replace("{team_name}", teamName), NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text(localeManager.getMessage(lang, "team.create.error").replace("{error}", response.body()), NamedTextColor.RED));
                    }
                });
    }

    /**
     * Handles the /team accept subcommand.
     *
     * @param player The player who executed the command.
     * @param args   The command arguments.
     */
    private void handleAccept(Player player, String[] args) {
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        if (args.length < 2) {
            player.sendMessage(localeManager.getComponent(lang, "team.accept.usage", NamedTextColor.RED));
            return;
        }
        String teamName = args[1];
        apiClient.acceptInvite(teamName, player.getUniqueId())
                .thenAccept(response -> {
                    if (response.isSuccess()) {
                        player.sendMessage(Component.text(localeManager.getMessage(lang, "team.accept.success").replace("{team_name}", teamName), NamedTextColor.GREEN));
                    } else {
                        player.sendMessage(Component.text(localeManager.getMessage(lang, "team.accept.error").replace("{error}", response.body()), NamedTextColor.RED));
                    }
                });
    }

    /**
     * Handles the /team leave subcommand.
     *
     * @param player The player who executed the command.
     */
    private void handleLeave(Player player) {
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        apiClient.getTeam(player.getUniqueId()).thenAccept(response -> {
            if (response.isSuccess()) {
                JsonObject teamJson = gson.fromJson(response.body(), JsonObject.class);
                int teamId = teamJson.get("id").getAsInt();
                apiClient.leaveTeam(teamId, player.getUniqueId())
                        .thenAccept(leaveResponse -> {
                            if (leaveResponse.isSuccess()) {
                                player.sendMessage(localeManager.getComponent(lang, "team.leave.success", NamedTextColor.GREEN));
                            } else {
                                player.sendMessage(Component.text(localeManager.getMessage(lang, "team.leave.error").replace("{error}", leaveResponse.body()), NamedTextColor.RED));
                            }
                        });
            } else {
                player.sendMessage(localeManager.getComponent(lang, "team.not_in_team", NamedTextColor.RED));
            }
        });
    }
    
    /**
     * Handles the /team rename subcommand.
     *
     * @param player The player who executed the command.
     * @param args   The command arguments.
     */
    private void handleRename(Player player, String[] args) {
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        if (args.length < 2) {
            player.sendMessage(localeManager.getComponent(lang, "team.rename.usage", NamedTextColor.RED));
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
                                player.sendMessage(Component.text(localeManager.getMessage(lang, "team.rename.success").replace("{new_name}", newName), NamedTextColor.GREEN));
                            } else {
                                player.sendMessage(Component.text(localeManager.getMessage(lang, "team.rename.error").replace("{error}", renameResponse.body()), NamedTextColor.RED));
                            }
                        });
            } else {
                player.sendMessage(localeManager.getComponent(lang, "team.not_in_team", NamedTextColor.RED));
            }
        });
    }

    /**
     * Handles the /team info subcommand.
     *
     * @param player The player who executed the command.
     */
    private void handleInfo(Player player) {
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        apiClient.getTeam(player.getUniqueId()).thenAccept(response -> {
            if (response.isSuccess()) {
                JsonObject teamJson = gson.fromJson(response.body(), JsonObject.class);
                String teamName = teamJson.get("name").getAsString();
                String ownerUuid = teamJson.get("owner_uuid").getAsString();
                JsonArray members = teamJson.getAsJsonArray("members");

                player.sendMessage(localeManager.getComponent(lang, "team.info.header", NamedTextColor.YELLOW));
                player.sendMessage(Component.text(localeManager.getMessage(lang, "team.info.name").replace("{team_name}", teamName), NamedTextColor.AQUA));
                
                // In a real application, you'd want to cache UUID to name lookups
                player.sendMessage(Component.text(localeManager.getMessage(lang, "team.info.owner").replace("{owner_uuid}", ownerUuid), NamedTextColor.AQUA));

                player.sendMessage(localeManager.getComponent(lang, "team.info.members", NamedTextColor.AQUA));
                for (JsonElement memberElement : members) {
                    JsonObject memberObject = memberElement.getAsJsonObject();
                    String memberUuid = memberObject.get("player_uuid").getAsString();
                    String role = memberObject.get("role").getAsString();
                    player.sendMessage(Component.text(localeManager.getMessage(lang, "team.info.member_format").replace("{member_uuid}", memberUuid).replace("{role}", role), NamedTextColor.GRAY));
                }

            } else {
                player.sendMessage(localeManager.getComponent(lang, "team.not_in_team", NamedTextColor.RED));
            }
        });
    }

    /**
     * Sends the help message for the /team command.
     *
     * @param player The player to send the message to.
     */
    private void sendHelp(Player player) {
        String lang = player.getPlayerSettings().getLocale().getLanguage();
        player.sendMessage(localeManager.getComponent(lang, "team.help.header", NamedTextColor.YELLOW));
        player.sendMessage(localeManager.getComponent(lang, "team.help.create", NamedTextColor.AQUA));
        player.sendMessage(localeManager.getComponent(lang, "team.help.accept", NamedTextColor.AQUA));
        player.sendMessage(localeManager.getComponent(lang, "team.help.leave", NamedTextColor.AQUA));
        player.sendMessage(localeManager.getComponent(lang, "team.help.rename", NamedTextColor.AQUA));
        player.sendMessage(localeManager.getComponent(lang, "team.help.info", NamedTextColor.AQUA));
    }

    /**
     * Checks if the command source has permission to execute the command.
     *
     * @param invocation The command invocation.
     * @return True if the command source has permission, false otherwise.
     */
    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
