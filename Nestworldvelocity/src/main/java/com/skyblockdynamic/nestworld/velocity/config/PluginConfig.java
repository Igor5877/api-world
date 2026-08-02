package com.skyblockdynamic.nestworld.velocity.config;

import com.moandjiezana.toml.Toml;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Represents the plugin's configuration.
 */
public class PluginConfig {

    private final String apiUrl;
    private final String apiKey;
    private final String fallbackServerName;
    private final int apiRequestTimeoutSeconds;
    private final int pollingIntervalMillis;
    private final int maxPollingAttempts;
    private final boolean autoRedirectToIslandEnabled;
    private final int tpaTimeoutSeconds;
    private final int stopDelaySeconds;

    /**
     * Constructs a new PluginConfig.
     *
     * @param apiUrl                      The API URL.
     * @param fallbackServerName          The name of the fallback server.
     * @param apiRequestTimeoutSeconds    The timeout for API requests in seconds.
     * @param pollingIntervalMillis       The polling interval in milliseconds.
     * @param maxPollingAttempts          The maximum number of polling attempts.
     * @param autoRedirectToIslandEnabled Whether to automatically redirect players to their island on login.
     * @param tpaTimeoutSeconds           The timeout for TPA requests in seconds.
     * @param stopDelaySeconds            How long to wait after the last team member fully disconnects
     *                                    before actually stopping their island (a dropped connection or
     *                                    brief network hiccup shouldn't force a full island restart).
     */
    private PluginConfig(String apiUrl, String apiKey, String fallbackServerName, int apiRequestTimeoutSeconds, int pollingIntervalMillis, int maxPollingAttempts, boolean autoRedirectToIslandEnabled, int tpaTimeoutSeconds, int stopDelaySeconds) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.fallbackServerName = fallbackServerName;
        this.apiRequestTimeoutSeconds = apiRequestTimeoutSeconds;
        this.pollingIntervalMillis = pollingIntervalMillis;
        this.maxPollingAttempts = maxPollingAttempts;
        this.autoRedirectToIslandEnabled = autoRedirectToIslandEnabled;
        this.tpaTimeoutSeconds = tpaTimeoutSeconds;
        this.stopDelaySeconds = stopDelaySeconds;
    }

    /**
     * Gets the API URL.
     *
     * @return The API URL.
     */
    public String getApiUrl() { return apiUrl; }

    public String getApiKey() { return apiKey; }

    /**
     * Gets the name of the fallback server.
     *
     * @return The name of the fallback server.
     */
    public String getFallbackServerName() { return fallbackServerName; }

    /**
     * Gets the timeout for API requests in seconds.
     *
     * @return The timeout for API requests in seconds.
     */
    public int getApiRequestTimeoutSeconds() { return apiRequestTimeoutSeconds; }

    /**
     * Gets the polling interval in milliseconds.
     *
     * @return The polling interval in milliseconds.
     */
    public int getPollingIntervalMillis() { return pollingIntervalMillis; }

    /**
     * Gets the maximum number of polling attempts.
     *
     * @return The maximum number of polling attempts.
     */
    public int getMaxPollingAttempts() { return maxPollingAttempts; }

    /**
     * Checks if auto-redirect to island is enabled.
     *
     * @return True if auto-redirect is enabled, false otherwise.
     */
    public boolean isAutoRedirectToIslandEnabled() { return autoRedirectToIslandEnabled; }

    /**
     * Gets the timeout for TPA requests in seconds.
     *
     * @return The timeout for TPA requests in seconds.
     */
    public int getTpaTimeoutSeconds() { return tpaTimeoutSeconds; }

    /**
     * Gets the delay, in seconds, between the last team member fully
     * disconnecting and the island actually being stopped.
     *
     * @return The stop delay in seconds.
     */
    public int getStopDelaySeconds() { return stopDelaySeconds; }

    /**
     * Loads the plugin configuration.
     *
     * @param dataDirectory The data directory for the plugin.
     * @param logger        The logger.
     * @return The loaded plugin configuration.
     */
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
            String apiKey = toml.getString("api.api_key", "");
            String fallbackServer = toml.getString("general.fallback_server", "hub");
            boolean autoRedirect = toml.getBoolean("general.auto_redirect_to_island_on_login", false);

            long timeout = toml.getLong("api.request_timeout_seconds", 10L);
            long interval = toml.getLong("api.polling_interval_millis", 2000L);
            long attempts = toml.getLong("api.max_polling_attempts", 15L);
            long tpaTimeout = toml.getLong("api.tpa_timeout_seconds", 60L);
            long stopDelay = toml.getLong("general.stop_delay_seconds", 900L); // 15 min

            logger.info("Successfully loaded configuration from " + configPath);
            logger.info("API URL: {}", apiUrl);
            logger.info("Fallback Server: {}", fallbackServer);
            logger.info("Auto-redirect to island on login: {}", autoRedirect);
            logger.info("API Request Timeout: {}s", timeout);
            logger.info("Polling Interval: {}ms, Max Attempts: {}", interval, attempts);
            logger.info("TPA Timeout: {}s", tpaTimeout);
            logger.info("Stop Delay: {}s", stopDelay);

            return new PluginConfig(apiUrl, apiKey, fallbackServer, (int)timeout, (int)interval, (int)attempts, autoRedirect, (int)tpaTimeout, (int)stopDelay);

        } catch (Exception e) {
            logger.error("Error loading NestworldVelocityPlugin configuration: ", e);
            logger.warn("Using default/hardcoded configuration values as a fallback.");
            return createMinimalHardcodedConfig(logger);
        }
    }
    
    /**
     * Creates and saves a minimal configuration file.
     *
     * @param configPath The path to the configuration file.
     * @param logger     The logger.
     * @return The created plugin configuration.
     */
    private static PluginConfig createAndSaveMinimalConfig(Path configPath, Logger logger) {
        String defaultApiUrl = "http://127.0.0.1:8000/api/v1";
        String defaultApiKey = "";
        String defaultFallback = "hub";
        boolean defaultAutoRedirect = false;
        int defaultTimeout = 10;
        int defaultInterval = 2000;
        int defaultAttempts = 15;
        int defaultTpaTimeout = 60;
        int defaultStopDelay = 900; // 15 min

        try {
            String content = String.format(
                "[general]\n" +
                "fallback_server = \"%s\"\n" +
                "auto_redirect_to_island_on_login = %b\n" +
                "stop_delay_seconds = %d\n\n" +
                "[api]\n" +
                "base_url = \"%s\"\n" +
                "api_key = \"\"\n" +
                "request_timeout_seconds = %d\n" +
                "polling_interval_millis = %d\n" +
                "max_polling_attempts = %d\n" +
                "tpa_timeout_seconds = %d\n",
                defaultFallback, defaultAutoRedirect, defaultStopDelay, defaultApiUrl, defaultTimeout, defaultInterval, defaultAttempts, defaultTpaTimeout
            );
            Files.writeString(configPath, content);
            logger.info("Created a minimal configuration file at: " + configPath);
        } catch (IOException ex) {
            logger.error("Failed to write minimal configuration file: ", ex);
        }
        return new PluginConfig(defaultApiUrl, defaultApiKey, defaultFallback, defaultTimeout, defaultInterval, defaultAttempts, defaultAutoRedirect, defaultTpaTimeout, defaultStopDelay);
    }

    /**
     * Creates a minimal hardcoded configuration.
     *
     * @param logger The logger.
     * @return The created plugin configuration.
     */
    private static PluginConfig createMinimalHardcodedConfig(Logger logger) {
        logger.warn("Creating a minimal hardcoded config due to previous errors.");
        return new PluginConfig("http://127.0.0.1:8000/api/v1", "", "hub", 10, 2000, 15, false, 60, 900);
    }
}
