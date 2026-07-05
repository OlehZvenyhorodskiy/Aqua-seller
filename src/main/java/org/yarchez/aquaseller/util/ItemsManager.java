package org.yarchez.aquaseller.util;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.yarchez.aquaseller.AquaSeller;

import java.io.File;
import java.util.*;

/**
 * Loads custom items from items.yml and can create them as ItemStack.
 *
 * For this task we mainly use it for custom furnaces ("печки").
 */
public class ItemsManager {

    public static final String ITEMS_ROOT = "items";

    private final AquaSeller plugin;
    private File file;
    private FileConfiguration cfg;

    private final NamespacedKey keyItemId;
    private final NamespacedKey keyFurnaceLevel;
    private final NamespacedKey keyAcceleratorLevel;
    private final NamespacedKey keySmokerLevel;

    public ItemsManager(AquaSeller plugin) {
        this.plugin = plugin;
        this.keyItemId = new NamespacedKey(plugin, "item_id");
        this.keyFurnaceLevel = new NamespacedKey(plugin, "furnace_level");
        this.keyAcceleratorLevel = new NamespacedKey(plugin, "accelerator_level");
        this.keySmokerLevel = new NamespacedKey(plugin, "smoker_level");
        ensure();
    }

    public void ensure() {
        file = new File(plugin.getDataFolder(), "items.yml");
        if (!file.exists()) {
            plugin.saveResource("items.yml", false);
        }

        cfg = YamlConfiguration.loadConfiguration(file);

        // Merge in any new default items from the jar without overwriting
        // admin-customized existing entries in /plugins/AquaSeller/items.yml.
        boolean changed = false;
        try (java.io.InputStream in = plugin.getResource("items.yml")) {
            if (in != null) {
                java.io.InputStreamReader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8);
                FileConfiguration defaults = YamlConfiguration.loadConfiguration(reader);
                ConfigurationSection defSec = defaults.getConfigurationSection(ITEMS_ROOT);
                if (defSec != null) {
                    for (String id : defSec.getKeys(false)) {
                        String path = ITEMS_ROOT + "." + id;
                        if (!cfg.isConfigurationSection(path)) {
                            cfg.set(path, defaults.get(path));
                            changed = true;
                            plugin.getLogger().info("[ITEMS] added missing default item: " + id);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("[ITEMS] could not merge default items.yml: " + ex.getMessage());
        }

        if (changed) {
            try {
                cfg.save(file);
            } catch (Exception ex) {
                plugin.getLogger().warning("[ITEMS] could not save merged items.yml: " + ex.getMessage());
            }
        }

        plugin.getLogger().info("[ITEMS] using items: " + file.getAbsolutePath());
    }

    public FileConfiguration cfg() {
        return cfg;
    }

    public NamespacedKey keyItemId() {
        return keyItemId;
    }

    public NamespacedKey keyFurnaceLevel() {
        return keyFurnaceLevel;
    }

    public NamespacedKey keyAcceleratorLevel() {
        return keyAcceleratorLevel;
    }

    public NamespacedKey keySmokerLevel() {
        return keySmokerLevel;
    }

    public Set<String> itemIds() {
        ConfigurationSection sec = cfg.getConfigurationSection(ITEMS_ROOT);
        if (sec == null) return Collections.emptySet();
        return sec.getKeys(false);
    }

    public boolean exists(String itemId) {
        return cfg.isConfigurationSection(ITEMS_ROOT + "." + itemId);
    }

    public ItemStack create(String itemId) {
        ConfigurationSection sec = cfg.getConfigurationSection(ITEMS_ROOT + "." + itemId);
        if (sec == null) return null;

        String materialName = sec.getString("material", "FURNACE");
        Material mat;
        try {
            mat = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            mat = Material.FURNACE;
        }

        int amount = Math.max(1, sec.getInt("amount", 1));
        ItemStack it = new ItemStack(mat, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        String name = sec.getString("name", "&fItem");
        meta.setDisplayName(color(name));

        List<String> lore = sec.getStringList("lore");
        if (lore != null && !lore.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (String s : lore) out.add(color(s));
            meta.setLore(out);
        }

        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        // Optional cosmetic glow (enchanted look without showing enchantment text).
        if (sec.getBoolean("glow", false)) {
            Enchantment glow = resolveGlowEnchant();
            if (glow != null) {
                meta.addEnchant(glow, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        }

        // tags
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyItemId, PersistentDataType.STRING, itemId);

        if (sec.contains("furnace.level")) {
            int lvl = sec.getInt("furnace.level", 1);
            pdc.set(keyFurnaceLevel, PersistentDataType.INTEGER, lvl);
        }
        if (sec.contains("accelerator.level")) {
            int lvl = sec.getInt("accelerator.level", 1);
            pdc.set(keyAcceleratorLevel, PersistentDataType.INTEGER, lvl);
        }
        if (sec.contains("smoker.level")) {
            int lvl = sec.getInt("smoker.level", 1);
            pdc.set(keySmokerLevel, PersistentDataType.INTEGER, lvl);
        }

        it.setItemMeta(meta);
        return it;
    }


    private Enchantment resolveGlowEnchant() {
        Enchantment ench = Enchantment.getByName("LUCK");
        if (ench == null) ench = Enchantment.getByName("LOOT_BONUS_BLOCKS");
        if (ench == null) ench = Enchantment.getByName("DURABILITY");
        if (ench == null) ench = Enchantment.getByName("UNBREAKING");
        if (ench == null) ench = Enchantment.values().length > 0 ? Enchantment.values()[0] : null;
        return ench;
    }

    public String getItemId(ItemStack it) {
        if (it == null) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(keyItemId, PersistentDataType.STRING);
    }

    public Integer getFurnaceLevel(ItemStack it) {
        if (it == null) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(keyFurnaceLevel, PersistentDataType.INTEGER);
    }

    public boolean isCustomFurnace(ItemStack it) {
        return getFurnaceLevel(it) != null;
    }

    public Integer getSmokerLevel(ItemStack it) {
        if (it == null) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(keySmokerLevel, PersistentDataType.INTEGER);
    }

    public boolean isCustomSmoker(ItemStack it) {
        return getSmokerLevel(it) != null;
    }

    public boolean isCustomCookingBlock(ItemStack it) {
        return isCustomFurnace(it) || isCustomSmoker(it);
    }

    public Integer getAcceleratorLevel(ItemStack it) {
        if (it == null) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(keyAcceleratorLevel, PersistentDataType.INTEGER);
    }

    public boolean isCropAccelerator(ItemStack it) {
        return getAcceleratorLevel(it) != null;
    }

    public AcceleratorStats getAcceleratorStats(int level) {
        ConfigurationSection sec = cfg.getConfigurationSection(ITEMS_ROOT);
        if (sec == null) return null;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            if (s.getInt("accelerator.level", -1) == level) {
                int radius = s.getInt("accelerator.radius", 5);
                double bonus = s.getDouble("accelerator.growth_bonus", 0.30D);
                return new AcceleratorStats(level, radius, bonus, id);
            }
        }
        return null;
    }

    public AcceleratorStats getAcceleratorStatsByItem(ItemStack it) {
        Integer lvl = getAcceleratorLevel(it);
        if (lvl == null) return null;
        AcceleratorStats st = getAcceleratorStats(lvl);
        if (st != null) return st;
        return new AcceleratorStats(lvl, 5, 0.30D, getItemId(it));
    }

    public FurnaceStats getFurnaceStats(int level) {
        // find any item with furnace.level==level (we only have one line now)
        ConfigurationSection sec = cfg.getConfigurationSection(ITEMS_ROOT);
        if (sec == null) return null;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            if (s.getInt("furnace.level", -1) == level) {
                double fuel = s.getDouble("furnace.fuel_multiplier", 1.0);
                double speed = s.getDouble("furnace.speed_multiplier", 1.0);
                double out = s.getDouble("furnace.output_multiplier", 1.0);
                return new FurnaceStats(level, fuel, speed, out, id);
            }
        }
        return null;
    }

    public FurnaceStats getFurnaceStatsByItem(ItemStack it) {
        Integer lvl = getFurnaceLevel(it);
        if (lvl == null) return null;
        FurnaceStats st = getFurnaceStats(lvl);
        if (st != null) return st;
        // fallback to defaults
        return new FurnaceStats(lvl, 1.0, 1.0, 1.0, getItemId(it));
    }


    public FurnaceStats getSmokerStats(int level) {
        ConfigurationSection sec = cfg.getConfigurationSection(ITEMS_ROOT);
        if (sec == null) return null;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            if (s.getInt("smoker.level", -1) == level) {
                double fuel = s.getDouble("smoker.fuel_multiplier", 1.0);
                double speed = s.getDouble("smoker.speed_multiplier", 1.0);
                double out = s.getDouble("smoker.output_multiplier", 1.0);
                return new FurnaceStats(level, fuel, speed, out, id);
            }
        }
        return null;
    }

    public FurnaceStats getSmokerStatsByItem(ItemStack it) {
        Integer lvl = getSmokerLevel(it);
        if (lvl == null) return null;
        FurnaceStats st = getSmokerStats(lvl);
        if (st != null) return st;
        return new FurnaceStats(lvl, 1.0, 1.0, 1.0, getItemId(it));
    }

    public String getAcceleratorItemIdByLevel(int level) {
        ConfigurationSection sec = cfg.getConfigurationSection(ITEMS_ROOT);
        if (sec == null) return null;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            if (s.getInt("accelerator.level", -1) == level) return id;
        }
        return null;
    }

    public String getFurnaceItemIdByLevel(int level) {
        ConfigurationSection sec = cfg.getConfigurationSection(ITEMS_ROOT);
        if (sec == null) return null;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            if (s.getInt("furnace.level", -1) == level) return id;
        }
        return null;
    }

    public String getSmokerItemIdByLevel(int level) {
        ConfigurationSection sec = cfg.getConfigurationSection(ITEMS_ROOT);
        if (sec == null) return null;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(id);
            if (s == null) continue;
            if (s.getInt("smoker.level", -1) == level) return id;
        }
        return null;
    }

    private static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static class AcceleratorStats {
        public final int level;
        public final int radius;
        public final double growthBonus;
        public final String itemId;

        public AcceleratorStats(int level, int radius, double growthBonus, String itemId) {
            this.level = level;
            this.radius = radius;
            this.growthBonus = growthBonus;
            this.itemId = itemId;
        }
    }

    public static class FurnaceStats {
        public final int level;
        public final double fuelMultiplier;
        public final double speedMultiplier;
        public final double outputMultiplier;
        public final String itemId;

        public FurnaceStats(int level, double fuelMultiplier, double speedMultiplier, double outputMultiplier, String itemId) {
            this.level = level;
            this.fuelMultiplier = fuelMultiplier;
            this.speedMultiplier = speedMultiplier;
            this.outputMultiplier = outputMultiplier;
            this.itemId = itemId;
        }
    }
}
