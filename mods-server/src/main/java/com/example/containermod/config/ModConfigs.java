package com.example.containermod.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.apache.commons.lang3.tuple.Pair;

public class ModConfigs {

    public static final ForgeConfigSpec CLIENT_SPEC;
    public static final ClientConfig CLIENT;

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final ServerConfig SERVER;

    static {
        final Pair<ClientConfig, ForgeConfigSpec> clientPair = new ForgeConfigSpec.Builder().configure(ClientConfig::new);
        CLIENT_SPEC = clientPair.getRight();
        CLIENT = clientPair.getLeft();

        final Pair<ServerConfig, ForgeConfigSpec> serverPair = new ForgeConfigSpec.Builder().configure(ServerConfig::new);
        SERVER_SPEC = serverPair.getRight();
        SERVER = serverPair.getLeft();
    }

    public static class ClientConfig {
        // Client-specific configs can go here if needed in the future
        ClientConfig(ForgeConfigSpec.Builder builder) {
            builder.comment("Client configuration settings for ContainerMod").push("client");
            // Example: public final ForgeConfigSpec.BooleanValue someClientSetting;
            // someClientSetting = builder.comment("Some client-only setting.").define("someClientSetting", true);
            builder.pop();
        }
    }

    public static class ServerConfig {
        public final ForgeConfigSpec.ConfigValue<String> API_BASE_URL;

        ServerConfig(ForgeConfigSpec.Builder builder) {
            builder.comment("Server configuration settings for ContainerMod").push("server");

            API_BASE_URL = builder
                    .comment("The base URL for the container management API.",
                             "Example: http://127.0.0.1:8000/baza/")
                    .define("general.apiBaseUrl", "http://127.0.0.1:8000/baza/");

            // Other server-specific configs can go here
            builder.pop();
        }
    }

    public static void setup() {
        // Registering server config. Client config can be registered similarly if needed.
        // For server-side logic like API calls, server config is appropriate.
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SERVER_SPEC);
        // If you had client configs:
        // ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CLIENT_SPEC);
    }

    // Config values are automatically loaded by Forge.
    // This method is not strictly necessary for just reading values via .get(),
    // but can be useful if you need to react to config changes or load them at a specific time manually.
    // However, direct use of ModConfigs.SERVER.API_BASE_URL.get() is usually preferred.
    // For simplicity and common Forge practice, direct access after registration is sufficient.
    // No explicit loadConfig() method will be added here as Forge handles loading.
    // If config reload handling is needed, one would subscribe to ModConfigEvent.Reloading or ModConfigEvent.Loading.
}
