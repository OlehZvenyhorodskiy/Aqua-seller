package org.yarchez.aquaseller.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Sound;
import org.yarchez.aquaseller.AquaSeller;
import org.yarchez.aquaseller.util.Text;

import java.util.*;

/**
 * Handles GUIs and selling logic.
 *
 * Main menu -> category menus.
 * Category menus:
 *  - LEFT CLICK  = продать 1 предмет
 *  - RIGHT CLICK = продать ВСЁ этого предмета из инвентаря
 *  - SHIFT + RIGHT CLICK = включить/выключить автопродажу именно этого предмета
 *
 * Autosell task is run from AquaSeller every 100 тиков.
 *
 * BattlePass bonus:
 * Players with permission "battlepass.premium" get a price bonus.
 * The bonus percent is stored in config.yml (bonus_battlepass).
 * finalPrice = basePrice * amount * multiplier * (1 + bonus_battlepass/100)
 */
public class SellerGui implements Listener {

    private final AquaSeller plugin;
    private Inventory mainTemplate;
    private String mainTitle;
    private final Map<String, CategoryTemplate> categories = new HashMap<>();

    // command maps for decorative buttons in main menu
    private final Map<Integer, String> mainLeftCmd = new HashMap<>();
    private final Map<Integer, String> mainRightCmd = new HashMap<>();

    // ---------------------------------------------------------------------
    // helpers / holders
    // ---------------------------------------------------------------------
    private static class TagHolder implements InventoryHolder {
        final String tag;
        TagHolder(String tag) { this.tag = tag; }
        @Override
        public Inventory getInventory() { return null; }
    }

    private static class NextInfo {
        long remaining;
        double nextMultiplier;
        boolean atMax;
    }

    private static class DynamicDecor {
        final int requiredRudyLevel;
        final ItemStack item;
        final List<Integer> slots;
        DynamicDecor(int requiredRudyLevel, ItemStack item, List<Integer> slots) {
            this.requiredRudyLevel = requiredRudyLevel;
            this.item = item;
            this.slots = slots;
        }
    }

    // ---------------------------------------------------------------------

    public SellerGui(AquaSeller plugin) {
        this.plugin = plugin;
        buildTemplates();
    }

    public void reload() {
        categories.clear();
        buildTemplates();
    }

    public void openMain(Player p) {
        if (mainTemplate == null) {
            buildTemplates();
        }
        p.openInventory(buildMainFor(p));
    }


    public boolean openCategory(Player p, String categoryId) {
        if (p == null || categoryId == null || categoryId.isEmpty()) return false;
        if (categories.isEmpty()) buildTemplates();
        String resolvedId = resolveCategoryId(categoryId);
        CategoryTemplate ct = categories.get(resolvedId);
        if (ct == null) return false;
        p.openInventory(ct.buildFor(p));
        return true;
    }

    private String normalizeProfessionKey(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        return switch (normalized) {
            case "shahter", "miner", "mining", "mines", "ore", "ores", "rudy" -> "rudy";
            case "fermer", "farmer", "farm", "farming" -> "fermer";
            case "killer", "hunter", "mob", "mobs" -> "killer";
            case "fishman", "fisher", "fishing", "fish" -> "fishman";
            default -> null;
        };
    }

    private List<String> professionConfigKeys(String profession) {
        String normalized = normalizeProfessionKey(profession);
        if (normalized == null) return Collections.emptyList();
        List<String> keys = new ArrayList<>();
        keys.add(normalized);
        if ("rudy".equals(normalized)) {
            keys.add("shahter");
        } else if ("fishman".equals(normalized)) {
            keys.add("fisher");
        }
        return keys;
    }

    private String resolveProfessionConfigBase(String profession) {
        FileConfiguration cfg = plugin.cfg().get();
        for (String key : professionConfigKeys(profession)) {
            if (cfg.isConfigurationSection("profession." + key)) {
                return "profession." + key;
            }
        }
        String normalized = normalizeProfessionKey(profession);
        return normalized == null ? "profession.rudy" : "profession." + normalized;
    }

    private long getProfessionLong(String profession, String suffix, long def) {
        FileConfiguration cfg = plugin.cfg().get();
        for (String key : professionConfigKeys(profession)) {
            String path = "profession." + key + "." + suffix;
            if (cfg.contains(path)) return cfg.getLong(path, def);
        }
        String normalized = normalizeProfessionKey(profession);
        String fallback = "profession." + (normalized == null ? "rudy" : normalized) + "." + suffix;
        return cfg.getLong(fallback, def);
    }

    private double getProfessionDouble(String profession, String suffix, double def) {
        FileConfiguration cfg = plugin.cfg().get();
        for (String key : professionConfigKeys(profession)) {
            String path = "profession." + key + "." + suffix;
            if (cfg.contains(path)) return cfg.getDouble(path, def);
        }
        String normalized = normalizeProfessionKey(profession);
        String fallback = "profession." + (normalized == null ? "rudy" : normalized) + "." + suffix;
        return cfg.getDouble(fallback, def);
    }

    private String getProfessionString(String profession, String suffix, String def) {
        FileConfiguration cfg = plugin.cfg().get();
        for (String key : professionConfigKeys(profession)) {
            String path = "profession." + key + "." + suffix;
            if (cfg.contains(path)) return cfg.getString(path, def);
        }
        String normalized = normalizeProfessionKey(profession);
        String fallback = "profession." + (normalized == null ? "rudy" : normalized) + "." + suffix;
        return cfg.getString(fallback, def);
    }

    private ConfigurationSection getProfessionSection(String profession, String suffix) {
        FileConfiguration cfg = plugin.cfg().get();
        for (String key : professionConfigKeys(profession)) {
            ConfigurationSection sec = cfg.getConfigurationSection("profession." + key + "." + suffix);
            if (sec != null) return sec;
        }
        String normalized = normalizeProfessionKey(profession);
        return cfg.getConfigurationSection("profession." + (normalized == null ? "rudy" : normalized) + "." + suffix);
    }

    private String resolveCategoryId(String rawId) {
        if (rawId == null) return null;
        String id = rawId.trim();
        if (id.isEmpty()) return id;

        if (categories.containsKey(id)) return id;
        for (String key : categories.keySet()) {
            if (key.equalsIgnoreCase(id)) return key;
        }

        String normalized = id.toLowerCase(Locale.ROOT);
        String alias = normalizeProfessionKey(normalized);
        if (alias == null) alias = normalized;

        if (categories.containsKey(alias)) return alias;
        for (String key : categories.keySet()) {
            if (key.equalsIgnoreCase(alias)) return key;
        }
        return id;
    }

    // ---------------------------------------------------------------------
    // Build templates from config
    // ---------------------------------------------------------------------
    private void buildTemplates() {
        FileConfiguration cfg = plugin.cfg().get();
        ConfigurationSection root = cfg.getConfigurationSection("inv_setting");
        if (root == null) return;

        // MAIN MENU TEMPLATE
        ConfigurationSection mainSec = root.getConfigurationSection("main_inv");
        int size = mainSec != null ? mainSec.getInt("size", 54) : 54;
        String rawTitle = mainSec != null ? mainSec.getString("name", "&8Категории товаров") : "&8Категории товаров";
        mainTitle = ChatColor.translateAlternateColorCodes('&', rawTitle);
        mainTemplate = Bukkit.createInventory(new TagHolder("main"), size, mainTitle);

        // reset command maps for main menu buttons
        mainLeftCmd.clear();
        mainRightCmd.clear();

        // decorate_main_inv
        ConfigurationSection decoMain = root.getConfigurationSection("decorate_main_inv");
        if (decoMain != null) {
            for (String key : decoMain.getKeys(false)) {
                Material mat = Material.matchMaterial(key);
                if (mat == null) continue;
                ConfigurationSection sec = decoMain.getConfigurationSection(key);
                if (sec == null) continue;
                List<Integer> slots = sec.getIntegerList("slots");
                String name = sec.getString("name", "");
                List<String> lore = sec.getStringList("lore");
                String leftCmd = sec.getString("left_click_cmd", null);
                String rightCmd = sec.getString("right_click_cmd", null);
                ItemStack decoItem = createSimpleItem(mat, name, lore);
                for (int slot : slots) {
                    if (slot >= 0 && slot < mainTemplate.getSize()) {
                        mainTemplate.setItem(slot, decoItem);
                        if (leftCmd != null && !leftCmd.isEmpty()) {
                            mainLeftCmd.put(slot, leftCmd);
                        }
                        if (rightCmd != null && !rightCmd.isEmpty()) {
                            mainRightCmd.put(slot, rightCmd);
                        }
                    }
                }
            }
        }

        // categories
        ConfigurationSection catsSec = root.getConfigurationSection("categories");
        if (catsSec != null) {
            for (String id : catsSec.getKeys(false)) {
                ConfigurationSection cs = catsSec.getConfigurationSection(id);
                if (cs == null) continue;

                int slot = cs.getInt("slot", cs.getInt("slot_categoria", 0));
                String title = cs.getString("name", cs.getString("name_item", id));
                String iconName = cs.getString("icon", cs.getString("display_item", "CHEST"));
                Material icon = Material.matchMaterial(iconName == null ? "CHEST" : iconName);
                List<String> lore = cs.getStringList("lore");
                if (lore == null || lore.isEmpty()) {
                    lore = cs.getStringList("lore_display_item");
                }

                ItemStack catItem = createSimpleItem(icon, title, lore);

                if (slot >= 0 && slot < mainTemplate.getSize()) {
                    mainTemplate.setItem(slot, catItem);
                }

                CategoryTemplate ct = new CategoryTemplate(id, cs);
                categories.put(id, ct);
            }
        }

        plugin.getLogger().info("[GUI] templates loaded. categories=" + categories.size());
    }

