package org.yarchez.aquaseller.util;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yarchez.aquaseller.AquaSeller;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Stores player persistent data:
 * - autosell toggles
 * - per-material autosell toggles
 * - how many of each material player has sold (for multipliers)
 *
 * YAML structure (player-data.yml):
 * autosell.<uuid>: boolean                      (legacy global autosell flag)
 * autosell_items.<uuid>.<MATERIAL>: boolean     (per-material autosell flag)
 * sold.<uuid>.<MATERIAL>: long                  (total sold count)
 */
public class DataStore {

    private static final String AUTO_PATH = "autosell";
    private static final String AUTO_ITEM_PATH = "autosell_items";
    private static final String SOLD_PATH = "sold";
    private static final String PLAYER_NAMES_PATH = "player_names";

    // ---------------------------------------------------------------------
    // Shahter profession (miner) progression
    // ---------------------------------------------------------------------
    /**
     * shahter.<uuid>.level
     * shahter.<uuid>.total_sold               (cumulative sold amount in mining category)
     * shahter.<uuid>.progress.<1-4>           (legacy / stats per item tier, kept for backwards compatibility)
     * shahter.<uuid>.max_reward_claimed
     *
     * Legacy compatibility:
     * old builds stored this profession under rudy.<uuid>.*
     */
    private static final String SHAHTER_PATH = "shahter";
    private static final String LEGACY_RUDY_PATH = "rudy";
    private static final String SHAHTER_TOTAL_SOLD = "total_sold";

    // ---------------------------------------------------------------------
    // Fermer profession progression (same logic as Rudy, separate namespace)
    // ---------------------------------------------------------------------
    private static final String FERMER_PATH = "fermer";
    private static final String FERMER_TOTAL_SOLD = "total_sold";

    private final AquaSeller plugin;
    private final Object ioLock = new Object();
    private File file;
    private FileConfiguration cfg;

    public DataStore(AquaSeller plugin) {
        this.plugin = plugin;
        ensure();
    }

