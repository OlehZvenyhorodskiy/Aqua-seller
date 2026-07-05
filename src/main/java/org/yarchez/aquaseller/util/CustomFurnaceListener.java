package org.yarchez.aquaseller.util;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.yarchez.aquaseller.AquaSeller;

import java.util.Objects;

/**
 * Custom miners furnaces + hunter smokers.
 *
 * Miner furnace bonuses now apply only to ore smelting.
 * Food inside a miner furnace behaves like vanilla.
 */
public class CustomFurnaceListener implements Listener {

    private final AquaSeller plugin;
    private final ItemsManager items;

    private final NamespacedKey blockFurnaceLevelKey;
    private final NamespacedKey blockSmokerLevelKey;

    public CustomFurnaceListener(AquaSeller plugin) {
        this.plugin = plugin;
        this.items = plugin.items();
        this.blockFurnaceLevelKey = new NamespacedKey(plugin, "furnace_level_block");
        this.blockSmokerLevelKey = new NamespacedKey(plugin, "smoker_level_block");
    }

    private enum CustomCookingType {
        FURNACE,
        SMOKER
    }

    private static final java.util.Set<Material> MINER_ORE_INPUTS = java.util.EnumSet.of(
            Material.IRON_ORE,
            Material.DEEPSLATE_IRON_ORE,
            Material.RAW_IRON,
            Material.GOLD_ORE,
            Material.DEEPSLATE_GOLD_ORE,
            Material.NETHER_GOLD_ORE,
            Material.RAW_GOLD,
            Material.COPPER_ORE,
            Material.DEEPSLATE_COPPER_ORE,
            Material.RAW_COPPER,
            Material.NETHER_QUARTZ_ORE,
            Material.ANCIENT_DEBRIS
    );

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        ItemStack inHand = e.getItemInHand();
        Integer furnaceLevel = items.getFurnaceLevel(inHand);
        Integer smokerLevel = items.getSmokerLevel(inHand);
        if (furnaceLevel == null && smokerLevel == null) return;

        Block b = e.getBlockPlaced();
        BlockState st = b.getState();
        if (!(st instanceof TileState ts)) return;

        if (furnaceLevel != null && b.getType() == Material.FURNACE) {
            ts.getPersistentDataContainer().set(blockFurnaceLevelKey, PersistentDataType.INTEGER, furnaceLevel);
            ts.update(true, false);
            return;
        }