    private ItemStack createSimpleItem(Material mat, String name, List<String> lore) {
        if (mat == null) mat = Material.CHEST;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            if (name != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            }
            if (lore != null && !lore.isEmpty()) {
                List<String> coloredLore = new ArrayList<>();
                for (String raw : lore) {
                    coloredLore.add(ChatColor.translateAlternateColorCodes('&', raw));
                }
                meta.setLore(coloredLore);
            }
            meta.addItemFlags(ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    // Build MAIN inventory for a specific player (apply PlaceholderAPI etc)
    private Inventory buildMainFor(Player p) {
        Inventory inv = Bukkit.createInventory(new TagHolder("main"), mainTemplate.getSize(), mainTitle);
        for (int slot = 0; slot < mainTemplate.getSize(); slot++) {
            ItemStack base = mainTemplate.getItem(slot);
            if (base == null) continue;
            inv.setItem(slot, applyBasicPlaceholders(p, base));
        }
        return inv;
    }

    private ItemStack applyBasicPlaceholders(Player p, ItemStack template) {
        ItemStack out = new ItemStack(template.getType(), template.getAmount());
        ItemMeta srcMeta = template.getItemMeta();
        if (srcMeta == null) return template.clone();

        ItemMeta m2 = out.getItemMeta();
        if (m2 == null) return template.clone();

        if (srcMeta.hasDisplayName()) {
            String nm = replaceInternalPlaceholders(p, srcMeta.getDisplayName());
            nm = Text.papi(plugin, p, nm);
            nm = Text.color(nm);
            m2.setDisplayName(nm);
        }
        if (srcMeta.hasLore()) {
            List<String> newLore = new ArrayList<>();
            for (String raw : srcMeta.getLore()) {
                String ln = replaceInternalPlaceholders(p, raw);
                ln = Text.papi(plugin, p, ln);
                ln = Text.color(ln);
                newLore.add(ln);
            }
            m2.setLore(newLore);
        }
        m2.addItemFlags(ItemFlag.values());
        out.setItemMeta(m2);
        return out;
    }

    /**
     * Internal placeholders (not PlaceholderAPI).
     * - %rudyrewards1%: "" or "&fВЫДАНА" (reward for level II)
     * - %rudyrewards2%: "" or "&fВЫДАНА" (reward for level III)
     * - %rudyrewards3%: "" or "&fВЫДАНА" (reward for level IV)
     */
    private String replaceInternalPlaceholders(Player p, String s) {
        if (s == null || s.isEmpty() || p == null) return s;
        String out = s;
        try {
            java.util.UUID u = p.getUniqueId();
            out = out.replace("%rudyrewards1%", plugin.data().isRudyRewardClaimed(u, 2) ? "&fВЫДАНА" : "");
            out = out.replace("%rudyrewards2%", plugin.data().isRudyRewardClaimed(u, 3) ? "&fВЫДАНА" : "");
            out = out.replace("%rudyrewards3%", plugin.data().isRudyRewardClaimed(u, 4) ? "&fВЫДАНА" : "");

            out = out.replace("%fermerrewards1%", plugin.data().isFermerRewardClaimed(u, 2) ? "&fВЫДАНА" : "");
            out = out.replace("%fermerrewards2%", plugin.data().isFermerRewardClaimed(u, 3) ? "&fВЫДАНА" : "");
            out = out.replace("%fermerrewards3%", plugin.data().isFermerRewardClaimed(u, 4) ? "&fВЫДАНА" : "");
            out = out.replace("%killerrewards1%", plugin.data().isKillerRewardClaimed(u, 2) ? "&fВЫДАНА" : "");
            out = out.replace("%killerrewards2%", plugin.data().isKillerRewardClaimed(u, 3) ? "&fВЫДАНА" : "");
            out = out.replace("%killerrewards3%", plugin.data().isKillerRewardClaimed(u, 4) ? "&fВЫДАНА" : "");
            out = out.replace("%fishmanrewards1%", plugin.data().isFishRewardClaimed(u, 2) ? "&fВЫДАНА" : "");
            out = out.replace("%fishmanrewards2%", plugin.data().isFishRewardClaimed(u, 3) ? "&fВЫДАНА" : "");
            out = out.replace("%fishmanrewards3%", plugin.data().isFishRewardClaimed(u, 4) ? "&fВЫДАНА" : "");
            out = out.replace("%fisherrewards1%", plugin.data().isFishRewardClaimed(u, 2) ? "&fВЫДАНА" : "");
            out = out.replace("%fisherrewards2%", plugin.data().isFishRewardClaimed(u, 3) ? "&fВЫДАНА" : "");
            out = out.replace("%fisherrewards3%", plugin.data().isFishRewardClaimed(u, 4) ? "&fВЫДАНА" : "");
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Reward commands are configured in config.yml:
     * profession:
     *   rudy:
     *     rewards:
     *       2:
     *         cmd:
     *           - 'give %player% iron_ingot 1'
     *       3:
     *         cmd:
     *           - 'give %player% iron_ingot 2'
     *       4:
     *         cmd:
     *           - 'give %player% iron_ingot 3'
     */
    private List<String> getProfessionRewardCommands(String profKey, int level) {
        if (profKey == null) return Collections.emptyList();
        try {
            ConfigurationSection sec = getProfessionSection(profKey, "rewards." + level);
            if (sec == null) return Collections.emptyList();
            List<String> list = sec.getStringList("cmd");
            if (list != null && !list.isEmpty()) return list;
            String single = sec.getString("cmd", "");
            if (single != null && !single.isEmpty()) return Collections.singletonList(single);
        } catch (Throwable ignored) {
        }
        return Collections.emptyList();
    }

    private List<String> getRudyRewardCommands(int level) {
        return getProfessionRewardCommands("rudy", level);
    }

    private List<String> getFermerRewardCommands(int level) { return getProfessionRewardCommands("fermer", level); }
    private List<String> getKillerRewardCommands(int level) { return getProfessionRewardCommands("killer", level); }
    private List<String> getFishRewardCommands(int level) { return getProfessionRewardCommands("fishman", level); }

    // ---------------------------------------------------------------------
    // Click handling
    // ---------------------------------------------------------------------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();

        Inventory top = e.getView().getTopInventory();
        if (top == null) return;
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof TagHolder)) return;

        // prevent grabbing items from GUI
        e.setCancelled(true);

        TagHolder th = (TagHolder) holder;
        String tag = th.tag;

        // MAIN GUI CLICK
        if ("main".equalsIgnoreCase(tag)) {
            int slot = e.getRawSlot();

            boolean leftClick = e.isLeftClick();
            boolean rightClick = e.isRightClick();

            // decorative button commands (like back arrow, etc.)
            String cmdToRun = null;
            if (leftClick && mainLeftCmd.containsKey(slot)) {
                cmdToRun = mainLeftCmd.get(slot);
            } else if (rightClick && mainRightCmd.containsKey(slot)) {
                cmdToRun = mainRightCmd.get(slot);
            }

            if (cmdToRun != null && !cmdToRun.isEmpty()) {
                p.closeInventory();
                p.performCommand(cmdToRun);
                return;
            }

            for (CategoryTemplate ct : categories.values()) {
                if (ct.mainSlot == slot) {
                    p.openInventory(ct.buildFor(p));
                    return;
                }
            }
            return;
        }
        // CATEGORY GUI CLICK
        if (tag.startsWith("cat:")) {
            String catId = tag.substring("cat:".length());
            CategoryTemplate ct = categories.get(catId);
            if (ct == null) return;

            int rawSlot = e.getRawSlot();
            if (rawSlot < 0 || rawSlot >= top.getSize()) return;

            boolean leftClick = e.isLeftClick();
            boolean rightClick = e.isRightClick();

            // check decorative buttons in category (e.g. back arrow)
            String cmdToRun2 = null;
            if (leftClick && ct.leftCmds.containsKey(rawSlot)) {
                cmdToRun2 = ct.leftCmds.get(rawSlot);
            } else if (rightClick && ct.rightCmds.containsKey(rawSlot)) {
                cmdToRun2 = ct.rightCmds.get(rawSlot);
            }

            if (cmdToRun2 != null && !cmdToRun2.isEmpty()) {
                p.closeInventory();
                p.performCommand(cmdToRun2);
                return;
            }

            // Reward buttons (profession rewards) live inside profession category GUIs.
            // They are decorative items, not sell-items, so handle them before the sell logic.
            if ((ct.professionKey != null) && ct.rewardButtons.containsKey(rawSlot)) {
                CategoryTemplate.RewardButton rb = ct.rewardButtons.get(rawSlot);
                if (rb != null) {
                    handleProfessionRewardClick(ct.professionKey, p, ct, rb);
                    // refresh GUI after claim attempt (to update glint/claimed state)
                    p.openInventory(ct.buildFor(p));
                }
                return;
            }

            ItemStack clicked = e.getCurrentItem();
            if (clicked == null) return;
            Material clickedMat = clicked.getType();

            // only handle items that are actually for sale
            if (!ct.prices.containsKey(clickedMat)) {
                return;
            }

            if (ct.professionKey != null) {
                int need = ct.itemLevels.getOrDefault(clickedMat, 1);
                int have = plugin.data().getProfessionLevel(ct.professionKey, p.getUniqueId());
                if (need > have) {
                    String msg = plugin.cfg().get().getString("messages." + ct.professionKey + "_level_required", "&cДля продажи этого предмета нужен уровень профессии &6%need%&c. Ваш уровень: &6%have%");
                    msg = msg.replace("%need%", String.valueOf(need)).replace("%have%", String.valueOf(have));
                    p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                    return;
                }
            }
            boolean shift = e.isShiftClick();
            boolean right = rightClick;
            boolean left = leftClick;
            // SHIFT + RIGHT CLICK => toggle autosell for this material
            if (right && shift) {
                if (ct.professionKey != null) {
                    int lvl = plugin.data().getProfessionLevel(ct.professionKey, p.getUniqueId());
                    if (lvl < 4) {
                        String msg = plugin.cfg().get().getString("messages." + ct.professionKey + "_autosell_requires_level4", "&cАвтоскупка доступна только на уровне профессии &6IV&c.");
                        p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                        try { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f); } catch (Throwable ignored) {}
                        return;
                    }
                }

                boolean cur = plugin.data().isAuto(p.getUniqueId(), clickedMat.name());
                plugin.data().setAuto(p.getUniqueId(), clickedMat.name(), !cur);

                String key = !cur ? "autosell_enabled" : "autosell_disabled";
                String tmpl = plugin.cfg().get().getString("messages." + key,
                        !cur ? "&aАвтопродажа %material% включена."
                             : "&cАвтопродажа %material% отключена.");
                String finalMsg = ChatColor.translateAlternateColorCodes('&',
                        tmpl.replace("%material%", clickedMat.name()));
                p.sendMessage(finalMsg);

                p.openInventory(ct.buildFor(p));
                return;
            }

            // Determine how many to sell
            int amountToSell = 0;
            if (left) {
                amountToSell = 1;
            } else if (right) {
                amountToSell = countInInventory(p, clickedMat);
            }

            if (amountToSell <= 0) return;

            int actuallySold = removeFromInventory(p, clickedMat, amountToSell);
            if (actuallySold <= 0) {
                String noItemsMsg = ChatColor.translateAlternateColorCodes('&',
                        plugin.cfg().get().getString("messages.no_items",
                                "&cУ вас нет достаточно предметов для продажи."));
                p.sendMessage(noItemsMsg);
                return;
            }

            // Calculate price: base * sold * multiplier * battlepassBonus
            double basePrice = ct.prices.get(clickedMat);
            long soldBefore = plugin.data().getSold(p.getUniqueId(), clickedMat.name());
            long totalAfter = soldBefore + actuallySold;

            double multiplier = getCurrentMultiplier(p, clickedMat, totalAfter);
            double finalPrice = basePrice * actuallySold * multiplier;

            if (p.hasPermission("battlepass.premium")) {
                double bonus = plugin.cfg().get().getDouble("bonus_battlepass", 0.0D);
                finalPrice *= (1.0D + (bonus / 100.0D));
            }

            if (plugin.economy() != null) {
                plugin.economy().depositPlayer(p, finalPrice);
            }

            plugin.data().addSold(p.getUniqueId(), clickedMat.name(), actuallySold);

            if (ct.professionKey != null) {
                int itemLvl = ct.itemLevels.getOrDefault(clickedMat, 1);
                handleProfessionProgressAndLevelUp(ct.professionKey, p, itemLvl, actuallySold);
            }

            String soldMsgTmpl = plugin.cfg().get().getString("messages.success_sell",
                    "&7Вы продали &f%amount%&7 &6%material% &7за &2%price% &7монет. Текущий множитель: &3%multiplier%");
            String soldMsg = soldMsgTmpl
                    .replace("%amount%", String.valueOf(actuallySold))
                    .replace("%material%", clickedMat.name())
                    .replace("%price%", formatPrice(finalPrice))
                    .replace("%multiplier%", formatPrice(multiplier));
            soldMsg = ChatColor.translateAlternateColorCodes('&', soldMsg);
            soldMsg = Text.papi(plugin, p, soldMsg);
            p.sendMessage(soldMsg);

            // refresh GUI
            p.openInventory(ct.buildFor(p));
        }
    }

