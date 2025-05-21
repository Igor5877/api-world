package com.example.containermod.command;

import com.example.containermod.ContainerMod;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.UUID;

public class CreateContainerCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(CreateContainerCommand.class);
    private static final String DEFAULT_WORLD_NAME = "world"; // Default name for the container

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("mycontainer")
                .then(Commands.literal("create")
                        .executes(CreateContainerCommand::execute)
                );
        dispatcher.register(command);
    }

    private static int execute(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID playerUuid = player.getUUID();
        String apiUrl = ContainerMod.API_BASE_URL + "/add";

        player.sendSystemMessage(Component.literal("Запит на створення вашого світу відправлено..."));

        JsonObject payload = new JsonObject();
        payload.addProperty("player", playerUuid.toString());
        payload.addProperty("name", DEFAULT_WORLD_NAME);

        try {
            CloseableHttpAsyncClient httpClient = ContainerMod.getHttpClient();
            if (httpClient == null) {
                player.sendSystemMessage(Component.literal("Помилка: HTTP клієнт не ініціалізовано.").withStyle(ChatFormatting.RED));
                LOGGER.error("HTTP client not initialized in ContainerMod.");
                return 0;
            }

            URI requestUri = new URI(apiUrl);
            HttpHost target = new HttpHost(requestUri.getScheme(), requestUri.getHost(), requestUri.getPort());
            final SimpleHttpRequest request = SimpleRequestBuilder.post(target, requestUri.getPath())
                    .setBody(payload.toString(), ContentType.APPLICATION_JSON)
                    .build();
            
            LOGGER.info("Sending container creation request for player {} to {}", playerUuid, apiUrl);

            httpClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    try {
                        String responseBody = response.getBodyText();
                        LOGGER.info("Received response from API (status {}): {}", response.getCode(), responseBody);
                        JsonObject jsonResponse = JsonParser.parseString(responseBody).getAsJsonObject();

                        if (response.getCode() >= 200 && response.getCode() < 300) {
                            if (jsonResponse.has("status") && "success".equals(jsonResponse.get("status").getAsString())) {
                                String ip = jsonResponse.has("ip") ? jsonResponse.get("ip").getAsString() : "N/A";
                                int port = jsonResponse.has("port") ? jsonResponse.get("port").getAsInt() : -1;
                                String message = String.format("Світ створюється... IP: %s Порт: %d. Спробуйте підключитися за хвилину!", ip, port);
                                player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.GREEN));
                                LOGGER.info("Successfully initiated container creation for player {}. IP: {}, Port: {}", playerUuid, ip, port);
                            } else {
                                String apiErrorMessage = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "Невідома помилка API.";
                                player.sendSystemMessage(Component.literal("Помилка створення світу: " + apiErrorMessage).withStyle(ChatFormatting.RED));
                                LOGGER.error("API error for player {}: {}", playerUuid, apiErrorMessage);
                            }
                        } else {
                            String apiErrorMessage = jsonResponse.has("message") ? jsonResponse.get("message").getAsString() : "Помилка сервера API.";
                            player.sendSystemMessage(Component.literal("Помилка створення світу (код " + response.getCode() + "): " + apiErrorMessage).withStyle(ChatFormatting.RED));
                            LOGGER.error("API server error for player {} (code {}): {}", playerUuid, response.getCode(), apiErrorMessage);
                        }
                    } catch (Exception e) {
                        player.sendSystemMessage(Component.literal("Помилка обробки відповіді від API.").withStyle(ChatFormatting.RED));
                        LOGGER.error("Failed to parse API response for player {}", playerUuid, e);
                    }
                }

                @Override
                public void failed(Exception ex) {
                    player.sendSystemMessage(Component.literal("Помилка зв'язку з API для створення світу.").withStyle(ChatFormatting.RED));
                    LOGGER.error("HTTP request failed for player {} to API endpoint {}", playerUuid, apiUrl, ex);
                }

                @Override
                public void cancelled() {
                    player.sendSystemMessage(Component.literal("Запит на створення світу скасовано.").withStyle(ChatFormatting.YELLOW));
                    LOGGER.warn("HTTP request cancelled for player {} to API endpoint {}", playerUuid, apiUrl);
                }
            });

        } catch (Exception e) {
            player.sendSystemMessage(Component.literal("Не вдалося відправити запит на створення світу.").withStyle(ChatFormatting.RED));
            LOGGER.error("Failed to send container creation request for player {}", playerUuid, e);
            return 0; // Indicate command did not succeed in dispatching
        }

        return 1; // Indicate command successfully dispatched the async task
    }
}
