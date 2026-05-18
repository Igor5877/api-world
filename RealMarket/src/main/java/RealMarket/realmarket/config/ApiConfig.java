package RealMarket.realmarket.config;

import java.io.*;
import java.nio.file.*;

public class ApiConfig {
    private static final String CONFIG_NAME = "realmarket-api.toml";
    private static String cachedToken = null;
    private static String cachedServerUrl = null;
    private static int cachedServerId = -1;
    private static String cachedApiWorldUrl = null;
    private static String cachedIslandUuid = null;

    public static String getToken() {
        if (cachedToken == null) loadConfig();
        if (cachedToken == null || cachedToken.isEmpty()) {
            System.err.println("[RealMarket API] ⚠ Token not configured!");
            return null;
        }
        return cachedToken;
    }

    public static String getApiUrl() {
        if (cachedServerUrl == null) loadConfig();
        return (cachedServerUrl != null && !cachedServerUrl.isEmpty())
                ? cachedServerUrl : "https://nestworld.site/api/azlink";
    }

    public static int getServerId() {
        if (cachedServerId == -1) loadConfig();
        return cachedServerId > 0 ? cachedServerId : 1;
    }

    public static String getApiWorldUrl() {
        if (cachedApiWorldUrl == null) loadConfig();
        return (cachedApiWorldUrl != null && !cachedApiWorldUrl.isEmpty())
                ? cachedApiWorldUrl : "https://nestworld.site";
    }

    public static String getIslandUuid() {
        if (cachedIslandUuid == null) loadConfig();
        return (cachedIslandUuid != null && !cachedIslandUuid.isEmpty())
                ? cachedIslandUuid : null;
    }

    private static void loadConfig() {
        try {
            Path configPath = findConfigPath();
            if (configPath != null && Files.exists(configPath)) {
                System.out.println("[RealMarket API] Found config: " + configPath.toAbsolutePath());
                parseToml(new String(Files.readAllBytes(configPath)));
            } else {
                configPath = createDefaultConfig();
                if (configPath != null) {
                    System.out.println("[RealMarket API] Created default config: " + configPath.toAbsolutePath());
                    parseToml(new String(Files.readAllBytes(configPath)));
                } else {
                    System.err.println("[RealMarket API] Failed to create config file!");
                    setDefaults();
                }
            }
        } catch (Exception e) {
            System.err.println("[RealMarket API] Failed to load config: " + e.getMessage());
            setDefaults();
        }
    }

    private static void setDefaults() {
        cachedToken = null;
        cachedServerUrl = "https://nestworld.site/api/azlink";
        cachedServerId = 1;
        cachedApiWorldUrl = "https://nestworld.site";
        cachedIslandUuid = null;
    }

    private static Path createDefaultConfig() {
        try {
            Path configDir = Paths.get("run/config");
            if (!Files.exists(configDir)) configDir = Paths.get("config");
            Files.createDirectories(configDir);

            Path configFile = configDir.resolve(CONFIG_NAME);
            String defaultConfig =
                "# RealMarket API Configuration\n" +
                "# DO NOT commit this file with your API token!\n\n" +
                "[azuriom]\n" +
                "    token = \"\"\n" +
                "    url = \"https://nestworld.site/api/azlink\"\n" +
                "    server_id = 1\n\n" +
                "[api_world]\n" +
                "    url = \"https://nestworld.site\"\n" +
                "    island_uuid = \"\"\n";

            Files.write(configFile, defaultConfig.getBytes());
            return configFile;
        } catch (Exception e) {
            System.err.println("[RealMarket API] Error creating default config: " + e.getMessage());
            return null;
        }
    }

    private static Path findConfigPath() {
        Path[] paths = {
            Paths.get("./config/realmarket-api.toml"),
            Paths.get("run/config/realmarket-api.toml"),
            Paths.get("config/realmarket-api.toml")
        };
        for (Path p : paths) {
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static void parseToml(String content) {
        for (String rawLine : content.split("\\r?\\n")) {
            // Видаляємо коментарі
            int commentIdx = rawLine.indexOf('#');
            String line = (commentIdx != -1 ? rawLine.substring(0, commentIdx) : rawLine).trim();

            if (line.isEmpty() || line.startsWith("[")) continue;

            int eq = line.indexOf('=');
            if (eq == -1) continue;

            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();

            // Видаляємо лапки
            if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }

            switch (key.toLowerCase()) {
                case "token" -> {
                    cachedToken = value;
                    System.out.println("[RealMarket API] Token: " +
                        (!value.isEmpty() ? "✓ " + value.substring(0, Math.min(8, value.length())) + "..." : "✗ empty"));
                }
                case "url" -> {
                    if (!value.isEmpty()) {
                        // Azuriom URL містить /api/azlink, FastAPI - ні
                        if (value.contains("/api/azlink")) {
                            cachedServerUrl = value;
                            System.out.println("[RealMarket API] Azuriom URL: " + cachedServerUrl);
                        } else {
                            cachedApiWorldUrl = value;
                            System.out.println("[RealMarket API] API World URL: " + cachedApiWorldUrl);
                        }
                    }
                }
                case "server_id" -> {
                    try {
                        if (!value.isEmpty()) {
                            cachedServerId = Integer.parseInt(value);
                            System.out.println("[RealMarket API] Server ID: " + cachedServerId);
                        }
                    } catch (NumberFormatException e) {
                        cachedServerId = 1;
                    }
                }
                case "island_uuid" -> {
                    if (!value.isEmpty()) {
                        cachedIslandUuid = value;
                        System.out.println("[RealMarket API] Island UUID: " + cachedIslandUuid);
                    }
                }
            }
        }
    }
}