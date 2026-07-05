package org.yarchez.aquaseller.util;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.yarchez.aquaseller.AquaSeller;

import java.util.*;

public class AquaSellerAdminCommand implements TabExecutor {
    private final AquaSeller plugin;
    public AquaSellerAdminCommand(AquaSeller plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("aquaseller")) return false;
        if (args.length == 0) { sendHelp(sender, label); return true; }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> handleGive(sender, label, args);
            case "top" -> handleTop(sender, label, args);
            case "progress" -> handleProgress(sender, label, args);
            case "setnpcplase", "setnpcplace" -> handleSetNpcPlace(sender, label, args);
            case "setlevel" -> handleSetLevel(sender, label, args);
            case "addprogress" -> handleAddProgress(sender, label, args);
            default -> { sendHelp(sender, label); yield true; }
        };
    }

    private boolean handleGive(CommandSender sender, String label, String[] args) {
        boolean allowed = (sender instanceof ConsoleCommandSender) || sender.isOp() || sender.hasPermission("aquaseller.admin");
        if (!allowed) { sender.sendMessage("§cНет прав."); return true; }
        if (args.length < 2) { sender.sendMessage("§cИспользование: /" + label + " give <itemId> [player]"); return true; }
        String itemId = args[1];
        if (!plugin.items().exists(itemId)) {
            sender.sendMessage("§cНеизвестный itemId: §f" + itemId);
            sender.sendMessage("§7Доступные: §f" + String.join(", ", plugin.items().itemIds()));
            return true;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) { sender.sendMessage("§cИгрок не найден: §f" + args[2]); return true; }
        } else {
            if (!(sender instanceof Player p)) { sender.sendMessage("§cУкажите игрока: /" + label + " give <itemId> <player>"); return true; }
            target = p;
        }
        ItemStack item = plugin.items().create(itemId);
        if (item == null) { sender.sendMessage("§cНе удалось создать предмет: §f" + itemId); return true; }
        Map<Integer, ItemStack> left = target.getInventory().addItem(item);
        if (!left.isEmpty()) left.values().forEach(it -> target.getWorld().dropItemNaturally(target.getLocation(), it));
        sender.sendMessage("§aВыдано: §f" + itemId + " §7-> §e" + target.getName());
        if (sender != target) target.sendMessage("§aВам выдан предмет: §f" + itemId);
        return true;
    }

    private boolean handleTop(CommandSender sender, String label, String[] args) {
        if (args.length < 2) { sender.sendMessage("§cИспользование: /" + label + " top <rudy|fermer|killer|fishman|topseller>"); return true; }
        String profession = normalizeProfession(args[1]);
        if (profession == null) { sender.sendMessage("§cПрофессия должна быть: rudy, fermer, killer, fishman или topseller."); return true; }
        Map<UUID, Long> top = plugin.topCache().getTop(profession, 10);
        sender.sendMessage(color("&6Топ AquaSeller: &e" + nice(profession) + " &7(обновление раз в 5 минут)"));
        if (top.isEmpty()) { sender.sendMessage("§7Пока данных нет."); return true; }
        int i = 1;
        for (Map.Entry<UUID, Long> e : top.entrySet()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            String name = op.getName() != null ? op.getName() : e.getKey().toString();
            if (profession.equals("overall")) sender.sendMessage(color("&e#" + i + " &f" + name + " &7- &a" + e.getValue()));
            else sender.sendMessage(color("&e#" + i + " &f" + name + " &7- &a" + e.getValue() + " &8[&6" + roman(plugin.data().getProfessionLevel(profession, e.getKey())) + "&8]"));
            i++;
        }
        return true;
    }

    private boolean handleProgress(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player viewer)) { sender.sendMessage("§cЭта команда только для игроков."); return true; }
        if (args.length < 2) { sender.sendMessage("§cИспользование: /" + label + " progress <rudy|fermer|killer|fishman> [player]"); return true; }
        String profession = normalizeProfession(args[1]);
        if (profession == null || profession.equals("overall")) { sender.sendMessage("§cПрофессия должна быть: rudy, fermer, killer или fishman."); return true; }
        OfflinePlayer target = viewer;
        if (args.length >= 3) {
            boolean allowed = viewer.isOp() || viewer.hasPermission("aquaseller.admin");
            if (!allowed) { viewer.sendMessage("§cЧужой прогресс могут открывать только OP/админ."); return true; }
            Player online = Bukkit.getPlayerExact(args[2]);
            target = online != null ? online : Bukkit.getOfflinePlayer(args[2]);
        }
        plugin.progressGui().open(viewer, profession, target);
        return true;
    }

    private boolean handleSetNpcPlace(CommandSender sender, String label, String[] args) {
        boolean allowed = (sender instanceof ConsoleCommandSender) || sender.isOp() || sender.hasPermission("aquaseller.admin");
        if (!allowed) { sender.sendMessage("§cНет прав."); return true; }
        if (!(sender instanceof Player p)) { sender.sendMessage("§cКоманда только для игрока (нужна точка установки)." ); return true; }
        if (args.length < 2) { sender.sendMessage("§cИспользование: /" + label + " setnpcplase <mining|fermer|killer|fisher|topseller>"); return true; }
        String kind = args[1].toLowerCase(Locale.ROOT);
        if (!plugin.npcManager().savePlacement(kind, p.getLocation())) { sender.sendMessage("§cНеизвестный тип NPC."); return true; }
        try { plugin.npcManager().refreshNpc(kind); } catch (Throwable t) { sender.sendMessage("§eТочка сохранена, но NPC не обновлён: " + t.getMessage()); return true; }
        sender.sendMessage("§aNPC точка сохранена: §f" + kind);
        return true;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage("§eAquaSeller:");
        sender.sendMessage("§f/" + label + " give <itemId> [player]");
        sender.sendMessage("§f/" + label + " top <rudy|fermer|killer|fishman|topseller>");
        sender.sendMessage("§f/" + label + " progress <rudy|fermer|killer|fishman> [player]");
        sender.sendMessage("§f/" + label + " setnpcplase <mining|fermer|killer|fisher|topseller>");
        boolean admin = (sender instanceof ConsoleCommandSender) || sender.isOp() || sender.hasPermission("aquaseller.admin");
        if (admin) {
            sender.sendMessage("§f/" + label + " setlevel <player> <rudy|fermer|killer|fishman> <level>");
            sender.sendMessage("§f/" + label + " addprogress <player> <rudy|fermer|killer|fishman> <amount>");
        }
    }

    private boolean handleSetLevel(CommandSender sender, String label, String[] args) {
        boolean allowed = (sender instanceof ConsoleCommandSender) || sender.isOp() || sender.hasPermission("aquaseller.admin");
        if (!allowed) { sender.sendMessage("§cНет прав."); return true; }
        if (args.length < 4) { sender.sendMessage("§cИспользование: /" + label + " setlevel <player> <profession> <level>"); return true; }
        
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) { sender.sendMessage("§cИгрок никогда не играл на сервере."); return true; }
        
        String profession = resolveProfession(args[2]);
        if (profession == null) { sender.sendMessage("§cНеверная профессия. Доступные: rudy, fermer, killer, fishman"); return true; }
        
        int level;
        try {
            level = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cУровень должен быть числом от 1 до 4.");
            return true;
        }
        
        if (level < 1 || level > 4) {
            sender.sendMessage("§cУровень должен быть от 1 до 4.");
            return true;
        }
        
        plugin.data().setProfessionLevel(profession, target.getUniqueId(), level);
        sender.sendMessage("§aИгроку §e" + target.getName() + " §aустановлен уровень §6" + roman(level) + " §aдля профессии §e" + nice(profession));
        
        if (target.isOnline()) {
            Player p = target.getPlayer();
            if (p != null) {
                p.sendMessage("§aВам установлен уровень §6" + roman(level) + " §aдля профессии §e" + nice(profession));
                try {
                    plugin.sellerGui().refreshOpenProfessionMenu(profession, p);
                } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    private boolean handleAddProgress(CommandSender sender, String label, String[] args) {
        boolean allowed = (sender instanceof ConsoleCommandSender) || sender.isOp() || sender.hasPermission("aquaseller.admin");
        if (!allowed) { sender.sendMessage("§cНет прав."); return true; }
        if (args.length < 4) { sender.sendMessage("§cИспользование: /" + label + " addprogress <player> <profession> <amount>"); return true; }
        
        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) { sender.sendMessage("§cИгрок никогда не играл на сервере."); return true; }
        
        String profession = resolveProfession(args[2]);
        if (profession == null) { sender.sendMessage("§cНеверная профессия. Доступные: rudy, fermer, killer, fishman"); return true; }
        
        long amount;
        try {
            amount = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cКоличество должно быть числом.");
            return true;
        }
        
        if (amount < 0) {
            sender.sendMessage("§cКоличество должно быть положительным числом.");
            return true;
        }
        
        UUID uuid = target.getUniqueId();
        int curLevel = plugin.data().getProfessionLevel(profession, uuid);
        
        // Add progress per current tier
        plugin.data().addProfessionProgress(profession, uuid, curLevel, amount);
        
        // Add progress to total_sold
        long total = plugin.data().addProfessionTotalSold(profession, uuid, amount);
        
        sender.sendMessage("§aДобавлено §e" + amount + " §aпрогресса игроку §e" + target.getName() + " §aв профессию §e" + nice(profession) + "§a. Текущий прогресс: §6" + total);
        
        // Check for level ups
        int newLevel = curLevel;
        if (curLevel == 1) {
            long need = plugin.cfg().get().getLong("profession." + (profession.equals("rudy") ? "rudy" : profession) + ".to_level.2", 5000L);
            if (total >= need) newLevel = 2;
        }
        if (curLevel <= 2 && newLevel >= 2) {
            long need = plugin.cfg().get().getLong("profession." + (profession.equals("rudy") ? "rudy" : profession) + ".to_level.3", 15000L);
            if (total >= need) newLevel = 3;
        }
        if (curLevel <= 3 && newLevel >= 3) {
            long need = plugin.cfg().get().getLong("profession." + (profession.equals("rudy") ? "rudy" : profession) + ".to_level.4", 30000L);
            if (total >= need) newLevel = 4;
        }
        
        if (newLevel > curLevel) {
            plugin.data().setProfessionLevel(profession, uuid, newLevel);
            if (target.isOnline()) {
                Player p = target.getPlayer();
                if (p != null) {
                    p.sendMessage(ChatColor.translateAlternateColorCodes('&', 
                        plugin.cfg().get().getString("profession." + (profession.equals("rudy") ? "rudy" : profession) + ".level_up." + newLevel + ".player_message", 
                        "&aВы прокачали профессию &6" + nice(profession) + "&a до уровня &6" + roman(newLevel) + "&a!")));
                    try {
                        String sName = plugin.cfg().get().getString("profession." + (profession.equals("rudy") ? "rudy" : profession) + ".level_up_sound.sound", "UI_TOAST_CHALLENGE_COMPLETE");
                        p.playSound(p.getLocation(), org.bukkit.Sound.valueOf(sName), 1.0f, 1.0f);
                    } catch (Throwable ignored) {}
                }
            }
        }
        
        if (target.isOnline()) {
            Player p = target.getPlayer();
            if (p != null) {
                try {
                    plugin.sellerGui().refreshOpenProfessionMenu(profession, p);
                } catch (Throwable ignored) {}
            }
        }
        return true;
    }

    private String resolveProfession(String raw) {
        if (raw == null) return null;
        String s = raw.toLowerCase(Locale.ROOT).trim();
        if (s.equals("rudy") || s.equals("shahter") || s.equals("miner") || s.equals("mining")) return "rudy";
        if (s.equals("fermer") || s.equals("farmer") || s.equals("farm")) return "fermer";
        if (s.equals("killer") || s.equals("hunter")) return "killer";
        if (s.equals("fishman") || s.equals("fisher") || s.equals("fish") || s.equals("fishing")) return "fishman";
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("aquaseller")) return Collections.emptyList();
        boolean admin = (sender instanceof ConsoleCommandSender) || sender.isOp() || sender.hasPermission("aquaseller.admin");
        if (args.length == 1) {
            List<String> list = new ArrayList<>(Arrays.asList("top", "progress"));
            if (admin) { list.add("give"); list.add("setnpcplase"); list.add("setlevel"); list.add("addprogress"); }
            return prefix(list, args[0]);
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give")) return admin ? prefix(new ArrayList<>(plugin.items().itemIds()), args[1]) : Collections.emptyList();
            if (args[0].equalsIgnoreCase("top") || args[0].equalsIgnoreCase("progress")) return prefix(Arrays.asList("rudy","fermer","killer","fishman","fisher","topseller"), args[1]);
            if (args[0].equalsIgnoreCase("setnpcplase") || args[0].equalsIgnoreCase("setnpcplace")) return admin ? prefix(Arrays.asList("mining","fermer","killer","fisher","topseller"), args[1]) : Collections.emptyList();
            if (args[0].equalsIgnoreCase("setlevel") || args[0].equalsIgnoreCase("addprogress")) return admin ? onlineNames(args[1]) : Collections.emptyList();
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("progress")) return admin ? onlineNames(args[2]) : Collections.emptyList();
            if (args[0].equalsIgnoreCase("setlevel") || args[0].equalsIgnoreCase("addprogress")) return admin ? prefix(Arrays.asList("rudy","fermer","killer","fishman"), args[2]) : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private List<String> onlineNames(String start) { List<String> names = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName()); return prefix(names, start); }
    private static List<String> prefix(List<String> list, String p) { if (p == null || p.isEmpty()) return list; String lower = p.toLowerCase(Locale.ROOT); List<String> out = new ArrayList<>(); for (String s : list) if (s.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(s); out.sort(String::compareToIgnoreCase); return out; }
    private static String normalizeProfession(String raw) { return TopCache.normalizeProfession(raw); }
    private static String nice(String prof) { return switch (prof) { case "rudy" -> "ШАХТЁР"; case "fermer" -> "ФЕРМЕР"; case "killer" -> "ОХОТНИК"; case "fishman" -> "РЫБАК"; case "overall" -> "ПРОДАВЕЦ"; default -> prof; }; }
    private static String roman(int level) { return switch (level) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; default -> String.valueOf(level); }; }
    private static String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
