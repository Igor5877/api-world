package RealMarket.realmarket.config;

import java.io.*;
import java.nio.file.*;

/**
 * Конфігурація для Azuriom API
 * Читає токен з конфіг-файла у папці конфіга мода
 */
public class ApiConfig {
    private static final String CONFIG_NAME = "realmarket-api.toml";
    private static String cachedToken = null;
    private static String cachedServerUrl = null;
    private static int cachedServerId = -1;

    public static String getToken() {
        if (cachedToken == null) {
            loadConfig();
        }
        if (cachedToken == null || cachedToken.isEmpty()) {
            System.err.println("[RealMarket API] ⚠ Token not configured! Please set token in config/realmarket-api.toml or run/config/realmarket-api.toml");
            return null;
        }
        return cachedToken;
    }

    public static String getApiUrl() {
        if (cachedServerUrl == null) {
            loadConfig();
        }
        return (cachedServerUrl != null && !cachedServerUrl.isEmpty()) ? cachedServerUrl : "https://nestworld.site/api/azlink";
    }

    public static int getServerId() {
        if (cachedServerId == -1) {
            loadConfig();
        }
        return cachedServerId > 0 ? cachedServerId : 1;
    }

    private static void loadConfig() {
        try {
            // Шукаємо конфіг у папці мода конфігів
            Path configPath = findConfigPath();
            
            if (configPath != null && Files.exists(configPath)) {
                System.out.println("[RealMarket API] Found config: " + configPath.toAbsolutePath());
                String content = new String(Files.readAllBytes(configPath));
                parseToml(content);
            } else {
                // Конфіг не знайдено - створюємо новий
                configPath = createDefaultConfig();
                if (configPath != null) {
                    System.out.println("[RealMarket API] Created default config: " + configPath.toAbsolutePath());
                    String content = new String(Files.readAllBytes(configPath));
                    parseToml(content);
                } else {
                    System.err.println("[RealMarket API] Failed to create config file!");
                    cachedToken = null;
                    cachedServerUrl = "https://nestworld.site/api/azlink";
                    cachedServerId = 1;
                }
            }
        } catch (Exception e) {
            System.err.println("[RealMarket API] Failed to load config: " + e.getMessage());
            e.printStackTrace();
            cachedToken = null;
            cachedServerUrl = "https://nestworld.site/api/azlink";
            cachedServerId = 1;
        }
    }

    private static Path createDefaultConfig() {
        try {
            // Переважно шукаємо у run/config
            Path configDir = Paths.get("run/config");
            if (!Files.exists(configDir)) {
                configDir = Paths.get("config");
            }
            if (!Files.exists(configDir)) {
                configDir = Paths.get("./config");
            }
            
            // Створюємо папку якщо вона не існує
            Files.createDirectories(configDir);
            
            Path configFile = configDir.resolve(CONFIG_NAME);
            
            // Створюємо дефолтний конфіг
            String defaultConfig = "# RealMarket API Configuration\n" +
                    "# DO NOT commit this file with your API token to version control!\n" +
                    "# Store your token securely or use environment variables instead\n\n" +
                    "[azuriom]\n" +
                    "    # Your Azuriom API token - keep it secret!\n" +
                    "    # Get it from Azuriom admin panel: https://nestworld.site/admin\n" +
                    "    token = \"\"\n" +
                    "    \n" +
                    "    # API URL endpoint\n" +
                    "    url = \"https://nestworld.site/api/azlink\"\n" +
                    "    \n" +
                    "    # Server ID for Azuriom linking\n" +
                    "    server_id = 1\n";
            
            Files.write(configFile, defaultConfig.getBytes());
            System.out.println("[RealMarket API] Created default config at: " + configFile.toAbsolutePath());
            
            return configFile;
        } catch (Exception e) {
            System.err.println("[RealMarket API] Error creating default config: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static Path findConfigPath() {
        // Кілька можливих локацій конфіга
        Path[] possiblePaths = {
            Paths.get("./config/realmarket-api.toml"),
            Paths.get("run/config/realmarket-api.toml"),
            Paths.get("config/realmarket-api.toml")
        };
        
        for (Path path : possiblePaths) {
            if (Files.exists(path)) {
                return path;
            }
        }
        
        return null;
    }

    private static void parseToml(String content) {
        // Простий та надійніший парсер для TOML конфігурації
        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            int commentIdx = line.indexOf('#');
            if (commentIdx != -1) {
                line = line.substring(0, commentIdx); // Видаляємо коментарі в кінці рядка
            }
            line = line.trim();

            if (line.isEmpty() || line.startsWith("[")) {
                continue; // Пропускаємо пусті рядки і заголовки секцій
            }

            int equalsPos = line.indexOf('=');
            if (equalsPos != -1) {
                String key = line.substring(0, equalsPos).trim();
                String value = line.substring(equalsPos + 1).trim();

                // Видаляємо лапки зі значення
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                } else if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }

                if (key.equalsIgnoreCase("token")) {
                    cachedToken = value;
                    System.out.println("[RealMarket API] Token loaded: " + (cachedToken != null && !cachedToken.isEmpty() ? "✓ " + cachedToken.substring(0, Math.min(8, cachedToken.length())) + "..." : "✗ (empty)"));
                } else if (key.equalsIgnoreCase("url")) {
                    if (!value.isEmpty()) {
                        cachedServerUrl = value;
                        System.out.println("[RealMarket API] API URL: " + cachedServerUrl);
                    }
                } else if (key.equalsIgnoreCase("server_id")) {
                    try {
                        if (!value.isEmpty()) {
                            cachedServerId = Integer.parseInt(value);
                            System.out.println("[RealMarket API] Server ID: " + cachedServerId);
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("[RealMarket API] Invalid server_id format: " + value);
                        cachedServerId = 1;
                    }
                }
            }
        }
    }
}
