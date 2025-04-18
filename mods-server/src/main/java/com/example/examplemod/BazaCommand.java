package com.cody.clone;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.util.text.StringTextComponent;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

public class BazaCommand {

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(
            Commands.literal("baza")
                .then(Commands.literal("add")
                    .then(Commands.argument("name", StringArgumentType.string())
                        .executes(context -> {
                            String name = StringArgumentType.getString(context, "name");
                            String player = context.getSource().getTextName();

                            context.getSource().sendSuccess(
                                new StringTextComponent("⏳ Готуємо ваш світ..."), false
                            );

                            new Thread(() -> handleRequest(context.getSource(), player, name)).start();
                            return 1;
                        }))));
    }

    private static void handleRequest(CommandSource source, String player, String name) {
        try {
            URL url = new URL("http://nestworld.site:5000/baza/add");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            String json = String.format("{\"player\":\"%s\", \"name\":\"%s\"}", player, name);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes());
                os.flush();
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder responseSB = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                responseSB.append(inputLine);
            }
            in.close();

            // Парсимо відповідь вручну (простий спосіб без JSON парсера)
            String response = responseSB.toString();
            String ip = extractValue(response, "ip");
            String port = extractValue(response, "port");

            source.sendSuccess(
                new StringTextComponent("✅ Готово! Переходимо на: " + ip + ":" + port), false
            );

            // Тут має бути код перекидання на інший сервер (див. нижче)
            // Можна інтегрувати через Velocity Messaging / Forge / Bungee

        } catch (Exception e) {
            source.sendFailure(new StringTextComponent("❌ Помилка: " + e.getMessage()));
        }
    }

    // Примітивний парсер (не рекомендований для продакшену)
    private static String extractValue(String json, String key) {
        try {
            int idx = json.indexOf("\"" + key + "\":");
            if (idx == -1) return null;
            int start = json.indexOf("\"", idx + key.length() + 3) + 1;
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }
}
