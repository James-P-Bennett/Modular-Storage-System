package org.jamesphbennett.massstorageserver.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.jamesphbennett.massstorageserver.MassStorageServer;
import org.jamesphbennett.massstorageserver.managers.ItemManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class PlayerListener implements Listener {

    private final MassStorageServer plugin;
    private final ItemManager itemManager;

    public PlayerListener(MassStorageServer plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
    }

    /**
     * Handle chat events for terminal search input
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String message = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.message()).trim();

        // Check if player is awaiting search input
        if (plugin.getGUIManager().isAwaitingSearchInput(player)) {
            plugin.getLogger().info("Player " + player.getName() + " sent search input: '" + message + "'");

            // Cancel the chat event so it doesn't appear in public chat
            event.setCancelled(true);

            // Handle the search input on the main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                boolean handled = plugin.getGUIManager().handleSearchInput(player, message);
                if (!handled) {
                    player.sendMessage(Component.text("Search input expired. Please try again.", NamedTextColor.RED));
                }
            });
            return;
        }

        // Check if player is awaiting player input for security terminal
        if (plugin.getGUIManager().isAwaitingPlayerInput(player)) {
            plugin.getLogger().info("Player " + player.getName() + " sent player input: '" + message + "'");

            // Cancel the chat event so it doesn't appear in public chat
            event.setCancelled(true);

            // Handle the player input on the main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getGUIManager().handlePlayerInput(player, message));
        }
    }

    /**
     * Clean up search input when player disconnects
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Cancel any pending search input
        plugin.getGUIManager().cancelSearchInput(player);
        
        // Cancel any pending player input
        plugin.getGUIManager().cancelPlayerInput(player);

        // Close any open GUIs
        plugin.getGUIManager().closeGUI(player);
    }

    /**
     * Handle disk recycling on shift + right click
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Only handle shift + right click in air
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }

        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            return;
        }

        // Check if the item is a storage disk
        if (!itemManager.isStorageDisk(item)) {
            return;
        }

        // Check if the disk has any contents (used capacity > 0)
        if (hasStorageContent(item, player)) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.disk.cannot-dismantle-with-items"));
            return;
        }

        // Cancel the event to prevent any other actions
        event.setCancelled(true);

        // Get the disk tier to create the correct platter
        String diskTier = itemManager.getDiskTier(item);
        if (diskTier == null) {
            return;
        }

        // Create the recycled components
        ItemStack diskPlatter = itemManager.createDiskPlatter(diskTier);
        ItemStack diskHousing = itemManager.createStorageDiskHousing();

        if (diskPlatter == null || diskHousing == null) {
            return;
        }

        // Remove the disk from player's hand
        item.setAmount(item.getAmount() - 1);

        // Try to add components to player's inventory
        if (player.getInventory().firstEmpty() == -1) {
            // Inventory is full, drop at player's feet
            player.getWorld().dropItemNaturally(player.getLocation(), diskPlatter);
            player.getWorld().dropItemNaturally(player.getLocation(), diskHousing);
        } else {
            // Try to add to inventory
            var remaining1 = player.getInventory().addItem(diskPlatter);
            var remaining2 = player.getInventory().addItem(diskHousing);

            // Drop any items that couldn't fit
            if (!remaining1.isEmpty()) {
                for (ItemStack remaining : remaining1.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), remaining);
                }
            }
            if (!remaining2.isEmpty()) {
                for (ItemStack remaining : remaining2.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), remaining);
                }
            }

        }

        // Remove disk from database if it exists
        removeStorageDiskFromDatabase(item, player);

        plugin.getLogger().info("Player " + player.getName() + " dismantled a " + diskTier + " storage disk");
    }

    /**
     * Handle crafting preparation - this is where we validate custom component recipes
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareItemCraft(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        String customRecipeName;

        // Handle different crafting grid sizes
        if (event.getInventory().getSize() == 10) {
            // 3x3 crafting table - support all recipes (shaped and shapeless)
            customRecipeName = plugin.getRecipeManager().matchCustomComponentRecipe(matrix);
        } else if (event.getInventory().getSize() == 5) {
            // 2x2 survival crafting - only support shapeless recipes
            customRecipeName = plugin.getRecipeManager().matchCustomShapelessRecipe(matrix);
        } else {
            // Other inventory types not supported
            return;
        }

        // Check for custom component recipes FIRST
        if (customRecipeName != null) {
            // This matches a custom component recipe
            if (plugin.getConfigManager().isDebugMode()) {
                plugin.getLogger().info("Custom component recipe matched: " + customRecipeName);
            }

            // Check if recipe is enabled
            if (!plugin.getConfigManager().isRecipeEnabled(customRecipeName)) {
                event.getInventory().setResult(null);
                return;
            }

            // Set the correct result
            ItemStack result = plugin.getRecipeManager().getCustomRecipeResult(customRecipeName);
            if (result != null) {
                event.getInventory().setResult(result);
                if (plugin.getConfigManager().isDebugMode()) {
                    plugin.getLogger().info("Set custom recipe result: " + result.getType() + " for " + customRecipeName);
                }
            }
            return; // IMPORTANT: Return here to avoid blocking the recipe
        }

        // Check if any MSS items (including cables) are being used in non-MSS recipes
        if (containsMSSItems(matrix)) {
            Recipe recipe = event.getRecipe();
            if (recipe instanceof ShapedRecipe shapedRecipe) {
                String namespace = shapedRecipe.getKey().getNamespace();
                String recipeKey = shapedRecipe.getKey().getKey();

                // Allow display recipes to show results (we'll validate during crafting)
                if (namespace.equals(plugin.getName().toLowerCase()) && recipeKey.endsWith("_display")) {
                    plugin.getLogger().info("Allowing display recipe to show result: " + recipeKey);
                    return; // Let the display recipe work normally
                }

                // If this is not an MSS recipe, prevent crafting
                if (!namespace.equals(plugin.getName().toLowerCase())) {
                    event.getInventory().setResult(null);
                    plugin.getLogger().info("Prevented MSS items from being used in vanilla recipe: " + shapedRecipe.getKey().getKey());
                }
            } else if (recipe != null) {
                // Not a shaped recipe but contains MSS items - prevent it
                event.getInventory().setResult(null);
                plugin.getLogger().info("Prevented MSS items from being used in non-shaped recipe");
            } else {
                // No recipe detected but MSS items present - this might be a custom component recipe that wasn't matched
                // Don't block it here, let the crafting event handle it
                plugin.getLogger().info("MSS items detected but no recipe matched - allowing for potential custom component recipe");
            }
        }
    }

    /**
     * Handle actual crafting event
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraftItem(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Determine crafting grid type
        boolean is3x3CraftingTable = event.getInventory().getSize() == 10;
        boolean is2x2SurvivalCrafting = event.getInventory().getSize() == 5;
        boolean supportsMSSRecipes = is3x3CraftingTable || is2x2SurvivalCrafting;

        // Check crafting permission first
        if (plugin.getConfigManager().isRequireCraftPermission() && !player.hasPermission("massstorageserver.craft")) {
            // Check if this involves any MSS items (including cables)
            if (containsMSSItems(event.getInventory().getMatrix()) ||
                    (event.getCurrentItem() != null && itemManager.isMSSItem(event.getCurrentItem()))) {
                event.setCancelled(true);
                player.sendMessage(Component.text("You don't have permission to craft Mass Storage items.", NamedTextColor.RED));
                return;
            }
        }

        Recipe recipe = event.getRecipe();
        ItemStack result = event.getCurrentItem();

        // Handle vanilla MSS recipes (blocks, cables, simple items)
        if (recipe instanceof ShapedRecipe shapedRecipe && isMSSRecipe(shapedRecipe)) {
            String recipeKey = shapedRecipe.getKey().getKey();

            // Handle display recipes - these work automatically with RecipeChoice, but we add logging
            if (recipeKey.endsWith("_display")) {
                String realRecipeName = recipeKey.replace("_display", "");
                String componentName = getComponentDisplayName(realRecipeName);
                player.sendMessage(Component.text(componentName + " crafted successfully!", NamedTextColor.GREEN));
                plugin.getLogger().info("Player " + player.getName() + " crafted " + realRecipeName + " using display recipe");
                return;
            }

            // Only handle storage disk recipes here (components are handled by custom system)
            if (isStorageDiskRecipe(shapedRecipe)) {
                handleStorageDiskCrafting(event, player, recipeKey);
                return;
            }

            // Other MSS items (blocks, cables) are handled normally by vanilla system
            plugin.getLogger().info("Player " + player.getName() + " crafted vanilla MSS item: " + recipeKey);
            return;
        }

        // Handle custom component recipes
        if (result != null && itemManager.isMSSItem(result) && supportsMSSRecipes) {
            ItemStack[] matrix = event.getInventory().getMatrix();
            String customRecipeName;

            if (is3x3CraftingTable) {
                // 3x3 crafting table - support all recipes (shaped and shapeless)
                customRecipeName = plugin.getRecipeManager().matchCustomComponentRecipe(matrix);
            } else {
                // 2x2 survival crafting - only support shapeless recipes
                customRecipeName = plugin.getRecipeManager().matchCustomShapelessRecipe(matrix);
            }

            if (customRecipeName != null) {
                handleCustomComponentCrafting(event, player, customRecipeName);
                return;
            }
        }

        // Final check - prevent any MSS items (including cables) from being used in non-MSS recipes
        if (containsMSSItems(event.getInventory().getMatrix())) {
            boolean isMSSRecipe = false;

            if (recipe instanceof ShapedRecipe shapedRecipe) {
                isMSSRecipe = isMSSRecipe(shapedRecipe);
            }

            // Also check if this is a custom component recipe
            if (supportsMSSRecipes) {
                String customRecipeName;
                if (is3x3CraftingTable) {
                    // 3x3 crafting table - support all recipes (shaped and shapeless)
                    customRecipeName = plugin.getRecipeManager().matchCustomComponentRecipe(event.getInventory().getMatrix());
                } else {
                    // 2x2 survival crafting - only support shapeless recipes
                    customRecipeName = plugin.getRecipeManager().matchCustomShapelessRecipe(event.getInventory().getMatrix());
                }
                if (customRecipeName != null) {
                    isMSSRecipe = true;
                }
            }

            if (!isMSSRecipe) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Mass Storage components cannot be used in vanilla recipes!", NamedTextColor.RED));
                plugin.getLogger().info("Prevented player " + player.getName() + " from using MSS items in non-MSS recipe");
            }
        }
    }

    /**
     * Check if the crafting matrix contains any MSS items (including cables)
     */
    private boolean containsMSSItems(ItemStack[] matrix) {
        for (ItemStack item : matrix) {
            if (item != null && itemManager.isMSSItem(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Handle storage disk crafting (vanilla recipes but need database registration)
     */
    private void handleStorageDiskCrafting(CraftItemEvent event, Player player, String recipeKey) {
        ItemStack storageDisk = null;
        String diskType = "1k"; // Default

        // Determine which tier is being crafted based on recipe key
        switch (recipeKey) {
            case "storage_disk_1k" -> {
                storageDisk = itemManager.createStorageDisk(player.getUniqueId().toString(), player.getName());
                diskType = "1k";
            }
            case "storage_disk_4k" -> {
                storageDisk = itemManager.createStorageDisk4k(player.getUniqueId().toString(), player.getName());
                diskType = "4k";
            }
            case "storage_disk_16k" -> {
                storageDisk = itemManager.createStorageDisk16k(player.getUniqueId().toString(), player.getName());
                diskType = "16k";
            }
            case "storage_disk_64k" -> {
                storageDisk = itemManager.createStorageDisk64k(player.getUniqueId().toString(), player.getName());
                diskType = "64k";
            }
        }

        if (storageDisk != null) {
            // Replace the result with our custom item
            event.setCurrentItem(storageDisk);

            // Register the disk in the database
            try {
                registerStorageDisk(storageDisk, player, diskType);
                player.sendMessage(Component.text("Storage Disk [" + diskType.toUpperCase() + "] crafted successfully!", NamedTextColor.GREEN));
            } catch (SQLException e) {
                player.sendMessage(Component.text("Error registering storage disk: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error registering storage disk: " + e.getMessage());
            }
        }
    }

    private void handleCustomComponentCrafting(CraftItemEvent event, Player player, String recipeName) {
        // Check if this is a storage disk recipe that needs special handling
        if (isStorageDiskAltRecipe(recipeName)) {
            handleStorageDiskAltCrafting(event, player, recipeName);
            return;
        }

        // For non-storage disk recipes, the result was already set in PrepareItemCraftEvent
        // We just need to provide feedback and logging
        ItemStack result = event.getCurrentItem();
        if (result != null) {
            String componentName = getComponentDisplayName(recipeName);
            player.sendMessage(Component.text(componentName + " crafted successfully!", NamedTextColor.GREEN));
            plugin.getLogger().info("Player " + player.getName() + " crafted custom component: " + recipeName);
        }
    }

    /**
     * Check if the recipe is an alternative storage disk recipe
     */
    private boolean isStorageDiskAltRecipe(String recipeName) {
        return recipeName.equals("storage_disk_1k_alt") || 
               recipeName.equals("storage_disk_4k_alt") || 
               recipeName.equals("storage_disk_16k_alt") || 
               recipeName.equals("storage_disk_64k_alt");
    }

    /**
     * Handle alternative storage disk crafting with proper player data
     */
    private void handleStorageDiskAltCrafting(CraftItemEvent event, Player player, String recipeName) {
        ItemStack storageDisk = null;
        String diskType = "1k"; // Default

        // Determine which tier is being crafted based on recipe name
        switch (recipeName) {
            case "storage_disk_1k_alt" -> {
                storageDisk = itemManager.createStorageDisk(player.getUniqueId().toString(), player.getName());
                diskType = "1k";
            }
            case "storage_disk_4k_alt" -> {
                storageDisk = itemManager.createStorageDisk4k(player.getUniqueId().toString(), player.getName());
                diskType = "4k";
            }
            case "storage_disk_16k_alt" -> {
                storageDisk = itemManager.createStorageDisk16k(player.getUniqueId().toString(), player.getName());
                diskType = "16k";
            }
            case "storage_disk_64k_alt" -> {
                storageDisk = itemManager.createStorageDisk64k(player.getUniqueId().toString(), player.getName());
                diskType = "64k";
            }
        }

        if (storageDisk != null) {
            // Replace the result with our properly created disk
            event.setCurrentItem(storageDisk);

            // Register the disk in the database
            try {
                registerStorageDisk(storageDisk, player, diskType);
                player.sendMessage(Component.text("Storage Disk [" + diskType.toUpperCase() + "] crafted successfully!", NamedTextColor.GREEN));
                plugin.getLogger().info("Player " + player.getName() + " crafted " + diskType + " storage disk via alternative recipe: " + recipeName);
            } catch (SQLException e) {
                player.sendMessage(Component.text("Error registering storage disk: " + e.getMessage(), NamedTextColor.RED));
                plugin.getLogger().severe("Error registering storage disk: " + e.getMessage());
            }
        }
    }

    /**
     * Get display name for a component recipe
     */
    private String getComponentDisplayName(String recipeName) {
        return switch (recipeName) {
            case "disk_platter_1k" -> "Disk Platter [1K]";
            case "disk_platter_4k" -> "Disk Platter [4K]";
            case "disk_platter_16k" -> "Disk Platter [16K]";
            case "disk_platter_64k" -> "Disk Platter [64K]";
            case "storage_disk_housing" -> "Storage Disk Housing";
            default -> "MSS Component";
        };
    }

    private boolean isStorageDiskRecipe(ShapedRecipe recipe) {
        String namespace = recipe.getKey().getNamespace();
        String key = recipe.getKey().getKey();

        return namespace.equals(plugin.getName().toLowerCase()) &&
                (key.equals("storage_disk_1k") || key.equals("storage_disk_4k") ||
                        key.equals("storage_disk_16k") || key.equals("storage_disk_64k"));
    }

    private void registerStorageDisk(ItemStack disk, Player player, String diskType) throws SQLException {
        String diskId = itemManager.getStorageDiskId(disk);
        if (diskId == null) {
            throw new SQLException("Storage disk missing ID");
        }

        // Get tier from disk item, fallback to diskType parameter
        String diskTier = itemManager.getDiskTier(disk);
        final String tier = (diskTier != null) ? diskTier : diskType; // Make effectively final

        // Use 64 cells as the standard for all disks
        final int maxCells = 64;

        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (var stmt = conn.prepareStatement(
                    "INSERT INTO storage_disks (disk_id, crafter_uuid, crafter_name, tier, max_cells, used_cells) VALUES (?, ?, ?, ?, ?, ?)")) {
                stmt.setString(1, diskId);
                stmt.setString(2, player.getUniqueId().toString());
                stmt.setString(3, player.getName());
                stmt.setString(4, tier); // Now effectively final
                stmt.setInt(5, maxCells); // Always 64 cells
                stmt.setInt(6, 0);
                stmt.executeUpdate();
            }
        });

        plugin.getLogger().info("Registered " + tier + " storage disk " + diskId + " for player " + player.getName() + " with " + maxCells + " cells");
    }

    private boolean isMSSRecipe(ShapedRecipe recipe) {
        return recipe.getKey().getNamespace().equals(plugin.getName().toLowerCase());
    }

    /**
     * Check if a storage disk has any contents (used capacity > 0)
     */
    private boolean hasStorageContent(ItemStack disk, Player player) {
        String diskId = itemManager.getStorageDiskId(disk);
        if (diskId == null) {
            return false; // If no disk ID, assume it's empty
        }

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT used_cells FROM storage_disks WHERE disk_id = ?")) {
            
            stmt.setString(1, diskId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int usedCells = rs.getInt("used_cells");
                    return usedCells > 0;
                }
            }
            return false; // Disk not found in database, assume empty
        } catch (SQLException e) {
            plugin.getLogger().warning("Error checking disk storage content for " + diskId + ": " + e.getMessage());
            return true; // Error occurred, be safe and don't allow recycling
        }
    }

    /**
     * Remove a storage disk from the database when recycled
     */
    private void removeStorageDiskFromDatabase(ItemStack disk, Player player) {
        String diskId = itemManager.getStorageDiskId(disk);
        if (diskId == null) {
            return; // No disk ID, nothing to remove
        }

        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                // First, remove any storage items associated with this disk
                try (var stmt1 = conn.prepareStatement("DELETE FROM storage_items WHERE disk_id = ?")) {
                    stmt1.setString(1, diskId);
                    int itemsRemoved = stmt1.executeUpdate();
                    if (itemsRemoved > 0) {
                        plugin.getLogger().info("Removed " + itemsRemoved + " storage items for recycled disk " + diskId);
                    }
                }

                // Then, remove the disk record
                try (var stmt2 = conn.prepareStatement("DELETE FROM storage_disks WHERE disk_id = ?")) {
                    stmt2.setString(1, diskId);
                    int disksRemoved = stmt2.executeUpdate();
                    if (disksRemoved > 0) {
                        plugin.getLogger().info("Removed disk record for recycled disk " + diskId);
                    }
                }
            });
        } catch (SQLException e) {
            plugin.getLogger().warning("Error removing recycled disk " + diskId + " from database: " + e.getMessage());
        }
    }
}