    /**
     * Load / reload player-data.yml.
     */
    public void ensure() {
        file = new File(plugin.getDataFolder(), "player-data.yml");
        if (!file.exists()) {
            plugin.saveResource("player-data.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        migrateLegacyProfessionData();
    }


    private void migrateLegacyProfessionData() {
        boolean changed = false;
        changed |= migrateRootSection(LEGACY_RUDY_PATH, SHAHTER_PATH);
        if (changed) {
            save();
        }
    }

    private boolean migrateRootSection(String from, String to) {
        if (from == null || to == null || from.equalsIgnoreCase(to)) return false;
        if (!cfg.isConfigurationSection(from)) return false;

        org.bukkit.configuration.ConfigurationSection fromSec = cfg.getConfigurationSection(from);
        if (fromSec == null) return false;

        if (!cfg.isConfigurationSection(to)) {
            cfg.set(to, fromSec.getValues(true));
            cfg.set(from, null);
            return true;
        }

        org.bukkit.configuration.ConfigurationSection toSec = cfg.getConfigurationSection(to);
        if (toSec == null) return false;

        boolean changed = mergeMissing(fromSec, toSec, "");
        cfg.set(from, null);
        return true || changed;
    }

    private boolean mergeMissing(org.bukkit.configuration.ConfigurationSection fromSec,
                                 org.bukkit.configuration.ConfigurationSection toSec,
                                 String relativePath) {
        boolean changed = false;
        for (String key : fromSec.getKeys(false)) {
            String childPath = relativePath == null || relativePath.isEmpty() ? key : relativePath + "." + key;
            Object value = fromSec.get(key);
            if (value instanceof org.bukkit.configuration.ConfigurationSection) {
                if (!toSec.isConfigurationSection(key)) {
                    toSec.set(key, ((org.bukkit.configuration.ConfigurationSection) value).getValues(true));
                    changed = true;
                } else {
                    changed |= mergeMissing((org.bukkit.configuration.ConfigurationSection) value,
                            toSec.getConfigurationSection(key), childPath);
                }
            } else if (!toSec.contains(key)) {
                toSec.set(key, value);
                changed = true;
            }
        }
        return changed;
    }

    public void rememberPlayerName(UUID uuid, String name) {
        if (uuid == null || name == null || name.isBlank()) return;
        String path = PLAYER_NAMES_PATH + "." + uuid.toString();
        String old = cfg.getString(path, "");
        if (!name.equals(old)) {
            cfg.set(path, name);
            save();
        }
    }

    public String getRememberedPlayerName(UUID uuid) {
        if (uuid == null) return null;
        String name = cfg.getString(PLAYER_NAMES_PATH + "." + uuid.toString(), null);
        return (name == null || name.isBlank()) ? null : name;
    }

    public void backfillRememberedNamesFromSections() {
        boolean changed = false;
        changed |= backfillRememberedNamesFromSection("shahter");
        changed |= backfillRememberedNamesFromSection("fermer");
        changed |= backfillRememberedNamesFromSection("killer");
        changed |= backfillRememberedNamesFromSection("fishman");
        if (changed) save();
    }

    private boolean backfillRememberedNamesFromSection(String root) {
        org.bukkit.configuration.ConfigurationSection sec = cfg.getConfigurationSection(root);
        if (sec == null) return false;
        boolean changed = false;
        for (String key : sec.getKeys(false)) {
            java.util.UUID uuid;
            try {
                uuid = java.util.UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (getRememberedPlayerName(uuid) != null) continue;
            try {
                org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
                if (online != null && online.getName() != null && !online.getName().isBlank()) {
                    cfg.set(PLAYER_NAMES_PATH + "." + uuid.toString(), online.getName());
                    changed = true;
                    continue;
                }
                org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(uuid);
                if (op != null && op.getName() != null && !op.getName().isBlank()) {
                    cfg.set(PLAYER_NAMES_PATH + "." + uuid.toString(), op.getName());
                    changed = true;
                }
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }

    private void save() {
        try {
            String yamlString;
            synchronized (ioLock) {
                yamlString = cfg.saveToString();
            }
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                synchronized (ioLock) {
                    try {
                        java.nio.file.Files.writeString(file.toPath(), yamlString, java.nio.charset.StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        plugin.getLogger().warning("Could not save " + file.getName() + ": " + e.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().severe("Error preparing data save: " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // Legacy global autosell (kept for backwards compatibility)
    // ---------------------------------------------------------------------
    public boolean isAuto(UUID uuid) {
        return cfg.getBoolean(AUTO_PATH + "." + uuid.toString(), false);
    }

    public void setAuto(UUID uuid, boolean value) {
        cfg.set(AUTO_PATH + "." + uuid.toString(), value);
        save();
    }

    // ---------------------------------------------------------------------
    // Per-material autosell
    // ---------------------------------------------------------------------
    public boolean isAuto(UUID uuid, String material) {
        return cfg.getBoolean(AUTO_ITEM_PATH + "." + uuid.toString() + "." + material, false);
    }

    public void setAuto(UUID uuid, String material, boolean value) {
        cfg.set(AUTO_ITEM_PATH + "." + uuid.toString() + "." + material, value);
        save();
    }

    // ---------------------------------------------------------------------
    // Sold counters (for multipliers)
    // ---------------------------------------------------------------------
    public long addSold(UUID uuid, String material, long amount) {
        String path = SOLD_PATH + "." + uuid.toString() + "." + material;
        long newValue = cfg.getLong(path, 0L) + amount;
        cfg.set(path, newValue);
        save();
        return newValue;
    }

    public long getSold(UUID uuid, String material) {
        return cfg.getLong(SOLD_PATH + "." + uuid.toString() + "." + material, 0L);
    }

    // ---------------------------------------------------------------------
    // RUDY profession
    // ---------------------------------------------------------------------
    public int getRudyLevel(UUID uuid) {
        return getProfessionLevel("shahter", uuid);
    }

    public void setRudyLevel(UUID uuid, int level) {
        setProfessionLevel("shahter", uuid, level);
    }

    public long getRudyProgress(UUID uuid, int itemLevel) {
        return cfg.getLong(SHAHTER_PATH + "." + uuid.toString() + ".progress." + itemLevel, 0L);
    }

    public long addRudyProgress(UUID uuid, int itemLevel, long amount) {
        String path = SHAHTER_PATH + "." + uuid.toString() + ".progress." + itemLevel;
        long newValue = cfg.getLong(path, 0L) + amount;
        cfg.set(path, newValue);
        save();
        return newValue;
    }

    // Cumulative counter (used for profession leveling)
    public long getRudyTotalSold(UUID uuid) {
        return getProfessionTotalSold("shahter", uuid);
    }

    public long addRudyTotalSold(UUID uuid, long amount) {
        return addProfessionTotalSold("shahter", uuid, amount);
    }

    public boolean isRudyMaxRewardClaimed(UUID uuid) {
        return cfg.getBoolean(SHAHTER_PATH + "." + uuid.toString() + ".max_reward_claimed", false);
    }

    public void setRudyMaxRewardClaimed(UUID uuid, boolean claimed) {
        cfg.set(SHAHTER_PATH + "." + uuid.toString() + ".max_reward_claimed", claimed);
        save();
    }

    // ---------------------------------------------------------------------
    // RUDY level reward buttons (one-time claim per level)
    // shahter.<uuid>.rewards_claimed.<level>: boolean
    // ---------------------------------------------------------------------
    public boolean isRudyRewardClaimed(UUID uuid, int level) {
        return cfg.getBoolean(SHAHTER_PATH + "." + uuid.toString() + ".rewards_claimed." + level, false);
    }

    public void setRudyRewardClaimed(UUID uuid, int level, boolean claimed) {
        cfg.set(SHAHTER_PATH + "." + uuid.toString() + ".rewards_claimed." + level, claimed);
        save();
    }

    // ---------------------------------------------------------------------
    // FERMER profession
    // ---------------------------------------------------------------------
    public int getFermerLevel(UUID uuid) {
        return getProfessionLevel("fermer", uuid);
    }

    public void setFermerLevel(UUID uuid, int level) {
        setProfessionLevel("fermer", uuid, level);
    }

    public long getFermerProgress(UUID uuid, int itemLevel) {
        return cfg.getLong(FERMER_PATH + "." + uuid.toString() + ".progress." + itemLevel, 0L);
    }

    public long addFermerProgress(UUID uuid, int itemLevel, long amount) {
        String path = FERMER_PATH + "." + uuid.toString() + ".progress." + itemLevel;
        long newValue = cfg.getLong(path, 0L) + amount;
        cfg.set(path, newValue);
        save();
        return newValue;
    }

    public long getFermerTotalSold(UUID uuid) {
        return getProfessionTotalSold("fermer", uuid);
    }
 
    public long addFermerTotalSold(UUID uuid, long amount) {
        return addProfessionTotalSold("fermer", uuid, amount);
    }

    public boolean isFermerMaxRewardClaimed(UUID uuid) {
        return cfg.getBoolean(FERMER_PATH + "." + uuid.toString() + ".max_reward_claimed", false);
    }

    public void setFermerMaxRewardClaimed(UUID uuid, boolean claimed) {
        cfg.set(FERMER_PATH + "." + uuid.toString() + ".max_reward_claimed", claimed);
        save();
    }

    public boolean isFermerRewardClaimed(UUID uuid, int level) {
        return cfg.getBoolean(FERMER_PATH + "." + uuid.toString() + ".rewards_claimed." + level, false);
    }

    public void setFermerRewardClaimed(UUID uuid, int level, boolean claimed) {
        cfg.set(FERMER_PATH + "." + uuid.toString() + ".rewards_claimed." + level, claimed);
        save();
    }

    public java.util.Map<java.util.UUID, Long> getTopProfessionTotals(String profession, int limit) {
        String prof = normalizeProfessionKey(profession);
        java.util.Map<java.util.UUID, Long> totals = new java.util.LinkedHashMap<>();
        org.bukkit.configuration.ConfigurationSection sec = cfg.getConfigurationSection(prof);
        if (sec == null) return totals;
        java.util.List<java.util.Map.Entry<java.util.UUID, Long>> list = new java.util.ArrayList<>();
        for (String key : sec.getKeys(false)) {
            try {
                java.util.UUID uuid = java.util.UUID.fromString(key);
                long total = getProfessionTotalSold(prof, uuid);
                if (total <= 0L) continue;
                list.add(new java.util.AbstractMap.SimpleEntry<>(uuid, total));
            } catch (IllegalArgumentException ignored) {
            }
        }
        list.sort((a, b) -> {
            int cmp = Long.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            return a.getKey().toString().compareToIgnoreCase(b.getKey().toString());
        });
        for (int i = 0; i < list.size() && i < Math.max(0, limit); i++) {
            totals.put(list.get(i).getKey(), list.get(i).getValue());
        }
        return totals;
    }


    public int getKillerLevel(UUID uuid) { return getProfessionLevel("killer", uuid); }
    public void setKillerLevel(UUID uuid, int level) { setProfessionLevel("killer", uuid, level); }
    public long getKillerProgress(UUID uuid, int itemLevel) { return getProfessionProgress("killer", uuid, itemLevel); }
    public long addKillerProgress(UUID uuid, int itemLevel, long amount) { return addProfessionProgress("killer", uuid, itemLevel, amount); }
    public long getKillerTotalSold(UUID uuid) { return getProfessionTotalSold("killer", uuid); }
    public long addKillerTotalSold(UUID uuid, long amount) { return addProfessionTotalSold("killer", uuid, amount); }
    public boolean isKillerMaxRewardClaimed(UUID uuid) { return isProfessionMaxRewardClaimed("killer", uuid); }
    public void setKillerMaxRewardClaimed(UUID uuid, boolean claimed) { setProfessionMaxRewardClaimed("killer", uuid, claimed); }
    public boolean isKillerRewardClaimed(UUID uuid, int level) { return isProfessionRewardClaimed("killer", uuid, level); }
    public void setKillerRewardClaimed(UUID uuid, int level, boolean claimed) { setProfessionRewardClaimed("killer", uuid, level, claimed); }

    public int getFishLevel(UUID uuid) { return getProfessionLevel("fishman", uuid); }
    public void setFishLevel(UUID uuid, int level) { setProfessionLevel("fishman", uuid, level); }
    public long getFishProgress(UUID uuid, int itemLevel) { return getProfessionProgress("fishman", uuid, itemLevel); }
    public long addFishProgress(UUID uuid, int itemLevel, long amount) { return addProfessionProgress("fishman", uuid, itemLevel, amount); }
    public long getFishTotalSold(UUID uuid) { return getProfessionTotalSold("fishman", uuid); }
    public long addFishTotalSold(UUID uuid, long amount) { return addProfessionTotalSold("fishman", uuid, amount); }
    public boolean isFishMaxRewardClaimed(UUID uuid) { return isProfessionMaxRewardClaimed("fishman", uuid); }
    public void setFishMaxRewardClaimed(UUID uuid, boolean claimed) { setProfessionMaxRewardClaimed("fishman", uuid, claimed); }
    public boolean isFishRewardClaimed(UUID uuid, int level) { return isProfessionRewardClaimed("fishman", uuid, level); }
    public void setFishRewardClaimed(UUID uuid, int level, boolean claimed) { setProfessionRewardClaimed("fishman", uuid, level, claimed); }

    // ---------------------------------------------------------------------
    // Generic profession helpers
    // ---------------------------------------------------------------------
    private static String normalizeProfessionKey(String raw) {
        if (raw == null) return "shahter";
        String s = raw.toLowerCase(java.util.Locale.ROOT);
        if (s.equals("rudy") || s.equals("shahter") || s.equals("miner") || s.equals("mining")) return "shahter";
        if (s.equals("fermer") || s.equals("farmer") || s.equals("farm")) return "fermer";
        if (s.equals("killer") || s.equals("hunter")) return "killer";
        if (s.equals("fishman") || s.equals("fisher") || s.equals("fish") || s.equals("fishing")) return "fishman";
        return s;
    }

    public int getProfessionLevel(String profession, UUID uuid) {
        String prof = normalizeProfessionKey(profession);
        return cfg.getInt(prof + "." + uuid.toString() + ".level", 1);
    }

    public void setProfessionLevel(String profession, UUID uuid, int level) {
        String prof = normalizeProfessionKey(profession);
        if (level < 1) level = 1;
        if (level > 4) level = 4;
        cfg.set(prof + "." + uuid.toString() + ".level", level);
        save();
    }

    public long getProfessionProgress(String profession, UUID uuid, int itemLevel) {
        String prof = normalizeProfessionKey(profession);
        return cfg.getLong(prof + "." + uuid.toString() + ".progress." + itemLevel, 0L);
    }

    public long addProfessionProgress(String profession, UUID uuid, int itemLevel, long amount) {
        String prof = normalizeProfessionKey(profession);
        String path = prof + "." + uuid.toString() + ".progress." + itemLevel;
        long newValue = cfg.getLong(path, 0L) + amount;
        cfg.set(path, newValue);
        save();
        return newValue;
    }

    public long getProfessionTotalSold(String profession, UUID uuid) {
        String prof = normalizeProfessionKey(profession);
        String base = prof + "." + uuid.toString() + ".";
        String path = base + "total_sold";
        long progressSum = 0L;
        for (int i = 1; i <= 4; i++) {
            progressSum += cfg.getLong(base + "progress." + i, 0L);
        }
        long stored = cfg.getLong(path, 0L);
        if (stored <= 0L && progressSum > 0L) {
            cfg.set(path, progressSum);
            save();
            return progressSum;
        }
        if (cfg.contains(path)) return stored;
        return progressSum;
    }

    public long addProfessionTotalSold(String profession, UUID uuid, long amount) {
        String prof = normalizeProfessionKey(profession);
        String path = prof + "." + uuid.toString() + ".total_sold";
        long current;
        if (cfg.contains(path)) {
            current = cfg.getLong(path, 0L);
        } else {
            current = 0L;
            String base = prof + "." + uuid.toString() + ".progress.";
            for (int i = 1; i <= 4; i++) {
                current += cfg.getLong(base + i, 0L);
            }
            current -= amount;
            if (current < 0L) current = 0L;
        }
        long newValue = current + amount;
        cfg.set(path, newValue);
        save();
        return newValue;
    }

    public boolean isProfessionMaxRewardClaimed(String profession, UUID uuid) {
        String prof = normalizeProfessionKey(profession);
        return cfg.getBoolean(prof + "." + uuid.toString() + ".max_reward_claimed", false);
    }

    public void setProfessionMaxRewardClaimed(String profession, UUID uuid, boolean claimed) {
        String prof = normalizeProfessionKey(profession);
        cfg.set(prof + "." + uuid.toString() + ".max_reward_claimed", claimed);
        save();
    }

    public boolean isProfessionRewardClaimed(String profession, UUID uuid, int level) {
        String prof = normalizeProfessionKey(profession);
        return cfg.getBoolean(prof + "." + uuid.toString() + ".rewards_claimed." + level, false);
    }

    public void setProfessionRewardClaimed(String profession, UUID uuid, int level, boolean claimed) {
        String prof = normalizeProfessionKey(profession);
        cfg.set(prof + "." + uuid.toString() + ".rewards_claimed." + level, claimed);
        save();
    }

    public long getOverallSold(UUID uuid) {
        long total = 0L;
        org.bukkit.configuration.ConfigurationSection sec = cfg.getConfigurationSection(SOLD_PATH + "." + uuid.toString());
        if (sec == null) return 0L;
        for (String key : sec.getKeys(false)) {
            total += sec.getLong(key, 0L);
        }
        return total;
    }

    public java.util.Map<java.util.UUID, Long> getTopOverallTotals(int limit) {
        java.util.Map<java.util.UUID, Long> totals = new java.util.LinkedHashMap<>();
        org.bukkit.configuration.ConfigurationSection sec = cfg.getConfigurationSection(SOLD_PATH);
        if (sec == null) return totals;
        java.util.List<java.util.Map.Entry<java.util.UUID, Long>> list = new java.util.ArrayList<>();
        for (String key : sec.getKeys(false)) {
            try {
                java.util.UUID uuid = java.util.UUID.fromString(key);
                long total = getOverallSold(uuid);
                if (total <= 0L) continue;
                list.add(new java.util.AbstractMap.SimpleEntry<>(uuid, total));
            } catch (IllegalArgumentException ignored) {}
        }
        list.sort((a, b) -> {
            int cmp = Long.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            return a.getKey().toString().compareToIgnoreCase(b.getKey().toString());
        });
        for (int i = 0; i < list.size() && i < Math.max(0, limit); i++) {
            totals.put(list.get(i).getKey(), list.get(i).getValue());
        }
        return totals;
    }

}

