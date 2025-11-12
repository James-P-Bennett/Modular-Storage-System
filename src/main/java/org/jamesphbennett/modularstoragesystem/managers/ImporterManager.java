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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ImporterManager implements org.jamesphbennett.modularstoragesystem.network.NetworkManager.NetworkUpdateListener {

    private final ModularStorageSystem plugin;
    private final Map<String, ImporterData> activeImporters = new ConcurrentHashMap<>();
    private final Map<String, Integer> importerCycleIndex = new ConcurrentHashMap<>();

    public ImporterManager(ModularStorageSystem plugin) {
        this.plugin = plugin;
        loadImporters();
        startImportTask();
        // Register as a listener for network updates
        plugin.getNetworkManager().registerUpdateListener(this);
    }

    /**
     * Called when a network is updated/registered
     * No action needed - importers are updated during network detection
     */
    @Override
    public void onNetworkUpdated(String networkId) {
        // Don't update on every network change - this causes race conditions
        // Importers will auto-reconnect when they try to import
    }

    /**
     * Called when a network is removed/unregistered
     * Updates importer network assignments to handle disconnections
     */
    @Override
    public void onNetworkRemoved(String networkId) {
        // Only update when networks are removed
        updateImporterNetworkAssignments();
    }

    /**
     * Data class for importer information
     */
    public static class ImporterData {
        public final String importerId;
        public final String networkId;
        public final Location location;
        public boolean enabled;
        public final List<String> filterItems = new ArrayList<>();
        public long lastImport;

        public ImporterData(String importerId, String networkId, Location location, boolean enabled) {
            this.importerId = importerId;
            this.networkId = networkId;
            this.location = location;
            this.enabled = enabled;
            this.lastImport = System.currentTimeMillis();
        }
    }

    /**
     * Load all importers from database
     */
    private void loadImporters() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT i.importer_id, i.network_id, i.world_name, i.x, i.y, i.z, i.enabled, i.last_import " +
                             "FROM importers i")) {

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String importerId = rs.getString("importer_id");
                    String networkId = rs.getString("network_id");
                    String worldName = rs.getString("world_name");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    boolean enabled = rs.getBoolean("enabled");

                    org.bukkit.World world = plugin.getServer().getWorld(worldName);
                    if (world != null) {
                        Location location = new Location(world, x, y, z);
                        ImporterData data = new ImporterData(importerId, networkId, location, enabled);

                        // Load filters for this importer
                        loadImporterFilters(conn, data);

                        activeImporters.put(importerId, data);
                        importerCycleIndex.put(importerId, 0);
                    }
                }
            }


        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading importers: " + e.getMessage());
        }
    }

    /**
     * Load filters for a specific importer - loads hashes for compatibility with import logic
     */
    private void loadImporterFilters(Connection conn, ImporterData data) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT item_hash FROM importer_filters WHERE importer_id = ? AND filter_type = 'whitelist'")) {
            stmt.setString(1, data.importerId);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    data.filterItems.add(rs.getString("item_hash"));
                }
            }
        }
    }

    /**
     * Start the periodic import task with rate limiting
     */
    private void startImportTask() {
        int tickInterval = plugin.getConfigManager().getExportTickInterval(); // Use same interval as exporters

        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            // Get max importers to process per tick from config
            int maxImportersPerTick = plugin.getConfigManager().getMaxImportersPerTick();
            int processedCount = 0;

            // Process importers with rate limiting
            for (ImporterData importer : activeImporters.values()) {
                if (importer.enabled) {
                    // Check if we've hit the rate limit
                    if (processedCount >= maxImportersPerTick) {
                        break; // Stop processing this tick, resume next tick
                    }

                    processImport(importer);
                    processedCount++;
                }
            }

            // Debug log if we hit the rate limit
            if (processedCount >= maxImportersPerTick && activeImporters.size() > processedCount) {
                plugin.debugLog("Importer rate limit reached: processed " + processedCount + " of " + activeImporters.size() + " active importers");
            }
        }, tickInterval, tickInterval);

        // Network assignment updates are now handled via NetworkUpdateListener callbacks
        // REMOVED: Periodic validation timer (now event-driven)

    }

    /**
     * Check if importer is physically connected to its assigned network
     */
    private boolean isImporterConnectedToNetwork(ImporterData importer) {
        // First check if network exists at all
        if (!plugin.getNetworkManager().isNetworkValid(importer.networkId)) {
            return true; // Return true when network doesn't exist - means disconnected
        }

        // Look for any adjacent network blocks or cables
        Location importerLoc = importer.location;

        // Check all 6 adjacent faces for network connectivity
        Location[] adjacentLocs = {
                importerLoc.clone().add(1, 0, 0),   // East
                importerLoc.clone().add(-1, 0, 0),  // West
                importerLoc.clone().add(0, 1, 0),   // Up
                importerLoc.clone().add(0, -1, 0),  // Down
                importerLoc.clone().add(0, 0, 1),   // South
                importerLoc.clone().add(0, 0, -1)   // North
        };

        for (Location loc : adjacentLocs) {
            String networkId = plugin.getNetworkManager().getNetworkId(loc);

            if (importer.networkId.equals(networkId)) {
                return false; // Found matching network at adjacent location
            }
        }

        return true; // No adjacent network blocks found
    }

    /**
     * Public method to check if importer is connected to any network (for GUI access control)
     */
    public boolean isImporterConnectedToAnyNetwork(String importerId) {
        ImporterData data = activeImporters.get(importerId);
        if (data == null) return false;
        
        String adjacentNetworkId = findAdjacentNetwork(data.location);
        return adjacentNetworkId != null;
    }

    /**
     * Refresh any open importer GUIs for a specific importer
     */
    private void refreshImporterGUIs(String importerId) {
        try {
            // Notify GUI manager to refresh this specific importer's GUIs
            plugin.getGUIManager().refreshImporterGUIs(importerId);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to refresh importer GUIs for " + importerId + ": " + e.getMessage());
        }
    }

    /**
     * Process a single import operation
     */
    private void processImport(ImporterData importer) {
        try {
            // Check if importer is physically connected to any network
            String adjacentNetworkId = findAdjacentNetwork(importer.location);
            if (adjacentNetworkId == null) {
                // AUTO-DISABLE: Set importer as disabled when disconnected
                try {
                    toggleImporter(importer.importerId, false);

                    // Refresh any open importer GUIs to show disabled status
                    refreshImporterGUIs(importer.importerId);

                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to auto-disable importer " + importer.importerId + ": " + e.getMessage());
                }

                return;
            }

            // If connected to a different network than assigned, update the assignment
            if (!adjacentNetworkId.equals(importer.networkId)) {
                try {
                    // Update database
                    plugin.getDatabaseManager().executeUpdate(
                            "UPDATE importers SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE importer_id = ?",
                            adjacentNetworkId, importer.importerId);
                    
                    // Update in memory - replace the importer data object
                    activeImporters.remove(importer.importerId);
                    ImporterData updatedData = new ImporterData(importer.importerId, adjacentNetworkId, importer.location, importer.enabled);
                    updatedData.filterItems.addAll(importer.filterItems);
                    activeImporters.put(importer.importerId, updatedData);
                    
                    // Update the reference for the rest of this method
                    importer = updatedData;
                    
                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to update importer network assignment: " + e.getMessage());
                    return;
                }
            }

            // Find the connected inventory
            Block importerBlock = importer.location.getBlock();
            if (importerBlock.getType() != Material.PLAYER_HEAD && importerBlock.getType() != Material.PLAYER_WALL_HEAD) {
                plugin.getLogger().warning("Importer at " + importer.location + " is not a player head!");
                return;
            }

            // Get the target inventory
            Container targetContainer = getTargetContainer(importerBlock);
            if (targetContainer == null) {
                return; // No valid target
            }

            Inventory targetInventory = targetContainer.getInventory();

            // Check container type for specialized slot handling
            Material containerType = targetContainer.getBlock().getType();
            boolean isFurnace = containerType == Material.FURNACE || 
                               containerType == Material.BLAST_FURNACE || 
                               containerType == Material.SMOKER;
            boolean isBrewingStand = containerType == Material.BREWING_STAND;
            
            if (isFurnace) {
                // Import from furnace output slot only
                importFromFurnace(importer, targetContainer);
            } else if (isBrewingStand) {
                // Import from brewing stand bottom potion slots only
                importFromBrewingStand(importer, targetContainer);
            } else {
                // Import from any slot in other containers
                importFromGenericContainer(importer, targetInventory);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error processing import for " + importer.importerId + ": " + e.getMessage());
        }
    }

    /**
     * Import from furnace output slot (slot 2)
     */
    private void importFromFurnace(ImporterData importer, Container furnaceContainer) {
        try {
            Inventory furnaceInventory = furnaceContainer.getInventory();
            int outputSlot = 2; // Furnace output slot

            ItemStack outputItem = furnaceInventory.getItem(outputSlot);
            if (outputItem == null || outputItem.getType().isAir()) {
                return; // No items to import
            }

            // Check filter if enabled
            if (!importer.filterItems.isEmpty()) {
                String itemHash = plugin.getItemManager().generateItemHash(outputItem);
                if (!importer.filterItems.contains(itemHash)) {
                    return; // Item not in filter
                }
            }

            // Try to store the item in the network
            List<ItemStack> itemsToStore = new ArrayList<>();
            itemsToStore.add(outputItem.clone());

            List<ItemStack> leftoverItems = plugin.getStorageManager().storeItems(importer.networkId, itemsToStore);

            // Calculate what was actually imported
            int originalAmount = outputItem.getAmount();
            int leftoverAmount = leftoverItems.isEmpty() ? 0 : leftoverItems.getFirst().getAmount();
            int importedAmount = originalAmount - leftoverAmount;

            if (importedAmount > 0) {
                // Remove imported items from furnace
                if (leftoverAmount == 0) {
                    furnaceInventory.setItem(outputSlot, null);
                } else {
                    outputItem.setAmount(leftoverAmount);
                    furnaceInventory.setItem(outputSlot, outputItem);
                }

                importer.lastImport = System.currentTimeMillis();
                updateLastImport(importer.importerId);

                // Refresh any open terminals
                plugin.getGUIManager().refreshNetworkTerminals(importer.networkId);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error importing from furnace: " + e.getMessage());
        }
    }


    /**
     * Import from brewing stand bottom potion slots (slots 0, 1, 2)
     */
    private void importFromBrewingStand(ImporterData importer, Container brewingStandContainer) {
        try {
            Inventory brewingInventory = brewingStandContainer.getInventory();
            int[] potionSlots = {0, 1, 2}; // Bottom potion slots

            boolean anyImported = false;

            for (int slot : potionSlots) {
                ItemStack potionItem = brewingInventory.getItem(slot);
                if (potionItem == null || potionItem.getType().isAir()) {
                    continue; // No item in this slot
                }

                // Check filter if enabled - special handling for brewing stands
                if (!importer.filterItems.isEmpty()) {
                    if (!isBrewingStandItemAllowed(importer.importerId, potionItem, slot)) {
                        continue; // Item not in filter for this slot
                    }
                }

                // Try to store the item in the network
                List<ItemStack> itemsToStore = new ArrayList<>();
                itemsToStore.add(potionItem.clone());

                List<ItemStack> leftoverItems = plugin.getStorageManager().storeItems(importer.networkId, itemsToStore);

                // Calculate what was actually imported
                int originalAmount = potionItem.getAmount();
                int leftoverAmount = leftoverItems.isEmpty() ? 0 : leftoverItems.getFirst().getAmount();
                int importedAmount = originalAmount - leftoverAmount;

                if (importedAmount > 0) {
                    // Remove imported items from brewing stand
                    if (leftoverAmount == 0) {
                        brewingInventory.setItem(slot, null);
                    } else {
                        potionItem.setAmount(leftoverAmount);
                        brewingInventory.setItem(slot, potionItem);
                    }

                    anyImported = true;
                }
            }

            if (anyImported) {
                importer.lastImport = System.currentTimeMillis();
                updateLastImport(importer.importerId);

                // Refresh any open terminals
                plugin.getGUIManager().refreshNetworkTerminals(importer.networkId);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Error importing from brewing stand: " + e.getMessage());
        }
    }

    /**
     * Import from any slot in generic containers (round-robin style)
     */
    private void importFromGenericContainer(ImporterData importer, Inventory targetInventory) {
        try {
            // Get current cycle index for round-robin
            int currentIndex = importerCycleIndex.get(importer.importerId);
            int inventorySize = targetInventory.getSize();
            
            // Try each slot once, starting from current index
            for (int i = 0; i < inventorySize; i++) {
                int checkIndex = (currentIndex + i) % inventorySize;
                ItemStack slotItem = targetInventory.getItem(checkIndex);
                
                if (slotItem == null || slotItem.getType().isAir()) {
                    continue; // No item in this slot
                }

                // Check filter if enabled (if no filters, import everything)
                if (!importer.filterItems.isEmpty()) {
                    String itemHash = plugin.getItemManager().generateItemHash(slotItem);
                    if (!importer.filterItems.contains(itemHash)) {
                        continue; // Item not in filter
                    }
                }

                // Try to store the item in the network
                List<ItemStack> itemsToStore = new ArrayList<>();
                itemsToStore.add(slotItem.clone());

                List<ItemStack> leftoverItems = plugin.getStorageManager().storeItems(importer.networkId, itemsToStore);

                // Calculate what was actually imported
                int originalAmount = slotItem.getAmount();
                int leftoverAmount = leftoverItems.isEmpty() ? 0 : leftoverItems.getFirst().getAmount();
                int importedAmount = originalAmount - leftoverAmount;

                if (importedAmount > 0) {
                    // Remove imported items from container
                    if (leftoverAmount == 0) {
                        targetInventory.setItem(checkIndex, null);
                    } else {
                        slotItem.setAmount(leftoverAmount);
                        targetInventory.setItem(checkIndex, slotItem);
                    }

                    // Update cycle index for next time
                    importerCycleIndex.put(importer.importerId, (checkIndex + 1) % inventorySize);

                    importer.lastImport = System.currentTimeMillis();
                    updateLastImport(importer.importerId);

                    // Refresh any open terminals
                    plugin.getGUIManager().refreshNetworkTerminals(importer.networkId);
                    return; // Import one item per cycle
                }
            }

            // Update cycle index even if nothing was imported
            importerCycleIndex.put(importer.importerId, (currentIndex + 1) % inventorySize);

        } catch (Exception e) {
            plugin.getLogger().severe("Error importing from generic container: " + e.getMessage());
        }
    }

    /**
     * Get the target container that the importer is physically attached to
     */
    private Container getTargetContainer(Block importerBlock) {
        try {
            Block attachedBlock = null;


            if (importerBlock.getType() == Material.PLAYER_HEAD) {
                // Floor mounted head - check block below
                attachedBlock = importerBlock.getRelative(BlockFace.DOWN);
            } else if (importerBlock.getType() == Material.PLAYER_WALL_HEAD) {
                // Wall mounted head - check block it's attached to
                org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) importerBlock.getBlockData();
                BlockFace facing = directional.getFacing();
                // The block the wall head is attached to is in the opposite direction
                attachedBlock = importerBlock.getRelative(facing.getOppositeFace());
            }

            // Check if the attached block is a container
            if (attachedBlock != null) {
                if (attachedBlock.getState() instanceof Container container) {
                    return container;
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error checking attached block for importer: " + e.getMessage());
        }

        return null;
    }

    /**
     * Remove an importer
     */
    public void removeImporter(String importerId) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Delete importer (filters cascade delete)
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM importers WHERE importer_id = ?")) {
                stmt.setString(1, importerId);
                stmt.executeUpdate();
            }
        });

        activeImporters.remove(importerId);
        importerCycleIndex.remove(importerId);
    }

    /**
     * Get importer at location
     */
    public ImporterData getImporterAtLocation(Location location) {
        for (ImporterData data : activeImporters.values()) {
            if (data.location.equals(location)) {
                return data;
            }
        }
        return null;
    }

    /**
     * Toggle importer enabled state
     */
    public void toggleImporter(String importerId, boolean enabled) throws SQLException {
        ImporterData data = activeImporters.get(importerId);
        if (data != null) {
            data.enabled = enabled;

            plugin.getDatabaseManager().executeUpdate(
                    "UPDATE importers SET enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE importer_id = ?",
                    enabled, importerId);

        }
    }

    /**
     * Update filter for an importer
     */
    public void updateImporterFilter(String importerId, List<ItemStack> filterItems) throws SQLException {
        ImporterData data = activeImporters.get(importerId);
        if (data == null) return;

        plugin.getDatabaseManager().executeTransaction(conn -> {
            // Clear existing filters
            try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM importer_filters WHERE importer_id = ?")) {
                stmt.setString(1, importerId);
                stmt.executeUpdate();
            }

            // Add new filters with both hashes and item data
            if (!filterItems.isEmpty()) {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT INTO importer_filters (importer_id, item_hash, item_data, filter_type) VALUES (?, ?, ?, 'whitelist')")) {

                    for (ItemStack item : filterItems) {
                        // Ensure we store single-item templates
                        ItemStack template = item.clone();
                        template.setAmount(1);

                        String itemHash = plugin.getItemManager().generateItemHash(template);
                        String itemData = plugin.getStorageManager().serializeItemStack(template);

                        stmt.setString(1, importerId);
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
     * Get filter items for an importer
     */
    public List<ItemStack> getImporterFilterItems(String importerId) {
        List<ItemStack> items = new ArrayList<>();

        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT item_data FROM importer_filters WHERE importer_id = ? AND item_data IS NOT NULL")) {

            stmt.setString(1, importerId);
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
            plugin.getLogger().severe("Error loading importer filters: " + e.getMessage());
        }

        return items;
    }

    /**
     * Handle network invalidation - disconnect importers from invalid networks
     */
    public void handleNetworkInvalidated(String networkId) {
        for (ImporterData importer : activeImporters.values()) {
            if (networkId.equals(importer.networkId)) {
                try {
                    // Mark importer as disconnected in database
                    plugin.getDatabaseManager().executeUpdate(
                            "UPDATE importers SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE importer_id = ?",
                            "UNCONNECTED", importer.importerId);

                    // Update in memory - create new ImporterData with UNCONNECTED status
                    activeImporters.remove(importer.importerId);
                    ImporterData disconnectedData = new ImporterData(importer.importerId, "UNCONNECTED", importer.location, false);
                    disconnectedData.filterItems.addAll(importer.filterItems);
                    activeImporters.put(importer.importerId, disconnectedData);

                } catch (SQLException e) {
                    plugin.getLogger().warning("Failed to disconnect importer " + importer.importerId + ": " + e.getMessage());
                }
            }
        }

    }

    /**
     * Create a new importer at the specified location
     */
    public String createImporter(Location location, String networkId) throws SQLException {
        String importerId = java.util.UUID.randomUUID().toString();
        String finalNetworkId = (networkId != null) ? networkId : "UNCONNECTED";

        plugin.getDatabaseManager().executeUpdate(
                "INSERT INTO importers (importer_id, network_id, world_name, x, y, z, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                importerId, finalNetworkId, location.getWorld().getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(), false);

        // Add to memory
        ImporterData importerData = new ImporterData(importerId, finalNetworkId, location, false);
        activeImporters.put(importerId, importerData);
        importerCycleIndex.put(importerId, 0); // Initialize cycle index

        return importerId;
    }

    public void updateImporterNetworkAssignments() {

        for (ImporterData importer : activeImporters.values()) {
            // Check if current network is valid
            boolean currentNetworkValid = importer.networkId != null &&
                    !"UNCONNECTED".equals(importer.networkId) &&
                    plugin.getNetworkManager().isNetworkValid(importer.networkId);

            if (!currentNetworkValid) {
                // Importer is unconnected or has invalid network - try to find a new network
                String newNetworkId = findAdjacentNetwork(importer.location);

                if (newNetworkId != null && !newNetworkId.equals(importer.networkId)) {
                    // Found a new valid network - reconnect
                    try {
                        plugin.getDatabaseManager().executeUpdate(
                                "UPDATE importers SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE importer_id = ?",
                                newNetworkId, importer.importerId);

                        // Update in memory
                        activeImporters.remove(importer.importerId);
                        ImporterData updatedData = new ImporterData(importer.importerId, newNetworkId, importer.location, importer.enabled);
                        updatedData.filterItems.addAll(importer.filterItems);
                        activeImporters.put(importer.importerId, updatedData);

                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to reconnect importer " + importer.importerId + ": " + e.getMessage());
                    }
                } else if (!"UNCONNECTED".equals(importer.networkId)) {
                    // No valid network found and not already marked as unconnected - disconnect
                    try {
                        plugin.getDatabaseManager().executeUpdate(
                                "UPDATE importers SET network_id = ?, updated_at = CURRENT_TIMESTAMP WHERE importer_id = ?",
                                "UNCONNECTED", importer.importerId);

                        // Update in memory
                        activeImporters.remove(importer.importerId);
                        ImporterData disconnectedData = new ImporterData(importer.importerId, "UNCONNECTED", importer.location, false);
                        disconnectedData.filterItems.addAll(importer.filterItems);
                        activeImporters.put(importer.importerId, disconnectedData);

                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to disconnect importer " + importer.importerId + ": " + e.getMessage());
                    }
                }
            } else {
                // Network is valid, but check physical connectivity
                if (importer.enabled && isImporterConnectedToNetwork(importer)) {
                    // Auto-disable importers that are enabled but physically disconnected
                    try {
                        toggleImporter(importer.importerId, false);
                        refreshImporterGUIs(importer.importerId);
                    } catch (SQLException e) {
                        plugin.getLogger().warning("Failed to auto-disable importer " + importer.importerId + ": " + e.getMessage());
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
     * Update last import timestamp
     */
    private void updateLastImport(String importerId) {
        try {
            plugin.getDatabaseManager().executeUpdate(
                    "UPDATE importers SET last_import = CURRENT_TIMESTAMP WHERE importer_id = ?",
                    importerId);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update last import timestamp: " + e.getMessage());
        }
    }

    /**
     * Check if an item is allowed for import from a specific brewing stand slot
     */
    private boolean isBrewingStandItemAllowed(String importerId, ItemStack item, int slot) {
        try {
            // Parse brewing stand filters
            BrewingStandFilters filters = parseBrewingStandFilters(importerId);
            
            // Check if there's a filter for this specific bottle slot
            if (slot >= 0 && slot < 3 && filters.bottleFilters[slot] != null) {
                String itemHash = plugin.getItemManager().generateItemHash(item);
                String filterHash = plugin.getItemManager().generateItemHash(filters.bottleFilters[slot]);
                return itemHash.equals(filterHash);
            }
            
            // If no specific filter for this slot, don't import
            return false;
            
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking brewing stand filter for " + importerId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Parse brewing stand specific filters from the importer's filter items
     */
    private BrewingStandFilters parseBrewingStandFilters(String importerId) {
        BrewingStandFilters filters = new BrewingStandFilters();
        
        try {
            List<ItemStack> filterItems = getImporterFilterItems(importerId);
            
            for (ItemStack item : filterItems) {
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    Component displayName = item.getItemMeta().displayName();
                    if (displayName == null) continue;
                    
                    // Use PlainTextComponentSerializer for reliable text extraction
                    String nameText = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName);
                    
                    if (nameText.startsWith("BREWING_BOTTLE_")) {
                        try {
                            int bottleIndex = Integer.parseInt(nameText.substring("BREWING_BOTTLE_".length()));
                            if (bottleIndex >= 0 && bottleIndex < 3) {
                                // Remove the marker and store the actual filter item
                                ItemStack filterItem = item.clone();
                                ItemMeta meta = filterItem.getItemMeta();
                                meta.displayName(null); // Remove the marker name
                                filterItem.setItemMeta(meta);
                                filters.bottleFilters[bottleIndex] = filterItem;
                            }
                        } catch (NumberFormatException e) {
                            plugin.getLogger().warning("Invalid bottle index in brewing filter: " + nameText);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error parsing brewing stand filters for " + importerId + ": " + e.getMessage());
        }
        
        return filters;
    }

    /**
     * Data class for brewing stand filter settings
     */
    private static class BrewingStandFilters {
        ItemStack[] bottleFilters = new ItemStack[3]; // For slots 0, 1, 2
    }
}