    // ---------------------------------------------------------------------
    // Category template
    // ---------------------------------------------------------------------
    class CategoryTemplate {
        final String id;
        final int size;
        final String title;
        final Map<Integer, ItemStack> decor = new HashMap<>();
        final List<DynamicDecor> dynamicDecor = new ArrayList<>();
        final Map<Integer, RewardButton> rewardButtons = new HashMap<>();
        final Map<Integer, String> leftCmds = new HashMap<>();
        final Map<Integer, String> rightCmds = new HashMap<>();
        final Map<Material, Double> prices = new HashMap<>();
        final Map<Material, Integer> itemSlots = new HashMap<>();
        final Map<Material, Integer> itemLevels = new HashMap<>();
        final List<String> sellLore;
        final int mainSlot;
        final boolean isRudy;
        final boolean isFermer;
        final boolean isKiller;
        final boolean isFish;
        final String professionKey;

        class RewardButton {
            final int requiredLevel;
            final java.util.List<String> yesMsg;
            final java.util.List<String> noMsg;

            RewardButton(int requiredLevel, java.util.List<String> yesMsg, java.util.List<String> noMsg) {
                this.requiredLevel = requiredLevel;
                this.yesMsg = yesMsg;
                this.noMsg = noMsg;
            }
        }

        CategoryTemplate(String id, ConfigurationSection cs) {
            this.id = id;
            String explicitProfession = cs != null ? cs.getString("profession", null) : null;
            this.professionKey = normalizeProfessionKey(explicitProfession != null && !explicitProfession.isBlank() ? explicitProfession : id);
            this.isRudy = "rudy".equalsIgnoreCase(this.professionKey);
            this.isFermer = "fermer".equalsIgnoreCase(this.professionKey);
            this.isKiller = "killer".equalsIgnoreCase(this.professionKey);
            this.isFish = "fishman".equalsIgnoreCase(this.professionKey);
            this.size = cs.getInt("size_inv", 27);
            String rawTitle = cs.getString("name_inv", cs.getString("name_item", id));
            this.title = ChatColor.translateAlternateColorCodes('&', rawTitle == null ? id : rawTitle);
            this.sellLore = cs.getStringList("lore_sell_item");
            this.mainSlot = cs.getInt("slot", cs.getInt("slot_categoria", 0));

            // decorations
            ConfigurationSection deco = cs.getConfigurationSection("decorate_categoria");
            if (deco != null) {
                for (String key : deco.getKeys(false)) {
                    Material mat = Material.matchMaterial(key);
                    ConfigurationSection sec = deco.getConfigurationSection(key);
                    if (sec == null) continue;
                    List<Integer> slots = sec.getIntegerList("slots");
                    String name = sec.getString("name", "");
                    List<String> lore = sec.getStringList("lore");
                    String leftCmd = sec.getString("left_click_cmd", null);
                    String rightCmd = sec.getString("right_click_cmd", null);

                    // Optional reward button config (profession)
                    RewardButton rewardButton = null;
                    if (professionKey != null) {
                        ConfigurationSection rewardsSec = sec.getConfigurationSection("left_click_cmd_rewards");
                        if (rewardsSec == null) rewardsSec = sec.getConfigurationSection("rewards");
                        if (rewardsSec != null) {
                            ConfigurationSection yes = rewardsSec.getConfigurationSection("yes");
                            ConfigurationSection no = rewardsSec.getConfigurationSection("no");
                            java.util.List<String> yesMsg = yes != null ? yes.getStringList("msg") : java.util.Collections.emptyList();
                            java.util.List<String> noMsg = no != null ? no.getStringList("msg") : java.util.Collections.emptyList();
                            java.util.List<String> cmds = readStringListFlexible(yes, "cmd");

                            // required level can be specified, otherwise infer from common slots (25->2, 34->3, 43->4)
                            int req = sec.getInt("required_level", sec.getInt("requiredLevel", 0));
                            if (req <= 0 && slots.size() == 1) {
                                int s0 = slots.get(0);
                                if (s0 == 25) req = 2;
                                if (s0 == 34) req = 3;
                                if (s0 == 43) req = 4;
                            }
                            if (req > 0) {
                                rewardButton = new RewardButton(req, yesMsg, noMsg);
                                // legacy cmdsLegacy are intentionally ignored now (kept for backward compatibility in click handler)
                            }
                        }
                    }

                    // Support dynamic placeholder keys like %rudyplayer2%, %fermerplayer2%, %killerplayer2%,
                    // %fisherplayer2%, %fishmanplayer2% and legacy variants like %killer_level2%.
                    if (mat == null && key != null && key.startsWith("%") && key.endsWith("%") && (professionKey != null)) {
                        int reqLvl = parseDynamicRequiredLevel(key, professionKey);
                        if (reqLvl > 0) {
                            String matName = sec.getString("item", "NETHER_STAR");
                            Material dynMat = Material.matchMaterial(matName);
                            if (dynMat == null) dynMat = Material.NETHER_STAR;
                            ItemStack decoItem = createSimpleItem(dynMat, name, lore);
                            dynamicDecor.add(new DynamicDecor(reqLvl, decoItem, slots));
                            continue;
                        }
                    }

                    if (mat == null) continue;

                    ItemStack decoItem = createSimpleItem(mat, name, lore);
                    for (int s : slots) {
                        if (s >= 0 && s < size) {
                            decor.put(s, decoItem);
                            if (rewardButton != null) {
                                rewardButtons.put(s, rewardButton);
                            }
                            if (leftCmd != null && !leftCmd.isEmpty()) {
                                leftCmds.put(s, leftCmd);
                            }
                            if (rightCmd != null && !rightCmd.isEmpty()) {
                                rightCmds.put(s, rightCmd);
                            }
                        }
                    }
                }
            }
            // legacy "items": MATERIAL: price
            ConfigurationSection itemsOld = cs.getConfigurationSection("items");
            if (itemsOld != null) {
                for (String m : itemsOld.getKeys(false)) {
                    Material mat = Material.matchMaterial(m);
                    if (mat == null) continue;
                    prices.put(mat, itemsOld.getDouble(m, 1.0D));
                }
            }

            // new "seller_items": MATERIAL: {price, slot}
            ConfigurationSection itemsNew = cs.getConfigurationSection("seller_items");
            if (itemsNew != null) {
                for (String m : itemsNew.getKeys(false)) {
                    Material mat = Material.matchMaterial(m);
                    if (mat == null) continue;
                    ConfigurationSection isec = itemsNew.getConfigurationSection(m);
                    if (isec == null) continue;
                    double pr = isec.getDouble("price", 1.0D);
                    int sl = isec.getInt("slot", -1);
                    int ilvl = isec.getInt("levl", isec.getInt("level", 1));
                    prices.put(mat, pr);
                    itemSlots.put(mat, sl);
                    itemLevels.put(mat, ilvl);
                }
            }
        }


