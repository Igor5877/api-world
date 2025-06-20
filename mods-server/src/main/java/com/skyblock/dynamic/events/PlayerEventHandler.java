package com.skyblock.dynamic.events;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.skyblock.dynamic.SkyBlockMod;
import com.skyblock.dynamic.Config; // For API URL

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod.EventBusSubscriber(modid = SkyBlockMod.MODID)
public class PlayerEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer) {
            ServerPlayer player = (ServerPlayer) event.getEntity();
            UUID playerUuid = player.getUUID();
            String playerName = player.getName().getString();

            // We need to determine if this logout is from an actual island server
            // or from the hub. For now, we'll assume this mod (when installed on a server)
            // means it's an island server that should be frozen on logout.
            // More sophisticated logic might be needed if this mod also runs on the hub
            // and needs to differentiate. The project description implies this mod is for "Hub and Islands".
            // For MVP, sending freeze on any logout from a server with this mod seems acceptable.

            LOGGER.info("Player {} (UUID: {}) logged out. Attempting to send freeze request to API.", playerName, playerUuid);

            String apiUrl = Config.getApiBaseUrl() + "islands/" + playerUuid.toString() + "/freeze"; // Reverted to original, which was correct

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .POST(HttpRequest.BodyPublishers.noBody()) // No body needed for freeze usually
                    .header("Content-Type", "application/json") // Still good practice
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> { // Changed thenApply to thenAccept
                    int statusCode = response.statusCode();
                    if (statusCode == 200 || statusCode == 202) { // OK or Accepted
                        LOGGER.info("API call to freeze island for player {} (UUID: {}) successful. Status: {}", playerName, playerUuid, statusCode);
                    } else if (statusCode == 404) {
                        LOGGER.warn("API call to freeze island for player {} (UUID: {}) returned 404 - island not found or not running. Status: {}, Body: {}", playerName, playerUuid, statusCode, response.body());
                    } else {
                        LOGGER.error("API call to freeze island for player {} (UUID: {}) failed. Status: {}, Body: {}", playerName, playerUuid, statusCode, response.body());
                    }
                    // No player message is sent here, this is purely server-side.
                })
                .exceptionally(e -> {
                    LOGGER.error("Exception during API call to freeze island for player {} (UUID: {}): {}", playerName, playerUuid, e.getMessage(), e);
                    return null; // Void for exceptionally
                });
        }
    }
}
