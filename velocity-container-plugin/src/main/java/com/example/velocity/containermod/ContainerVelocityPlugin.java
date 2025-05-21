package com.example.velocity.containermod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties; // Using Properties as a simple key-value store, will format as TOML manually

@Plugin(
        id = "containervelocityplugin",
        name = "ContainerVelocityPlugin",
        version = "1.0.0",
        description = "A Velocity plugin to interact with a container management API.",
        authors = {"YourNameOrAlias"}
)
public class ContainerVelocityPlugin {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    // This will hold the API URL loaded from config.toml
    // Made non-static as it's specific to this plugin instance and its data folder.
    private String apiBaseUrl; 
    public static final String DEFAULT_API_BASE_URL = "http://127.0.0.1:8000/baza/";

    // HTTP Client & Gson (similar to Forge mod)
    private static CloseableHttpAsyncClient httpAsyncClient; // Static for global access if needed by commands/listeners
    public static final Gson GSON = new GsonBuilder().create();

    @Inject
    public ContainerVelocityPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

        logger.info("ContainerVelocityPlugin: Constructing plugin.");
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        logger.info("ContainerVelocityPlugin: Initializing...");

        // Ensure data directory exists
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
                logger.info("ContainerVelocityPlugin: Created data directory at {}", dataDirectory);
            }
        } catch (IOException e) {
            logger.error("ContainerVelocityPlugin: Could not create data directory!", e);
            // Continue with default API URL if directory creation fails
        }

        loadOrSaveConfig(); // Load/save config.toml

        // Initialize and start the HTTP client
        try {
            final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder.create().build();
            httpAsyncClient = HttpAsyncClients.custom()
                    .setConnectionManager(connectionManager)
                    .build();
            httpAsyncClient.start();
            logger.info("ContainerVelocityPlugin: Apache HttpAsyncClient started successfully.");
        } catch (Exception e) {
            logger.error("ContainerVelocityPlugin: Failed to start HttpAsyncClient. Plugin may not function correctly.", e);
        }
        
        // Register commands here (to be implemented)
        // registerCommands();

        // Register event listeners here (to be implemented)
        // registerEventListeners();

        logger.info("ContainerVelocityPlugin: Initialization complete.");
    }

    private void loadOrSaveConfig() {
        Path configFile = dataDirectory.resolve("config.toml");
        Properties props = new Properties();

        if (Files.exists(configFile)) {
            try (BufferedReader reader = Files.newBufferedReader(configFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("apiBaseUrl")) {
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            String value = parts[1].trim();
                            // Remove quotes if present
                            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >=2) {
                                value = value.substring(1, value.length() - 1);
                            }
                            props.setProperty("apiBaseUrl", value);
                            break; // Found the key
                        }
                    }
                }
                this.apiBaseUrl = props.getProperty("apiBaseUrl", DEFAULT_API_BASE_URL);
                logger.info("ContainerVelocityPlugin: Loaded configuration from {}", configFile);
            } catch (IOException e) {
                logger.error("ContainerVelocityPlugin: Could not read config file {}. Using default API URL.", configFile, e);
                this.apiBaseUrl = DEFAULT_API_BASE_URL;
                saveDefaultConfig(configFile); // Attempt to save a default if read failed
            }
        } else {
            logger.info("ContainerVelocityPlugin: Config file {} not found. Creating default configuration.", configFile);
            this.apiBaseUrl = DEFAULT_API_BASE_URL;
            saveDefaultConfig(configFile);
        }
        logger.info("ContainerVelocityPlugin: API Base URL set to: {}", this.apiBaseUrl);
    }

    private void saveDefaultConfig(Path configFile) {
        try (BufferedWriter writer = Files.newBufferedWriter(configFile)) {
            writer.write("# Configuration file for ContainerVelocityPlugin\n");
            writer.write("apiBaseUrl = \"" + DEFAULT_API_BASE_URL + "\"\n");
            logger.info("ContainerVelocityPlugin: Saved default configuration to {}", configFile);
        } catch (IOException e) {
            logger.error("ContainerVelocityPlugin: Could not save default config file {}", configFile, e);
        }
    }


    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("ContainerVelocityPlugin: Shutting down...");

        if (httpAsyncClient != null) {
            try {
                httpAsyncClient.close();
                logger.info("ContainerVelocityPlugin: Apache HttpAsyncClient closed successfully.");
            } catch (IOException e) {
                logger.error("ContainerVelocityPlugin: Failed to close HttpAsyncClient", e);
            }
        }
        logger.info("ContainerVelocityPlugin: Shutdown complete.");
    }

    private void registerCommands() {
        logger.info("ContainerVelocityPlugin: Commands would be registered here.");
    }

    private void registerEventListeners() {
        logger.info("ContainerVelocityPlugin: Event listeners would be registered here.");
    }
    
    public static CloseableHttpAsyncClient getHttpClient() {
        return httpAsyncClient;
    }

    // Getter for the loaded API base URL
    public String getApiBaseUrl() {
        return this.apiBaseUrl;
    }

    public ProxyServer getServer() {
        return server;
    }

    public Logger getLogger() {
        return logger;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }
}
