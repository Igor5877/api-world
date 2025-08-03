package com.skyblockdynamic.nestworld.velocity.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.network.ApiClient;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import static com.mojang.brigadier.arguments.StringArgumentType.string;
import com.velocitypowered.api.proxy.Player;

public class IslandAdminCommand {

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final ApiClient apiClient;
    private final PluginConfig config;
    private final MyIslandCommand myIslandCommand;

    public IslandAdminCommand(NestworldVelocityPlugin plugin, ProxyServer proxy, Logger logger, ApiClient apiClient, PluginConfig config, MyIslandCommand myIslandCommand) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.apiClient = apiClient;
        this.config = config;
        this.myIslandCommand = myIslandCommand;
    }

    public BrigadierCommand createBrigadierCommand() {
        LiteralCommandNode<CommandSource> node = LiteralArgumentBuilder.<CommandSource>literal("islandadmin")
            .requires(source -> source.hasPermission("nestworld.admin"))
            .then(literal("status").then(argument("player", string()).executes(this::status)))
            .then(literal("start").then(argument("player", string()).executes(this::start)))
            .then(literal("stop").then(argument("player", string()).executes(this::stop)))
            .then(literal("teleport").then(argument("player", string()).executes(this::teleport)))
            .build();
        return new BrigadierCommand(node);
    }

    private int status(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        String playerName = context.getArgument("player", String.class);
        CommandSource source = context.getSource();

        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            apiClient.getIslandDetails(player.getUniqueId()).thenAccept(apiResponse -> {
                if (apiResponse.isSuccess()) {
                    source.sendMessage(Component.text("Island details for " + playerName + ":\n" + apiResponse.body(), NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text("Failed to get island details for " + playerName + ". Status: " + apiResponse.statusCode(), NamedTextColor.RED));
                }
            });
        }, () -> {
            source.sendMessage(Component.text("Player " + playerName + " not found.", NamedTextColor.RED));
        });

        return Command.SINGLE_SUCCESS;
    }

    private int start(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        String playerName = context.getArgument("player", String.class);
        CommandSource source = context.getSource();

        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            apiClient.requestIslandStart(player.getUniqueId()).thenAccept(apiResponse -> {
                if (apiResponse.isSuccess() || apiResponse.statusCode() == 409) {
                    source.sendMessage(Component.text("Island start request for " + playerName + " accepted.", NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text("Failed to start island for " + playerName + ". Status: " + apiResponse.statusCode(), NamedTextColor.RED));
                }
            });
        }, () -> {
            source.sendMessage(Component.text("Player " + playerName + " not found.", NamedTextColor.RED));
        });

        return Command.SINGLE_SUCCESS;
    }

    private int stop(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        String playerName = context.getArgument("player", String.class);
        CommandSource source = context.getSource();

        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            apiClient.requestIslandStop(player.getUniqueId()).thenAccept(apiResponse -> {
                if (apiResponse.isSuccess() || apiResponse.statusCode() == 409) {
                    source.sendMessage(Component.text("Island stop request for " + playerName + " accepted.", NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text("Failed to stop island for " + playerName + ". Status: " + apiResponse.statusCode(), NamedTextColor.RED));
                }
            });
        }, () -> {
            source.sendMessage(Component.text("Player " + playerName + " not found.", NamedTextColor.RED));
        });

        return Command.SINGLE_SUCCESS;
    }

    private int teleport(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        String playerName = context.getArgument("player", String.class);
        CommandSource source = context.getSource();

        if (!(source instanceof Player)) {
            source.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED));
            return 0;
        }

        Player admin = (Player) source;

        proxy.getPlayer(playerName).ifPresentOrElse(player -> {
            // TODO: Замінити на правильний метод з MyIslandCommand
            // myIslandCommand.teleportToIsland(player); // можливий варіант
            admin.sendMessage(Component.text("Teleporting to " + playerName + "'s island...", NamedTextColor.GREEN));
        }, () -> {
            admin.sendMessage(Component.text("Player " + playerName + " not found.", NamedTextColor.RED));
        });

        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSource, T> argument(String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }
}