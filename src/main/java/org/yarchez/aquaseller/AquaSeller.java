package org.yarchez.aquaseller;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.yarchez.aquaseller.gui.SellerGui;
import org.yarchez.aquaseller.gui.ProfessionProgressGui;
import org.yarchez.aquaseller.util.ConfigManager;
import org.yarchez.aquaseller.util.DataStore;
import org.yarchez.aquaseller.util.ItemsManager;
import org.yarchez.aquaseller.util.CustomFurnaceListener;
import org.yarchez.aquaseller.util.AquaSellerAdminCommand;
import org.yarchez.aquaseller.util.AquaSellerPlaceholders;
import org.yarchez.aquaseller.util.CropAcceleratorListener;
import org.yarchez.aquaseller.util.TopCache;
import org.yarchez.aquaseller.util.NpcManager;
import org.yarchez.aquaseller.util.KillerProgressListener;

/**
 * Main plugin class.
 *
 * Features:
 * - GUI for selling items
 * - Auto-sell task every 100 ticks (~5s)
 * - Multipliers & battle pass bonus (bonus_battlepass from config)
 * - /seller command and /seller reload
 *
 * NOTE about battle pass bonus:
 * Players with permission "battlepass.premium" sell items more expensive.
 * The bonus percent is read from config 'bonus_battlepass' and interpreted as
 * (1 + bonus_battlepass/100). We always read the config when we calculate a price,
 * so reloading the plugin (via /seller reload) immediately applies the new coefficient.
 */
public class AquaSeller extends JavaPlugin implements TabExecutor, Listener {

    private static AquaSeller instance;

    private Economy economy;
    private ConfigManager configManager;
    private DataStore dataStore;
    private ItemsManager itemsManager;
    private SellerGui sellerGui;
    private ProfessionProgressGui professionProgressGui;
    private TopCache topCache;
    private NpcManager npcManager;
    private final java.util.Map<String, String> categoryCommands = new java.util.LinkedHashMap<>();

    private CustomFurnaceListener customFurnaceListener;
    private CropAcceleratorListener cropAcceleratorListener;
    private KillerProgressListener killerProgressListener;

    private org.bukkit.scheduler.BukkitTask autosellTask;

    public static AquaSeller get() {
        return instance;
    }

    public Economy economy() {
        return economy;
    }

    public ConfigManager cfg() {
        return configManager;
    }

    public DataStore data() {
        return dataStore;
    }

    public ItemsManager items() {
        return itemsManager;
    }

    public SellerGui sellerGui() {
        return sellerGui;
    }

    public ProfessionProgressGui progressGui() { return professionProgressGui; }
    public TopCache topCache() { return topCache; }
    public NpcManager npcManager() { return npcManager; }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResource("items.yml", false);

        configManager = new ConfigManager(this);
        dataStore = new DataStore(this);
        itemsManager = new ItemsManager(this);
        sellerGui = new SellerGui(this);
        professionProgressGui = new ProfessionProgressGui(this);
        topCache = new TopCache(this);
        npcManager = new NpcManager(this);

        if (!setupEconomy()) {
            getLogger().warning("[AquaSeller] Vault economy was not found. Money rewards will be disabled.");
        }

        // register events
        Bukkit.getPluginManager().registerEvents(sellerGui, this);
        Bukkit.getPluginManager().registerEvents(professionProgressGui, this);

        customFurnaceListener = new CustomFurnaceListener(this);
        Bukkit.getPluginManager().registerEvents(customFurnaceListener, this);

        cropAcceleratorListener = new CropAcceleratorListener(this);
        Bukkit.getPluginManager().registerEvents(cropAcceleratorListener, this);