    private int parseDynamicRequiredLevel(String key, String professionKey) {
        if (key == null || professionKey == null) return 0;
        String k = key.trim().toLowerCase(java.util.Locale.ROOT);
        String prof = professionKey.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> aliases = new java.util.ArrayList<>();
        aliases.add(prof);
        if ("fishman".equals(prof)) aliases.add("fisher");
        for (String alias : aliases) {
            if (k.equals("%" + alias + "player2%") || k.equals("%" + alias + "_level2%") || k.equals("%" + alias + "level2%")) return 2;
            if (k.equals("%" + alias + "player3%") || k.equals("%" + alias + "_level3%") || k.equals("%" + alias + "level3%")) return 3;
            if (k.equals("%" + alias + "player4%") || k.equals("%" + alias + "_level4%") || k.equals("%" + alias + "level4%")) return 4;
        }
        return 0;
    }

        Inventory buildFor(Player p) {
            // Safety: if player already met the level IV progress requirement earlier,
            // grant the auto-reward on GUI open as well (no clicks required).
            if (professionKey != null && p != null) {
                try {
                    long total = plugin.data().getProfessionTotalSold(professionKey, p.getUniqueId());
                    tryGrantProfessionLevel4AutoReward(professionKey, p, total);
                } catch (Throwable ignored) {}
            }
            Inventory inv = Bukkit.createInventory(new TagHolder("cat:" + id), size, title);

            // place decorations (with PlaceholderAPI + colors)
            for (Map.Entry<Integer, ItemStack> en : decor.entrySet()) {
                int slot = en.getKey();
                ItemStack decoItem = en.getValue();
                if (slot >= 0 && slot < inv.getSize()) {
                    ItemStack it = applyBasicPlaceholders(p, decoItem);
                    // If this slot is a reward button and already claimed, show glint
                    if ((professionKey != null) && rewardButtons.containsKey(slot)) {
                        RewardButton rb = rewardButtons.get(slot);
                        boolean claimed = false;
                        if (rb != null && professionKey != null) {
                            claimed = plugin.data().isProfessionRewardClaimed(professionKey, p.getUniqueId(), rb.requiredLevel);
                        }
                        if (claimed) it = addGlint(it);
                    }
                    inv.setItem(slot, it);
                }
            }

            if (professionKey != null && !dynamicDecor.isEmpty()) {
                int lvl = plugin.data().getProfessionLevel(professionKey, p.getUniqueId());
                for (DynamicDecor dd : dynamicDecor) {
                    if (lvl >= dd.requiredRudyLevel) {
                        for (int slot : dd.slots) {
                            if (slot >= 0 && slot < inv.getSize()) inv.setItem(slot, applyBasicPlaceholders(p, dd.item));
                        }
                    }
                }
            }

            // place sellable items
            int nextIndex = 0;
            for (Map.Entry<Material, Double> en : prices.entrySet()) {
                Material mat = en.getKey();
                double basePrice = en.getValue();

                if (professionKey != null) {
                    int needLvl = itemLevels.getOrDefault(mat, 1);
                    int haveLvl = plugin.data().getProfessionLevel(professionKey, p.getUniqueId());
                    if (needLvl > haveLvl) continue;
                }

                ItemStack sellItem = buildSellItemForPlayer(p, mat, basePrice);

                Integer desired = itemSlots.get(mat);
                if (desired != null && desired >= 0 && desired < inv.getSize() && inv.getItem(desired) == null) {
                    inv.setItem(desired, sellItem);
                } else {
                    // find next free slot
                    while (nextIndex < inv.getSize() && inv.getItem(nextIndex) != null) {
                        nextIndex++;
                    }
                    if (nextIndex < inv.getSize()) {
                        inv.setItem(nextIndex, sellItem);
                        nextIndex++;
                    }
                }
            }

            return inv;
        }

