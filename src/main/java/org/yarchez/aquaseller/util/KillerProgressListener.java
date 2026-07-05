package org.yarchez.aquaseller.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.yarchez.aquaseller.AquaSeller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KillerProgressListener implements Listener {
    private static final long DAMAGE_CACHE_TTL_MS = 30_000L;

    private final AquaSeller plugin;
    private final Map<UUID, DamageRecord> lastDamager = new ConcurrentHashMap<>();

    public KillerProgressListener(AquaSeller plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player || target instanceof ArmorStand || target instanceof Villager) return;

        Player attacker = resolvePlayerDamager(event.getDamager());
        if (attacker == null) return;

        lastDamager.put(target.getUniqueId(), new DamageRecord(attacker.getUniqueId(), System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player) return;
        if (entity instanceof ArmorStand) return;
        if (entity instanceof Villager) return;
        if (entity.hasMetadata("NPC")) return;

        Player killer = entity.getKiller();
        if (killer == null) {
            DamageRecord rec = lastDamager.remove(entity.getUniqueId());
            if (rec != null && (System.currentTimeMillis() - rec.timeMs) <= DAMAGE_CACHE_TTL_MS) {
                killer = Bukkit.getPlayer(rec.playerId);
            }
        } else {
            lastDamager.remove(entity.getUniqueId());
        }

        if (killer == null) return;
        handleKillerProgress(killer, 1);
    }

    private Player resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private void handleKillerProgress(Player p, int amount) {
        if (p == null || amount <= 0) return;
        int curLevel = plugin.data().getKillerLevel(p.getUniqueId());
        if (curLevel < 1) curLevel = 1;
        if (curLevel > 4) curLevel = 4;
        plugin.data().addKillerProgress(p.getUniqueId(), curLevel, amount);
        long total = plugin.data().addKillerTotalSold(p.getUniqueId(), amount);
        curLevel = plugin.data().getKillerLevel(p.getUniqueId());
        if (curLevel == 1) {
            long need = plugin.cfg().get().getLong("profession.killer.to_level.2", 5000L);
            if (total >= need) levelUp(p, 2);
        } else if (curLevel == 2) {
            long need = plugin.cfg().get().getLong("profession.killer.to_level.3", 15000L);
            if (total >= need) levelUp(p, 3);
        } else if (curLevel == 3) {
            long need = plugin.cfg().get().getLong("profession.killer.to_level.4", 30000L);
            if (total >= need) levelUp(p, 4);
        }
        tryGrantLevel4AutoReward(p, total);
    }

    private void levelUp(Player p, int newLevel) {
        int current = plugin.data().getKillerLevel(p.getUniqueId());
        if (newLevel <= current) return;
        plugin.data().setKillerLevel(p.getUniqueId(), newLevel);
        playLevelUpSound(p);
        sendMessagesOnly(p, "profession.killer.level_up." + newLevel);
        try {
            if (plugin.sellerGui() != null) plugin.sellerGui().reload();
        } catch (Throwable ignored) {}
    }

    private void playLevelUpSound(Player p) {
        if (p == null) return;
        try {
            String name = plugin.cfg().get().getString("profession.killer.level_up_sound.sound", "UI_TOAST_CHALLENGE_COMPLETE");
            float volume = (float) plugin.cfg().get().getDouble("profession.killer.level_up_sound.volume", 1.0D);
            float pitch = (float) plugin.cfg().get().getDouble("profession.killer.level_up_sound.pitch", 1.0D);
            Sound s;
            try { s = Sound.valueOf(name); } catch (Exception ignored) { s = Sound.UI_TOAST_CHALLENGE_COMPLETE; }
            p.playSound(p.getLocation(), s, volume, pitch);
        } catch (Throwable ignored) {}
    }

    private void tryGrantLevel4AutoReward(Player p, long total) {
        if (p == null) return;
        try {
            if (plugin.data().getKillerLevel(p.getUniqueId()) < 4) return;
            if (plugin.data().isKillerMaxRewardClaimed(p.getUniqueId())) return;
            long need = plugin.cfg().get().getLong("profession.killer.level4_reward.required", 20000L);
            if (total < need) return;
            List<String> cmds = Collections.emptyList();
            ConfigurationSection sec = plugin.cfg().get().getConfigurationSection("profession.killer.level4_reward");
            if (sec != null) cmds = readStringListFlexible(sec, "commands");
            if (cmds != null && !cmds.isEmpty()) {
                for (String c : cmds) {
                    if (c == null || c.trim().isEmpty()) continue;
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()));
                }
            }
            plugin.data().setKillerMaxRewardClaimed(p.getUniqueId(), true);
            plugin.data().setKillerRewardClaimed(p.getUniqueId(), 4, true);
            sendMessagesOnly(p, "profession.killer.level4_reward");
        } catch (Throwable ignored) {}
    }

    private void sendMessagesOnly(Player p, String basePath) {
        if (p == null || basePath == null) return;
        String msg = plugin.cfg().get().getString(basePath + ".player_message", plugin.cfg().get().getString(basePath + ".message", ""));
        if (msg != null && !msg.isEmpty()) p.sendMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("%player%", p.getName())));
        if (plugin.cfg().get().getBoolean(basePath + ".broadcast", false)) {
            String bmsg = plugin.cfg().get().getString(basePath + ".broadcast_message", "");
            if (bmsg != null && !bmsg.isEmpty()) Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', bmsg.replace("%player%", p.getName())));
        }
    }

    private List<String> readStringListFlexible(ConfigurationSection sec, String key) {
        if (sec == null || key == null) return Collections.emptyList();
        List<String> list = sec.getStringList(key);
        if (list != null && !list.isEmpty()) return list;
        String single = sec.getString(key, "");
        if (single != null && !single.trim().isEmpty()) return Collections.singletonList(single);
        return Collections.emptyList();
    }

    private static final class DamageRecord {
        private final UUID playerId;
        private final long timeMs;

        private DamageRecord(UUID playerId, long timeMs) {
            this.playerId = playerId;
            this.timeMs = timeMs;
        }
    }
}
