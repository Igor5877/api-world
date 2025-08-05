package com.skyblockdynamic.nestworld.velocity.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import org.slf4j.Logger;
import com.skyblockdynamic.nestworld.velocity.NestworldVelocityPlugin;
import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import static com.mojang.brigadier.arguments.StringArgumentType.string;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TpaCommand {

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginConfig config;
    private final MyIslandCommand myIslandCommand;

    private static class TpaRequest {
        final UUID requester;
        final long timestamp;

        TpaRequest(UUID requester) {
            this.requester = requester;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Map<UUID, TpaRequest> pendingRequests = new ConcurrentHashMap<>();

    public TpaCommand(NestworldVelocityPlugin plugin, ProxyServer proxy, Logger logger, PluginConfig config, MyIslandCommand myIslandCommand) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.myIslandCommand = myIslandCommand;
    }

    public BrigadierCommand createTpaCommand() {
        LiteralCommandNode<CommandSource> node = literal("tpa")
            .then(argument("player", string()).executes(this::tpa))
            .build();
        return new BrigadierCommand(node);
    }

    public BrigadierCommand createTpAcceptCommand() {
        LiteralCommandNode<CommandSource> node = literal("tpaccept")
            .executes(this::tpaccept)
            .build();
        return new BrigadierCommand(node);
    }

    public BrigadierCommand createTpDenyCommand() {
        LiteralCommandNode<CommandSource> node = literal("tpdeny")
            .executes(this::tpdeny)
            .build();
        return new BrigadierCommand(node);
    }
    
    private int tpa(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        if (!(context.getSource() instanceof Player)) {
            context.getSource().sendMessage(Component.text("Only players can send TPA requests.", NamedTextColor.RED));
            return 0;
        }

        Player requester = (Player) context.getSource();
        String targetName = context.getArgument("player", String.class);

        if (requester.getUsername().equalsIgnoreCase(targetName)) {
            requester.sendMessage(Component.text("You cannot send a TPA request to yourself.", NamedTextColor.RED));
            return 0;
        }

        proxy.getPlayer(targetName).ifPresentOrElse(target -> {
            pendingRequests.put(target.getUniqueId(), new TpaRequest(requester.getUniqueId()));
            requester.sendMessage(Component.text("TPA request sent to " + target.getUsername() + ".", NamedTextColor.GREEN));
            
            Component acceptButton = Component.text("[ACCEPT]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tpaccept"));
            Component denyButton = Component.text("[DENY]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/tpdeny"));

            target.sendMessage(Component.text(requester.getUsername() + " has sent you a TPA request. ")
                .append(acceptButton)
                .append(Component.text(" or "))
                .append(denyButton));

        }, () -> {
            requester.sendMessage(Component.text("Player " + targetName + " not found.", NamedTextColor.RED));
        });

        return Command.SINGLE_SUCCESS;
    }

    private int tpaccept(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        if (!(context.getSource() instanceof Player)) {
            context.getSource().sendMessage(Component.text("Only players can accept TPA requests.", NamedTextColor.RED));
            return 0;
        }

        Player target = (Player) context.getSource();
        TpaRequest request = pendingRequests.get(target.getUniqueId());

        if (request == null) {
            target.sendMessage(Component.text("You have no pending TPA requests.", NamedTextColor.RED));
            return 0;
        }

        long timeout = config.getTpaTimeoutSeconds() * 1000L;
        if (System.currentTimeMillis() - request.timestamp > timeout) {
            pendingRequests.remove(target.getUniqueId());
            target.sendMessage(Component.text("The TPA request has expired.", NamedTextColor.RED));
            return 0;
        }

        proxy.getPlayer(request.requester).ifPresentOrElse(requester -> {
            myIslandCommand.teleportToIsland(requester, target);
            requester.sendMessage(Component.text(target.getUsername() + " has accepted your TPA request.", NamedTextColor.GREEN));
            target.sendMessage(Component.text("You have accepted the TPA request from " + requester.getUsername() + ".", NamedTextColor.GREEN));
            pendingRequests.remove(target.getUniqueId());
        }, () -> {
            target.sendMessage(Component.text("The player who sent the request is no longer online.", NamedTextColor.RED));
        });

        return Command.SINGLE_SUCCESS;
    }

    private int tpdeny(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        if (!(context.getSource() instanceof Player)) {
            context.getSource().sendMessage(Component.text("Only players can deny TPA requests.", NamedTextColor.RED));
            return 0;
        }

        Player target = (Player) context.getSource();
        TpaRequest request = pendingRequests.remove(target.getUniqueId());

        if (request == null) {
            target.sendMessage(Component.text("You have no pending TPA requests.", NamedTextColor.RED));
            return 0;
        }

        proxy.getPlayer(request.requester).ifPresent(requester -> {
            requester.sendMessage(Component.text(target.getUsername() + " has denied your TPA request.", NamedTextColor.RED));
        });

        target.sendMessage(Component.text("You have denied the TPA request.", NamedTextColor.GREEN));

        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSource, T> argument(String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }
}
