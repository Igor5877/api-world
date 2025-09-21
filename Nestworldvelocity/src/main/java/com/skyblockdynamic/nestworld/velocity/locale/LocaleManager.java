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

public class LocaleManager {

    private final Path dataDirectory;
    private final Logger logger;
    private final Map<String, Properties> langMessages = new ConcurrentHashMap<>();
    private final String defaultLang = "en";

    public LocaleManager(Path dataDirectory, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        loadLocales();
    }

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

    public String getMessage(String lang, String key) {
        return langMessages.getOrDefault(lang, langMessages.get(defaultLang)).getProperty(key, key);
    }

    public TextComponent getComponent(String lang, String key) {
        return Component.text(getMessage(lang, key));
    }
    
    public TextComponent getComponent(String lang, String key, NamedTextColor color) {
        return Component.text(getMessage(lang, key), color);
    }
}
