package com.cody.clone;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.entity.player.ServerPlayerEntity;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class CloneCommand {
    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(Commands.literal("clonecontainer")
            .then(Commands.argument("name", StringArgumentType.string())
            .executes(context -> {
                String name = StringArgumentType.getString(context, "name");
                CommandSource source = context.getSource();
                String player = source.getDisplayName().getString();

                sendCloneRequest(player, name);
                source.sendSuccess(new net.minecraft.util.text.StringTextComponent("Запит на клонування надіслано."), false);
                return 1;
            })));
    }

    private static void sendCloneRequest(String player, String name) {
        try {
            URL url = new URL("http://nestworld.site:5000/clone");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String json = String.format("{\"player\":\"%s\",\"name\":\"%s\"}", player, name);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes());
                os.flush();
            }

            conn.getInputStream().close(); // Читати не треба, лише виклик
        } catch (Exception e) {
            System.err.println("Помилка HTTP-запиту: " + e.getMessage());
        }
        
    }
}
