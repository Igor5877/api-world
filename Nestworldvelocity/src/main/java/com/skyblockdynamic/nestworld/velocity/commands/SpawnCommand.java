package com.skyblockdynamic.nestworld.velocity.commands;

import com.skyblockdynamic.nestworld.velocity.config.PluginConfig;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Optional;

public class SpawnCommand implements SimpleCommand {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final PluginConfig config;

    public SpawnCommand(ProxyServer proxyServer, Logger logger, PluginConfig config) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.config = config;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        if (!(source instanceof Player)) {
            source.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) source;
        String spawnServerName = config.getFallbackServerName(); // Отримуємо ім'я спавн-сервера з конфігу

        Optional<RegisteredServer> spawnServer = proxyServer.getServer(spawnServerName);

        if (spawnServer.isEmpty()) {
            logger.error("The configured spawn server ('{}') was not found in Velocity's configuration!", spawnServerName);
            player.sendMessage(Component.text("The spawn server is not configured correctly. Please contact an administrator.", NamedTextColor.RED));
            return;
        }
        
        // Перевірка, чи гравець вже на цьому сервері
        if (player.getCurrentServer().isPresent() && player.getCurrentServer().get().getServerInfo().getName().equals(spawnServerName)) {
            player.sendMessage(Component.text("You are already connected to the spawn server.", NamedTextColor.YELLOW));
            return;
        }

        player.sendMessage(Component.text("Connecting you to the spawn server...", NamedTextColor.AQUA));

        player.createConnectionRequest(spawnServer.get()).connect()
            .thenAccept(result -> {
                if (result.isSuccessful()) {
                    player.sendMessage(Component.text("Successfully connected to spawn!", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Could not connect you to spawn.", NamedTextColor.RED));
                }
            });
    }
    
    @Override
    public boolean hasPermission(Invocation invocation) {
        // Дозволяємо всім гравцям використовувати цю команду
        return true; 
    }
}