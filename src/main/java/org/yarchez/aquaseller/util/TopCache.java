package org.yarchez.aquaseller.util;

import org.yarchez.aquaseller.AquaSeller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class TopCache {
    public static final long REFRESH_TICKS = 20L * 60L * 5L;
    private final AquaSeller plugin;
    private final Map<String, LinkedHashMap<UUID, Long>> cache = new LinkedHashMap<>();
    private long lastRefresh = 0L;

    public TopCache(AquaSeller plugin) { this.plugin = plugin; }

    public void refresh() {
        cache.put("rudy", new LinkedHashMap<>(plugin.data().getTopProfessionTotals("rudy", 100)));
        cache.put("fermer", new LinkedHashMap<>(plugin.data().getTopProfessionTotals("fermer", 100)));
        cache.put("killer", new LinkedHashMap<>(plugin.data().getTopProfessionTotals("killer", 100)));
        cache.put("fishman", new LinkedHashMap<>(plugin.data().getTopProfessionTotals("fishman", 100)));
        cache.put("overall", new LinkedHashMap<>(plugin.data().getTopOverallTotals(100)));
        rememberKnownNames();
        lastRefresh = System.currentTimeMillis();
    }

    private void rememberKnownNames() {
        try {
            for (Map<UUID, Long> top : cache.values()) {
                for (UUID uuid : top.keySet()) {
                    if (uuid == null) continue;
                    org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
                    if (online != null && online.getName() != null && !online.getName().isBlank()) {
                        plugin.data().rememberPlayerName(uuid, online.getName());
                        continue;
                    }
                    org.bukkit.OfflinePlayer offline = org.bukkit.Bukkit.getOfflinePlayer(uuid);
                    if (offline != null && offline.getName() != null && !offline.getName().isBlank()) {
                        plugin.data().rememberPlayerName(uuid, offline.getName());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public Map<UUID, Long> getTop(String profession, int limit) {
        String prof = normalizeProfession(profession);
        LinkedHashMap<UUID, Long> src = cache.getOrDefault(prof, new LinkedHashMap<>());
        if (limit >= src.size()) return Collections.unmodifiableMap(src);
        LinkedHashMap<UUID, Long> out = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<UUID, Long> e : src.entrySet()) {
            out.put(e.getKey(), e.getValue());
            if (++i >= limit) break;
        }
        return out;
    }

    public long getLastRefresh() { return lastRefresh; }

    public static String normalizeProfession(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT);
        if (s.equals("rudy") || s.equals("miner") || s.equals("mining")) return "rudy";
        if (s.equals("fermer") || s.equals("farmer") || s.equals("farm")) return "fermer";
        if (s.equals("killer") || s.equals("hunter")) return "killer";
        if (s.equals("fishman") || s.equals("fisher") || s.equals("fish") || s.equals("fishing")) return "fishman";
        if (s.equals("topseller") || s.equals("overall") || s.equals("seller")) return "overall";
        return null;
    }
}
