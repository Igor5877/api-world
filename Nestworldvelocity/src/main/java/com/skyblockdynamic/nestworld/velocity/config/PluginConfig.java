package com.skyblockdynamic.nestworld.velocity.config;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class PluginConfig {

    private final String apiUrl;
    private final String fallbackServerName;
    private final int apiRequestTimeoutSeconds;
    private final int pollingIntervalMillis;
    private final int maxPollingAttempts;

    private PluginConfig(String apiUrl, String fallbackServerName, int apiRequestTimeoutSeconds, int pollingIntervalMillis, int maxPollingAttempts) {
        this.apiUrl = apiUrl;
        this.fallbackServerName = fallbackServerName;
        this.apiRequestTimeoutSeconds = apiRequestTimeoutSeconds;
        this.pollingIntervalMillis = pollingIntervalMillis;
        this.maxPollingAttempts = maxPollingAttempts;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public String getFallbackServerName() {
        return fallbackServerName;
    }

    public int getApiRequestTimeoutSeconds() {
        return apiRequestTimeoutSeconds;
    }
    
    public int getPollingIntervalMillis() {
        return pollingIntervalMillis;
    }

    public int getMaxPollingAttempts() {
        return maxPollingAttempts;
    }

    public static PluginConfig load(Path dataDirectory, Logger logger) {
        try {
            Path configPath = dataDirectory.resolve("nestworldvelocity.toml");
            if (!Files.exists(configPath)) {
                logger.info("Configuration file not found, creating default: " + configPath);
                try (InputStream in = PluginConfig.class.getClassLoader().getResourceAsStream("nestworldvelocity_default.toml")) {
                    if (in == null) {
                        logger.error("Default configuration file (nestworldvelocity_default.toml) not found in plugin JAR.");
                        // Create a minimal config directly if default is missing
                        return createAndSaveMinimalConfig(configPath, logger);
                    }
                    Files.createDirectories(dataDirectory); // Ensure directory exists
                    Files.copy(in, configPath);
                } catch (IOException e) {
                    logger.error("Failed to copy default configuration file: ", e);
                    return createMinimalHardcodedConfig(logger); // Fallback
                }
            }

            Toml toml = new Toml().read(configPath.toFile());

            String apiUrl = toml.getString("api.base_url", "http://127.0.0.1:8000/api/v1");
            String fallbackServer = toml.getString("general.fallback_server", "hub");
            long timeout = toml.getLong("api.request_timeout_seconds", 10L);
            long interval = toml.getLong("api.polling_interval_millis", 2000L); // 2 seconds
            long attempts = toml.getLong("api.max_polling_attempts", 15L);  // 15 attempts * 2s = 30s total polling time

            logger.info("Successfully loaded configuration from " + configPath);
            logger.info("API URL: {}", apiUrl);
            logger.info("Fallback Server: {}", fallbackServer);
            logger.info("API Request Timeout: {}s", timeout);
            logger.info("Polling Interval: {}ms, Max Attempts: {}", interval, attempts);


            return new PluginConfig(apiUrl, fallbackServer, (int)timeout, (int)interval, (int)attempts);

        } catch (Exception e) {
            logger.error("Error loading NestworldVelocityPlugin configuration: ", e);
            logger.warn("Using default/hardcoded configuration values as a fallback.");
            return createMinimalHardcodedConfig(logger);
        }
    }
    
    private static PluginConfig createAndSaveMinimalConfig(Path configPath, Logger logger) {
        String defaultApiUrl = "http://127.0.0.1:8000/api/v1";
        String defaultFallback = "hub";
        int defaultTimeout = 10;
        int defaultInterval = 2000;
        int defaultAttempts = 15;

        try {
            String content = String.format(
                "[general]\n" +
                "fallback_server = \"%s\"\n\n" +
                "[api]\n" +
                "base_url = \"%s\"\n" +
                "request_timeout_seconds = %d\n" +
                "polling_interval_millis = %d\n" +
                "max_polling_attempts = %d\n",
                defaultFallback, defaultApiUrl, defaultTimeout, defaultInterval, defaultAttempts
            );
            Files.writeString(configPath, content);
            logger.info("Created a minimal configuration file at: " + configPath);
        } catch (IOException ex) {
            logger.error("Failed to write minimal configuration file: ", ex);
        }
        return new PluginConfig(defaultApiUrl, defaultFallback, defaultTimeout, defaultInterval, defaultAttempts);
    }

    private static PluginConfig createMinimalHardcodedConfig(Logger logger) {
        logger.warn("Creating a minimal hardcoded config due to previous errors.");
        return new PluginConfig("http://127.0.0.1:8000/api/v1", "hub", 10, 2000, 15);
    }
}
