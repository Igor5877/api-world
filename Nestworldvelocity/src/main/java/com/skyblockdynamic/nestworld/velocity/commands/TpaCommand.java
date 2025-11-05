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

/**
 * The /tpa, /tpaccept, and /tpdeny commands.
 */
public class TpaCommand {

    private final NestworldVelocityPlugin plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginConfig config;
    private final MyIslandCommand myIslandCommand;
    private final com.skyblockdynamic.nestworld.velocity.locale.LocaleManager localeManager;

    private static class TpaRequest {
        final UUID requester;
        final long timestamp;

        TpaRequest(UUID requester) {
            this.requester = requester;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private final Map<UUID, TpaRequest> pendingRequests = new ConcurrentHashMap<>();

    /**
     * Constructs a new TpaCommand.
     *
     * @param plugin         The plugin instance.
     * @param proxy          The proxy server.
     * @param logger         The logger.
     * @param config         The plugin configuration.
     * @param myIslandCommand The /myisland command.
     * @param localeManager  The locale manager.
     */
    public TpaCommand(NestworldVelocityPlugin plugin, ProxyServer proxy, Logger logger, PluginConfig config, MyIslandCommand myIslandCommand, com.skyblockdynamic.nestworld.velocity.locale.LocaleManager localeManager) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.config = config;
        this.myIslandCommand = myIslandCommand;
        this.localeManager = localeManager;
    }

    /**
     * Creates the /tpa command.
     *
     * @return The /tpa command.
     */
    public BrigadierCommand createTpaCommand() {
        LiteralCommandNode<CommandSource> node = literal("tpa")
            .then(argument("player", string())
                .suggests((context, builder) -> {
                    String input = builder.getRemaining().toLowerCase();
                    proxy.getAllPlayers().stream()
                        .map(Player::getUsername)
                        .filter(name -> name.toLowerCase().startsWith(input))
                        .forEach(builder::suggest);
                    return builder.buildFuture();
                })
                .executes(this::tpa))
            .build();
        return new BrigadierCommand(node);
    }

    /**
     * Creates the /tpaccept command.
     *
     * @return The /tpaccept command.
     */
    public BrigadierCommand createTpAcceptCommand() {
        LiteralCommandNode<CommandSource> node = literal("tpaccept")
            .executes(this::tpaccept)
            .build();
        return new BrigadierCommand(node);
    }

    /**
     * Creates the /tpdeny command.
     *
     * @return The /tpdeny command.
     */
    public BrigadierCommand createTpDenyCommand() {
        LiteralCommandNode<CommandSource> node = literal("tpdeny")
            .executes(this::tpdeny)
            .build();
        return new BrigadierCommand(node);
    }
    
