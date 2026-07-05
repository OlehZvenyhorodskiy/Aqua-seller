package org.yarchez.aquaseller.util;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.yarchez.aquaseller.AquaSeller;

public class Text {
    private static long getProgressCap(AquaSeller plugin, String profession, int level, long def) {
        if (plugin == null || profession == null) return def;
        String normalized = profession.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> keys = new java.util.ArrayList<>();
        keys.add(normalized);
        if ("rudy".equals(normalized)) keys.add("shahter");
        if ("fishman".equals(normalized)) keys.add("fisher");
        for (String key : keys) {
            String path = "profession." + key + ".progress_caps." + level;
            if (plugin.cfg().get().contains(path)) return plugin.cfg().get().getLong(path, def);
        }
        return plugin.cfg().get().getLong("profession." + normalized + ".progress_caps." + level, def);
    }

    /**
     * Translate & color codes to §.
     */
    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', (s == null ? "" : s));
    }

    /**
     * Apply PlaceholderAPI placeholders if available.
     */
    public static String papi(Plugin plugin, Player p, String line) {
        if (line == null) return "";
        // apply internal placeholders first (so they work even without PlaceholderAPI)
        if (plugin instanceof AquaSeller) {
            line = applyInternalPlaceholders((AquaSeller) plugin, p, line);
        }
        try {
            if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                return PlaceholderAPI.setPlaceholders(p, line);
            }
        } catch (Throwable ignored) {
        }
        return line;
    }

    private static String applyInternalPlaceholders(AquaSeller plugin, Player p, String line) {
        if (p == null) return line;
        try {
            int rudyLvl = plugin.data().getRudyLevel(p.getUniqueId());
            int fermerLvl = plugin.data().getFermerLevel(p.getUniqueId());
            int killerLvl = plugin.data().getKillerLevel(p.getUniqueId());
            int fishLvl = plugin.data().getFishLevel(p.getUniqueId());

            // Rudy reward placeholders (claim status per level reward button)
            boolean r1 = plugin.data().isRudyRewardClaimed(p.getUniqueId(), 2);
            boolean r2 = plugin.data().isRudyRewardClaimed(p.getUniqueId(), 3);
            boolean r3 = plugin.data().isRudyRewardClaimed(p.getUniqueId(), 4);

            String r1s = r1 ? "&fВЫДАНА" : "";
            String r2s = r2 ? "&fВЫДАНА" : "";
            String r3s = r3 ? "&fВЫДАНА" : "";

            // We track miner progression as a cumulative total sold amount in the RUDY category.
            // Older builds stored progress per item tier; we still keep those stats, but UI/leveling uses total.
            long rudyTotal = plugin.data().getRudyTotalSold(p.getUniqueId());
            long fermerTotal = plugin.data().getFermerTotalSold(p.getUniqueId());
            long killerTotal = plugin.data().getKillerTotalSold(p.getUniqueId());
            long fishTotal = plugin.data().getFishTotalSold(p.getUniqueId());

            long rCap1 = getProgressCap(plugin, "rudy", 1, 5000L);
            long rCap2 = getProgressCap(plugin, "rudy", 2, 10000L);
            long rCap3 = getProgressCap(plugin, "rudy", 3, 15000L);
            long rCap4 = getProgressCap(plugin, "rudy", 4, 20000L);

            long fCap1 = getProgressCap(plugin, "fermer", 1, 5000L);
            long fCap2 = getProgressCap(plugin, "fermer", 2, 10000L);
            long fCap3 = getProgressCap(plugin, "fermer", 3, 15000L);
            long fCap4 = getProgressCap(plugin, "fermer", 4, 20000L);

            long kCap1 = getProgressCap(plugin, "killer", 1, 5000L);
            long kCap2 = getProgressCap(plugin, "killer", 2, 10000L);
            long kCap3 = getProgressCap(plugin, "killer", 3, 15000L);
            long kCap4 = getProgressCap(plugin, "killer", 4, 20000L);

            long fishCap1 = getProgressCap(plugin, "fishman", 1, 5000L);
            long fishCap2 = getProgressCap(plugin, "fishman", 2, 10000L);
            long fishCap3 = getProgressCap(plugin, "fishman", 3, 15000L);
            long fishCap4 = getProgressCap(plugin, "fishman", 4, 20000L);

            long pr1 = Math.min(rudyTotal, rCap1);
            long pr2 = Math.min(rudyTotal, rCap2);
            long pr3 = Math.min(rudyTotal, rCap3);
            long pr4 = Math.min(rudyTotal, rCap4);

            long fpr1 = Math.min(fermerTotal, fCap1);
            long fpr2 = Math.min(fermerTotal, fCap2);
            long fpr3 = Math.min(fermerTotal, fCap3);
            long fpr4 = Math.min(fermerTotal, fCap4);
            long kpr1 = Math.min(killerTotal, kCap1);
            long kpr2 = Math.min(killerTotal, kCap2);
            long kpr3 = Math.min(killerTotal, kCap3);
            long kpr4 = Math.min(killerTotal, kCap4);
            long fishPr1 = Math.min(fishTotal, fishCap1);
            long fishPr2 = Math.min(fishTotal, fishCap2);
            long fishPr3 = Math.min(fishTotal, fishCap3);
            long fishPr4 = Math.min(fishTotal, fishCap4);

            boolean fr1 = plugin.data().isFermerRewardClaimed(p.getUniqueId(), 2);
            boolean fr2 = plugin.data().isFermerRewardClaimed(p.getUniqueId(), 3);
            boolean fr3 = plugin.data().isFermerRewardClaimed(p.getUniqueId(), 4);
            String fr1s = fr1 ? "&fВЫДАНА" : "";
            String fr2s = fr2 ? "&fВЫДАНА" : "";
            String fr3s = fr3 ? "&fВЫДАНА" : "";
            boolean kr1 = plugin.data().isKillerRewardClaimed(p.getUniqueId(), 2);
            boolean kr2 = plugin.data().isKillerRewardClaimed(p.getUniqueId(), 3);
            boolean kr3 = plugin.data().isKillerRewardClaimed(p.getUniqueId(), 4);
            String kr1s = kr1 ? "&fВЫДАНА" : "";
            String kr2s = kr2 ? "&fВЫДАНА" : "";
            String kr3s = kr3 ? "&fВЫДАНА" : "";
            boolean fishR1 = plugin.data().isFishRewardClaimed(p.getUniqueId(), 2);
            boolean fishR2 = plugin.data().isFishRewardClaimed(p.getUniqueId(), 3);
            boolean fishR3 = plugin.data().isFishRewardClaimed(p.getUniqueId(), 4);
            String fishR1s = fishR1 ? "&fВЫДАНА" : "";
            String fishR2s = fishR2 ? "&fВЫДАНА" : "";
            String fishR3s = fishR3 ? "&fВЫДАНА" : "";

            line = line
                    .replace("%levlplayerI%", String.valueOf(pr1))
                    .replace("%levlplayerII%", String.valueOf(pr2))
                    .replace("%levlplayerIII%", String.valueOf(pr3))
                    .replace("%levlplayerIV%", String.valueOf(pr4))
                    .replace("%rudy_level%", String.valueOf(rudyLvl))
                    .replace("%rudyrewards1%", r1s)
                    .replace("%rudyrewards2%", r2s)
                    .replace("%rudyrewards3%", r3s)
                    .replace("%fermer_level%", String.valueOf(fermerLvl))
                    .replace("%fermerlevlplayerI%", String.valueOf(fpr1))
                    .replace("%fermerlevlplayerII%", String.valueOf(fpr2))
                    .replace("%fermerlevlplayerIII%", String.valueOf(fpr3))
                    .replace("%fermerlevlplayerIV%", String.valueOf(fpr4))
                    .replace("%fermerrewards1%", fr1s)
                    .replace("%fermerrewards2%", fr2s)
                    .replace("%fermerrewards3%", fr3s)
                    .replace("%killer_level%", String.valueOf(killerLvl))
                    .replace("%killerlevlplayerI%", String.valueOf(kpr1))
                    .replace("%killerlevlplayerII%", String.valueOf(kpr2))
                    .replace("%killerlevlplayerIII%", String.valueOf(kpr3))
                    .replace("%killerlevlplayerIV%", String.valueOf(kpr4))
                    .replace("%killerrewards1%", kr1s)
                    .replace("%killerrewards2%", kr2s)
                    .replace("%killerrewards3%", kr3s)
                    .replace("%fishman_level%", String.valueOf(fishLvl))
                    .replace("%fisher_level%", String.valueOf(fishLvl))
                    .replace("%fishmanlevlplayerI%", String.valueOf(fishPr1))
                    .replace("%fishmanlevlplayerII%", String.valueOf(fishPr2))
                    .replace("%fishmanlevlplayerIII%", String.valueOf(fishPr3))
                    .replace("%fishmanlevlplayerIV%", String.valueOf(fishPr4))
                    .replace("%fisherlevlplayerI%", String.valueOf(fishPr1))
                    .replace("%fisherlevlplayerII%", String.valueOf(fishPr2))
                    .replace("%fisherlevlplayerIII%", String.valueOf(fishPr3))
                    .replace("%fisherlevlplayerIV%", String.valueOf(fishPr4))
                    .replace("%fishmanrewards1%", fishR1s)
                    .replace("%fishmanrewards2%", fishR2s)
                    .replace("%fishmanrewards3%", fishR3s)
                    .replace("%fisherrewards1%", fishR1s)
                    .replace("%fisherrewards2%", fishR2s)
                    .replace("%fisherrewards3%", fishR3s);
        } catch (Throwable ignored) {
        }
        return line;
    }
}