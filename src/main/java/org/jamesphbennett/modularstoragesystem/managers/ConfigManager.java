package org.jamesphbennett.modularstoragesystem.managers;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConfigManager {

    private final ModularStorageSystem plugin;
    private FileConfiguration config;
    private FileConfiguration recipesConfig;

    // Cached configuration values
    private int maxNetworkBlocks;
    private int maxNetworkCables;
    private int maxExporters;
    private int maxImporters;
    private int exportTickInterval;
    private Set<Material> blacklistedItems;
    private boolean requireUsePermission;
    private boolean requireCraftPermission;

    // Debug settings
    private boolean debugMode;

    // Recipe configuration values
    private boolean recipesEnabled;
    private boolean showUnlockMessages;

    // HARDCODED: All disks have 64 cells - no longer configurable
    private static final int HARDCODED_CELLS_PER_DISK = 64;

    // HARDCODED: Items per cell are tier-specific - no longer configurable
    // 1k = 127, 4k = 508, 16k = 2032, 64k = 8128

    public ConfigManager(ModularStorageSystem plugin) {
        this.plugin = plugin;
        loadConfig();
        loadRecipesConfig();
    }

    public void loadConfig() {
        // Save default config if it doesn't exist
        plugin.saveDefaultConfig();

        // Reload config from file
        plugin.reloadConfig();
        config = plugin.getConfig();

        // Load and cache all configuration values
        loadNetworkSettings();
        loadStorageSettings();
        loadBlacklistedItems();
        loadPermissionSettings();
        loadLoggingSettings();
        loadDebugSettings();

        plugin.getLogger().info("Configuration loaded successfully!");
        // Removed redundant configuration details - available in debug mode
    }

    private void loadRecipesConfig() {
        File recipesFile = new File(plugin.getDataFolder(), "recipes.yml");

        // Save default recipes config if it doesn't exist
        if (!recipesFile.exists()) {
            plugin.saveResource("recipes.yml", false);
        }

        recipesConfig = YamlConfiguration.loadConfiguration(recipesFile);

        // Load defaults from jar if available
        InputStream defConfigStream = plugin.getResource("recipes.yml");
        if (defConfigStream != null) {
            YamlConfiguration defConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defConfigStream));
            recipesConfig.setDefaults(defConfig);
        }

        // Load recipe settings
        loadRecipeSettings();

        plugin.getLogger().info("Recipes configuration loaded successfully!");
        plugin.getLogger().info("Recipes enabled: " + recipesEnabled);
        plugin.getLogger().info("Recipe unlock messages: " + showUnlockMessages);
    }

    private void loadRecipeSettings() {
        recipesEnabled = recipesConfig.getBoolean("settings.enabled", true);
        showUnlockMessages = recipesConfig.getBoolean("settings.show_unlock_messages", false);
    }

    private void loadNetworkSettings() {
        maxNetworkBlocks = config.getInt("network.max_blocks", 128);
        maxNetworkCables = config.getInt("network.max_cables", 800);
        maxExporters = config.getInt("network.max_exporters", 200);
        maxImporters = config.getInt("network.max_importers", 200);
        
        // Load tick interval (moved from storage section)
        if (config.contains("storage.import_export_tick_interval")) {
            exportTickInterval = config.getInt("storage.import_export_tick_interval", 20);
            plugin.getLogger().warning("Config setting 'storage.import_export_tick_interval' should be moved to 'network.import_export_tick_interval'");
        } else {
            exportTickInterval = config.getInt("network.import_export_tick_interval", 20); // Default 20 ticks = 1 second
        }
    }

    private void loadStorageSettings() {

        // Legacy support for old export_tick_interval in storage section
        if (config.contains("storage.export_tick_interval")) {
            plugin.getLogger().warning("Config setting 'storage.export_tick_interval' is deprecated. Please move to 'network.import_export_tick_interval'");
        }

        // NOTE: default_cells_per_disk config option is now IGNORED - hardcoded to 64
        if (config.contains("storage.default_cells_per_disk")) {
            plugin.getLogger().warning("Config option 'default_cells_per_disk' is deprecated and ignored. All disks now have " + HARDCODED_CELLS_PER_DISK + " cells.");
        }

        // NOTE: max_items_per_cell config option is now IGNORED - tier-specific values are hardcoded
        if (config.contains("storage.max_items_per_cell")) {
            plugin.getLogger().warning("Config option 'max_items_per_cell' is deprecated and ignored. Items per cell are now tier-specific and hardcoded.");
        }
    }

    private void loadBlacklistedItems() {
        blacklistedItems = new HashSet<>();
        List<String> blacklistedItemNames = config.getStringList("blacklisted_items");

        for (String itemName : blacklistedItemNames) {
            try {
                Material material = Material.valueOf(itemName.toUpperCase());
                blacklistedItems.add(material);
                plugin.getLogger().info("Blacklisted item: " + material.name());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid material in blacklist: " + itemName);
            }
        }

        plugin.getLogger().info("Loaded " + blacklistedItems.size() + " blacklisted items from config");
    }

    private void loadPermissionSettings() {
        requireUsePermission = config.getBoolean("permissions.require_use_permission", true);
        requireCraftPermission = config.getBoolean("permissions.require_craft_permission", true);
    }

    private void loadLoggingSettings() {
    }

    private void loadDebugSettings() {
        debugMode = config.getBoolean("debug.enabled", false);
    }

    // Getter methods for configuration values
    public int getMaxNetworkBlocks() {
        return maxNetworkBlocks;
    }

    public int getMaxNetworkCables() {
        return maxNetworkCables;
    }

    public int getMaxExporters() {
        return maxExporters;
    }

    public int getMaxImporters() {
        return maxImporters;
    }

    /*
      REMOVED: Items per cell are now tier-specific and hardcoded
      1k = 127, 4k = 508, 16k = 2032, 64k = 8128
      Use ItemManager.getItemsPerCellForTier(tier) instead
     */

    /**
     * HARDCODED: All storage disks have exactly 64 cells
     * This method now ignores any config value and always returns 64
     */
    public int getDefaultCellsPerDisk() {
        return HARDCODED_CELLS_PER_DISK;
    }

    /**
     * Get the tick interval for export operations
     */
    public int getExportTickInterval() {
        return exportTickInterval;
    }

    public boolean isItemBlacklisted(Material material) {
        return blacklistedItems.contains(material);
    }

    public boolean isRequireUsePermission() {
        return requireUsePermission;
    }

    public boolean isRequireCraftPermission() {
        return requireCraftPermission;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    // Recipe configuration getters
    public boolean areRecipesEnabled() {
        return recipesEnabled;
    }

    /**
     * Check if a specific recipe is enabled
     */
    public boolean isRecipeEnabled(String recipeName) {
        if (!recipesEnabled) {
            return false;
        }
        return recipesConfig.getBoolean("recipes." + recipeName + ".enabled", false);
    }

    /**
     * Get recipe configuration section
     */
    public ConfigurationSection getRecipeSection(String recipeName) {
        return recipesConfig.getConfigurationSection("recipes." + recipeName);
    }

    /**
     * Get all recipe names from the configuration
     */
    public Set<String> getRecipeNames() {
        Set<String> recipeNames = new HashSet<>();
        ConfigurationSection recipesSection = recipesConfig.getConfigurationSection("recipes");
        if (recipesSection != null) {
            recipeNames.addAll(recipesSection.getKeys(false));
        }
        return recipeNames;
    }

    /**
     * Reload both main configuration and recipes configuration from file
     */
    public void reloadConfig() {
        loadConfig();
        loadRecipesConfig();
    }

}