        private ItemStack buildSellItemForPlayer(Player p, Material mat, double basePrice) {
            long soldSoFar = plugin.data().getSold(p.getUniqueId(), mat.name());
            double multiplierNow = getCurrentMultiplier(p, mat, soldSoFar);

            boolean bpActive = p.hasPermission("battlepass.premium");
            double bpPercent = bpActive ? plugin.cfg().get().getDouble("bonus_battlepass", 0.0D) : 0.0D;
            double coeff = 1.0D + (bpPercent / 100.0D);

            int have = countInInventory(p, mat);

            double priceAll = basePrice * have * multiplierNow * coeff;
            double pricePerItemMul = basePrice * multiplierNow;
            double pricePerItemMulBP = pricePerItemMul * coeff;

            // next multiplier info
            NextInfo ni = getNextInfo(p, mat, soldSoFar);

            boolean autoEnabled = plugin.data().isAuto(p.getUniqueId(), mat.name());

            String statusAuto = autoEnabled
                    ? msg("statusautosell.enabled", "&aВКЛ")
                    : msg("statusautosell.disabled", "&cВЫКЛ");

            String bpText = bpActive
                    ? msg("activbattlerpass.yes", "&aДа")
                    : msg("activbattlerpass.no", "&cНет");

            List<String> loreLines = new ArrayList<>();
            for (String raw : sellLore) {
                String line = raw;
                line = line.replace("%price%", formatPrice(basePrice))
                        .replace("%multiplier%", formatPrice(multiplierNow))
                        .replace("%multiplied_price%", formatPrice(pricePerItemMul))
                        .replace("%price_battlepass_multiplied%", formatPrice(pricePerItemMulBP))
                        .replace("%right_click_price%", formatPrice(priceAll))
                        .replace("%mode_autosell%", statusAuto)
                        .replace("%active_battlepass%", bpText)
                        .replace("%current_item%", String.valueOf(soldSoFar))
                        .replace("%required_items%", ni.atMax
                                ? msg("max_text_for_current_and_required", "&aМакс")
                                : String.valueOf(ni.remaining))
                        .replace("%next_multiplier%", ni.atMax
                                ? msg("max_text_for_next_multiplier", "&aМакс")
                                : ("x" + formatPrice(ni.nextMultiplier)));

                line = Text.papi(plugin, p, line);
                line = Text.color(line);
                loreLines.add(line);
            }

            ItemStack it = new ItemStack(mat);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(ChatColor.YELLOW + mat.name());
                meta.setLore(loreLines);
                // Visual indicator: if autosell is enabled, show enchant glint
                if (autoEnabled) {
                    Enchantment glow = resolveGlowEnchant();
                    if (glow != null) {
                        meta.addEnchant(glow, 1, true);
                        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    }
                }
                meta.addItemFlags(ItemFlag.values());
                it.setItemMeta(meta);
            }
            return it;
        }
    }

    // ---------------------------------------------------------------------
    // AUTOSELL LOGIC
    // ---------------------------------------------------------------------
    public void autoSellTickFor(Player pl) {
        try {
            if (pl == null) return;
            if (!pl.isOnline()) return;

            for (CategoryTemplate ct : categories.values()) {
                if (ct.professionKey != null && plugin.data().getProfessionLevel(ct.professionKey, pl.getUniqueId()) < 4) {
                    continue;
                }
                for (Map.Entry<Material, Double> entry : ct.prices.entrySet()) {
                    Material mat = entry.getKey();
                    if (!plugin.data().isAuto(pl.getUniqueId(), mat.name())) continue;

                    if (ct.professionKey != null) {
                        int need = ct.itemLevels.getOrDefault(mat, 1);
                        int have = plugin.data().getProfessionLevel(ct.professionKey, pl.getUniqueId());
                        if (need > have) continue;
                    }

                    int sold = removeAllFromInventory(pl, mat);
                    if (sold <= 0) continue;

                    long soldBefore = plugin.data().getSold(pl.getUniqueId(), mat.name());
                    long totalAfter = soldBefore + sold;

                    double multiplier = getCurrentMultiplier(pl, mat, totalAfter);
                    double price = entry.getValue() * sold * multiplier;

                    if (pl.hasPermission("battlepass.premium")) {
                        double bonus = plugin.cfg().get().getDouble("bonus_battlepass", 0.0D);
                        price *= (1.0D + (bonus / 100.0D));
                    }

                    if (plugin.economy() != null) {
                        plugin.economy().depositPlayer(pl, price);
                    }

                    plugin.data().addSold(pl.getUniqueId(), mat.name(), sold);

                    if (ct.professionKey != null) {
                        int itemLvl = ct.itemLevels.getOrDefault(mat, 1);
                        handleProfessionProgressAndLevelUp(ct.professionKey, pl, itemLvl, sold);
                    }

                    pl.sendMessage("§7[Автопродажа] §a+" + formatPrice(price)
                            + " §7за §ex" + sold + " " + mat.name());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[AutoSell] " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // RUDY profession logic
    // ---------------------------------------------------------------------
    private void handleRudyProgressAndLevelUp(Player p, int itemLevel, int soldAmount) {
        if (p == null || soldAmount <= 0) return;

        // store progress per item level (1..4)
        if (itemLevel < 1) itemLevel = 1;
        if (itemLevel > 4) itemLevel = 4;

        // keep legacy per-tier stats
        plugin.data().rememberPlayerName(p.getUniqueId(), p.getName());
        plugin.data().addRudyProgress(p.getUniqueId(), itemLevel, soldAmount);

        // cumulative progress for profession leveling and UI
        long total = plugin.data().addRudyTotalSold(p.getUniqueId(), soldAmount);

        // check level-ups based on cumulative total sold in the RUDY category
        int curLevel = plugin.data().getRudyLevel(p.getUniqueId());
        if (curLevel == 1) {
            long need = plugin.cfg().get().getLong("profession.rudy.to_level.2", 5000L);
            if (total >= need) levelUpRudy(p, 2);
        } else if (curLevel == 2) {
            long need = plugin.cfg().get().getLong("profession.rudy.to_level.3", 10000L);
            if (total >= need) levelUpRudy(p, 3);
        } else if (curLevel == 3) {
            long need = plugin.cfg().get().getLong("profession.rudy.to_level.4", 15000L);
            if (total >= need) levelUpRudy(p, 4);
        }

        // Optional: one-time AUTO reward for reaching configured cumulative progress at level 4.
        // This must be granted WITHOUT clicks.
        tryGrantRudyLevel4AutoReward(p, total);
    }

    private void tryGrantRudyLevel4AutoReward(Player p, long total) {
        if (p == null) return;
        try {
            if (plugin.data().getRudyLevel(p.getUniqueId()) < 4) return;
            if (plugin.data().isRudyMaxRewardClaimed(p.getUniqueId())) return;

            long need = plugin.cfg().get().getLong("profession.rudy.level4_reward.required", 20000L);
            if (total < need) return;

            // Commands are configured in:
            // profession.rudy.level4_reward.commands
            // Backward-compatible fallback:
            // profession.rudy.rewards.4.cmd
            java.util.List<String> cmds = java.util.Collections.emptyList();
            ConfigurationSection sec = plugin.cfg().get().getConfigurationSection("profession.rudy.level4_reward");
            if (sec != null) {
                cmds = readStringListFlexible(sec, "commands");
            }
            if (cmds == null || cmds.isEmpty()) {
                cmds = getRudyRewardCommands(4);
            }

            // If commands are configured - execute them safely.
            // If not configured at all - still mark as claimed and just send the message.
            if (cmds != null && !cmds.isEmpty()) {
                if (!executeRewardActions(p, cmds)) {
                    String m = plugin.cfg().get().getString(
                            "messages.rudy_reward_command_failed",
                            "&cНе удалось выдать награду. Проверьте настройки награды и попробуйте снова."
                    );
                    p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
                    return;
                }
            }

            // Mark as claimed only after successful execution.
            plugin.data().setRudyMaxRewardClaimed(p.getUniqueId(), true);
            // Also mark the level IV reward button as claimed (so GUI shows it as received).
            plugin.data().setRudyRewardClaimed(p.getUniqueId(), 4, true);

            // Send configured messages/broadcast (if present)
            sendRudyMessagesOnly(p, "profession.rudy.level4_reward");

        } catch (Throwable ignored) {
        }
    }

    private void levelUpRudy(Player p, int newLevel) {
        int current = plugin.data().getRudyLevel(p.getUniqueId());
        if (newLevel <= current) return;
        plugin.data().setRudyLevel(p.getUniqueId(), newLevel);

        playRudyLevelUpSound(p);

        String path = "profession.rudy.level_up." + newLevel;
        sendRudyMessageAndCommands(p, path);

        // If the player currently has the RUDY menu open, refresh it immediately so new items/icons appear.
        refreshOpenRudyMenu(p);
    }

    private void refreshOpenRudyMenu(Player p) {
        if (p == null) return;
        try {
            Inventory top = p.getOpenInventory().getTopInventory();
            InventoryHolder holder = top == null ? null : top.getHolder();
            if (holder instanceof TagHolder th) {
                if (th.tag != null && th.tag.equalsIgnoreCase("cat:RUDY")) {
                    CategoryTemplate ct = categories.get("RUDY");
                    if (ct != null) {
                        p.openInventory(ct.buildFor(p));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void playRudyLevelUpSound(Player p) {
        if (p == null) return;
        try {
            String name = plugin.cfg().get().getString(
                    "profession.rudy.level_up_sound.sound",
                    "UI_TOAST_CHALLENGE_COMPLETE" // "quest completed" style sound
            );
            float volume = (float) plugin.cfg().get().getDouble("profession.rudy.level_up_sound.volume", 1.0D);
            float pitch = (float) plugin.cfg().get().getDouble("profession.rudy.level_up_sound.pitch", 1.0D);

            Sound s;
            try {
                s = Sound.valueOf(name);
            } catch (Exception ignored) {
                s = Sound.UI_TOAST_CHALLENGE_COMPLETE;
            }
            p.playSound(p.getLocation(), s, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    private void sendRudyMessageAndCommands(Player p, String basePath) {
        // player message (backward compatible with old key: .message)
        String msg = plugin.cfg().get().getString(basePath + ".player_message", null);
        if (msg == null) msg = plugin.cfg().get().getString(basePath + ".message", "");
        if (msg != null && !msg.isEmpty()) {
            msg = msg.replace("%player%", p.getName());
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }

        // optional broadcast message (NOT /say). This is a normal colored broadcast line.
        boolean broadcast = plugin.cfg().get().getBoolean(basePath + ".broadcast", false);
        if (broadcast) {
            String bmsg = plugin.cfg().get().getString(basePath + ".broadcast_message", "");
            if (bmsg != null && !bmsg.isEmpty()) {
                bmsg = bmsg.replace("%player%", p.getName());
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', bmsg));
            }
        }

        // IMPORTANT: Level-up rewards must NOT be executed automatically.
        // Any rewards (commands/items) should be granted ONLY after clicking the
        // reward slots in the RUDY GUI (25/34/43).
        // Therefore, for profession level-up paths we ignore the `.commands` section.
        if (basePath != null && basePath.startsWith("profession.rudy.level_up.")) {
            return;
        }

        // commands (console)
        List<String> cmds = plugin.cfg().get().getStringList(basePath + ".commands");
        if (cmds != null) {
            for (String c : cmds) {
                if (c == null || c.trim().isEmpty()) continue;
                String cmd = c.replace("%player%", p.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }
    }

    private void sendRudyMessagesOnly(Player p, String basePath) {
        if (p == null || basePath == null) return;
        try {
            String msg = plugin.cfg().get().getString(basePath + ".player_message", null);
            if (msg == null) msg = plugin.cfg().get().getString(basePath + ".message", "");
            if (msg != null && !msg.isEmpty()) {
                msg = msg.replace("%player%", p.getName());
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            }

            boolean broadcast = plugin.cfg().get().getBoolean(basePath + ".broadcast", false);
            if (broadcast) {
                String bmsg = plugin.cfg().get().getString(basePath + ".broadcast_message", "");
                if (bmsg != null && !bmsg.isEmpty()) {
                    bmsg = bmsg.replace("%player%", p.getName());
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', bmsg));
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // RUDY reward buttons (GUI)
    // ---------------------------------------------------------------------
    private void handleRudyRewardClick(Player p, CategoryTemplate ct, CategoryTemplate.RewardButton rb) {
        if (p == null || ct == null || rb == null) return;

        int have = plugin.data().getRudyLevel(p.getUniqueId());
        int need = rb.requiredLevel;

        // Not enough level
        if (have < need) {
            sendLines(p, rb.noMsg);
            playVillagerNo(p);
            return;
        }

        // Already claimed
        if (plugin.data().isRudyRewardClaimed(p.getUniqueId(), need)) {
            String m = plugin.cfg().get().getString(
                    "messages.rudy_reward_already_claimed",
                    "&eВы уже получали награду за этот уровень."
            );
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }

        // Inventory space check
        if (p.getInventory().firstEmpty() == -1) {
            String m = plugin.cfg().get().getString(
                    "messages.no_inventory_space",
                    "&cНедостаточно места в инвентаре. Освободите место и попробуйте снова."
            );
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }

        // Success path: execute reward actions.
        // IMPORTANT: do NOT mark as claimed unless ALL actions were successfully executed.
        if (!executeRewardActions(p, getRudyRewardCommands(rb.requiredLevel))) {
            String m = plugin.cfg().get().getString(
                    "messages.rudy_reward_command_failed",
                    "&cНе удалось выдать награду. Проверьте настройки награды и попробуйте снова."
            );
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }

        sendLines(p, rb.yesMsg);
        plugin.data().setRudyRewardClaimed(p.getUniqueId(), need, true);

        // Refresh GUI so the glint and %rudyrewardsX% placeholders update immediately.
        try {
            p.openInventory(ct.buildFor(p));
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------------
    // FERMER reward buttons (GUI)
    // ---------------------------------------------------------------------
    private void handleFermerRewardClick(Player p, CategoryTemplate ct, CategoryTemplate.RewardButton rb) {
        if (p == null || ct == null || rb == null) return;

        int have = plugin.data().getFermerLevel(p.getUniqueId());
        int need = rb.requiredLevel;

        if (have < need) {
            sendLines(p, rb.noMsg);
            playVillagerNo(p);
            return;
        }

        if (plugin.data().isFermerRewardClaimed(p.getUniqueId(), need)) {
            String m = plugin.cfg().get().getString(
                    "messages.fermer_reward_already_claimed",
                    "&eВы уже получали награду за этот уровень."
            );
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }

        if (p.getInventory().firstEmpty() == -1) {
            String m = plugin.cfg().get().getString(
                    "messages.no_inventory_space",
                    "&cНедостаточно места в инвентаре. Освободите место и попробуйте снова."
            );
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }

        if (!executeRewardActions(p, getFermerRewardCommands(rb.requiredLevel))) {
            String m = plugin.cfg().get().getString(
                    "messages.fermer_reward_command_failed",
                    "&cНе удалось выдать награду. Проверьте настройки награды и попробуйте снова."
            );
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }

        sendLines(p, rb.yesMsg);
        plugin.data().setFermerRewardClaimed(p.getUniqueId(), need, true);

        try {
            p.openInventory(ct.buildFor(p));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Executes reward commands. Supports safe API-based item giving for commands of the form:
     *   give %player% <material> <amount>
     * All other commands are executed from console.
     *
     * @return true if all actions succeeded.
     */
    
    /**
     * Reads a config value that may be either a YAML list or a single string.
     * Bukkit's getStringList returns empty if the value is a plain string, so we handle both.
     */
    private java.util.List<String> readStringListFlexible(ConfigurationSection sec, String key) {
        if (sec == null || key == null) return java.util.Collections.emptyList();
        java.util.List<String> list = sec.getStringList(key);
        if (list != null && !list.isEmpty()) return list;

        Object raw = sec.get(key);
        if (raw instanceof String s) {
            String v = s.trim();
            return v.isEmpty() ? java.util.Collections.emptyList() : java.util.Collections.singletonList(v);
        }
        if (raw instanceof java.util.List<?> l) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (Object o : l) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return java.util.Collections.emptyList();
    }

private boolean executeRewardActions(Player p, java.util.List<String> cmds) {
        if (p == null) return false;
        if (cmds == null || cmds.isEmpty()) {
            // Misconfigured reward button (no commands).
            String msg = plugin.cfg().get().getString("messages.reward_config_error", null);
            if (msg == null || msg.isEmpty()) {
                msg = plugin.cfg().get().getString(
                        "messages.rudy_reward_config_error",
                        "&cНаграда настроена неверно. Обратитесь к администрации."
                );
            }
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            return false;
        }

        for (String raw : cmds) {
            if (raw == null) continue;
            String c = raw.trim();
            if (c.isEmpty()) continue;

            // Replace placeholder
            c = c.replace("%player%", p.getName());

            // Some admins write commands with a leading '/'
            if (c.startsWith("/")) c = c.substring(1);

            // Try to parse a give command and give via Bukkit API to guarantee delivery.
            // Example: give PlayerName iron_ingot 1
            String lc = c.toLowerCase(java.util.Locale.ROOT);
            if (lc.startsWith("give ") || lc.startsWith("minecraft:give ")) {
                String[] parts = c.split("\\s+");
                // Accept both "give <target> <item> [count]" and "give <item> [count]".
                if (parts.length >= 2) {
                    String target;
                    String matToken;
                    String amtToken;
                    if (parts.length >= 4) {
                        target = parts[1];
                        matToken = parts[2];
                        amtToken = parts[3];
                    } else if (parts.length >= 3) {
                        target = p.getName();
                        matToken = parts[1];
                        amtToken = parts[2];
                    } else {
                        target = null;
                        matToken = null;
                        amtToken = null;
                    }

                    if (target != null && target.equalsIgnoreCase(p.getName())) {
                        int amount;
                        try {
                            amount = Integer.parseInt(amtToken);
                        } catch (NumberFormatException ex) {
                            return false;
                        }
                        if (amount <= 0) return false;

                        // Strip namespace for items like minecraft:iron_ingot
                        if (matToken != null && matToken.toLowerCase(java.util.Locale.ROOT).startsWith("minecraft:")) {
                            matToken = matToken.substring("minecraft:".length());
                        }

                        org.bukkit.Material mat = org.bukkit.Material.matchMaterial(matToken);
                        if (mat == null) {
                            // support minecraft:iron_ingot
                            if (matToken.contains(":")) {
                                matToken = matToken.substring(matToken.indexOf(':') + 1);
                            }
                            mat = org.bukkit.Material.matchMaterial(matToken.toUpperCase(java.util.Locale.ROOT));
                        }
                        if (mat == null) return false;

                        // Ensure inventory has enough capacity for the whole amount (no partial give).
                        if (!hasCapacityFor(p, mat, amount)) {
                            String m = plugin.cfg().get().getString(
                                    "messages.no_inventory_space",
                                    "&cНедостаточно места в инвентаре. Освободите место и попробуйте снова."
                            );
                            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
                            return false;
                        }

                        int left = amount;
                        int max = mat.getMaxStackSize();
                        while (left > 0) {
                            int giveNow = Math.min(left, max);
                            org.bukkit.inventory.ItemStack it = new org.bukkit.inventory.ItemStack(mat, giveNow);
                            java.util.Map<Integer, org.bukkit.inventory.ItemStack> leftover = p.getInventory().addItem(it);
                            if (leftover != null && !leftover.isEmpty()) {
                                return false;
                            }
                            left -= giveNow;
                        }
                        p.updateInventory();
                        continue; // handled
                    }
                }
            }

            // Fallback: run from console
            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) return false;
        }
        return true;
    }

    /**
     * Calculates if a player's inventory can fit the specified amount of a material.
     */
    private boolean hasCapacityFor(Player p, org.bukkit.Material mat, int amount) {
        if (p == null) return false;
        if (amount <= 0) return true;
        int capacity = 0;
        int max = mat.getMaxStackSize();
        org.bukkit.inventory.PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            org.bukkit.inventory.ItemStack it = inv.getItem(i);
            if (it == null || it.getType() == org.bukkit.Material.AIR) {
                capacity += max;
            } else if (it.getType() == mat && it.getAmount() < max) {
                capacity += (max - it.getAmount());
            }
            if (capacity >= amount) return true;
        }
        return capacity >= amount;
    }

    private void sendLines(Player p, java.util.List<String> lines) {
        if (p == null || lines == null) return;
        for (String l : lines) {
            if (l == null) continue;
            l = l.replace("%player%", p.getName());
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', l));
        }
    }

    private Enchantment resolveGlowEnchant() {
        Enchantment ench = Enchantment.getByName("LUCK");
        if (ench == null) ench = Enchantment.getByName("LOOT_BONUS_BLOCKS");
        if (ench == null) ench = Enchantment.getByName("DURABILITY");
        if (ench == null) ench = Enchantment.getByName("UNBREAKING");
        if (ench == null) ench = Enchantment.values().length > 0 ? Enchantment.values()[0] : null;
        return ench;
    }

    private void playVillagerNo(Player p) {
        try {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
        } catch (Throwable ignored) {
        }
    }

    private ItemStack addGlint(ItemStack base) {
        if (base == null) return null;
        ItemStack it = base.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            try {
                Enchantment glow = resolveGlowEnchant();
                if (glow != null) {
                    meta.addEnchant(glow, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
            } catch (Throwable ignored) {
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    // ---------------------------------------------------------------------
    // ---------------------------------------------------------------------
    // FERMER profession logic (same as RUDY, separate config/data paths)
    // ---------------------------------------------------------------------
    private void handleFermerProgressAndLevelUp(Player p, int itemLevel, int soldAmount) {
        if (p == null || soldAmount <= 0) return;

        if (itemLevel < 1) itemLevel = 1;
        if (itemLevel > 4) itemLevel = 4;

        plugin.data().rememberPlayerName(p.getUniqueId(), p.getName());
        plugin.data().addFermerProgress(p.getUniqueId(), itemLevel, soldAmount);
        long total = plugin.data().addFermerTotalSold(p.getUniqueId(), soldAmount);

        int curLevel = plugin.data().getFermerLevel(p.getUniqueId());
        if (curLevel == 1) {
            long need = plugin.cfg().get().getLong("profession.fermer.to_level.2", 5000L);
            if (total >= need) levelUpFermer(p, 2);
        } else if (curLevel == 2) {
            long need = plugin.cfg().get().getLong("profession.fermer.to_level.3", 10000L);
            if (total >= need) levelUpFermer(p, 3);
        } else if (curLevel == 3) {
            long need = plugin.cfg().get().getLong("profession.fermer.to_level.4", 15000L);
            if (total >= need) levelUpFermer(p, 4);
        }

        tryGrantFermerLevel4AutoReward(p, total);
    }

    private void tryGrantFermerLevel4AutoReward(Player p, long total) {
        if (p == null) return;
        try {
            if (plugin.data().getFermerLevel(p.getUniqueId()) < 4) return;
            if (plugin.data().isFermerMaxRewardClaimed(p.getUniqueId())) return;

            long need = plugin.cfg().get().getLong("profession.fermer.level4_reward.required", 20000L);
            if (total < need) return;

            java.util.List<String> cmds = java.util.Collections.emptyList();
            ConfigurationSection sec = plugin.cfg().get().getConfigurationSection("profession.fermer.level4_reward");
            if (sec != null) {
                cmds = readStringListFlexible(sec, "commands");
            }
            if (cmds == null || cmds.isEmpty()) {
                cmds = getFermerRewardCommands(4);
            }

            if (cmds != null && !cmds.isEmpty()) {
                if (!executeRewardActions(p, cmds)) {
                    String m = plugin.cfg().get().getString(
                            "messages.fermer_reward_command_failed",
                            "&cНе удалось выдать награду. Проверьте настройки награды и попробуйте снова."
                    );
                    p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
                    return;
                }
            }

            plugin.data().setFermerMaxRewardClaimed(p.getUniqueId(), true);
            plugin.data().setFermerRewardClaimed(p.getUniqueId(), 4, true);
            sendFermerMessagesOnly(p, "profession.fermer.level4_reward");
        } catch (Throwable ignored) {
        }
    }

    private void levelUpFermer(Player p, int newLevel) {
        int current = plugin.data().getFermerLevel(p.getUniqueId());
        if (newLevel <= current) return;
        plugin.data().setFermerLevel(p.getUniqueId(), newLevel);

        playFermerLevelUpSound(p);
        String path = "profession.fermer.level_up." + newLevel;
        sendFermerMessageAndCommands(p, path);
        refreshOpenFermerMenu(p);
    }

    private void refreshOpenFermerMenu(Player p) {
        if (p == null) return;
        try {
            Inventory top = p.getOpenInventory().getTopInventory();
            InventoryHolder holder = top == null ? null : top.getHolder();
            if (holder instanceof TagHolder th) {
                if (th.tag != null && th.tag.equalsIgnoreCase("cat:fermer")) {
                    CategoryTemplate ct = categories.get("fermer");
                    if (ct != null) {
                        p.openInventory(ct.buildFor(p));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void playFermerLevelUpSound(Player p) {
        if (p == null) return;
        try {
            String name = plugin.cfg().get().getString(
                    "profession.fermer.level_up_sound.sound",
                    "UI_TOAST_CHALLENGE_COMPLETE"
            );
            float volume = (float) plugin.cfg().get().getDouble("profession.fermer.level_up_sound.volume", 1.0D);
            float pitch = (float) plugin.cfg().get().getDouble("profession.fermer.level_up_sound.pitch", 1.0D);

            Sound s;
            try {
                s = Sound.valueOf(name);
            } catch (Exception ignored) {
                s = Sound.UI_TOAST_CHALLENGE_COMPLETE;
            }
            p.playSound(p.getLocation(), s, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    private void sendFermerMessageAndCommands(Player p, String basePath) {
        String msg = plugin.cfg().get().getString(basePath + ".player_message", null);
        if (msg == null) msg = plugin.cfg().get().getString(basePath + ".message", "");
        if (msg != null && !msg.isEmpty()) {
            msg = msg.replace("%player%", p.getName());
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }

        boolean broadcast = plugin.cfg().get().getBoolean(basePath + ".broadcast", false);
        if (broadcast) {
            String bmsg = plugin.cfg().get().getString(basePath + ".broadcast_message", "");
            if (bmsg != null && !bmsg.isEmpty()) {
                bmsg = bmsg.replace("%player%", p.getName());
                Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', bmsg));
            }
        }

        if (basePath != null && basePath.startsWith("profession.fermer.level_up.")) {
            return;
        }

        List<String> cmds = plugin.cfg().get().getStringList(basePath + ".commands");
        if (cmds != null) {
            for (String c : cmds) {
                if (c == null || c.trim().isEmpty()) continue;
                String cmd = c.replace("%player%", p.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
        }
    }

    private void sendFermerMessagesOnly(Player p, String basePath) {
        if (p == null || basePath == null) return;
        try {
            String msg = plugin.cfg().get().getString(basePath + ".player_message", null);
            if (msg == null) msg = plugin.cfg().get().getString(basePath + ".message", "");
            if (msg != null && !msg.isEmpty()) {
                msg = msg.replace("%player%", p.getName());
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
            }

            boolean broadcast = plugin.cfg().get().getBoolean(basePath + ".broadcast", false);
            if (broadcast) {
                String bmsg = plugin.cfg().get().getString(basePath + ".broadcast_message", "");
                if (bmsg != null && !bmsg.isEmpty()) {
                    bmsg = bmsg.replace("%player%", p.getName());
                    Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', bmsg));
                }
            }
        } catch (Throwable ignored) {
        }
    }



    private void handleProfessionProgressAndLevelUp(String profession, Player p, int itemLevel, int soldAmount) {
        if (p == null || soldAmount <= 0 || profession == null) return;
        // Killer progression is based on entity/mob kills, not on selling mob drops.
        if ("killer".equalsIgnoreCase(profession)) return;
        if (itemLevel < 1) itemLevel = 1;
        if (itemLevel > 4) itemLevel = 4;
        plugin.data().rememberPlayerName(p.getUniqueId(), p.getName());
        plugin.data().addProfessionProgress(profession, p.getUniqueId(), itemLevel, soldAmount);
        long total = plugin.data().addProfessionTotalSold(profession, p.getUniqueId(), soldAmount);
        int curLevel = plugin.data().getProfessionLevel(profession, p.getUniqueId());
        if (curLevel == 1) {
            long need = getProfessionLong(profession, "to_level.2", 5000L);
            if (total >= need) levelUpProfession(profession, p, 2);
        } else if (curLevel == 2) {
            long need = getProfessionLong(profession, "to_level.3", 15000L);
            if (total >= need) levelUpProfession(profession, p, 3);
        } else if (curLevel == 3) {
            long need = getProfessionLong(profession, "to_level.4", 30000L);
            if (total >= need) levelUpProfession(profession, p, 4);
        }
        tryGrantProfessionLevel4AutoReward(profession, p, total);
    }

    private void tryGrantProfessionLevel4AutoReward(String profession, Player p, long total) {
        if (p == null || profession == null) return;
        try {
            if (plugin.data().getProfessionLevel(profession, p.getUniqueId()) < 4) return;
            if (plugin.data().isProfessionMaxRewardClaimed(profession, p.getUniqueId())) return;
            long need = getProfessionLong(profession, "level4_reward.required", 20000L);
            if (total < need) return;
            java.util.List<String> cmds = java.util.Collections.emptyList();
            ConfigurationSection sec = getProfessionSection(profession, "level4_reward");
            if (sec != null) cmds = readStringListFlexible(sec, "commands");
            if (cmds == null || cmds.isEmpty()) cmds = getProfessionRewardCommands(profession, 4);
            if (cmds != null && !cmds.isEmpty() && !executeRewardActions(p, cmds)) {
                String m = plugin.cfg().get().getString("messages." + profession + "_reward_command_failed", "&cНе удалось выдать награду. Проверьте настройки награды и попробуйте снова.");
                p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
                return;
            }
            plugin.data().setProfessionMaxRewardClaimed(profession, p.getUniqueId(), true);
            plugin.data().setProfessionRewardClaimed(profession, p.getUniqueId(), 4, true);
            sendProfessionMessagesOnly(profession, p, resolveProfessionConfigBase(profession) + ".level4_reward");
        } catch (Throwable ignored) {}
    }

    private void levelUpProfession(String profession, Player p, int newLevel) {
        int current = plugin.data().getProfessionLevel(profession, p.getUniqueId());
        if (newLevel <= current) return;
        plugin.data().setProfessionLevel(profession, p.getUniqueId(), newLevel);
        playProfessionLevelUpSound(profession, p);
        sendProfessionMessageAndCommands(profession, p, resolveProfessionConfigBase(profession) + ".level_up." + newLevel);
        refreshOpenProfessionMenu(profession, p);
    }

    private void refreshOpenProfessionMenu(String profession, Player p) {
        if (p == null || profession == null) return;
        try {
            Inventory top = p.getOpenInventory().getTopInventory();
            InventoryHolder holder = top == null ? null : top.getHolder();
            if (holder instanceof TagHolder th && th.tag != null) {
                String wanted = switch (profession) { case "rudy" -> "cat:RUDY"; case "fermer" -> "cat:fermer"; case "killer" -> "cat:KILLER"; case "fishman" -> "cat:FISHMAN"; default -> null; };
                if (wanted != null && th.tag.equalsIgnoreCase(wanted)) {
                    String categoryId = wanted.substring(4);
                    CategoryTemplate ct = categories.get(categoryId);
                    if (ct != null) p.openInventory(ct.buildFor(p));
                }
            }
        } catch (Throwable ignored) {}
    }

    private void playProfessionLevelUpSound(String profession, Player p) {
        if (p == null) return;
        try {
            String name = getProfessionString(profession, "level_up_sound.sound", "UI_TOAST_CHALLENGE_COMPLETE");
            float volume = (float) getProfessionDouble(profession, "level_up_sound.volume", 1.0D);
            float pitch = (float) getProfessionDouble(profession, "level_up_sound.pitch", 1.0D);
            Sound s; try { s = Sound.valueOf(name); } catch (Exception ignored) { s = Sound.UI_TOAST_CHALLENGE_COMPLETE; }
            p.playSound(p.getLocation(), s, volume, pitch);
        } catch (Throwable ignored) {}
    }

    private void sendProfessionMessageAndCommands(String profession, Player p, String basePath) {
        String msg = plugin.cfg().get().getString(basePath + ".player_message", plugin.cfg().get().getString(basePath + ".message", ""));
        if (msg != null && !msg.isEmpty()) p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("%player%", p.getName())));
        if (plugin.cfg().get().getBoolean(basePath + ".broadcast", false)) {
            String bmsg = plugin.cfg().get().getString(basePath + ".broadcast_message", "");
            if (bmsg != null && !bmsg.isEmpty()) Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', bmsg.replace("%player%", p.getName())));
        }
    }

    private void sendProfessionMessagesOnly(String profession, Player p, String basePath) {
        sendProfessionMessageAndCommands(profession, p, basePath);
    }

    private void handleProfessionRewardClick(String profession, Player p, CategoryTemplate ct, CategoryTemplate.RewardButton rb) {
        if (p == null || profession == null || rb == null) return;
        int need = rb.requiredLevel;
        int have = plugin.data().getProfessionLevel(profession, p.getUniqueId());
        if (have < need) {
            sendLines(p, rb.noMsg);
            playVillagerNo(p);
            return;
        }
        if (plugin.data().isProfessionRewardClaimed(profession, p.getUniqueId(), need)) {
            String m = plugin.cfg().get().getString("messages." + profession + "_reward_already_claimed", "&eВы уже получали награду за этот уровень.");
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }
        if (p.getInventory().firstEmpty() == -1) {
            String m = plugin.cfg().get().getString("messages.no_inventory_space", "&cНедостаточно места в инвентаре. Освободите место и попробуйте снова.");
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }
        if (!executeRewardActions(p, getProfessionRewardCommands(profession, rb.requiredLevel))) {
            String m = plugin.cfg().get().getString("messages." + profession + "_reward_command_failed", "&cНе удалось выдать награду. Проверьте настройки награды и попробуйте снова.");
            p.sendMessage(ChatColor.translateAlternateColorCodes('&', m));
            playVillagerNo(p);
            return;
        }
        sendLines(p, rb.yesMsg);
        plugin.data().setProfessionRewardClaimed(profession, p.getUniqueId(), need, true);
        try { p.openInventory(ct.buildFor(p)); } catch (Throwable ignored) {}
    }

    // Utility methods
    // ---------------------------------------------------------------------
    private String msg(String path, String def) {
        return ChatColor.translateAlternateColorCodes(
                '&',
                plugin.cfg().get().getString("messages." + path, def)
        );
    }

    private String formatPrice(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private int countInInventory(Player p, Material mat) {
        int have = 0;
        PlayerInventory pinv = p.getInventory();
        for (int i = 0; i < pinv.getSize(); i++) {
            ItemStack it = pinv.getItem(i);
            if (it == null) continue;
            if (it.getType() == mat) {
                have += it.getAmount();
            }
        }
        return have;
    }

    // remove up to maxAmount items of type mat
    private int removeFromInventory(Player p, Material mat, int maxAmount) {
        int toSell = maxAmount;
        int sold = 0;
        PlayerInventory pinv = p.getInventory();
        for (int slot = 0; slot < pinv.getSize() && toSell > 0; slot++) {
            ItemStack it = pinv.getItem(slot);
            if (it == null || it.getType() != mat) continue;
            int take = Math.min(it.getAmount(), toSell);
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) {
                pinv.setItem(slot, null);
            }
            toSell -= take;
            sold += take;
        }
        return sold;
    }

    // remove ALL items of type mat
    private int removeAllFromInventory(Player p, Material mat) {
        return removeFromInventory(p, mat, Integer.MAX_VALUE);
    }

    /**
     * Calculate the current multiplier for a player/material,
     * given that they have sold 'soldAmount' in total.
     */
    private double getCurrentMultiplier(Player p, Material mat, long soldAmount) {
        FileConfiguration cfg = plugin.cfg().get();
        String mpath = "multipliers." + mat.name();
        ConfigurationSection sec = cfg.getConfigurationSection(mpath);

        double curMul = 1.0D;
        if (sec != null) {
            curMul = sec.getDouble("default", 1.0D);
            for (String key : sec.getKeys(false)) {
                if (key.equalsIgnoreCase("default")) continue;
                try {
                    long threshold = Long.parseLong(key);
                    double mv = sec.getDouble(key, curMul);
                    if (soldAmount >= threshold && mv > curMul) {
                        curMul = mv;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return curMul;
    }

    /**
     * Next multiplier info:
     * how many more items are required and what the next multiplier will be.
     */
    private NextInfo getNextInfo(Player p, Material mat, long soldAmount) {
        NextInfo info = new NextInfo();
        info.atMax = true;
        info.remaining = 0L;
        info.nextMultiplier = 0.0D;

        FileConfiguration cfg = plugin.cfg().get();
        String mpath = "multipliers." + mat.name();
        ConfigurationSection sec = cfg.getConfigurationSection(mpath);
        if (sec == null) {
            return info;
        }

        TreeMap<Long, Double> map = new TreeMap<>();
        for (String key : sec.getKeys(false)) {
            if (key.equalsIgnoreCase("default")) continue;
            try {
                long threshold = Long.parseLong(key);
                double mul = sec.getDouble(key, 1.0D);
                map.put(threshold, mul);
            } catch (NumberFormatException ignored) {
            }
        }

        for (Map.Entry<Long, Double> en : map.entrySet()) {
            if (en.getKey() > soldAmount) {
                info.atMax = false;
                info.remaining = en.getKey() - soldAmount;
                info.nextMultiplier = en.getValue();
                break;
            }
        }

        return info;
    }
}