    /**
     * Executes the /tpa command.
     *
     * @param context The command context.
     * @return The result of the command.
     */
    private int tpa(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        if (!(context.getSource() instanceof Player)) {
            context.getSource().sendMessage(localeManager.getComponent("en", "command.player_only", NamedTextColor.RED));
            return 0;
        }

        Player requester = (Player) context.getSource();
        String lang = requester.getPlayerSettings().getLocale().getLanguage();
        String targetName = context.getArgument("player", String.class);

        if (requester.getUsername().equalsIgnoreCase(targetName)) {
            requester.sendMessage(localeManager.getComponent(lang, "tpa.request.self", NamedTextColor.RED));
            return 0;
        }

        proxy.getPlayer(targetName).ifPresentOrElse(target -> {
            pendingRequests.put(target.getUniqueId(), new TpaRequest(requester.getUniqueId()));
            requester.sendMessage(Component.text(localeManager.getMessage(lang, "tpa.request.sent").replace("{player_name}", target.getUsername()), NamedTextColor.GREEN));
            
            String targetLang = target.getPlayerSettings().getLocale().getLanguage();
            Component acceptButton = Component.text(localeManager.getMessage(targetLang, "tpa.request.accept"), NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tpaccept"));
            Component denyButton = Component.text(localeManager.getMessage(targetLang, "tpa.request.deny"), NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/tpdeny"));

            target.sendMessage(Component.text(localeManager.getMessage(targetLang, "tpa.request.received").replace("{player_name}", requester.getUsername()))
                .append(acceptButton)
                .append(Component.text(" or "))
                .append(denyButton));

        }, () -> {
            requester.sendMessage(Component.text(localeManager.getMessage(lang, "tpa.request.not_found").replace("{player_name}", targetName), NamedTextColor.RED));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Executes the /tpaccept command.
     *
     * @param context The command context.
     * @return The result of the command.
     */
    private int tpaccept(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        if (!(context.getSource() instanceof Player)) {
            context.getSource().sendMessage(localeManager.getComponent("en", "command.player_only", NamedTextColor.RED));
            return 0;
        }

        Player target = (Player) context.getSource();
        String lang = target.getPlayerSettings().getLocale().getLanguage();
        TpaRequest request = pendingRequests.get(target.getUniqueId());

        if (request == null) {
            target.sendMessage(localeManager.getComponent(lang, "tpa.accept.no_pending", NamedTextColor.RED));
            return 0;
        }

        long timeout = config.getTpaTimeoutSeconds() * 1000L;
        if (System.currentTimeMillis() - request.timestamp > timeout) {
            pendingRequests.remove(target.getUniqueId());
            target.sendMessage(localeManager.getComponent(lang, "tpa.accept.expired", NamedTextColor.RED));
            return 0;
        }

        proxy.getPlayer(request.requester).ifPresentOrElse(requester -> {
            String requesterLang = requester.getPlayerSettings().getLocale().getLanguage();
            myIslandCommand.teleportToIsland(requester, target.getUsername());
            requester.sendMessage(Component.text(localeManager.getMessage(requesterLang, "tpa.accept.requester_notified").replace("{player_name}", target.getUsername()), NamedTextColor.GREEN));
            target.sendMessage(Component.text(localeManager.getMessage(lang, "tpa.accept.success").replace("{player_name}", requester.getUsername()), NamedTextColor.GREEN));
            pendingRequests.remove(target.getUniqueId());
        }, () -> {
            target.sendMessage(localeManager.getComponent(lang, "tpa.accept.sender_offline", NamedTextColor.RED));
        });

        return Command.SINGLE_SUCCESS;
    }

    /**
     * Executes the /tpdeny command.
     *
     * @param context The command context.
     * @return The result of the command.
     */
    private int tpdeny(com.mojang.brigadier.context.CommandContext<CommandSource> context) {
        if (!(context.getSource() instanceof Player)) {
            context.getSource().sendMessage(localeManager.getComponent("en", "command.player_only", NamedTextColor.RED));
            return 0;
        }

        Player target = (Player) context.getSource();
        String lang = target.getPlayerSettings().getLocale().getLanguage();
        TpaRequest request = pendingRequests.remove(target.getUniqueId());

        if (request == null) {
            target.sendMessage(localeManager.getComponent(lang, "tpa.deny.no_pending", NamedTextColor.RED));
            return 0;
        }

        proxy.getPlayer(request.requester).ifPresent(requester -> {
            String requesterLang = requester.getPlayerSettings().getLocale().getLanguage();
            requester.sendMessage(Component.text(localeManager.getMessage(requesterLang, "tpa.deny.requester_notified").replace("{player_name}", target.getUsername()), NamedTextColor.RED));
        });

        target.sendMessage(localeManager.getComponent(lang, "tpa.deny.success", NamedTextColor.GREEN));

        return Command.SINGLE_SUCCESS;
    }

    private static LiteralArgumentBuilder<CommandSource> literal(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSource, T> argument(String name, com.mojang.brigadier.arguments.ArgumentType<T> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }
}
