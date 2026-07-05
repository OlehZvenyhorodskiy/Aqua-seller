package org.yarchez.aquaseller.gui;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.yarchez.aquaseller.AquaSeller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProfessionProgressGui implements Listener {
    private final AquaSeller plugin;
    public ProfessionProgressGui(AquaSeller plugin) { this.plugin = plugin; }

    private static class GuiHolder implements InventoryHolder {
        private final String tag;
        private GuiHolder(String tag) { this.tag = tag; }
        @Override public Inventory getInventory() { return null; }
    }

    public void open(Player viewer, String profession, OfflinePlayer target) {
        String prof = normalizeProfession(profession);
        if (prof == null) return;
        String nice = niceProfession(prof);
        String title = color("&8Прогресс: &6" + nice + " &7- &f" + safeName(target));
        Inventory inv = Bukkit.createInventory(new GuiHolder("progress:" + prof), 27, title);

        ItemStack pane = item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot : new int[]{0,1,2,3,5,6,7,8,9,17,18,19,20,21,23,24,25,26}) inv.setItem(slot, pane);

        int level = plugin.data().getProfessionLevel(prof, target.getUniqueId());
        long total = plugin.data().getProfessionTotalSold(prof, target.getUniqueId());
        long cap1 = plugin.cfg().get().getLong("profession." + prof + ".progress_caps.1", 5000L);
        long cap2 = plugin.cfg().get().getLong("profession." + prof + ".progress_caps.2", 15000L);
        long cap3 = plugin.cfg().get().getLong("profession." + prof + ".progress_caps.3", 30000L);
        long cap4 = plugin.cfg().get().getLong("profession." + prof + ".progress_caps.4", 50000L);

        inv.setItem(4, item(Material.NETHER_STAR, "&6&lПРОФЕССИЯ: &e" + nice,
                "&7Игрок: &f" + safeName(target),
                "&7Текущий уровень: &6" + roman(level),
                "&7Всего продано: &a" + total,
                "",
                "&7PlaceholderAPI:",
                "&e%aquaseller_" + prof + "_level%"
        ));

        inv.setItem(10, levelItem(1, total, cap1, level >= 1));
        inv.setItem(11, levelItem(2, total, cap2, level >= 2));
        inv.setItem(12, levelItem(3, total, cap3, level >= 3));
        inv.setItem(13, levelItem(4, total, cap4, level >= 4));

        long nextNeed = 0L;
        if (level < 2) nextNeed = plugin.cfg().get().getLong("profession." + prof + ".to_level.2", 5000L);
        else if (level < 3) nextNeed = plugin.cfg().get().getLong("profession." + prof + ".to_level.3", 15000L);
        else if (level < 4) nextNeed = plugin.cfg().get().getLong("profession." + prof + ".to_level.4", 30000L);

        List<String> nextLore = new ArrayList<>();
        nextLore.add(color("&7Текущий уровень: &6" + roman(level)));
        if (level >= 4) {
            nextLore.add(color("&aМаксимальный уровень достигнут"));
            long rewardNeed = plugin.cfg().get().getLong("profession." + prof + ".level4_reward.required", 20000L);
            nextLore.add(color("&7Финальная награда при: &e" + rewardNeed));
            nextLore.add(color("&7Текущий финальный прогресс: &a" + Math.min(total, rewardNeed) + "&7/&e" + rewardNeed));
        } else {
            nextLore.add(color("&7До следующего уровня нужно: &e" + nextNeed));
            nextLore.add(color("&7Текущий прогресс: &a" + Math.min(total, nextNeed) + "&7/&e" + nextNeed));
            nextLore.add(color("&7Осталось: &6" + Math.max(0L, nextNeed - total)));
        }
        inv.setItem(15, item(Material.BOOK, "&6&lСЛЕДУЮЩАЯ ЦЕЛЬ", nextLore.toArray(new String[0])));
        inv.setItem(16, item(Material.PAPER, "&a&lТОП КОМАНДЫ",
                "&7/aquaseller top " + prof,
                "&7/aquaseller progress " + prof,
                target.getPlayer() != null && viewer.hasPermission("aquaseller.admin") ? "&7/aquaseller progress " + prof + " " + safeName(target) : ""
        ));
        viewer.openInventory(inv);
    }

    private static String normalizeProfession(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.equals("rudy") || s.equals("miner") || s.equals("mining")) return "rudy";
        if (s.equals("fermer") || s.equals("farmer") || s.equals("farm")) return "fermer";
        if (s.equals("killer") || s.equals("hunter")) return "killer";
        if (s.equals("fishman") || s.equals("fisher") || s.equals("fish") || s.equals("fishing")) return "fishman";
        return null;
    }
    private static String niceProfession(String prof) {
        return switch (prof) {
            case "rudy" -> "ШАХТЁР";
            case "fermer" -> "ФЕРМЕР";
            case "killer" -> "ОХОТНИК";
            case "fishman" -> "РЫБАК";
            default -> prof.toUpperCase(Locale.ROOT);
        };
    }

    private ItemStack levelItem(int level, long total, long cap, boolean unlocked) {
        Material mat = unlocked ? Material.LIME_DYE : Material.GRAY_DYE;
        return item(mat, "&6Уровень &e" + roman(level), "&7Прогресс: &a" + Math.min(total, cap) + "&7/&e" + cap, unlocked ? "&aОткрыт" : "&cЗакрыт");
    }
    private ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> loreList = new ArrayList<>();
            if (lore != null) for (String s : lore) if (s != null && !s.isEmpty()) loreList.add(color(s));
            meta.setLore(loreList);
            meta.addItemFlags(ItemFlag.values());
            item.setItemMeta(meta);
        }
        return item;
    }
    @EventHandler public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory() == null) return;
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof GuiHolder gh) || !gh.tag.startsWith("progress:")) return;
        event.setCancelled(true);
    }
    private String safeName(OfflinePlayer p) { return p.getName() != null ? p.getName() : p.getUniqueId().toString(); }
    private static String roman(int level) { return switch (level) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; default -> String.valueOf(level); }; }
    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
}
