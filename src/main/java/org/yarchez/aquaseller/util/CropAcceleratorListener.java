package org.yarchez.aquaseller.util;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.yarchez.aquaseller.AquaSeller;

import java.util.Objects;

/**
 * Crop accelerators: passive items that work while they are in a player inventory.
 * They do NOT work when used like bonemeal; right click usage is blocked.
 * They can be combined in an anvil: I+I->II, II+II->III.
 */
public class CropAcceleratorListener implements Listener {

    private final AquaSeller plugin;
    private final ItemsManager items;
    private final java.util.Map<java.util.UUID, ItemsManager.AcceleratorStats> playerAccelerators = new java.util.concurrent.ConcurrentHashMap<>();

    public CropAcceleratorListener(AquaSeller plugin) {
        this.plugin = plugin;
        this.items = plugin.items();
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
            updatePlayerAccelerator(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRightClick(PlayerInteractEvent e) {
        ItemStack it = e.getItem();
        if (!items.isCropAccelerator(it)) return;
        switch (e.getAction()) {
            case RIGHT_CLICK_AIR:
            case RIGHT_CLICK_BLOCK:
                e.setCancelled(true);
                break;
            default:
                break;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrindstoneClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof GrindstoneInventory)) return;
        if (items.isCropAccelerator(e.getCurrentItem()) || items.isCropAccelerator(e.getCursor())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrindstoneDrag(InventoryDragEvent e) {
        if (!(e.getInventory() instanceof GrindstoneInventory)) return;
        if (items.isCropAccelerator(e.getOldCursor())) {
            e.setCancelled(true);
        }
    }

    private static String stripColors(String s) {
        if (s == null) return "";
        return ChatColor.stripColor(s).trim();
    }

    private static String displayNameStripped(ItemStack it) {
        if (it == null) return "";
        ItemMeta m = it.getItemMeta();
        if (m == null || !m.hasDisplayName()) return "";
        return stripColors(m.getDisplayName());
    }

    private static boolean isRenameAttempt(AnvilInventory inv, ItemStack baseItem) {
        String renameText = inv.getRenameText();
        if (renameText == null) return false;
        String typed = renameText.trim();
        if (typed.isBlank()) return false;
        String original = displayNameStripped(baseItem);
        return !stripColors(typed).equalsIgnoreCase(original);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);

        Integer l1 = items.getAcceleratorLevel(left);
        Integer l2 = items.getAcceleratorLevel(right);
        if (l1 != null && l2 != null && Objects.equals(l1, l2)) {
            if (l1 < 3) {
                String nextId = items.getAcceleratorItemIdByLevel(l1 + 1);
                if (nextId != null) {
                    e.setResult(items.create(nextId));
                    return;
                }
            }
            e.setResult(null);
            return;
        }

        if (l1 != null && isRenameAttempt(inv, left)) {
            e.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilTake(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory)) return;
        AnvilInventory inv = (AnvilInventory) e.getInventory();
        if (e.getRawSlot() != 2) return;
        ItemStack out = inv.getItem(2);
        if (!items.isCropAccelerator(out)) return;

        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        Integer l1 = items.getAcceleratorLevel(left);
        Integer l2 = items.getAcceleratorLevel(right);

        if (l1 != null && l2 != null && Objects.equals(l1, l2) && l1 < 3) {
            if (!(e.getWhoClicked() instanceof Player p)) {
                e.setCancelled(true);
                return;
            }

            ItemStack result = out.clone();
            e.setCancelled(true);

            if (!e.isShiftClick()) {
                ItemStack cursor = e.getCursor();
                if (cursor != null && cursor.getType() != Material.AIR) return;
                e.setCursor(result);
            } else {
                PlayerInventory pi = p.getInventory();
                var leftovers = pi.addItem(result);
                if (!leftovers.isEmpty()) return;
            }

            consumeOne(inv, 0);
            consumeOne(inv, 1);
            inv.setItem(2, null);
            p.updateInventory();
            return;
        }

        if (l1 != null && isRenameAttempt(inv, left)) {
            e.setCancelled(true);
        }
    }

    private static void consumeOne(AnvilInventory inv, int slot) {
        ItemStack it = inv.getItem(slot);
        if (it == null || it.getType() == Material.AIR) return;
        int amt = it.getAmount();
        if (amt <= 1) inv.setItem(slot, null);
        else {
            it.setAmount(amt - 1);
            inv.setItem(slot, it);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent e) {
        if (!(e.getNewState().getBlockData() instanceof Ageable)) return;

        Block block = e.getBlock();
        ItemsManager.AcceleratorStats best = getBestAccelerator(block);
        if (best == null) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> applyExtraGrowth(block, best.growthBonus));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent e) {
        updatePlayerAccelerator(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(PlayerQuitEvent e) {
        playerAccelerators.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) {
            updatePlayerAcceleratorLater(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent e) {
        updatePlayerAcceleratorLater(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPickupItem(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) {
            updatePlayerAcceleratorLater(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent e) {
        updatePlayerAcceleratorLater(e.getPlayer());
    }

    private void updatePlayerAcceleratorLater(Player p) {
        if (p == null) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> updatePlayerAccelerator(p));
    }

    private void updatePlayerAccelerator(Player p) {
        if (p == null) return;
        ItemsManager.AcceleratorStats st = getBestFromInventory(p);
        if (st != null) {
            playerAccelerators.put(p.getUniqueId(), st);
        } else {
            playerAccelerators.remove(p.getUniqueId());
        }
    }

    private ItemsManager.AcceleratorStats getBestAccelerator(Block block) {
        ItemsManager.AcceleratorStats best = null;
        for (Player player : block.getWorld().getPlayers()) {
            ItemsManager.AcceleratorStats st = playerAccelerators.get(player.getUniqueId());
            if (st == null) continue;
            double distSq = player.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5));
            if (distSq > (double) st.radius * st.radius) continue;
            if (best == null || st.level > best.level) best = st;
        }
        return best;
    }

    private ItemsManager.AcceleratorStats getBestFromInventory(Player player) {
        ItemsManager.AcceleratorStats best = null;
        for (ItemStack item : player.getInventory().getContents()) {
            ItemsManager.AcceleratorStats st = items.getAcceleratorStatsByItem(item);
            if (st == null) continue;
            if (best == null || st.level > best.level) best = st;
        }
        return best;
    }

    private void applyExtraGrowth(Block block, double growthBonus) {
        if (!(block.getBlockData() instanceof Ageable ageable)) return;
        if (ageable.getAge() >= ageable.getMaximumAge()) return;

        int extraSteps = 0;
        if (growthBonus >= 1.0D) {
            extraSteps = 1;
        } else if (Math.random() < growthBonus) {
            extraSteps = 1;
        }

        if (extraSteps <= 0) return;

        int newAge = Math.min(ageable.getMaximumAge(), ageable.getAge() + extraSteps);
        ageable.setAge(newAge);
        block.setBlockData(ageable, true);
    }
}
