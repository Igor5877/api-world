package com.skyblockdynamic.nestworld.velocity.config;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

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
    private final boolean autoRedirectToIslandEnabled;
    private final boolean useWebsockets;
    private final int tpaTimeoutSeconds;

    private PluginConfig(String apiUrl, String fallbackServerName, int apiRequestTimeoutSeconds, int pollingIntervalMillis, int maxPollingAttempts, boolean autoRedirectToIslandEnabled, boolean useWebsockets, int tpaTimeoutSeconds) {
        this.apiUrl = apiUrl;
        this.fallbackServerName = fallbackServerName;
        this.apiRequestTimeoutSeconds = apiRequestTimeoutSeconds;
        this.pollingIntervalMillis = pollingIntervalMillis;
        this.maxPollingAttempts = maxPollingAttempts;
        this.autoRedirectToIslandEnabled = autoRedirectToIslandEnabled;
        this.useWebsockets = useWebsockets;
        this.tpaTimeoutSeconds = tpaTimeoutSeconds;
    }

    public String getApiUrl() { return apiUrl; }
    public String getFallbackServerName() { return fallbackServerName; }
    public int getApiRequestTimeoutSeconds() { return apiRequestTimeoutSeconds; }
    public int getPollingIntervalMillis() { return pollingIntervalMillis; }
    public int getMaxPollingAttempts() { return maxPollingAttempts; }
    public boolean isAutoRedirectToIslandEnabled() { return autoRedirectToIslandEnabled; }
    public boolean isUseWebsockets() { return useWebsockets; }
    public int getTpaTimeoutSeconds() { return tpaTimeoutSeconds; }

    public static PluginConfig load(Path dataDirectory, Logger logger) {
        try {
            Path configPath = dataDirectory.resolve("nestworldvelocity.toml");
            if (!Files.exists(configPath)) {
                logger.info("Configuration file not found, creating default: " + configPath);
                try (InputStream in = PluginConfig.class.getClassLoader().getResourceAsStream("nestworldvelocity_default.toml")) {
                    if (in == null) {
                        logger.error("Default configuration file (nestworldvelocity_default.toml) not found in plugin JAR.");
                        return createAndSaveMinimalConfig(configPath, logger);
                    }
                    Files.createDirectories(dataDirectory);
                    Files.copy(in, configPath);
                } catch (IOException e) {
                    logger.error("Failed to copy default configuration file: ", e);
                    return createMinimalHardcodedConfig(logger);
                }
            }

            Toml toml = new Toml().read(configPath.toFile());

            String apiUrl = toml.getString("api.base_url", "http://127.0.0.1:8000/api/v1");
            String fallbackServer = toml.getString("general.fallback_server", "hub");
            boolean autoRedirect = toml.getBoolean("general.auto_redirect_to_island_on_login", true);
            boolean useWebsockets = toml.getBoolean("general.use_websockets", false);
            int tpaTimeout = toml.getLong("tpa.timeout_seconds", 60L).intValue();
            
            long timeout = toml.getLong("api.request_timeout_seconds", 10L);
            long interval = toml.getLong("api.polling_interval_millis", 5000L);
            long attempts = toml.getLong("api.max_polling_attempts", 120L);

            logger.info("Successfully loaded configuration from " + configPath);
            logger.info("API URL: {}", apiUrl);
            logger.info("Fallback Server: {}", fallbackServer);
            logger.info("Auto-redirect to island on login: {}", autoRedirect);
            logger.info("Use WebSockets: {}", useWebsockets);
            logger.info("TPA Timeout: {}s", tpaTimeout);
            logger.info("API Request Timeout: {}s", timeout);
            logger.info("Polling Interval: {}ms, Max Attempts: {}", interval, attempts);

            return new PluginConfig(apiUrl, fallbackServer, (int)timeout, (int)interval, (int)attempts, autoRedirect, useWebsockets, tpaTimeout);

        } catch (Exception e) {
            logger.error("Error loading NestworldVelocityPlugin configuration: ", e);
            logger.warn("Using default/hardcoded configuration values as a fallback.");
            return createMinimalHardcodedConfig(logger);
        }
    }
    
    private static PluginConfig createAndSaveMinimalConfig(Path configPath, Logger logger) {
        String defaultApiUrl = "http://127.0.0.1:8000/api/v1";
        String defaultFallback = "hub";
        boolean defaultAutoRedirect = false;
        boolean defaultUseWebsockets = false;
        int defaultTpaTimeout = 60;
        int defaultTimeout = 10;
        int defaultInterval = 5000;
        int defaultAttempts = 120;

        try {
            String content = String.format(
                "[general]\n" +
                "fallback_server = \"%s\"\n" +
                "auto_redirect_to_island_on_login = %b\n" +
                "use_websockets = %b\n\n" +
                "[tpa]\n" +
                "timeout_seconds = %d\n\n" +
                "[api]\n" +
                "base_url = \"%s\"\n" +
                "request_timeout_seconds = %d\n" +
                "polling_interval_millis = %d\n" +
                "max_polling_attempts = %d\n",
                defaultFallback, defaultAutoRedirect, defaultUseWebsockets, defaultTpaTimeout, defaultApiUrl, defaultTimeout, defaultInterval, defaultAttempts
            );
            Files.writeString(configPath, content);
            logger.info("Created a minimal configuration file at: " + configPath);
        } catch (IOException ex) {
            logger.error("Failed to write minimal configuration file: ", ex);
        }
        return new PluginConfig(defaultApiUrl, defaultFallback, defaultTimeout, defaultInterval, defaultAttempts, defaultAutoRedirect, defaultUseWebsockets, defaultTpaTimeout);
    }

    private static PluginConfig createMinimalHardcodedConfig(Logger logger) {
        logger.warn("Creating a minimal hardcoded config due to previous errors.");
        return new PluginConfig("http://127.0.0.1:8000/api/v1", "hub", 10, 5000, 120, true, false, 60);
    }
}
