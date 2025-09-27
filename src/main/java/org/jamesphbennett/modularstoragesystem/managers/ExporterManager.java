package org.jamesphbennett.modularstoragesystem.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;
import org.jamesphbennett.modularstoragesystem.storage.StoredItem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ExporterManager {

    private final ModularStorageSystem plugin;
    private final Map<String, ExporterData> activeExporters = new ConcurrentHashMap<>();
    private final Map<String, Integer> exporterCycleIndex = new ConcurrentHashMap<>();

    public ExporterManager(ModularStorageSystem plugin) {
        this.plugin = plugin;
        loadExporters();
        startExportTask();
    }

    /**
     * Data class for exporter information
     */
    public static class ExporterData {
        public final String exporterId;
        public final String networkId;
        public final Location location;
        public boolean enabled;
        public final List<String> filterItems = new ArrayList<>();
        public long lastExport;

        public ExporterData(String exporterId, String networkId, Location location, boolean enabled) {
            this.exporterId = exporterId;
            this.networkId = networkId;
            this.location = location;
            this.enabled = enabled;
            this.lastExport = System.currentTimeMillis();
        }
    }

    /**
     * Load all exporters from database
     */
    private void loadExporters() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT e.exporter_id, e.network_id, e.world_name, e.x, e.y, e.z, e.enabled, e.last_export " +
                             "FROM exporters e")) {

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String exporterId = rs.getString("exporter_id");
                    String networkId = rs.getString("network_id");
                    String worldName = rs.getString("world_name");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    boolean enabled = rs.getBoolean("enabled");

                    org.bukkit.World world = plugin.getServer().getWorld(worldName);
                    if (world != null) {
                        Location location = new Location(world, x, y, z);
                        ExporterData data = new ExporterData(exporterId, networkId, location, enabled);

                        // Load filters for this exporter
                        loadExporterFilters(conn, data);

                        activeExporters.put(exporterId, data);
                        exporterCycleIndex.put(exporterId, 0);
                    }
                }
            }


        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading exporters: " + e.getMessage());
        }
    }

    /**
     * Load filters for a specific exporter - loads hashes for compatibility with export logic
     */
    private void loadExporterFilters(Connection conn, ExporterData data) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT item_hash FROM exporter_filters WHERE exporter_id = ? AND filter_type = 'whitelist'")) {
            stmt.setString(1, data.exporterId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    data.filterItems.add(rs.getString("item_hash"));
                }
            }
        }
    }

    /**
     * Start the periodic export task
     */
    private void startExportTask() {
        int tickInterval = plugin.getConfigManager().getExportTickInterval();

        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (ExporterData exporter : activeExporters.values()) {
                if (exporter.enabled) {
                    processExport(exporter);
                }
            }
        }, tickInterval, tickInterval);

        // ADDED: Periodic validation of exporter network assignments (every 30 seconds)
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateExporterNetworkAssignments, 600L, 600L); // 600 ticks = 30 seconds

        // REMOVED: Particle effects disabled for performance

    }
    /**
     * Check if exporter is physically connected to its assigned network
     */
    private boolean isExporterConnectedToNetwork(ExporterData exporter) {
        // First check if network exists at all
        if (!plugin.getNetworkManager().isNetworkValid(exporter.networkId)) {
            return true; // Return true when network doesn't exist - means disconnected
        }

        // Look for any adjacent network blocks or cables
        Location exporterLoc = exporter.location;

        // Check all 6 adjacent faces for network connectivity
        Location[] adjacentLocs = {
                exporterLoc.clone().add(1, 0, 0),   // East
                exporterLoc.clone().add(-1, 0, 0),  // West
                exporterLoc.clone().add(0, 1, 0),   // Up
                exporterLoc.clone().add(0, -1, 0),  // Down
                exporterLoc.clone().add(0, 0, 1),   // South
                exporterLoc.clone().add(0, 0, -1)   // North
        };

        for (Location loc : adjacentLocs) {
            String networkId = plugin.getNetworkManager().getNetworkId(loc);

            if (exporter.networkId.equals(networkId)) {
                return false; // Found matching network at adjacent location
            }
        }

        return true; // No adjacent network blocks found
    }

    /**
     * Public method to check if an exporter is connected to any network (for GUI access)
     * Used by GUI manager for access control
     */
    public boolean isExporterConnectedToItsNetwork(String exporterId) {
        ExporterData exporter = activeExporters.get(exporterId);
        if (exporter == null) {
            return false; // Exporter not found - consider disconnected
        }
        
        // Just check if there's any adjacent network - don't require a specific one
        String adjacentNetworkId = findAdjacentNetwork(exporter.location);
        return adjacentNetworkId != null; // Connected if any adjacent network found
    }

    /**
     * Refresh any open exporter GUIs for a specific exporter
     */
    private void refreshExporterGUIs(String exporterId) {
        try {
            // Notify GUI manager to refresh this specific exporter's GUIs
            plugin.getGUIManager().refreshExporterGUIs(exporterId);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to refresh exporter GUIs for " + exporterId + ": " + e.getMessage());
        }
    }
    /**
     * Process a single export operation
     */
    private void processExport(ExporterData exporter) {
        try {
            // Check if exporter is physically connected to any network
            String adjacentNetworkId = findAdjacentNetwork(exporter.location);
            if (adjacentNetworkId == null) {
                // AUTO-DISABLE: Set exporter as disabled when disconnected
                try {
                    toggleExporter(exporter.exporterId, false);

                    // Refresh any open exporter GUIs to show disabled status
                    refreshExporterGUIs(exporter.exporterId);

                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to auto-disable exporter " + exporter.exporterId + ": " + e.getMessage());
                }

                return;
            }

            // If connected to a different network than assigned, update the assignment
            if (!adjacentNetworkId.equals(exporter.networkId)) {
                try {
                    // Update database
                    plugin.getDatabaseManager().executeUpdate(
                            "UPDATE exporters SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                            adjacentNetworkId, exporter.exporterId);
                    
                    // Update in memory - replace the exporter data object
                    activeExporters.remove(exporter.exporterId);
                    ExporterData updatedData = new ExporterData(exporter.exporterId, adjacentNetworkId, exporter.location, exporter.enabled);
                    updatedData.filterItems.addAll(exporter.filterItems);
                    activeExporters.put(exporter.exporterId, updatedData);
                    
                    // Update the reference for the rest of this method
                    exporter = updatedData;
                    
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to update exporter network assignment: " + e.getMessage());
                    return;
                }
            }

            // If exporter has no filters, it shouldn't export anything (but we still needed to check disconnection)
            if (exporter.filterItems.isEmpty()) {
                return;
            }

            // Find the connected inventory
            Block exporterBlock = exporter.location.getBlock();
            if (exporterBlock.getType() != Material.PLAYER_HEAD && exporterBlock.getType() != Material.PLAYER_WALL_HEAD) {
                plugin.getLogger().warning("Exporter at " + exporter.location + " is not a player head!");
                return;
            }

            // Get the target inventory
            Container targetContainer = getTargetContainer(exporterBlock);
            if (targetContainer == null) {
                return; // No valid target
            }

            Inventory targetInventory = targetContainer.getInventory();

            // Check if inventory has space (including partial stacks)
            if (!hasInventorySpace(targetInventory)) {
                return; // Inventory is completely full
            }

            // Check if this is a brewing stand and handle specially
            Material containerType = targetContainer.getBlock().getType();
            boolean isBrewingStand = containerType == Material.BREWING_STAND;
            
            if (isBrewingStand) {
                // Use brewing stand specific logic that handles slot selection internally
                exportBrewingStandWithSlotSelection(exporter, targetContainer);
            } else {
                // Use regular round-robin for other containers
                String itemHashToExport = getNextItemToExport(exporter);
                
                if (itemHashToExport == null) {
                    return; // No items to export
                }

                // Try to export the item with slot-specific routing
                exportItemWithSlotRouting(exporter, itemHashToExport, targetContainer);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error processing export for " + exporter.exporterId + ": " + e.getMessage());
        }
    }

    /**
     * FIXED: Check if inventory has any available space (empty slots or partial stacks)
     */
    private boolean hasInventorySpace(Inventory inventory) {
        // First check for any empty slots
        if (inventory.firstEmpty() != -1) {
            return true;
        }

        // Then check for any partial stacks that can be filled
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getAmount() < item.getMaxStackSize()) {
                return true; // Found a partial stack
            }
        }

        return false; // No space available
    }

    /**
     * FIXED: Add items to inventory with stack optimization (fills partial stacks first)
     * @param targetInventory The inventory to add items to
     * @param itemToAdd The item to add
     * @return The amount of items that couldn't be placed
     */
    private int addItemWithStackOptimization(Inventory targetInventory, ItemStack itemToAdd) {
        int remainingAmount = itemToAdd.getAmount();
        int maxStackSize = itemToAdd.getMaxStackSize();

        for (int slot = 0; slot < targetInventory.getSize(); slot++) {
            ItemStack existing = targetInventory.getItem(slot);
            if (existing != null && existing.isSimilar(itemToAdd)) {
                int currentAmount = existing.getAmount();
                if (currentAmount < maxStackSize) {
                    int canAdd = Math.min(remainingAmount, maxStackSize - currentAmount);
                    existing.setAmount(currentAmount + canAdd);
                    remainingAmount -= canAdd;

                    if (remainingAmount <= 0) {
                        return 0; // All items placed successfully
                    }
                }
            }
        }

        for (int slot = 0; slot < targetInventory.getSize() && remainingAmount > 0; slot++) {
            ItemStack existing = targetInventory.getItem(slot);
            if (existing == null || existing.getType() == Material.AIR) {
                int toPlace = Math.min(remainingAmount, maxStackSize);
                ItemStack newStack = itemToAdd.clone();
                newStack.setAmount(toPlace);
                targetInventory.setItem(slot, newStack);
                remainingAmount -= toPlace;
            }
        }

        return remainingAmount; // Return how many items couldn't be placed
    }

    /**
     * Check if an item can be placed in a specific brewing stand slot
     */
    private boolean canPlaceItemInBrewingSlot(Inventory brewingInventory, ItemStack itemToPlace, int slot) {
        if (slot < 0 || slot >= brewingInventory.getSize()) {
            return false;
        }
        
        ItemStack existingItem = brewingInventory.getItem(slot);
        
        // Slot is empty - can place
        if (existingItem == null || existingItem.getType() == Material.AIR) {
            return true;
        }
        
        // Slot has same item - check if we can stack
        if (existingItem.isSimilar(itemToPlace)) {
            int maxStackSize = getBrewingStandSlotMaxAmount(slot, itemToPlace.getType());
            return existingItem.getAmount() < maxStackSize;
        }
        
        // Slot has different item - can't place
        return false;
    }

    /**
     * Get the next item to export using round-robin
     */
    private String getNextItemToExport(ExporterData exporter) {
        if (exporter.filterItems.isEmpty()) {
            return null;
        }

        int currentIndex = exporterCycleIndex.get(exporter.exporterId);

        // Try each filter item once, starting from current index
        for (int i = 0; i < exporter.filterItems.size(); i++) {
            int checkIndex = (currentIndex + i) % exporter.filterItems.size();
            String itemHash = exporter.filterItems.get(checkIndex);

            // Check if this item is available in the network
            try {
                if (isItemAvailableInNetwork(exporter.networkId, itemHash)) {
                    // Update the cycle index for next time
                    exporterCycleIndex.put(exporter.exporterId, (checkIndex + 1) % exporter.filterItems.size());
                    return itemHash;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error checking item availability: " + e.getMessage());
            }
        }

        // No items available, increment index anyway for next cycle
        exporterCycleIndex.put(exporter.exporterId, (currentIndex + 1) % exporter.filterItems.size());
        return null;
    }

    /**
     * Check if an item is available in the network
     */
    private boolean isItemAvailableInNetwork(String networkId, String itemHash) throws Exception {
        List<StoredItem> networkItems = plugin.getStorageManager().getNetworkItems(networkId);
        for (StoredItem item : networkItems) {
            if (item.itemHash().equals(itemHash) && item.quantity() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Export specific item with slot-specific routing for furnaces
     */
    private void exportItemWithSlotRouting(ExporterData exporter, String itemHash, Container targetContainer) {
        try {
            // Check container type for specialized routing
            Material containerType = targetContainer.getBlock().getType();
            boolean isFurnace = containerType == Material.FURNACE || 
                               containerType == Material.BLAST_FURNACE || 
                               containerType == Material.SMOKER;
            boolean isBrewingStand = containerType == Material.BREWING_STAND;

            if (isFurnace) {
                // Handle furnace slot routing
                exportItemToFurnace(exporter, itemHash, targetContainer);
            } else if (isBrewingStand) {
                // Handle brewing stand slot routing
                exportItemToBrewingStand(exporter, itemHash, targetContainer);
            } else {
                // Use generic export for other containers
                exportItem(exporter, itemHash, targetContainer.getInventory());
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error in slot routing export: " + e.getMessage());
        }
    }

    /**
     * Export item to specific furnace slots based on filter slot targeting
     */
    private void exportItemToFurnace(ExporterData exporter, String itemHash, Container furnaceContainer) {
        try {
            // Get slot targeting information for this item
            String slotTarget = getSlotTargetForItem(exporter.exporterId, itemHash);
            
            // Retrieve up to one stack from the network
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);
            
            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return; // Nothing retrieved
            }

            Inventory furnaceInventory = furnaceContainer.getInventory();
            int targetSlot;

            // Determine target slot based on filter type
            if ("fuel".equals(slotTarget)) {
                targetSlot = 1; // Fuel slot (bottom)
            } else {
                targetSlot = 0; // Input slot (top) - default for material and generic
            }

            // Try to add to the specific furnace slot
            int leftoverAmount = addItemToSpecificSlot(furnaceInventory, retrievedItem, targetSlot);

            // Handle leftovers and logging
            handleExportCompletion(exporter, retrievedItem, leftoverAmount
            );

        } catch (Exception e) {
            plugin.getLogger().severe("Error exporting to furnace: " + e.getMessage());
        }
    }

    /**
     * Export to brewing stand with integrated slot selection and export
     */
    private void exportBrewingStandWithSlotSelection(ExporterData exporter, Container brewingStandContainer) {
        try {
            // Parse brewing stand filters from exporter filter items
            BrewingStandFilters brewingFilters = parseBrewingStandFilters(exporter.exporterId);
            
            Inventory brewingInventory = brewingStandContainer.getInventory();
            
            // Create a list of all possible exports with their priority
            List<PotentialExport> potentialExports = new ArrayList<>();
            
            // Check blaze powder (fuel) - only if fuel slot has space
            if (brewingFilters.fuelEnabled) {
                ItemStack blazePowder = new ItemStack(Material.BLAZE_POWDER);
                String blazeHash = plugin.getItemManager().generateItemHash(blazePowder);
                if (isItemAvailableInNetwork(exporter.networkId, blazeHash) && 
                    canPlaceItemInBrewingSlot(brewingInventory, blazePowder, 4)) {
                    potentialExports.add(new PotentialExport(blazeHash, 4, "fuel"));
                }
            }
            
            // Check ingredient filter - only if ingredient slot has space
            if (brewingFilters.ingredientFilter != null) {
                String ingredientHash = plugin.getItemManager().generateItemHash(brewingFilters.ingredientFilter);
                if (isItemAvailableInNetwork(exporter.networkId, ingredientHash) && 
                    canPlaceItemInBrewingSlot(brewingInventory, brewingFilters.ingredientFilter, 3)) {
                    potentialExports.add(new PotentialExport(ingredientHash, 3, "ingredient"));
                }
            }
            
            // Check each bottle filter independently - only if their specific slots have space
            for (int i = 0; i < 3; i++) {
                if (brewingFilters.bottleFilters[i] != null) {
                    String bottleHash = plugin.getItemManager().generateItemHash(brewingFilters.bottleFilters[i]);
                    if (isItemAvailableInNetwork(exporter.networkId, bottleHash) && 
                        canPlaceItemInBrewingSlot(brewingInventory, brewingFilters.bottleFilters[i], i)) {
                        potentialExports.add(new PotentialExport(bottleHash, i, "bottle " + (i + 1)));
                    }
                }
            }
            
            // If we have potential exports, use round-robin to cycle through them
            if (!potentialExports.isEmpty()) {
                // Get current cycle index for this exporter (reuse existing mechanism)
                int currentIndex = exporterCycleIndex.get(exporter.exporterId);
                PotentialExport selectedExport = potentialExports.get(currentIndex % potentialExports.size());
                
                // Update cycle index for next time
                exporterCycleIndex.put(exporter.exporterId, (currentIndex + 1) % potentialExports.size());
                
                // Now perform the actual export to the specific slot
                exportItemToSpecificBrewingSlot(exporter, selectedExport.itemHash, selectedExport.targetSlot, brewingStandContainer);
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("Error in brewing stand slot selection: " + e.getMessage());
        }
    }

    /**
     * Export item to a specific brewing stand slot (knows the exact slot to target)
     */
    private void exportItemToSpecificBrewingSlot(ExporterData exporter, String itemHash, int targetSlot, Container brewingStandContainer) {
        try {
            // Retrieve item from network first
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);
            
            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return;
            }

            // Limit item amount to slot-appropriate stack size
            int maxAmount = getBrewingStandSlotMaxAmount(targetSlot, retrievedItem.getType());
            if (retrievedItem.getAmount() > maxAmount) {
                // Split the stack - keep what we can use, return the rest
                ItemStack excess = retrievedItem.clone();
                excess.setAmount(retrievedItem.getAmount() - maxAmount);
                retrievedItem.setAmount(maxAmount);
                
                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(excess);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);
            }

            // Try to place in the target slot
            Inventory brewingInventory = brewingStandContainer.getInventory();
            ItemStack existingItem = brewingInventory.getItem(targetSlot);
            
            int leftoverAmount = 0;
            if (existingItem == null || existingItem.getType() == Material.AIR) {
                // Slot is empty, place the item
                brewingInventory.setItem(targetSlot, retrievedItem);
            } else if (existingItem.isSimilar(retrievedItem)) {
                // Slot has same item, try to stack
                int spaceAvailable = existingItem.getMaxStackSize() - existingItem.getAmount();
                int amountToAdd = Math.min(spaceAvailable, retrievedItem.getAmount());
                
                if (amountToAdd > 0) {
                    existingItem.setAmount(existingItem.getAmount() + amountToAdd);
                    leftoverAmount = retrievedItem.getAmount() - amountToAdd;
                } else {
                    leftoverAmount = retrievedItem.getAmount(); // No space
                }
            } else {
                // Slot has different item, can't place
                leftoverAmount = retrievedItem.getAmount();
            }

            // Handle completion and leftovers
            handleExportCompletion(exporter, retrievedItem, leftoverAmount);

        } catch (Exception e) {
            plugin.getLogger().severe("Error exporting to specific brewing stand slot: " + e.getMessage());
        }
    }

    /**
     * Export item to specific brewing stand slots based on brewing stand filter settings
     */
    private void exportItemToBrewingStand(ExporterData exporter, String itemHash, Container brewingStandContainer) {
        try {
            // Parse brewing stand filters from exporter filter items
            BrewingStandFilters brewingFilters = parseBrewingStandFilters(exporter.exporterId);
            
            // Retrieve item from network first (similar to furnace approach)
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);
            
            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return; // Nothing retrieved
            }

            Material itemType = retrievedItem.getType();
            int targetSlot = getTargetSlot(itemType, brewingFilters);

            if (targetSlot == -1) {
                // No valid target slot - return items to network
                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(retrievedItem);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);
                return;
            }

            // Limit item amount to slot-appropriate stack size
            int maxAmount = getBrewingStandSlotMaxAmount(targetSlot, itemType);
            if (retrievedItem.getAmount() > maxAmount) {
                // Split the stack - keep what we can use, return the rest
                ItemStack excess = retrievedItem.clone();
                excess.setAmount(retrievedItem.getAmount() - maxAmount);
                retrievedItem.setAmount(maxAmount);
                
                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(excess);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);
            }
            
            if (retrievedItem.getAmount() == 0) {
                return; // No items available
            }

            // Try to place in the target slot
            Inventory brewingInventory = brewingStandContainer.getInventory();
            ItemStack existingItem = brewingInventory.getItem(targetSlot);
            
            int leftoverAmount = 0;
            if (existingItem == null || existingItem.getType() == Material.AIR) {
                // Slot is empty, place the item
                brewingInventory.setItem(targetSlot, retrievedItem);
            } else if (existingItem.isSimilar(retrievedItem)) {
                // Slot has same item, try to stack
                int spaceAvailable = existingItem.getMaxStackSize() - existingItem.getAmount();
                int amountToAdd = Math.min(spaceAvailable, retrievedItem.getAmount());
                
                if (amountToAdd > 0) {
                    existingItem.setAmount(existingItem.getAmount() + amountToAdd);
                    leftoverAmount = retrievedItem.getAmount() - amountToAdd;
                } else {
                    leftoverAmount = retrievedItem.getAmount(); // No space
                }
            } else {
                // Slot has different item, can't place
                leftoverAmount = retrievedItem.getAmount();
            }

            // Handle completion and leftovers
            handleExportCompletion(exporter, retrievedItem, leftoverAmount
            );

        } catch (Exception e) {
            plugin.getLogger().severe("Error exporting to brewing stand: " + e.getMessage());
        }
    }

    private static int getTargetSlot(Material itemType, BrewingStandFilters brewingFilters) {
        int targetSlot = -1;

        // Determine target slot based on item type and filters
        if (itemType == Material.BLAZE_POWDER && brewingFilters.fuelEnabled) {
            targetSlot = 4; // Fuel slot
        } else if (brewingFilters.ingredientFilter != null &&
                  itemType == brewingFilters.ingredientFilter.getType()) {
            targetSlot = 3; // Ingredient slot
        } else {
            // Check bottle filters (slots 0, 1, 2)
            for (int i = 0; i < 3; i++) {
                if (brewingFilters.bottleFilters[i] != null &&
                    itemType == brewingFilters.bottleFilters[i].getType()) {
                    targetSlot = i;
                    break;
                }
            }
        }
        return targetSlot;
    }

    /**
     * Get maximum stack size for a brewing stand slot
     */
    private int getBrewingStandSlotMaxAmount(int slot, Material itemType) {
        if (slot == 4) {
            // Fuel slot - blaze powder stacks to 64
            return Math.min(64, itemType.getMaxStackSize());
        } else if (slot == 3) {
            // Ingredient slot - most ingredients stack to 64
            return Math.min(64, itemType.getMaxStackSize());
        } else {
            // Bottle slots (0, 1, 2) - bottles typically stack to 16, potions to 1
            return itemType.getMaxStackSize();
        }
    }

    /**
     * Parse brewing stand specific filters from the exporter's filter items
     */
    private BrewingStandFilters parseBrewingStandFilters(String exporterId) {
        BrewingStandFilters filters = new BrewingStandFilters();
        
        try {
            List<ItemStack> filterItems = getExporterFilterItems(exporterId);
            
            for (ItemStack item : filterItems) {
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    Component displayName = item.getItemMeta().displayName();
                    if (displayName == null) continue;
                    
                    // Use PlainTextComponentSerializer for reliable text extraction
                    String nameText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName);
                    
                    if ("BREWING_FUEL_ENABLED".equals(nameText)) {
                        filters.fuelEnabled = true;
                    } else if ("BREWING_INGREDIENT".equals(nameText)) {
                        filters.ingredientFilter = item.clone();
                        // Remove the marker name to get the actual item
                        ItemMeta meta = filters.ingredientFilter.getItemMeta();
                        meta.displayName(null);
                        filters.ingredientFilter.setItemMeta(meta);
                    } else if (nameText.startsWith("BREWING_BOTTLE_")) {
                        try {
                            int bottleIndex = Integer.parseInt(nameText.substring("BREWING_BOTTLE_".length()));
                            if (bottleIndex >= 0 && bottleIndex < 3) {
                                filters.bottleFilters[bottleIndex] = item.clone();
                                // Remove the marker name to get the actual item
                                ItemMeta meta = filters.bottleFilters[bottleIndex].getItemMeta();
                                meta.displayName(null);
                                filters.bottleFilters[bottleIndex].setItemMeta(meta);
                            }
                        } catch (NumberFormatException e) {
                            // Ignore invalid bottle indices
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error parsing brewing stand filters for " + exporterId + ": " + e.getMessage());
        }
        
        return filters;
    }

    /**
     * Data class for brewing stand filter settings
     */
    private static class BrewingStandFilters {
        boolean fuelEnabled = false;
        ItemStack ingredientFilter = null;
        ItemStack[] bottleFilters = new ItemStack[3];
    }

    /**
         * Helper class for potential brewing stand exports
         */
        private record PotentialExport(String itemHash, int targetSlot, String slotName) {
    }

    /**
     * Get the slot target for a specific item from the database
     */
    private String getSlotTargetForItem(String exporterId, String itemHash) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT slot_target FROM exporter_filters WHERE exporter_id = ? AND item_hash = ? LIMIT 1")) {

            stmt.setString(1, exporterId);
            stmt.setString(2, itemHash);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String slotTarget = rs.getString("slot_target");
                    return slotTarget != null ? slotTarget : "generic";
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting slot target for item: " + e.getMessage());
        }
        
        return "generic"; // Default fallback
    }

    /**
     * Add item to a specific inventory slot (for furnace slot targeting)
     */
    private int addItemToSpecificSlot(Inventory inventory, ItemStack itemToAdd, int targetSlot) {
        if (targetSlot < 0 || targetSlot >= inventory.getSize()) {
            return itemToAdd.getAmount(); // Invalid slot, return all as leftover
        }

        ItemStack slotItem = inventory.getItem(targetSlot);
        
        if (slotItem == null || slotItem.getType() == Material.AIR) {
            // Slot is empty, place the entire stack
            inventory.setItem(targetSlot, itemToAdd.clone());
            return 0; // No leftovers
        } else if (slotItem.isSimilar(itemToAdd)) {
            // Slot has similar item, try to merge
            int maxStackSize = slotItem.getMaxStackSize();
            int currentAmount = slotItem.getAmount();
            int spaceAvailable = maxStackSize - currentAmount;
            
            if (spaceAvailable > 0) {
                int amountToAdd = Math.min(spaceAvailable, itemToAdd.getAmount());
                slotItem.setAmount(currentAmount + amountToAdd);
                inventory.setItem(targetSlot, slotItem);
                return itemToAdd.getAmount() - amountToAdd;
            }
        }
        
        // Slot is full or has different item
        return itemToAdd.getAmount();
    }

    /**
     * Handle export completion (leftovers, logging, etc.)
     */
    private void handleExportCompletion(ExporterData exporter, ItemStack retrievedItem, 
                                       int leftoverAmount) {
        try {
            // If there's leftover, put it back in the network
            if (leftoverAmount > 0) {
                ItemStack leftoverStack = retrievedItem.clone();
                leftoverStack.setAmount(leftoverAmount);

                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(leftoverStack);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);

                // Calculate what was actually exported
                int exported = retrievedItem.getAmount() - leftoverAmount;
                if (exported > 0) {
                    exporter.lastExport = System.currentTimeMillis();
                    updateLastExport(exporter.exporterId);
                }
            } else {
                // Everything was exported successfully
                exporter.lastExport = System.currentTimeMillis();
                updateLastExport(exporter.exporterId);
            }

            // Refresh any open terminals
            plugin.getGUIManager().refreshNetworkTerminals(exporter.networkId);

        } catch (Exception e) {
            plugin.getLogger().severe("Error handling export completion: " + e.getMessage());
        }
    }

    /**
     * FIXED: Export an item to the target inventory with stack optimization (legacy method for non-furnaces)
     */
    private void exportItem(ExporterData exporter, String itemHash, Inventory targetInventory) {
        try {
            // Retrieve up to one stack from the network
            ItemStack retrievedItem = plugin.getStorageManager().retrieveItems(exporter.networkId, itemHash, 64);

            if (retrievedItem == null || retrievedItem.getAmount() == 0) {
                return; // Nothing retrieved
            }

            // Try to add to target inventory with stack optimization
            int leftoverAmount = addItemWithStackOptimization(targetInventory, retrievedItem);

            // If there's leftover, put it back in the network
            if (leftoverAmount > 0) {
                ItemStack leftoverStack = retrievedItem.clone();
                leftoverStack.setAmount(leftoverAmount);

                List<ItemStack> toReturn = new ArrayList<>();
                toReturn.add(leftoverStack);
                plugin.getStorageManager().storeItems(exporter.networkId, toReturn);

                // Calculate what was actually exported
                int exported = retrievedItem.getAmount() - leftoverAmount;
                if (exported > 0) {
                    exporter.lastExport = System.currentTimeMillis();
                    updateLastExport(exporter.exporterId);
                }
            } else {
                // Everything was exported successfully
                exporter.lastExport = System.currentTimeMillis();
                updateLastExport(exporter.exporterId);
            }

            // Refresh any open terminals
            plugin.getGUIManager().refreshNetworkTerminals(exporter.networkId);

        } catch (Exception e) {
            plugin.getLogger().severe("Error exporting item: " + e.getMessage());
        }
    }

    /**
     * Get the target container that the exporter is physically attached to
     */
    private Container getTargetContainer(Block exporterBlock) {
        try {
            Block attachedBlock = null;

            if (exporterBlock.getType() == Material.PLAYER_HEAD) {
                // Floor mounted head - check block below
                attachedBlock = exporterBlock.getRelative(BlockFace.DOWN);
            } else if (exporterBlock.getType() == Material.PLAYER_WALL_HEAD) {
                // Wall mounted head - check block it's attached to
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) exporterBlock.getBlockData();
                BlockFace facing = directional.getFacing();
                // The block the wall head is attached to is in the opposite direction
                attachedBlock = exporterBlock.getRelative(facing.getOppositeFace());
            }

            // Check if the attached block is a container
            if (attachedBlock != null) {
                if (attachedBlock.getState() instanceof Container) {
                    return (Container) attachedBlock.getState();
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking attached block for exporter: " + e.getMessage());
        }
        return null;
    }

    /**
     * Remove an exporter
     */
    public void removeExporter(String exporterId) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Delete exporter (filters cascade delete)
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM exporters WHERE exporter_id = ?")) {
                stmt.setString(1, exporterId);
                stmt.executeUpdate();
            }
        });

        activeExporters.remove(exporterId);
        exporterCycleIndex.remove(exporterId);
    }

    /**
     * Get exporter at location
     */
    public ExporterData getExporterAtLocation(Location location) {
        for (ExporterData data : activeExporters.values()) {
            if (data.location.equals(location)) {
                return data;
            }
        }
        return null;
    }

    /**
     * Toggle exporter enabled state
     */
    public void toggleExporter(String exporterId, boolean enabled) throws SQLException {
        ExporterData data = activeExporters.get(exporterId);
        if (data != null) {
            data.enabled = enabled;

            plugin.getDatabaseManager().executeUpdate(
                    "UPDATE exporters SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                    enabled, exporterId);

        }
    }

    /**
     * Update slot-specific filters for a furnace exporter
     */
    public void updateFurnaceExporterFilters(String exporterId, List<ItemStack> fuelItems, List<ItemStack> materialItems) throws SQLException {
        ExporterData data = activeExporters.get(exporterId);
        if (data == null) return;

        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Clear existing filters
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM exporter_filters WHERE exporter_id = ?")) {
                stmt.setString(1, exporterId);
                stmt.executeUpdate();
            }

            // Add fuel filters
            if (!fuelItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO exporter_filters (exporter_id, item_hash, item_data, filter_type, slot_target) VALUES (?, ?, ?, 'whitelist', 'fuel')")) {

                    for (ItemStack item : fuelItems) {
                        ItemStack template = item.clone();
                        template.setAmount(1);

                        String itemHash = plugin.getItemManager().generateItemHash(template);
                        String itemData = plugin.getStorageManager().serializeItemStack(template);

                        stmt.setString(1, exporterId);
                        stmt.setString(2, itemHash);
                        stmt.setString(3, itemData);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }

            // Add material filters
            if (!materialItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO exporter_filters (exporter_id, item_hash, item_data, filter_type, slot_target) VALUES (?, ?, ?, 'whitelist', 'material')")) {

                    for (ItemStack item : materialItems) {
                        ItemStack template = item.clone();
                        template.setAmount(1);

                        String itemHash = plugin.getItemManager().generateItemHash(template);
                        String itemData = plugin.getStorageManager().serializeItemStack(template);

                        stmt.setString(1, exporterId);
                        stmt.setString(2, itemHash);
                        stmt.setString(3, itemData);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        });

        // Update in-memory data with hashes from single-item templates
        data.filterItems.clear();
        for (ItemStack item : fuelItems) {
            ItemStack template = item.clone();
            template.setAmount(1);
            String itemHash = plugin.getItemManager().generateItemHash(template);
            data.filterItems.add(itemHash);
        }
        for (ItemStack item : materialItems) {
            ItemStack template = item.clone();
            template.setAmount(1);
            String itemHash = plugin.getItemManager().generateItemHash(template);
            data.filterItems.add(itemHash);
        }

    }

    /**
     * Get slot-specific filter items for a furnace exporter
     */
    public Map<String, List<ItemStack>> getFurnaceExporterFilterItems(String exporterId) {
        Map<String, List<ItemStack>> filters = new HashMap<>();
        filters.put("fuel", new ArrayList<>());
        filters.put("material", new ArrayList<>());

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT item_data, slot_target FROM exporter_filters WHERE exporter_id = ? AND item_data IS NOT NULL")) {

            stmt.setString(1, exporterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String itemData = rs.getString("item_data");
                    String slotTarget = rs.getString("slot_target");
                    
                    ItemStack item = plugin.getStorageManager().deserializeItemStack(itemData);
                    if (item != null) {
                        item.setAmount(1);
                        
                        if ("fuel".equals(slotTarget)) {
                            filters.get("fuel").add(item);
                        } else if ("material".equals(slotTarget)) {
                            filters.get("material").add(item);
                        } else {
                            // Backward compatibility - treat generic/null as material
                            filters.get("material").add(item);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading furnace exporter filters: " + e.getMessage());
        }

        return filters;
    }

    /**
     * Update filter for an exporter - FIXED to store actual item data as single-item templates
     */
    public void updateExporterFilter(String exporterId, List<ItemStack> filterItems) throws SQLException {
        ExporterData data = activeExporters.get(exporterId);
        if (data == null) return;

        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Clear existing filters
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM exporter_filters WHERE exporter_id = ?")) {
                stmt.setString(1, exporterId);
                stmt.executeUpdate();
            }

            // Add new filters with both hashes and item data
            if (!filterItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO exporter_filters (exporter_id, item_hash, item_data, filter_type) VALUES (?, ?, ?, 'whitelist')")) {

                    for (ItemStack item : filterItems) {
                        // Ensure we store single-item templates
                        ItemStack template = item.clone();
                        template.setAmount(1);

                        String itemHash = plugin.getItemManager().generateItemHash(template);
                        String itemData = plugin.getStorageManager().serializeItemStack(template); // Store actual item data

                        stmt.setString(1, exporterId);
                        stmt.setString(2, itemHash);
                        stmt.setString(3, itemData);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }
        });

        // Update in-memory data with hashes from single-item templates
        data.filterItems.clear();
        for (ItemStack item : filterItems) {
            ItemStack template = item.clone();
            template.setAmount(1);
            String itemHash = plugin.getItemManager().generateItemHash(template);
            data.filterItems.add(itemHash);
        }

    }

    /**
     * Get filter items for an exporter - FIXED to return actual ItemStacks as single-item templates
     */
    public List<ItemStack> getExporterFilterItems(String exporterId) {
        List<ItemStack> items = new ArrayList<>();

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT item_data FROM exporter_filters WHERE exporter_id = ? AND item_data IS NOT NULL")) {

            stmt.setString(1, exporterId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String itemData = rs.getString("item_data");
                    ItemStack item = plugin.getStorageManager().deserializeItemStack(itemData);
                    if (item != null) {
                        // Ensure it's a single-item template
                        item.setAmount(1);
                        items.add(item);
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading exporter filters: " + e.getMessage());
        }

        return items;
    }

    /**
     * ADDED: Handle network invalidation - disconnect exporters from invalid networks
     * This should be called when a network is unregistered/dissolved
     */
    public void handleNetworkInvalidated(String networkId) {
        for (ExporterData exporter : activeExporters.values()) {
            if (networkId.equals(exporter.networkId)) {
                try {
                    // Mark exporter as disconnected in database
                    plugin.getDatabaseManager().executeUpdate(
                            "UPDATE exporters SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                            "UNCONNECTED", exporter.exporterId);

                    // Update in memory - create new ExporterData with UNCONNECTED status
                    activeExporters.remove(exporter.exporterId);
                    ExporterData disconnectedData = new ExporterData(exporter.exporterId, "UNCONNECTED", exporter.location, false); // Disable when disconnected
                    disconnectedData.filterItems.addAll(exporter.filterItems); // Preserve filters
                    activeExporters.put(exporter.exporterId, disconnectedData);

                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to disconnect exporter " + exporter.exporterId + ": " + e.getMessage());
                }
            }
        }

    }

    /**
     * ADDED: Update exporter network assignments when networks change
     * This should be called after network detection/updates
     */
    public void updateExporterNetworkAssignments() {

        for (ExporterData exporter : activeExporters.values()) {
            // Check if current network is valid
            boolean currentNetworkValid = exporter.networkId != null &&
                    !"UNCONNECTED".equals(exporter.networkId) &&
                    plugin.getNetworkManager().isNetworkValid(exporter.networkId);

            if (!currentNetworkValid) {
                // Exporter is unconnected or has invalid network - try to find a new network
                String newNetworkId = findAdjacentNetwork(exporter.location);

                if (newNetworkId != null && !newNetworkId.equals(exporter.networkId)) {
                    // Found a new valid network - reconnect
                    try {
                        plugin.getDatabaseManager().executeUpdate(
                                "UPDATE exporters SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                                newNetworkId, exporter.exporterId);

                        // Update in memory
                        activeExporters.remove(exporter.exporterId);
                        ExporterData updatedData = new ExporterData(exporter.exporterId, newNetworkId, exporter.location, exporter.enabled);
                        updatedData.filterItems.addAll(exporter.filterItems);
                        activeExporters.put(exporter.exporterId, updatedData);

                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to reconnect exporter " + exporter.exporterId + ": " + e.getMessage());
                    }
                } else if (!"UNCONNECTED".equals(exporter.networkId)) {
                    // No valid network found and not already marked as unconnected - disconnect
                    try {
                        plugin.getDatabaseManager().executeUpdate(
                                "UPDATE exporters SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                                "UNCONNECTED", exporter.exporterId);

                        // Update in memory
                        activeExporters.remove(exporter.exporterId);
                        ExporterData disconnectedData = new ExporterData(exporter.exporterId, "UNCONNECTED", exporter.location, false);
                        disconnectedData.filterItems.addAll(exporter.filterItems);
                        activeExporters.put(exporter.exporterId, disconnectedData);

                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to disconnect exporter " + exporter.exporterId + ": " + e.getMessage());
                    }
                }
            } else {
                // Network is valid, but check physical connectivity
                if (exporter.enabled && isExporterConnectedToNetwork(exporter)) {
                    // Auto-disable exporters that are enabled but physically disconnected
                    try {
                        toggleExporter(exporter.exporterId, false);
                        refreshExporterGUIs(exporter.exporterId);
                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to auto-disable exporter " + exporter.exporterId + ": " + e.getMessage());
                    }
                }
            }
        }

    }

    /**
     * Find a valid network adjacent to the given location
     */
    private String findAdjacentNetwork(Location location) {
        // Check adjacent locations for network blocks or cables
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    // Only check face-adjacent blocks
                    int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                    if (nonZero == 1) {
                        Location adjacent = location.clone().add(dx, dy, dz);
                        String networkId = plugin.getNetworkManager().getNetworkId(adjacent);

                        if (networkId != null && !networkId.startsWith("UNCONNECTED") &&
                                plugin.getNetworkManager().isNetworkValid(networkId)) {
                            return networkId;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Update last export timestamp
     */
    private void updateLastExport(String exporterId) {
        try {
            plugin.getDatabaseManager().executeUpdate(
                    "UPDATE exporters SET last_export = CURRENT_TIMESTAMP WHERE exporter_id = ?",
                    exporterId);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update last export timestamp: " + e.getMessage());
        }
    }


}