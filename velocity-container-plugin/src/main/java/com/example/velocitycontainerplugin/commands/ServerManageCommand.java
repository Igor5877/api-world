package com.example.velocitycontainerplugin.commands;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
// import com.velocitypowered.api.proxy.server.RegisteredServer; // For dynamic server registration
import com.example.velocitycontainerplugin.VelocityContainerPlugin;
import com.example.velocitycontainerplugin.db.DBManager;
import com.example.velocitycontainerplugin.lxd.LXDManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.List;
// import java.util.Optional; // For dynamic server registration
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ServerManageCommand implements SimpleCommand {

    private final VelocityContainerPlugin plugin;
    private final Logger logger;
    private final DBManager dbManager;
    private final LXDManager lxdManager;

    public ServerManageCommand(VelocityContainerPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.dbManager = plugin.getDbManager();
        this.lxdManager = plugin.getLxdManager();
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player)) {
            invocation.source().sendMessage(Component.text("This command can only be run by a player.", NamedTextColor.RED));
            return;
        }

        Player player = (Player) invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            player.sendMessage(Component.text("Usage: /server <create|delete|list|join|...>", NamedTextColor.YELLOW));
            // You could send more detailed help here using multiple messages or a formatted text component
            return;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                handleCreateCommand(player, invocation);
                break;
            // TODO: Add cases for other subcommands like "delete", "list", "join"
            // case "delete":
            //     handleDeleteCommand(player, invocation);
            //     break;
            // case "list":
            //     handleListCommand(player, invocation);
            //     break;
            // case "join":
            //     handleJoinCommand(player, invocation);
            //     break;
            default:
                player.sendMessage(Component.text("Unknown subcommand. Usage: /server <create|delete|list|join|...>", NamedTextColor.RED));
        }
    }

    private void handleCreateCommand(Player player, Invocation invocation) {
        // TODO: Get lobby server name from config
        String lobbyServerName = "lobby"; // Placeholder
        // TODO: Get base container name from config
        String baseContainerName = "base-ubuntu"; // Placeholder
        // TODO: Get max containers from config
        int maxContainers = 10; // Placeholder

        if (!player.getCurrentServer().isPresent() || !player.getCurrentServer().get().getServerInfo().getName().equalsIgnoreCase(lobbyServerName)) {
            player.sendMessage(Component.text("You can only create a server from the '" + lobbyServerName + "' server.", NamedTextColor.RED));
            return;
        }

        // Placeholder: Check if player already has an active container (DBManager)
        // String existingContainer = dbManager.getActiveContainerForPlayer(player.getUniqueId().toString()); // Needs this method in DBManager
        // if (existingContainer != null) {
        //    player.sendMessage(Component.text("You already have an active server: " + existingContainer, NamedTextColor.RED));
        //    logger.info("Player " + player.getUsername() + " attempted to create a server but already has " + existingContainer);
        //    return;
        // }
        logger.info("Placeholder: DB check for existing container for " + player.getUsername());


        // Placeholder: Check container limits (DBManager or a dedicated service)
        // int currentContainerCount = dbManager.getActiveContainerCount(); // Needs this method in DBManager
        // if (currentContainerCount >= maxContainers) {
        //    player.sendMessage(Component.text("The maximum number of active servers ("+ maxContainers +") has been reached. Please try again later.", NamedTextColor.RED));
        //    logger.info("Max container limit reached. Player " + player.getUsername() + " cannot create a server.");
        //    return;
        // }
        logger.info("Placeholder: DB check for max container limit.");


        String newContainerName = "player-" + player.getUsername().toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + UUID.randomUUID().toString().substring(0, 6);
        
        player.sendMessage(Component.text("Attempting to create your server: " + newContainerName + ". This may take a moment...", NamedTextColor.GREEN));
        logger.info("Player " + player.getUsername() + " (UUID: " + player.getUniqueId() + ") initiated server creation: " + newContainerName + " from base " + baseContainerName);

        // Perform operations asynchronously
        CompletableFuture.runAsync(() -> {
            // 1. Record in DB (status: CREATING)
            boolean recorded = dbManager.recordNewContainer(player.getUniqueId().toString(), newContainerName, baseContainerName);
            if (!recorded) {
               player.sendMessage(Component.text("Failed to record server information in database. Please contact an admin.", NamedTextColor.RED));
               logger.error("Failed to record container " + newContainerName + " in DB for " + player.getUsername());
               return;
            }
            logger.info("Container " + newContainerName + " recorded in DB for player " + player.getUsername() + " with status CREATING.");


            // 2. Clone and Start LXD Container (LXDManager)
            boolean cloned = lxdManager.cloneContainer(baseContainerName, newContainerName);
            if (!cloned) {
               player.sendMessage(Component.text("Failed to clone the server image. Please contact an admin.", NamedTextColor.RED));
               dbManager.updateContainerStatus(newContainerName, "FAILED_CLONE"); 
               logger.error("Failed to clone LXD container " + newContainerName + " from " + baseContainerName);
               return;
            }
            logger.info("LXD container " + newContainerName + " cloned successfully from " + baseContainerName);

            boolean started = lxdManager.startContainer(newContainerName);
            if (!started) {
               player.sendMessage(Component.text("Failed to start your new server. Please contact an admin.", NamedTextColor.RED));
               dbManager.updateContainerStatus(newContainerName, "FAILED_START"); 
               logger.error("Failed to start LXD container " + newContainerName);
               return;
            }
            logger.info("LXD container " + newContainerName + " started successfully.");

            // 3. Update DB (status: RUNNING)
            dbManager.updateContainerStatus(newContainerName, "RUNNING");
            logger.info("DB status for " + newContainerName + " updated to RUNNING.");

            // 4. Proxy Player to New Container (This is complex and needs more infrastructure)
            // For now, we'll just inform the player. Dynamic server registration and proxying
            // would require a ServerInfo object, registering it with Velocity, and then connecting the player.
            
            // String containerIp = lxdManager.getContainerIp(newContainerName); // Needs implementation
            // if (containerIp == null) {
            //    player.sendMessage(Component.text("Server " + newContainerName + " created but could not retrieve its IP. Please contact an admin.", NamedTextColor.RED));
            //    logger.error("Could not get IP for container " + newContainerName);
            //    return;
            // }
            // logger.info("Container " + newContainerName + " IP: " + containerIp);
            //
            // ServerInfo newServerInfo = new ServerInfo(newContainerName, new InetSocketAddress(containerIp, 25565));
            // plugin.getServer().registerServer(newServerInfo);
            // logger.info("Server " + newContainerName + " registered with Velocity (hypothetically).");
            //
            // Optional<RegisteredServer> registeredServer = plugin.getServer().getServer(newContainerName);
            // if (registeredServer.isPresent()) {
            //    player.createConnectionRequest(registeredServer.get()).fireAndForget();
            //    player.sendMessage(Component.text("Server " + newContainerName + " is ready! Connecting you now...", NamedTextColor.GREEN));
            //    logger.info("Player " + player.getUsername() + " being connected to " + newContainerName);
            // } else {
            //    player.sendMessage(Component.text("Server " + newContainerName + " created but failed to connect you. Try /server join " + newContainerName, NamedTextColor.YELLOW));
            //    logger.error("Failed to get RegisteredServer for " + newContainerName + " after hypothetical registration.");
            // }
            player.sendMessage(Component.text("Server " + newContainerName + " has been provisioned! (Connection logic is placeholder). You might need to use /server join " + newContainerName + " once it's fully registered.", NamedTextColor.AQUA));
            logger.info("Placeholder: Player " + player.getUsername() + " would be connected to " + newContainerName + ". Dynamic registration/connection logic pending.");


        }).exceptionally(e -> {
            logger.error("Exception during server creation for " + player.getUsername() + " (container: " + newContainerName + ")", e);
            player.sendMessage(Component.text("An unexpected error occurred while creating your server. Please check logs or contact an admin.", NamedTextColor.RED));
            // Potentially update DB to an error state for the container
            dbManager.updateContainerStatus(newContainerName, "ERROR_UNKNOWN");
            return null;
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            return List.of("create", "delete", "list", "join"); // Suggest top-level commands
        }
        if (args.length == 1) {
            String current = args[0].toLowerCase();
            return List.of("create", "delete", "list", "join").stream()
                    .filter(s -> s.startsWith(current))
                    .toList();
        }
        // TODO: Add suggestions for sub-command arguments (e.g., list of player's servers for "delete" or "join")
        return List.of(); // No suggestions for args beyond the first for now
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        // For "create", check a specific permission like "velocitycontainer.command.server.create"
        // For other subcommands, check their respective permissions.
        // For now, allow all players to use the command.
        // This could also be handled per-subcommand in the execute method or handler methods.
        // String permission = "velocitycontainer.command.server." + (invocation.arguments().length > 0 ? invocation.arguments()[0].toLowerCase() : "base");
        // return invocation.source().hasPermission(permission);
        return true; // Allow everyone for now
    }
}
