package org.yarchez.aquaseller.util;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.yarchez.aquaseller.AquaSeller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class AquaSellerPlaceholders extends PlaceholderExpansion {
    private final AquaSeller plugin;
    public AquaSellerPlaceholders(AquaSeller plugin) { this.plugin = plugin; }
    @Override public String getIdentifier() { return "aquaseller"; }
    @Override public String getRequiredPlugin() { return "AquaSeller"; }
    @Override public String getAuthor() { return "OpenAI"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }
    @Override public String onPlaceholderRequest(org.bukkit.entity.Player player, String params) { return onRequest(player, params); }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params == null ? "" : params.toLowerCase(Locale.ROOT).trim();
        String direct = resolveDirect(player, key);
        if (direct != null) return direct;
        if (key.startsWith("top_")) {
            TopQuery q = parseTopQuery(key);
            if (q != null) return resolveTop(q.profession, q.place, q.field);
        }
        return null;
    }

    private long getProgressCap(String profession, int level, long def) {
        if (profession == null) return def;
        String normalized = profession.toLowerCase(Locale.ROOT);
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

    private String resolveDirect(OfflinePlayer player, String key) {
        if (player == null || player.getUniqueId() == null) {
            if (key.endsWith("_level") || key.endsWith("_total")) return "0";
            return null;
        }
        UUID u = player.getUniqueId();
        long rudyTotal = plugin.data().getProfessionTotalSold("rudy", u);
        long fermerTotal = plugin.data().getProfessionTotalSold("fermer", u);
        long killerTotal = plugin.data().getProfessionTotalSold("killer", u);
        long fishTotal = plugin.data().getProfessionTotalSold("fishman", u);
        return switch (key) {
            case "rudy_level" -> String.valueOf(plugin.data().getProfessionLevel("rudy", u));
            case "fermer_level" -> String.valueOf(plugin.data().getProfessionLevel("fermer", u));
            case "killer_level" -> String.valueOf(plugin.data().getProfessionLevel("killer", u));
            case "fishman_level", "fisher_level" -> String.valueOf(plugin.data().getProfessionLevel("fishman", u));
            case "rudy_total" -> String.valueOf(rudyTotal);
            case "fermer_total" -> String.valueOf(fermerTotal);
            case "killer_total" -> String.valueOf(killerTotal);
            case "fishman_total", "fisher_total" -> String.valueOf(fishTotal);
            case "levlplayeri" -> String.valueOf(Math.min(rudyTotal, getProgressCap("rudy", 1, 5000L)));
            case "levlplayerii" -> String.valueOf(Math.min(rudyTotal, getProgressCap("rudy", 2, 15000L)));
            case "levlplayeriii" -> String.valueOf(Math.min(rudyTotal, getProgressCap("rudy", 3, 30000L)));
            case "levlplayeriv" -> String.valueOf(Math.min(rudyTotal, getProgressCap("rudy", 4, 50000L)));
            case "fermerlevlplayeri" -> String.valueOf(Math.min(fermerTotal, getProgressCap("fermer", 1, 5000L)));
            case "fermerlevlplayerii" -> String.valueOf(Math.min(fermerTotal, getProgressCap("fermer", 2, 15000L)));
            case "fermerlevlplayeriii" -> String.valueOf(Math.min(fermerTotal, getProgressCap("fermer", 3, 30000L)));
            case "fermerlevlplayeriv" -> String.valueOf(Math.min(fermerTotal, getProgressCap("fermer", 4, 50000L)));
            case "killerlevlplayeri" -> String.valueOf(Math.min(killerTotal, getProgressCap("killer", 1, 5000L)));
            case "killerlevlplayerii" -> String.valueOf(Math.min(killerTotal, getProgressCap("killer", 2, 15000L)));
            case "killerlevlplayeriii" -> String.valueOf(Math.min(killerTotal, getProgressCap("killer", 3, 30000L)));
            case "killerlevlplayeriv" -> String.valueOf(Math.min(killerTotal, getProgressCap("killer", 4, 50000L)));
            case "fisherlevlplayeri", "fishmanlevlplayeri" -> String.valueOf(Math.min(fishTotal, getProgressCap("fishman", 1, 5000L)));
            case "fisherlevlplayerii", "fishmanlevlplayerii" -> String.valueOf(Math.min(fishTotal, getProgressCap("fishman", 2, 15000L)));
            case "fisherlevlplayeriii", "fishmanlevlplayeriii" -> String.valueOf(Math.min(fishTotal, getProgressCap("fishman", 3, 30000L)));
            case "fisherlevlplayeriv", "fishmanlevlplayeriv" -> String.valueOf(Math.min(fishTotal, getProgressCap("fishman", 4, 50000L)));
            default -> null;
        };
    }

    private String resolveTop(String profession, int place, String field) {
        List<Map.Entry<UUID, Long>> list = new ArrayList<>(plugin.topCache().getTop(profession, Math.max(place, 10)).entrySet());
        if (place <= 0 || place > list.size()) return emptyValue(field);
        Map.Entry<UUID, Long> entry = list.get(place - 1);
        UUID uuid = entry.getKey();
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        return switch (field) {
            case "name" -> resolveName(op, uuid);
            case "total", "sold" -> String.valueOf(entry.getValue());
            case "level" -> profession.equals("overall") ? "-" : String.valueOf(plugin.data().getProfessionLevel(profession, uuid));
            case "level_roman", "roman" -> profession.equals("overall") ? "-" : roman(plugin.data().getProfessionLevel(profession, uuid));
            default -> "";
        };
    }

    private String resolveName(OfflinePlayer op, UUID uuid) {
        if (uuid == null) return "-";

        Player online = Bukkit.getPlayer(uuid);
        if (online != null && online.getName() != null && !online.getName().isBlank()) {
            plugin.data().rememberPlayerName(uuid, online.getName());
            return online.getName();
        }

        if (op != null && op.getName() != null && !op.getName().isBlank()) {
            plugin.data().rememberPlayerName(uuid, op.getName());
            return op.getName();
        }

        try {
            for (OfflinePlayer candidate : Bukkit.getOfflinePlayers()) {
                if (candidate != null && uuid.equals(candidate.getUniqueId())) {
                    String name = candidate.getName();
                    if (name != null && !name.isBlank()) {
                        plugin.data().rememberPlayerName(uuid, name);
                        return name;
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }

        String remembered = plugin.data().getRememberedPlayerName(uuid);
        return remembered != null ? remembered : "-";
    }

    private TopQuery parseTopQuery(String key) {
        String[] parts = key.split("_");
        if (parts.length < 4 || !"top".equals(parts[0])) return null;
        String profession = TopCache.normalizeProfession(parts[1]);
        if (profession == null) return null;
        int place = parsePositive(parts[2]);
        if (place <= 0) return null;
        StringBuilder field = new StringBuilder(parts[3]);
        for (int i = 4; i < parts.length; i++) field.append('_').append(parts[i]);
        return new TopQuery(profession, place, field.toString());
    }

    private static int parsePositive(String raw) { try { return Integer.parseInt(raw); } catch (NumberFormatException ignored) { return -1; } }
    private static String emptyValue(String field) { return "name".equals(field) || "level_roman".equals(field) || "roman".equals(field) || "level".equals(field) ? "-" : "0"; }
    private static String roman(int level) { return switch (level) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; default -> String.valueOf(level); }; }
    private record TopQuery(String profession, int place, String field) {}
}
