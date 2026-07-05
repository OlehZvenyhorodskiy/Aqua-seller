package org.yarchez.aquaseller.util;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.yarchez.aquaseller.AquaSeller;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

public class NpcManager {
    private final AquaSeller plugin;
    private final File file;
    private FileConfiguration cfg;
    private final Map<String, Integer> npcIds = new LinkedHashMap<>();

    public NpcManager(AquaSeller plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            try { file.getParentFile().mkdirs(); file.createNewFile(); } catch (IOException ignored) {}
        }
        cfg = YamlConfiguration.loadConfiguration(file);
        npcIds.clear();
        ConfigurationSectionLike.loadInts(cfg, "npc_ids", npcIds);
    }

    public boolean savePlacement(String type, Location loc) {
        String key = normalize(type);
        if (key == null || loc == null || loc.getWorld() == null) return false;
        String base = "placements." + key + ".";
        cfg.set(base + "world", loc.getWorld().getName());
        cfg.set(base + "x", loc.getX());
        cfg.set(base + "y", loc.getY());
        cfg.set(base + "z", loc.getZ());
        cfg.set(base + "yaw", loc.getYaw());
        cfg.set(base + "pitch", loc.getPitch());
        save();
        return true;
    }

    public void refreshAll() {
        for (String key : new String[]{"mining","fermer","killer","fisher","topseller"}) {
            try { refreshNpc(key); } catch (Throwable t) { plugin.getLogger().warning("NPC " + key + ": " + t.getMessage()); }
        }
    }

    public void refreshNpc(String type) throws Exception {
        String key = normalize(type);
        if (key == null) return;
        Location loc = getPlacement(key);
        if (loc == null) return;
        if (!isCitizensAvailable()) return;

        TopEntry top = getTopEntry(key);
        String name = top.name == null ? "-" : top.name;
        Object registry = citizensApi("getNPCRegistry");

        java.util.List<Object> candidates = findCandidates(registry, key, loc);
        Object npc = selectPrimaryCandidate(candidates, key);
        if (npc == null) {
            npc = invoke(registry, "createNPC", new Class[]{EntityType.class, String.class}, EntityType.PLAYER, name);
        }

        rememberNpcId(key, npc);
        markNpc(npc, key);
        destroyOtherCandidates(candidates, npc);

        try { invoke(npc, "despawn"); } catch (Throwable ignored) {}
        invoke(npc, "setName", new Class[]{String.class}, name);
        Object skinTrait = invoke(npc, "getOrAddTrait", new Class[]{Class.class}, Class.forName("net.citizensnpcs.trait.SkinTrait"));
        if (top.name != null && !top.name.isEmpty()) {
            invoke(skinTrait, "setSkinName", new Class[]{String.class, boolean.class}, top.name, true);
            try { invoke(skinTrait, "setShouldUpdateSkins", new Class[]{boolean.class}, true); } catch (Throwable ignored) {}
        }
        Object equip = invoke(npc, "getOrAddTrait", new Class[]{Class.class}, Class.forName("net.citizensnpcs.api.trait.trait.Equipment"));
        Class<?> slotClass = Class.forName("net.citizensnpcs.api.trait.trait.Equipment$EquipmentSlot");
        Object hand = Enum.valueOf((Class<Enum>) slotClass.asSubclass(Enum.class), "HAND");
        invoke(equip, "set", new Class[]{slotClass, ItemStack.class}, hand, new ItemStack(handMaterial(key)));

        Object holo = invoke(npc, "getOrAddTrait", new Class[]{Class.class}, Class.forName("net.citizensnpcs.trait.HologramTrait"));
        invoke(holo, "clear");
        invoke(holo, "addLine", new Class[]{String.class}, hologramText(key));
        invoke(npc, "spawn", new Class[]{Location.class}, loc);
        applyLookClose(npc);
    }


    public void cleanupAll() {
        if (!isCitizensAvailable()) return;
        try {
            Object registry = citizensApi("getNPCRegistry");
            for (String key : new String[]{"mining","fermer","killer","fisher","topseller"}) {
                cleanupNpc(registry, key);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("NPC cleanup error: " + t.getMessage());
        }
    }


    private void applyLookClose(Object npc) {
        try {
            Class<?> lookCloseClass = Class.forName("net.citizensnpcs.trait.LookClose");
            Object lookClose = invoke(npc, "getOrAddTrait", new Class[]{Class.class}, lookCloseClass);
            invokeIfPresent(lookClose, "lookClose", new Class[]{boolean.class}, true);
            invokeIfPresent(lookClose, "setEnabled", new Class[]{boolean.class}, true);
            invokeIfPresent(lookClose, "setRange", new Class[]{double.class}, 12.0d);
            invokeIfPresent(lookClose, "setRealisticLooking", new Class[]{boolean.class}, false);
            invokeIfPresent(lookClose, "setDisableWhileNavigating", new Class[]{boolean.class}, false);
            invokeIfPresent(lookClose, "setHeadOnly", new Class[]{boolean.class}, false);
            invokeIfPresent(lookClose, "setRandomLook", new Class[]{boolean.class}, false);
            invokeIfPresent(lookClose, "setTargetNPCs", new Class[]{boolean.class}, false);
            invokeIfPresent(lookClose, "setPerPlayer", new Class[]{boolean.class}, false);
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось включить look-close для NPC: " + t.getMessage());
        }
    }

    private void cleanupNpc(Object registry, String key) throws Exception {
        Integer id = npcIds.get(key);
        if (id != null && id > 0) {
            Object npc = invoke(registry, "getById", new Class[]{int.class}, id);
            destroyNpcQuietly(npc);
        }
        cleanupDuplicates(registry, key);
    }

    private void cleanupDuplicates(Object registry, String key) throws Exception {
        Location loc = getPlacement(key);
        java.util.List<Object> candidates = findCandidates(registry, key, loc);
        Object keep = selectPrimaryCandidate(candidates, key);
        if (keep != null) {
            rememberNpcId(key, keep);
            markNpc(keep, key);
        }
        destroyOtherCandidates(candidates, keep);
    }

    private Object[] getAllNpcs(Object registry) throws Exception {
        Iterable<?> iterable = (Iterable<?>) invoke(registry, "sorted");
        java.util.List<Object> list = new java.util.ArrayList<>();
        for (Object npc : iterable) list.add(npc);
        return list.toArray();
    }


    private java.util.List<Object> findCandidates(Object registry, String key, Location loc) throws Exception {
        java.util.List<Object> out = new java.util.ArrayList<>();
        for (Object npc : getAllNpcs(registry)) {
            if (isManagedNpc(npc, key, loc)) out.add(npc);
        }
        return out;
    }

    private Object selectPrimaryCandidate(java.util.List<Object> candidates, String key) {
        if (candidates == null || candidates.isEmpty()) return null;
        Integer expectedId = npcIds.get(key);
        if (expectedId != null && expectedId > 0) {
            for (Object npc : candidates) {
                try {
                    int currentId = (Integer) invoke(npc, "getId");
                    if (currentId == expectedId) return npc;
                } catch (Throwable ignored) {}
            }
        }
        for (Object npc : candidates) {
            if (hasTypeMarker(npc, key)) return npc;
        }
        return candidates.get(0);
    }

    private void destroyOtherCandidates(java.util.List<Object> candidates, Object keep) {
        if (candidates == null || candidates.isEmpty()) return;
        Integer keepId = getNpcIdQuietly(keep);
        Set<Integer> destroyed = new HashSet<>();
        for (Object npc : candidates) {
            Integer currentId = getNpcIdQuietly(npc);
            if (currentId != null && keepId != null && currentId.intValue() == keepId.intValue()) continue;
            if (currentId != null && !destroyed.add(currentId)) continue;
            destroyNpcQuietly(npc);
        }
    }

    private void rememberNpcId(String key, Object npc) {
        Integer id = getNpcIdQuietly(npc);
        if (id == null || id <= 0) return;
        Integer previous = npcIds.put(key, id);
        if (previous != null && previous == id && cfg.getInt("npc_ids." + key) == id) return;
        cfg.set("npc_ids." + key, id);
        save();
    }

    private void markNpc(Object npc, String key) {
        try {
            Object data = invoke(npc, "data");
            invoke(data, "set", new Class[]{String.class, Object.class}, "aquaseller-type", key);
        } catch (Throwable ignored) {}
    }

    private boolean isManagedNpc(Object npc, String key) {
        return isManagedNpc(npc, key, null);
    }

    private boolean isManagedNpc(Object npc, String key, Location expectedLocation) {
        if (hasTypeMarker(npc, key)) return true;
        try {
            int id = (Integer) invoke(npc, "getId");
            Integer expectedId = npcIds.get(key);
            if (expectedId != null && expectedId == id) return true;
        } catch (Throwable ignored) {}
        return expectedLocation != null && isSameLocation(getNpcLocation(npc), expectedLocation);
    }

    private boolean hasTypeMarker(Object npc, String key) {
        try {
            Object data = invoke(npc, "data");
            Object value = invoke(data, "get", new Class[]{String.class}, "aquaseller-type");
            return value != null && key.equalsIgnoreCase(String.valueOf(value));
        } catch (Throwable ignored) {}
        return false;
    }

    private Integer getNpcIdQuietly(Object npc) {
        if (npc == null) return null;
        try { return (Integer) invoke(npc, "getId"); } catch (Throwable ignored) {}
        return null;
    }

    private Location getNpcLocation(Object npc) {
        if (npc == null) return null;
        try {
            Object stored = invoke(npc, "getStoredLocation");
            if (stored instanceof Location loc) return loc;
        } catch (Throwable ignored) {}
        try {
            boolean spawned = (Boolean) invoke(npc, "isSpawned");
            if (!spawned) return null;
            Object entity = invoke(npc, "getEntity");
            Object location = invoke(entity, "getLocation");
            if (location instanceof Location loc) return loc;
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isSameLocation(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() == null || b.getWorld() == null) return false;
        if (!a.getWorld().getUID().equals(b.getWorld().getUID())) return false;
        double epsilon = 0.05D;
        return Math.abs(a.getX() - b.getX()) <= epsilon
                && Math.abs(a.getY() - b.getY()) <= epsilon
                && Math.abs(a.getZ() - b.getZ()) <= epsilon;
    }

    private void destroyNpcQuietly(Object npc) {
        if (npc == null) return;
        try { invoke(npc, "despawn"); } catch (Throwable ignored) {}
        try { invoke(npc, "destroy"); } catch (Throwable ignored) {}
    }

    private TopEntry getTopEntry(String key) {
        String prof = switch (key) {
            case "mining" -> "rudy";
            case "fermer" -> "fermer";
            case "killer" -> "killer";
            case "fisher" -> "fishman";
            case "topseller" -> "overall";
            default -> "overall";
        };
        Map<UUID, Long> map = plugin.topCache().getTop(prof, 1);
        if (map.isEmpty()) return new TopEntry(null, null, 0L);
        Map.Entry<UUID, Long> e = map.entrySet().iterator().next();
        OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
        return new TopEntry(e.getKey(), op.getName(), e.getValue());
    }

    private Material handMaterial(String key) {
        return switch (key) {
            case "mining" -> Material.DIAMOND_PICKAXE;
            case "fermer" -> Material.DIAMOND_HOE;
            case "killer" -> Material.DIAMOND_SWORD;
            case "fisher" -> Material.FISHING_ROD;
            case "topseller" -> Material.SHULKER_BOX;
            default -> Material.STICK;
        };
    }
    private String hologramText(String key) {
        return switch (key) {
            case "mining" -> "&6ЛУЧШИЙ ШАХТЕР";
            case "fermer" -> "&6ЛУЧШИЙ ФЕРМЕР";
            case "killer" -> "&6ЛУЧШИЙ ОХОТНИК";
            case "fisher" -> "&6ЛУЧШИЙ РЫБАК";
            case "topseller" -> "&6ЛУЧШИЙ ПРОДАВЕЦ";
            default -> "&6ТОП";
        };
    }
    private Location getPlacement(String key) {
        String base = "placements." + key + ".";
        String world = cfg.getString(base + "world");
        if (world == null) return null;
        World w = Bukkit.getWorld(world);
        if (w == null) return null;
        return new Location(w, cfg.getDouble(base + "x"), cfg.getDouble(base + "y"), cfg.getDouble(base + "z"), (float) cfg.getDouble(base + "yaw"), (float) cfg.getDouble(base + "pitch"));
    }
    private boolean isCitizensAvailable() {
        Plugin pl = Bukkit.getPluginManager().getPlugin("Citizens");
        return pl != null && pl.isEnabled();
    }
    private Object citizensApi(String method) throws Exception {
        Class<?> api = Class.forName("net.citizensnpcs.api.CitizensAPI");
        return api.getMethod(method).invoke(null);
    }
    private static Object invoke(Object target, String method) throws Exception {
        Method m = findMethod(target.getClass(), method);
        return m.invoke(target);
    }
    private static Object invoke(Object target, String method, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getMethod(method, types);
        return m.invoke(target, args);
    }
    private static Method findMethod(Class<?> c, String name) throws NoSuchMethodException {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        throw new NoSuchMethodException(name);
    }
    private static void invokeIfPresent(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getMethod(method, types);
            m.invoke(target, args);
        } catch (Throwable ignored) {}
    }
    private static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "mining", "rudy", "miner" -> "mining";
            case "fermer", "farmer" -> "fermer";
            case "killer", "hunter" -> "killer";
            case "fisher", "fishman", "fish" -> "fisher";
            case "topseller", "overall", "seller" -> "topseller";
            default -> null;
        };
    }
    private void save() { try { cfg.save(file); } catch (IOException ignored) {} }
    private record TopEntry(UUID uuid, String name, long total) {}

    private static final class ConfigurationSectionLike {
        static void loadInts(FileConfiguration cfg, String path, Map<String, Integer> out) {
            if (cfg.getConfigurationSection(path) == null) return;
            for (String key : cfg.getConfigurationSection(path).getKeys(false)) out.put(key, cfg.getInt(path + "." + key));
        }
    }
}
