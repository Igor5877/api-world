package com.skyblockdynamic.nestworld.velocity.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Manages the plugin's locales.
 */
public class LocaleManager {

    private final Path dataDirectory;
    private final Logger logger;
    private final Map<String, Properties> langMessages = new ConcurrentHashMap<>();
    private final String defaultLang = "en";

    /**
     * Constructs a new LocaleManager.
     *
     * @param dataDirectory The data directory for the plugin.
     * @param logger        The logger.
     */
    public LocaleManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        loadLocales();
    }

    /**
     * Loads the locales from the configuration files.
     */
    private void loadLocales() {
        try {
            Path langDir = dataDirectory.resolve("lang");
            if (!Files.exists(langDir)) {
                Files.createDirectories(langDir);
            }
            
            // Load default English from resources
            loadLangFromResources("en");
            loadLangFromResources("uk");

            // Override with files from lang/ if they exist
            if (Files.exists(langDir.resolve("messages_en.properties"))) {
                try (InputStream is = Files.newInputStream(langDir.resolve("messages_en.properties"))) {
                    Properties props = new Properties();
                    props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                    langMessages.put("en", props);
                }
            }
            if (Files.exists(langDir.resolve("messages_uk.properties"))) {
                try (InputStream is = Files.newInputStream(langDir.resolve("messages_uk.properties"))) {
                    Properties props = new Properties();
                    props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                    langMessages.put("uk", props);
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load language files", e);
        }
    }

    /**
     * Loads a language from the plugin's resources.
     *
     * @param langCode The language code.
     */
    private void loadLangFromResources(String langCode) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("lang/messages_" + langCode + ".properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                langMessages.put(langCode, props);
                logger.info("Loaded {} translations from resources.", langCode);
            } else {
                logger.warn("Could not find messages_{}.properties in resources.", langCode);
            }
        } catch (IOException e) {
            logger.error("Could not load messages_{}.properties from resources", langCode, e);
        }
    }

    /**
     * Gets a message from the locale.
     *
     * @param lang The language.
     * @param key  The key of the message.
     * @return The message.
     */
    public String getMessage(String lang, String key) {
        return langMessages.getOrDefault(lang, langMessages.get(defaultLang)).getProperty(key, key);
    }

    /**
     * Gets a message from the locale as a TextComponent.
     *
     * @param lang The language.
     * @param key  The key of the message.
     * @return The message as a TextComponent.
     */
    public TextComponent getComponent(String lang, String key) {
        return Component.text(getMessage(lang, key));
    }
    
    /**
     * Gets a message from the locale as a TextComponent with a specified color.
     *
     * @param lang  The language.
     * @param key   The key of the message.
     * @param color The color of the message.
     * @return The message as a TextComponent.
     */
    public TextComponent getComponent(String lang, String key, NamedTextColor color) {
        return Component.text(getMessage(lang, key), color);
    }
}