        if (smokerLevel != null && b.getType() == Material.SMOKER) {
            ts.getPersistentDataContainer().set(blockSmokerLevelKey, PersistentDataType.INTEGER, smokerLevel);
            ts.update(true, false);
        }
    }

    private Integer getLevelFromBlock(BlockState st, CustomCookingType type) {
        if (!(st instanceof TileState ts)) return null;
        NamespacedKey key = (type == CustomCookingType.FURNACE) ? blockFurnaceLevelKey : blockSmokerLevelKey;
        return ts.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
    }

    private ItemsManager.FurnaceStats getStats(BlockState st, CustomCookingType type) {
        Integer lvl = getLevelFromBlock(st, type);
        if (lvl == null) return null;
        return (type == CustomCookingType.FURNACE) ? items.getFurnaceStats(lvl) : items.getSmokerStats(lvl);
    }

    private CustomCookingType getCustomType(BlockState st) {
        Material type = st.getType();
        if (type == Material.FURNACE && getLevelFromBlock(st, CustomCookingType.FURNACE) != null) {
            return CustomCookingType.FURNACE;
        }
        if (type == Material.SMOKER && getLevelFromBlock(st, CustomCookingType.SMOKER) != null) {
            return CustomCookingType.SMOKER;
        }
        return null;
    }

    private boolean shouldApplyCustomBonus(CustomCookingType type, ItemStack source, ItemStack result) {
        if (type == null) return false;
        if (type == CustomCookingType.SMOKER) {
            return true;
        }
        return isMinerOreRecipe(source, result);
    }

    private boolean isMinerOreRecipe(ItemStack source, ItemStack result) {
        if (source == null || source.getType() == Material.AIR) return false;
        Material src = source.getType();
        if (MINER_ORE_INPUTS.contains(src)) return true;
        if (src.name().endsWith("_ORE")) return true;
        if (src.name().startsWith("RAW_")) return true;
        if (result != null && result.getType() == Material.NETHERITE_SCRAP && src == Material.ANCIENT_DEBRIS) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(FurnaceBurnEvent e) {
        CustomCookingType type = getCustomType(e.getBlock().getState());
        if (type == null) return;

        ItemStack source = null;
        BlockState state = e.getBlock().getState();
        if (state instanceof org.bukkit.block.Furnace furnace) {
            source = furnace.getInventory().getSmelting();
        }
        if (!shouldApplyCustomBonus(type, source, null)) return;

        ItemsManager.FurnaceStats stats = getStats(e.getBlock().getState(), type);
        if (stats == null) return;

        double m = stats.fuelMultiplier;
        if (m <= 1.0) return;

        int burn = e.getBurnTime();
        int newBurn = (int) Math.max(1, Math.floor(burn / m));
        e.setBurnTime(newBurn);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStartSmelt(FurnaceStartSmeltEvent e) {
        CustomCookingType type = getCustomType(e.getBlock().getState());
        if (!shouldApplyCustomBonus(type, e.getSource(), null)) return;
        ItemsManager.FurnaceStats stats = getStats(e.getBlock().getState(), type);
        if (stats == null) return;

        double m = stats.speedMultiplier;
        if (m <= 1.0) return;

        int total = e.getTotalCookTime();
        int newTotal = (int) Math.max(1, Math.floor(total / m));
        e.setTotalCookTime(newTotal);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSmelt(FurnaceSmeltEvent e) {
        CustomCookingType type = getCustomType(e.getBlock().getState());
        if (!shouldApplyCustomBonus(type, e.getSource(), e.getResult())) return;
        ItemsManager.FurnaceStats stats = getStats(e.getBlock().getState(), type);
        if (stats == null) return;

        double m = stats.outputMultiplier;
        if (m <= 1.0) return;

        ItemStack result = e.getResult();
        if (result == null || result.getType() == Material.AIR) return;

        int base = Math.max(1, result.getAmount());
        int multiplied = (int) Math.max(1, Math.floor(base * m));
        int maxStack = result.getMaxStackSize();
        result.setAmount(Math.min(maxStack, multiplied));
        e.setResult(result);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrindstoneClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof GrindstoneInventory)) return;
        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();

        if (items.isCustomCookingBlock(current) || items.isCustomCookingBlock(cursor)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGrindstoneDrag(InventoryDragEvent e) {
        if (!(e.getInventory() instanceof GrindstoneInventory)) return;
        ItemStack cursor = e.getOldCursor();
        if (items.isCustomCookingBlock(cursor)) {
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

    private CustomCookingType getItemType(ItemStack item) {
        if (items.getFurnaceLevel(item) != null) return CustomCookingType.FURNACE;
        if (items.getSmokerLevel(item) != null) return CustomCookingType.SMOKER;
        return null;
    }

    private Integer getItemLevel(ItemStack item, CustomCookingType type) {
        if (type == CustomCookingType.FURNACE) return items.getFurnaceLevel(item);
        if (type == CustomCookingType.SMOKER) return items.getSmokerLevel(item);
        return null;
    }

    private String getNextItemId(CustomCookingType type, int level) {
        if (type == CustomCookingType.FURNACE) return items.getFurnaceItemIdByLevel(level);
        if (type == CustomCookingType.SMOKER) return items.getSmokerItemIdByLevel(level);
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();

        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);

        CustomCookingType leftType = getItemType(left);
        CustomCookingType rightType = getItemType(right);
        Integer l1 = getItemLevel(left, leftType);
        Integer l2 = getItemLevel(right, rightType);

        if (leftType != null && rightType != null && leftType == rightType && Objects.equals(l1, l2)) {
            int lvl = l1;
            if (lvl < 3) {
                String nextId = getNextItemId(leftType, lvl + 1);
                if (nextId != null) {
                    e.setResult(items.create(nextId));
                    return;
                }
            }
            e.setResult(null);
            return;
        }

        if (leftType != null && isRenameAttempt(inv, left)) {
            e.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAnvilTake(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof AnvilInventory inv)) return;
        if (e.getRawSlot() != 2) return;

        ItemStack out = inv.getItem(2);
        if (!items.isCustomCookingBlock(out)) return;

        ItemStack left = inv.getItem(0);
        ItemStack right = inv.getItem(1);
        CustomCookingType leftType = getItemType(left);
        CustomCookingType rightType = getItemType(right);
        Integer l1 = getItemLevel(left, leftType);
        Integer l2 = getItemLevel(right, rightType);

        if (leftType != null && rightType != null && leftType == rightType && Objects.equals(l1, l2) && l1 < 3) {
            if (!(e.getWhoClicked() instanceof Player p)) {
                e.setCancelled(true);
                return;
            }

            ItemStack result = out.clone();
            e.setCancelled(true);

            boolean shift = e.isShiftClick();
            ItemStack cursor = e.getCursor();

            if (!shift) {
                if (cursor != null && cursor.getType() != Material.AIR) {
                    return;
                }
                e.setCursor(result);
            } else {
                PlayerInventory pi = p.getInventory();
                var leftovers = pi.addItem(result);
                if (!leftovers.isEmpty()) {
                    return;
                }
            }

            consumeOne(inv, 0);
            consumeOne(inv, 1);
            inv.setItem(2, null);
            p.updateInventory();
            return;
        }

        if (leftType != null && isRenameAttempt(inv, left)) {
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
}
