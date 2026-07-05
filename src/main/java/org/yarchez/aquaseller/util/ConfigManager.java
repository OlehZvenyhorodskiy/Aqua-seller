package org.yarchez.aquaseller.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yarchez.aquaseller.AquaSeller;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Simple wrapper around config.yml.
 * We always (re)load from disk on ensure(), so /seller reload
 * will immediately reflect new values like bonus_battlepass.
 */
public class ConfigManager {

    private final AquaSeller plugin;
    private File file;
    private FileConfiguration cfg;

    public ConfigManager(AquaSeller plugin) {
        this.plugin = plugin;
        ensure();
    }

    /**
     * (Re)load config.yml from plugin folder.
     */
    public void ensure() {
        file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
        // Bukkit's SnakeYAML rejects unquoted keys that start with '%'.
        // Users may want to use placeholders like %rudyplayer2% as keys in config.
        // We pre-process the file and automatically quote such keys.
        try {
            String raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String fixed = raw
                    // keys on their own line: %key%:
                    .replaceAll("(?m)^(\\s*)(%[^:]+%):(\\s*)$", "$1'$2':$3")
                    // keys with inline values: %key%: something
                    .replaceAll("(?m)^(\\s*)(%[^:]+%):(\\s+)", "$1'$2':$3");

            cfg = new YamlConfiguration();
            cfg.loadFromString(fixed);
        } catch (Throwable t) {
            plugin.getLogger().warning("[CFG] Failed to preprocess config.yml, falling back to default loader: " + t.getMessage());
            cfg = YamlConfiguration.loadConfiguration(file);
        }
        plugin.getLogger().info("[CFG] using config: " + file.getAbsolutePath());
    }

    /**
     * Raw configuration.
     */
    public FileConfiguration get() {
        return cfg;
    }
}