        killerProgressListener = new KillerProgressListener(this);
        Bukkit.getPluginManager().registerEvents(killerProgressListener, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        for (Player player : Bukkit.getOnlinePlayers()) {
            dataStore.rememberPlayerName(player.getUniqueId(), player.getName());
        }
        dataStore.backfillRememberedNamesFromSections();

        topCache.refresh();
        try { npcManager.refreshAll(); } catch (Throwable ignored) {}

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                topCache.refresh();
                npcManager.refreshAll();
            } catch (Throwable t) {
                getLogger().warning("[AquaSeller] top/NPC refresh: " + t.getMessage());
            }
        }, TopCache.REFRESH_TICKS, TopCache.REFRESH_TICKS);

        // repeating autosell task
        autosellTask = Bukkit.getScheduler().runTaskTimer(
                this,
                () -> {
                    for (Player pl : Bukkit.getOnlinePlayers()) {
                        sellerGui.autoSellTickFor(pl);
                    }
                },
                100L,
                100L
        );

        if (getCommand("seller") != null) {
            getCommand("seller").setExecutor(this);
            getCommand("seller").setTabCompleter(this);
        }
        registerCategoryCommands();

        if (getCommand("aquaseller") != null) {
            AquaSellerAdminCommand adminCmd = new AquaSellerAdminCommand(this);
            getCommand("aquaseller").setExecutor(adminCmd);
            getCommand("aquaseller").setTabCompleter(adminCmd);
        }

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new AquaSellerPlaceholders(this).register();
                getLogger().info("[AquaSeller] PlaceholderAPI expansion registered: %aquaseller_*%.");
            } catch (Throwable t) {
                getLogger().warning("[AquaSeller] Could not register PlaceholderAPI expansion: " + t.getMessage());
            }
        }

        getLogger().info("[AquaSeller] enabled.");
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        dataStore.rememberPlayerName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    private String resolveCategoryForCommand(String typedCommand) {
        if (typedCommand == null || typedCommand.isBlank()) return null;
        String typed = typedCommand.trim().toLowerCase(java.util.Locale.ROOT);

        // First: current in-memory registered commands.
        String mapped = categoryCommands.get(typed);
        if (mapped != null) return mapped;

        org.bukkit.configuration.ConfigurationSection categories = cfg().get().getConfigurationSection("inv_setting.categories");
        if (categories == null) return null;

        // Exact key from config wins.
        for (String rawId : categories.getKeys(false)) {
            if (rawId != null && rawId.equalsIgnoreCase(typed)) return rawId;
        }

        // Compatibility aliases only if the real configured key exists.
        if (typed.equals("shahter")) {
            for (String rawId : categories.getKeys(false)) {
                if (rawId != null && rawId.equalsIgnoreCase("rudy")) return rawId;
            }
        }
        if (typed.equals("rudy")) {
            for (String rawId : categories.getKeys(false)) {
                if (rawId != null && rawId.equalsIgnoreCase("shahter")) return rawId;
            }
        }
        return null;
    }



    private void registerCategoryCommands() {
        categoryCommands.clear();

        org.bukkit.configuration.ConfigurationSection categories = cfg().get().getConfigurationSection("inv_setting.categories");
        if (categories == null) return;

        for (String rawId : categories.getKeys(false)) {
            if (rawId == null || rawId.isBlank()) continue;
            String categoryId = rawId.trim();
            String commandName = categoryId.toLowerCase(java.util.Locale.ROOT);
            registerSingleCategoryCommand(commandName, categoryId);

            // Compatibility aliases only when the configured key is the legacy one.
            if (commandName.equals("rudy")) {
                registerSingleCategoryCommand("shahter", categoryId);
            }
        }
    }

    private void registerSingleCategoryCommand(String commandName, String categoryId) {
        categoryCommands.put(commandName.toLowerCase(java.util.Locale.ROOT), categoryId);
        PluginCommand existing = getCommand(commandName);
        if (existing != null) {
            existing.setExecutor(this);
            existing.setTabCompleter(this);
            return;
        }
        try {
            java.lang.reflect.Field commandMapField = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            commandMapField.setAccessible(true);
            Object mapObj = commandMapField.get(Bukkit.getServer());
            if (!(mapObj instanceof SimpleCommandMap commandMap)) return;

            org.bukkit.command.defaults.BukkitCommand dynamic = new org.bukkit.command.defaults.BukkitCommand(commandName) {
                @Override
                public boolean execute(CommandSender sender, String label, String[] args) {
                    return AquaSeller.this.onCommand(sender, this, label, args);
                }

                @Override
                public java.util.List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                    java.util.List<String> out = AquaSeller.this.onTabComplete(sender, this, alias, args);
                    return out != null ? out : java.util.Collections.emptyList();
                }
            };
            dynamic.setDescription("Открыть меню категории " + categoryId);
            dynamic.setUsage("/" + commandName);
            dynamic.setPermission("aquaseller.use");
            commandMap.register(getDescription().getName().toLowerCase(java.util.Locale.ROOT), dynamic);
        } catch (Throwable t) {
            getLogger().warning("[AquaSeller] Could not register dynamic command /" + commandName + ": " + t.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (autosellTask != null) {
            autosellTask.cancel();
        }
        // NPC не удаляем при выключении плагина/сервера.
        // Они должны оставаться в Citizens и при следующем запуске
        // быть переиспользованы, а не создаваться заново.
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
        getLogger().info("[AquaSeller] disabled.");
    }


    @EventHandler(ignoreCancelled = true)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage();
        if (message == null || message.length() < 2 || message.charAt(0) != '/') return;

        String withoutSlash = message.substring(1).trim();
        if (withoutSlash.isEmpty()) return;

        String[] split = withoutSlash.split("\\s+");
        if (split.length == 0) return;
        String typed = split[0].toLowerCase(java.util.Locale.ROOT);

        String categoryId = resolveCategoryForCommand(typed);
        if (categoryId == null) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("aquaseller.use")) return;

        event.setCancelled(true);
        if (!sellerGui.openCategory(player, categoryId)) {
            player.sendMessage("§cКатегория не найдена: §f" + categoryId);
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    // --------------------------------------------------------------------
    // /seller command
    // --------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        String commandName = cmd.getName().toLowerCase(java.util.Locale.ROOT);
        String resolvedCategoryForCommand = resolveCategoryForCommand(commandName);
        if (!(commandName.equals("seller") || resolvedCategoryForCommand != null)) {
            return false;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("Команда только для игроков.");
            return true;
        }

        Player p = (Player) sender;

        if (!p.hasPermission("aquaseller.use")) {
            p.sendMessage("§cНет прав.");
            return true;
        }

        String categoryId = resolvedCategoryForCommand;
        if (categoryId != null) {
            if (!sellerGui.openCategory(p, categoryId)) p.sendMessage("§cКатегория не найдена: §f" + categoryId);
            return true;
        }

        // /seller reload
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!p.hasPermission("aquaseller.reload")) {
                p.sendMessage("§cНет прав.");
                return true;
            }

            // reload Bukkit config, then re-read into our managers
            reloadConfig();
            configManager.ensure();
            itemsManager.ensure();
            dataStore.ensure(); // re-open data file just in case
            if (npcManager != null) {
                npcManager.reload();
            }
            topCache.refresh();
            sellerGui.reload();
            registerCategoryCommands();
            try {
                npcManager.refreshAll();
            } catch (Throwable t) {
                getLogger().warning("[AquaSeller] NPC refresh on reload failed: " + t.getMessage());
            }

            String msg = ChatColor.translateAlternateColorCodes('&',
                    cfg().get().getString("messages.reload_success",
                            "§aКонфигурация и данные перезагружены."));
            p.sendMessage(msg);
            return true;
        }

        // normal open
        sellerGui.openMain(p);
        return true;
    }

    @Override
    public java.util.List<String> onTabComplete(
            CommandSender sender,
            Command cmd,
            String alias,
            String[] args
    ) {
        if (!cmd.getName().equalsIgnoreCase("seller")) return java.util.Collections.emptyList();
        if (args.length == 1 && sender.hasPermission("aquaseller.reload")) {
            return java.util.Collections.singletonList("reload");
        }
        return java.util.Collections.emptyList();
    }
}