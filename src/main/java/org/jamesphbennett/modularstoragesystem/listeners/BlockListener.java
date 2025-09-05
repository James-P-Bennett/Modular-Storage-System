package org.jamesphbennett.modularstoragesystem.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Crafter;
import org.bukkit.util.Vector;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;
import org.jamesphbennett.modularstoragesystem.managers.ItemManager;
import org.jamesphbennett.modularstoragesystem.managers.ExporterManager;
import org.jamesphbennett.modularstoragesystem.managers.ImporterManager;
import org.jamesphbennett.modularstoragesystem.managers.NetworkSecurityManager;
import static org.jamesphbennett.modularstoragesystem.managers.NetworkSecurityManager.PermissionType;
import org.jamesphbennett.modularstoragesystem.network.NetworkConnectivityManager;
import org.jamesphbennett.modularstoragesystem.network.NetworkManager;
import org.jamesphbennett.modularstoragesystem.network.NetworkInfo;
import org.jamesphbennett.modularstoragesystem.network.CableManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BlockListener implements Listener {

    private final ModularStorageSystem plugin;
    private final ItemManager itemManager;
    private final NetworkManager networkManager;
    private final CableManager cableManager;

    public BlockListener(ModularStorageSystem plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.networkManager = plugin.getNetworkManager();
        this.cableManager = plugin.getCableManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        Block block = event.getBlockPlaced();
        Location location = block.getLocation();

        if (itemManager.isStorageDisk(item)) {
            event.setCancelled(true);
            Component message = plugin.getMessageManager().getMessageComponent(player, "errors.placement.storage-disks-blocks");
            player.sendMessage(message);
            return;
        }

        // Prevent MSS components from being placed as blocks
        if (itemManager.isMSSComponent(item)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.mss-components-blocks"));
            return;
        }

        // Check if it's one of our CUSTOM network blocks, cables, exporters, importers, or security terminals
        if (!itemManager.isNetworkBlock(item) && !itemManager.isNetworkCable(item) && !itemManager.isExporter(item) && !itemManager.isImporter(item)) {
            return;
        }

        // Validate placement permissions
        if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("modularstoragesystem.use")) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.access-denied-place-blocks"));
            return;
        }

        // Check for security terminal permissions for block modification
        // We need to check adjacent blocks to see if there's a security terminal network
        String adjacentNetworkId = null;
        for (Location adjacent : getAdjacentLocations(location)) {
            if (isCustomNetworkBlock(adjacent.getBlock()) || cableManager.isCustomNetworkCable(adjacent.getBlock())) {
                adjacentNetworkId = networkManager.getNetworkId(adjacent);
                if (adjacentNetworkId != null) {
                    break;
                }
            }
        }

        // If we found an adjacent network, check block modification permissions
        if (adjacentNetworkId != null && !player.hasPermission("mss.admin") && !plugin.getSecurityManager().hasPermission(player, adjacentNetworkId, PermissionType.BLOCK_MODIFICATION)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.access-denied-place-blocks"));
            return;
        }

        // Handle exporter placement
        if (itemManager.isExporter(item)) {
            // Check if exporter is being placed against (attached to) an MSS block
            Block blockAgainst = event.getBlockAgainst();
            if (isCustomMSSBlock(blockAgainst)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.exporter-cannot-attach"));
                return;
            }

            // Check if there's a valid network nearby to connect to (optional)
            String nearbyNetworkId = null;
            for (Location adjacent : getAdjacentLocations(location)) {
                if (isCustomNetworkBlock(adjacent.getBlock()) || cableManager.isCustomNetworkCable(adjacent.getBlock()) || isCustomExporter(adjacent.getBlock())) {
                    nearbyNetworkId = networkManager.getNetworkId(adjacent);
                    if (nearbyNetworkId != null) {
                        break;
                    }
                }
            }

            // If no network found, use placeholder - exporter will remain inactive until connected
            if (nearbyNetworkId == null) {
                nearbyNetworkId = "UNCONNECTED";
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.exporter-unconnected"));
            } else {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.exporter-connected"));
            }

            // Mark as custom block
            try {
                markLocationAsCustomBlock(location, "EXPORTER");

                // Create the exporter in the manager
                String exporterId = plugin.getExporterManager().createExporter(location, nearbyNetworkId);
                plugin.getLogger().info("Created exporter " + exporterId + " for player " + player.getName() + " at " + location +
                        (nearbyNetworkId.equals("UNCONNECTED") ? " (unconnected)" : " (connected to " + nearbyNetworkId + ")"));

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.placement.block-error", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error creating exporter: " + e.getMessage());
                event.setCancelled(true);
                return;
            }
            return; // Don't continue to network detection for exporters
        }

        // Handle importer placement
        if (itemManager.isImporter(item)) {
            // Check if importer is being placed against (attached to) an MSS block
            Block blockAgainst = event.getBlockAgainst();
            if (isCustomMSSBlock(blockAgainst)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.importer-cannot-attach"));
                return;
            }

            // Check if there's a valid network nearby to connect to (optional)
            String nearbyNetworkId = null;
            for (Location adjacent : getAdjacentLocations(location)) {
                if (isCustomNetworkBlock(adjacent.getBlock()) || cableManager.isCustomNetworkCable(adjacent.getBlock()) || isCustomExporter(adjacent.getBlock()) || isCustomImporter(adjacent.getBlock())) {
                    nearbyNetworkId = networkManager.getNetworkId(adjacent);
                    if (nearbyNetworkId != null) {
                        break;
                    }
                }
            }

            // If no network found, use placeholder - importer will remain inactive until connected
            if (nearbyNetworkId == null) {
                nearbyNetworkId = "UNCONNECTED";
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.importer-unconnected"));
            } else {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.importer-connected"));
            }

            // Mark as custom block
            try {
                markLocationAsCustomBlock(location, "IMPORTER");

                // Create the importer in the manager
                String importerId = plugin.getImporterManager().createImporter(location, nearbyNetworkId);
                plugin.getLogger().info("Created importer " + importerId + " for player " + player.getName() + " at " + location +
                        (nearbyNetworkId.equals("UNCONNECTED") ? " (unconnected)" : " (connected to " + nearbyNetworkId + ")"));

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.placement.block-error", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error creating importer: " + e.getMessage());
                event.setCancelled(true);
                return;
            }
            return; // Don't continue to network detection for importers
        }

        // Handle security terminal placement
        if (itemManager.isSecurityTerminal(item)) {
            // FIRST: Check for security terminal conflicts BEFORE placing using NetworkConnectivityManager
            NetworkConnectivityManager connectivityManager = new NetworkConnectivityManager(plugin);
            NetworkConnectivityManager.ConflictResult conflictResult = connectivityManager.checkPlacementConflicts(location, NetworkConnectivityManager.BlockType.SECURITY_TERMINAL);
            if (conflictResult.hasConflict()) {
                event.setCancelled(true);
                Component message = MiniMessage.miniMessage().deserialize(conflictResult.getMessage());
                player.sendMessage(message);
                return;
            }
            
            // THEN: Mark this location as containing our custom block in the database (SYNCHRONOUSLY)
            try {
                markLocationAsCustomBlock(location, "SECURITY_TERMINAL");
                Component message = plugin.getMessageManager().getMessageComponent(player, "success.placement.security-terminal");
                player.sendMessage(message);
            } catch (Exception e) {
                plugin.getLogger().severe("Error marking security terminal location: " + e.getMessage());
                event.setCancelled(true);
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.placement.block-error", "error", e.getMessage());
                player.sendMessage(message);
                return;
            }
            // Continue to network detection for security terminals
        } else if (itemManager.isNetworkCable(item)) {
            // Check if placing this cable would connect to security terminals the player can't modify
            if (!canPlaceCableNearSecurityTerminals(player, location)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.access-denied-manage-network"));
                return;
            }
            
            if (!cableManager.handleCablePlacement(player, location)) {
                event.setCancelled(true);
                return;
            }
            // Cable placement successful, continue to network detection
        } else {
            // Handle network block placement with linking prevention
            String blockType = getBlockTypeFromItem(item);

            // Check for network linking conflicts
            String linkingConflict = cableManager.checkNetworkLinkingConflict(location);
            if (linkingConflict != null) {
                event.setCancelled(true);
                Component message = MiniMessage.miniMessage().deserialize(linkingConflict);
                player.sendMessage(message);
                return;
            }

            // Check for storage server conflicts
            String serverConflict = cableManager.checkStorageServerConflict(location, blockType);
            if (serverConflict != null) {
                event.setCancelled(true);
                Component message = MiniMessage.miniMessage().deserialize(serverConflict);
                player.sendMessage(message);
                return;
            }

            // Check for security terminal conflicts for all MSS blocks
            String securityConflictForMSS = cableManager.checkSecurityTerminalConflict(location, blockType);
            if (securityConflictForMSS != null) {
                event.setCancelled(true);
                Component message = MiniMessage.miniMessage().deserialize(securityConflictForMSS);
                player.sendMessage(message);
                return;
            }

            // Check if placing this MSS block would cause an exporter to be attached to it
            if (isMSSBlockType(blockType)) {
                String exporterConflict = checkExporterAttachmentConflict(location);
                if (exporterConflict != null) {
                    event.setCancelled(true);
                    Component message = MiniMessage.miniMessage().deserialize(exporterConflict);
                player.sendMessage(message);
                    return;
                }
            }

            // Mark this location as containing our custom block in the database (SYNCHRONOUSLY)
            try {
                markLocationAsCustomBlock(location, blockType);
            } catch (Exception e) {
                plugin.getLogger().severe("Error marking custom block location: " + e.getMessage());
                // If we can't mark the block, cancel the placement
                event.setCancelled(true);
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.placement.block-error", "error", e.getMessage());
                player.sendMessage(message);
                return;
            }
        }

        // Security terminal conflict checking is handled in the main block placement logic above
        // No need for duplicate checking here

        // Schedule network detection and block direction setting for next tick (after block is fully placed)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // Set block direction after the block is fully placed
            setBlockDirectionDelayed(block, player, item);
        });

        // Schedule network detection for next tick (after block is placed)
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                NetworkInfo network = networkManager.detectNetwork(location);


                // Create security terminal if this was a security terminal placement
                // Do this after checking the limit but before checking network validity so it works even for standalone terminals
                if (itemManager.isSecurityTerminal(item)) {
                    try {
                        String networkId = null;
                        if (network != null && network.isValid()) {
                            networkId = networkManager.getNetworkId(location);
                        } else {
                            // Try to find network ID from adjacent blocks since network detection failed
                            for (Location adjacent : getAdjacentLocations(location)) {
                                if (isCustomNetworkBlock(adjacent.getBlock()) || cableManager.isCustomNetworkCable(adjacent.getBlock())) {
                                    String adjNetworkId = networkManager.getNetworkId(adjacent);
                                    if (adjNetworkId != null) {
                                        networkId = adjNetworkId;
                                        break;
                                    }
                                }
                            }
                        }
                        plugin.getSecurityManager().createSecurityTerminal(location, player, networkId);
                        plugin.debugLog("Created security terminal at " + location + " for player " + player.getName() + " with networkId " + networkId);
                        
                        // If we have a network, update the association immediately
                        if (networkId != null) {
                            plugin.getSecurityManager().updateTerminalNetwork(location, networkId);
                            plugin.debugLog("Immediately updated security terminal network association to " + networkId);
                        } else {
                            // Schedule a delayed network association check in case network forms later
                            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                                try {
                                    String delayedNetworkId = networkManager.getNetworkId(location);
                                    if (delayedNetworkId != null) {
                                        plugin.getSecurityManager().updateTerminalNetwork(location, delayedNetworkId);
                                    }
                                } catch (Exception ex) {
                                    plugin.getLogger().severe("Error in delayed security terminal network update: " + ex.getMessage());
                                }
                            }, 20L); // 1 second delay
                        }
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error creating security terminal: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                if (network != null && network.isValid()) {
                    // Check network size limit (blocks only, not cables)
                    int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
                    int networkBlockCount = network.getAllBlocks().size() - network.getNetworkCables().size();

                    if (networkBlockCount > maxBlocks) {
                        // Network exceeds size limit - break the block and give it back
                        block.setType(Material.AIR);
                        removeCustomBlockMarker(location);
                        block.getWorld().dropItemNaturally(location, item);
                        Component message = plugin.getMessageManager().getMessageComponent(player, "errors.placement.network-size-limit", "max", String.valueOf(maxBlocks));
                        player.sendMessage(message);
                        return;
                    }

                    // Register the network with comprehensive drive bay restoration
                    networkManager.registerNetwork(network, player.getUniqueId());

                    // Update security terminal network association if this was a security terminal
                    if (itemManager.isSecurityTerminal(item)) {
                        try {
                            String networkId = networkManager.getNetworkId(location);
                            plugin.getSecurityManager().updateTerminalNetwork(location, networkId);
                            plugin.debugLog("Updated security terminal network association to " + networkId);
                        } catch (Exception e) {
                            plugin.getLogger().severe("Error updating security terminal network: " + e.getMessage());
                        }
                    }

                    // Check if any drive bay contents were restored
                    boolean hasRestoredContent = plugin.getDisksManager().checkForRestoredContent(network.getDriveBays());

                    // Only show connection message in debug mode
                    if (plugin.getConfigManager().isDebugMode()) {
                        Component message = plugin.getMessageManager().getMessageComponent(player, "success.placement.network-connected", "drive_bays", String.valueOf(network.getDriveBays().size()), "terminals", String.valueOf(network.getTerminals().size()));
                        player.sendMessage(message);
                    }

                    // Show restoration message only in debug mode - this happens frequently during network building
                    if (hasRestoredContent && plugin.getConfigManager().isDebugMode()) {
                        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.drive-bay-restored"));
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.check-terminals"));
                        }, 10L);
                    }
                } else {
                    if (itemManager.isStorageServer(item)) {
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.storage-server-requirements"));
                    } else if (itemManager.isNetworkCable(item)) {
                        // Remove cable placement message - too spammy during network construction
                    } else {
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.block-needs-connection"));
                    }
                }

                plugin.getExporterManager().updateExporterNetworkAssignments();
                plugin.getImporterManager().updateImporterNetworkAssignments();
                
                // Refresh terminals for the network (important for reconnections)
                if (network != null && network.isValid()) {
                    String finalNetworkId = networkManager.getNetworkId(location);
                    if (finalNetworkId != null) {
                        plugin.getGUIManager().refreshNetworkTerminals(finalNetworkId);
                        // Update drive bay network associations for reconnections
                        updateDriveBayNetworkAssociations(finalNetworkId, network.getDriveBays());
                    }
                }

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.network.connection-lost");
                player.sendMessage(message);
                plugin.getLogger().severe("Error setting up network: " + e.getMessage());
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location location = block.getLocation();

        // Check if it's one of our CUSTOM network blocks, cables, exporters, or importers
        if (!isCustomNetworkBlockOrCableOrExporterOrImporter(block)) {
            return;
        }

        try {
            String networkId = networkManager.getNetworkId(location);

            // Check security terminal permissions for block modification
            if (!player.hasPermission("mss.admin") && !plugin.getSecurityManager().hasPermission(player, networkId, PermissionType.BLOCK_MODIFICATION, location)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.access-denied-remove-blocks"));
                return;
            }

            // Handle exporter removal
            if (isCustomExporter(block)) {
                try {
                    // Get the exporter data before removing
                    ExporterManager.ExporterData exporterData = plugin.getExporterManager().getExporterAtLocation(location);
                    if (exporterData != null) {
                        // Remove from exporter manager
                        plugin.getExporterManager().removeExporter(exporterData.exporterId);
                        plugin.getLogger().info("Removed exporter " + exporterData.exporterId + " at " + location);
                    }

                    // Drop the custom item
                    event.setDropItems(false);
                    ItemStack customItem = plugin.getItemManager().createExporter();
                    if (customItem != null) {
                        block.getWorld().dropItemNaturally(location, customItem);
                    }

                    // Remove custom block marker
                    removeCustomBlockMarker(location);

                } catch (Exception e) {
                    Component message = plugin.getMessageManager().getMessageComponent(player, "errors.removal.error-exporter", "error", e.getMessage());
                    player.sendMessage(message);
                    plugin.getLogger().severe("Error removing exporter: " + e.getMessage());
                }
                return; // Exporters don't affect network topology, so return early
            }

            // Handle importer removal
            if (isCustomImporter(block)) {
                try {
                    // Get the importer data before removing
                    ImporterManager.ImporterData importerData = plugin.getImporterManager().getImporterAtLocation(location);
                    if (importerData != null) {
                        // Remove from importer manager
                        plugin.getImporterManager().removeImporter(importerData.importerId);
                        plugin.getLogger().info("Removed importer " + importerData.importerId + " at " + location);
                    }

                    // Drop the custom item
                    event.setDropItems(false);
                    ItemStack customItem = plugin.getItemManager().createImporter();
                    if (customItem != null) {
                        block.getWorld().dropItemNaturally(location, customItem);
                    }

                    // Remove custom block marker
                    removeCustomBlockMarker(location);

                } catch (Exception e) {
                    Component message = plugin.getMessageManager().getMessageComponent(player, "errors.removal.error-importer", "error", e.getMessage());
                    player.sendMessage(message);
                    plugin.getLogger().severe("Error removing importer: " + e.getMessage());
                }
                return; // Importers don't affect network topology, so return early
            }

            // Handle security terminal removal
            if (isCustomSecurityTerminal(block)) {
                try {
                    // Remove from security manager
                    plugin.getSecurityManager().removeSecurityTerminal(location);
                    plugin.getLogger().info("Removed security terminal at " + location);

                    // Drop the custom item
                    event.setDropItems(false);
                    ItemStack customItem = plugin.getItemManager().createSecurityTerminal();
                    if (customItem != null) {
                        block.getWorld().dropItemNaturally(location, customItem);
                    }

                    // Remove custom block marker
                    removeCustomBlockMarker(location);

                } catch (Exception e) {
                    Component message = plugin.getMessageManager().getMessageComponent(player, "errors.removal.error-security-terminal", "error", e.getMessage());
                    player.sendMessage(message);
                    plugin.getLogger().severe("Error removing security terminal: " + e.getMessage());
                }
                // Security terminals are network blocks, so continue to network processing
            }

            if (isCustomDriveBay(block)) {
                if (networkId != null) {
                    // Drive bay is part of a network - use network-aware dropping
                    plugin.getDisksManager().dropDriveBayContents(location, networkId);
                } else {
                    // Drive bay is not part of a network - check for orphaned/standalone contents
                    plugin.getLogger().info("Drive bay at " + location + " is not part of a network, checking for standalone contents");
                    plugin.getDisksManager().dropDriveBayContentsWithoutNetwork(location);
                }
            }

            // Always drop the custom item and prevent vanilla drops
            event.setDropItems(false);
            ItemStack customItem = getCustomItemForBlock(block);
            if (customItem != null) {
                block.getWorld().dropItemNaturally(location, customItem);
            }

            // Remove custom block marker
            removeCustomBlockMarker(location);

            // Update the network after the block is broken (if it was part of a network)
            if (networkId != null) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        // Try to re-detect the network from remaining blocks and handle fragmentation
                        boolean networkStillValid = false;
                        Set<Location> processedLocations = new HashSet<>();

                        // Check each adjacent location to see if it forms a valid network segment
                        for (Location adjacent : getAdjacentLocations(location)) {
                            if (isCustomNetworkBlockOrCableOrExporterOrImporter(adjacent.getBlock()) && !processedLocations.contains(adjacent)) {
                                NetworkInfo updatedNetwork = networkManager.detectNetwork(adjacent);
                                if (updatedNetwork != null && updatedNetwork.isValid()) {
                                    // Mark all blocks in this network segment as processed
                                    processedLocations.addAll(updatedNetwork.getAllBlocks());
                                    processedLocations.addAll(updatedNetwork.getNetworkCables());
                                    
                                    // Register this network segment (will get a new ID if different from original)
                                    networkManager.registerNetwork(updatedNetwork, player.getUniqueId());
                                    networkStillValid = true;
                                    
                                    // Refresh terminals for this network segment
                                    String newNetworkId = networkManager.getNetworkId(adjacent);
                                    if (newNetworkId != null) {
                                        plugin.getGUIManager().refreshNetworkTerminals(newNetworkId);
                                    }
                                    
                                    plugin.debugLog("Detected network segment with " + updatedNetwork.getAllBlocks().size() + " blocks and " + updatedNetwork.getNetworkCables().size() + " cables");
                                }
                            }
                        }

                        if (!networkStillValid) {
                            // Network was completely broken - refresh terminals (to close them) and unregister it
                            try {
                                plugin.getGUIManager().refreshNetworkTerminals(networkId);
                                networkManager.unregisterNetwork(networkId);
                                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.network-dissolved"));
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error unregistering network " + networkId + ": " + e.getMessage());
                            }
                        }
                        
                        // Always refresh terminals for the original network ID to handle disconnections
                        plugin.getGUIManager().refreshNetworkTerminals(networkId);

                        plugin.getExporterManager().updateExporterNetworkAssignments();
                        plugin.getImporterManager().updateImporterNetworkAssignments();

                    } catch (Exception e) {
                        Component message = plugin.getMessageManager().getMessageComponent(player, "errors.removal.error-network-update", "error", e.getMessage());
                        player.sendMessage(message);
                        plugin.getLogger().severe("Error updating network after block break: " + e.getMessage());
                    }
                });
            }

        } catch (Exception e) {
            Component message = plugin.getMessageManager().getMessageComponent(player, "errors.removal.error-block-break", "error", e.getMessage());
            player.sendMessage(message);
            plugin.getLogger().severe("Error in block break event: " + e.getMessage());
        }
    }

    /**
     * Handle entity explosions (creepers, TNT, etc.) that destroy MSS blocks
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.isCancelled()) return;
        plugin.getExplosionManager().handleExplosion(event.blockList(), event.getLocation());
    }

    /**
     * Handle block explosions (beds, respawn anchors, etc.) that destroy MSS blocks
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.isCancelled()) return;
        plugin.getExplosionManager().handleExplosion(event.blockList(), event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        // Only handle RIGHT CLICK events
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // Only handle main hand interactions to prevent duplicate events
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) {
            return;
        }

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        // Check if player is awaiting search input and cancel ALL interactions
        if (plugin.getGUIManager().isAwaitingSearchInput(player)) {
            // Player is in search mode - cancel the search and the interaction
            event.setCancelled(true);
            plugin.getGUIManager().cancelSearchInput(player);
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.search-cancelled"));
            plugin.getLogger().info("Cancelled search input for player " + player.getName() + " due to block interaction");
            return;
        }

        // Handle Exporter interactions (only custom ones)
        if (isCustomExporter(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("modularstoragesystem.use")) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.no-use-permission-exporters"));
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());
                if (networkId == null) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.exporter-not-connected"));
                    return;
                }

                // Check security permissions (unless player has admin bypass)
                if (!player.hasPermission("mss.admin") && !plugin.getSecurityManager().hasPermission(player, networkId, PermissionType.DRIVE_BAY_ACCESS, block.getLocation())) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.access-denied-access-items"));
                    return;
                }

                // Get the exporter from the manager
                ExporterManager.ExporterData exporterData = plugin.getExporterManager().getExporterAtLocation(block.getLocation());
                if (exporterData == null) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.exporter-data-not-found"));
                    return;
                }

                // Open the exporter GUI
                plugin.getGUIManager().openExporterGUI(player, block.getLocation(), exporterData.exporterId, networkId);

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-exporter", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error accessing exporter: " + e.getMessage());
            }
            return;
        }

        // Handle Importer interactions (only custom ones)
        if (isCustomImporter(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("modularstoragesystem.use")) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.no-use-permission-importers"));
                return;
            }

            try {
                // Find network ID from adjacent network blocks/cables (importers should carry network signals like exporters)
                String networkId = null;
                for (Location adjacent : getAdjacentLocations(block.getLocation())) {
                    if (isCustomNetworkBlock(adjacent.getBlock()) || cableManager.isCustomNetworkCable(adjacent.getBlock()) || isCustomExporter(adjacent.getBlock()) || isCustomImporter(adjacent.getBlock())) {
                        networkId = networkManager.getNetworkId(adjacent);
                        if (networkId != null) {
                            break;
                        }
                    }
                }
                
                if (networkId == null) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.importer-not-connected"));
                    return;
                }

                // Check security permissions (unless player has admin bypass)
                if (!player.hasPermission("mss.admin") && !plugin.getSecurityManager().hasPermission(player, networkId, PermissionType.DRIVE_BAY_ACCESS, block.getLocation())) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.access-denied-access-items"));
                    return;
                }

                // Get importer data
                ImporterManager.ImporterData importerData = plugin.getImporterManager().getImporterAtLocation(block.getLocation());
                if (importerData == null) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.importer-data-not-found"));
                    return;
                }

                // Open the importer GUI
                plugin.getGUIManager().openImporterGUI(player, block.getLocation(), importerData.importerId, networkId);

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-importer", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error accessing importer: " + e.getMessage());
            }
            return;
        }

        // Handle MSS Terminal interactions (only custom ones)
        if (isCustomMSSTerminal(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("modularstoragesystem.use")) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.no-use-permission-terminals"));
                return;
            }

            try {
                String networkId = networkManager.getNetworkId(block.getLocation());
                if (networkId == null || !networkManager.isNetworkValid(networkId)) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.terminal-not-connected"));
                    return;
                }

                // Check security permissions (unless player has admin bypass)
                if (!player.hasPermission("mss.admin") && !plugin.getSecurityManager().hasPermission(player, networkId, PermissionType.DRIVE_BAY_ACCESS, block.getLocation())) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.access-denied-access-items"));
                    return;
                }

                // Open the terminal GUI
                plugin.getGUIManager().openTerminalGUI(player, block.getLocation(), networkId);

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-terminal", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error accessing terminal: " + e.getMessage());
            }
            return;
        }

        // Handle Storage Server interactions (only custom ones)
        if (isCustomStorageServer(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("modularstoragesystem.use")) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.no-use-permission-storage-server"));
                return;
            }

            try {
                // Check security permissions (unless player has admin bypass)
                String networkId = networkManager.getNetworkId(block.getLocation());
                if (networkId != null && !player.hasPermission("mss.admin") && !plugin.getSecurityManager().hasPermission(player, networkId, PermissionType.DRIVE_BAY_ACCESS, block.getLocation())) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.access-denied-access-items"));
                    return;
                }
                
                // Always display storage server information, regardless of network validity
                displayStorageServerInfo(player, block.getLocation());

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-storage-server", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error accessing storage server: " + e.getMessage());
            }
            return;
        }

        // Handle Security Terminal interactions (only custom ones)
        if (isCustomSecurityTerminal(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("modularstoragesystem.use")) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.access-denied-manage-network"));
                return;
            }

            try {
                // Check if player is the owner
                if (!plugin.getSecurityManager().isOwner(player, block.getLocation())) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.access-denied-manage-network"));
                    return;
                }

                // Get security terminal data
                var terminalData = plugin.getSecurityManager().getSecurityTerminal(block.getLocation());
                if (terminalData == null) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.network.security-terminal-data-not-found"));
                    return;
                }

                // Open security terminal GUI
                plugin.getGUIManager().openSecurityTerminalGUI(player, block.getLocation(), terminalData.terminalId, terminalData.ownerUuid);

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-security-terminal", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error accessing security terminal: " + e.getMessage());
            }
            return;
        }

        // Handle Drive Bay interactions (only custom ones)
        if (isCustomDriveBay(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("modularstoragesystem.use")) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.no-use-permission-drive-bays"));
                return;
            }

            try {
                // Check security terminal permissions
                String networkId = networkManager.getNetworkId(block.getLocation());
                if (!player.hasPermission("mss.admin") && !plugin.getSecurityManager().hasPermission(player, networkId, PermissionType.DRIVE_BAY_ACCESS, block.getLocation())) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.access-denied-access-items"));
                    return;
                }

                // Allow access even without network, show status message
                if (networkId == null) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.drive-bay-no-network"));
                } else if (!networkManager.isNetworkValid(networkId)) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.drive-bay-invalid-network"));
                }

                // Open drive bay GUI regardless of network validity
                plugin.getGUIManager().openDriveBayGUI(player, block.getLocation(), networkId);

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-drive-bay", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error accessing drive bay: " + e.getMessage());
            }
        }
    }

    // Helper methods to check if blocks are OUR custom blocks or cables
    private boolean isCustomNetworkBlockOrCableOrExporterOrImporter(Block block) {
        return isCustomNetworkBlock(block) || cableManager.isCustomNetworkCable(block) || isCustomExporter(block) || isCustomImporter(block) || isCustomSecurityTerminal(block);
    }

    // Helper method for network signal carriers (excludes importers since they don't carry network signals)
    private boolean isCustomNetworkBlockOrCableOrExporter(Block block) {
        return isCustomNetworkBlock(block) || cableManager.isCustomNetworkCable(block) || isCustomExporter(block);
    }

    private boolean isCustomNetworkBlock(Block block) {
        return isCustomStorageServer(block) || isCustomDriveBay(block) || isCustomMSSTerminal(block) || isCustomSecurityTerminal(block);
    }

    private boolean isCustomStorageServer(Block block) {
        if (block.getType() != Material.CHISELED_TUFF) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "STORAGE_SERVER");
    }

    private boolean isCustomDriveBay(Block block) {
        if (block.getType() != Material.CHISELED_TUFF_BRICKS) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "DRIVE_BAY");
    }

    private boolean isCustomMSSTerminal(Block block) {
        if (block.getType() != Material.CRAFTER) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "MSS_TERMINAL");
    }

    private boolean isCustomExporter(Block block) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "EXPORTER");
    }

    private boolean isCustomImporter(Block block) {
        if (block.getType() != Material.PLAYER_HEAD && block.getType() != Material.PLAYER_WALL_HEAD) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "IMPORTER");
    }

    private boolean isCustomSecurityTerminal(Block block) {
        if (block.getType() != Material.OBSERVER) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "SECURITY_TERMINAL");
    }

    /**
     * Check if a block is a custom MSS block (Storage Server, Drive Bay, MSS Terminal, or Security Terminal)
     * Used for exporter placement validation
     */
    private boolean isCustomMSSBlock(Block block) {
        return isCustomStorageServer(block) || isCustomDriveBay(block) || isCustomMSSTerminal(block) || isCustomSecurityTerminal(block);
    }

    /**
     * Check if a block type is an MSS block type
     */
    private boolean isMSSBlockType(String blockType) {
        return "STORAGE_SERVER".equals(blockType) || "DRIVE_BAY".equals(blockType) || "MSS_TERMINAL".equals(blockType) || "SECURITY_TERMINAL".equals(blockType);
    }

    /**
     * Check if placing an MSS block at this location would cause an existing exporter to be attached to it
     */
    private String checkExporterAttachmentConflict(Location mssBlockLocation) {
        // Check all adjacent locations for exporters that would be attached to this MSS block
        for (Location adjacent : getAdjacentLocations(mssBlockLocation)) {
            Block adjacentBlock = adjacent.getBlock();
            
            // Check if there's an exporter at this adjacent location
            if (isCustomExporter(adjacentBlock)) {
                // Check if this exporter would be a wall/floor mounted on the MSS block we're placing
                if (isExporterAttachedToBlock(adjacent, mssBlockLocation)) {
                    return plugin.getMessageManager().getMessage((Player) null, "errors.placement.exporter-cannot-attach");
                }
            }
        }
        return null; // No conflict
    }

    /**
     * Check if an exporter at exporterLocation is attached to (wall/floor mounted on) the block at blockLocation
     */
    private boolean isExporterAttachedToBlock(Location exporterLocation, Location blockLocation) {
        Block exporterBlock = exporterLocation.getBlock();
        
        // For player heads, we need to check if they're attached to the specific block
        if (exporterBlock.getType() == Material.PLAYER_HEAD) {
            // Floor mounted head - check if it's sitting on top of the block
            Location below = exporterLocation.clone().add(0, -1, 0);
            return below.equals(blockLocation);
        } else if (exporterBlock.getType() == Material.PLAYER_WALL_HEAD) {
            // Wall mounted head - check if it's attached to any face of the block
            // Player wall heads are attached to the block they're facing away from
            org.bukkit.block.data.Directional directional = (org.bukkit.block.data.Directional) exporterBlock.getBlockData();
            org.bukkit.block.BlockFace facing = directional.getFacing();
            
            // The block the wall head is attached to is in the opposite direction
            Location attachedTo = exporterLocation.clone().add(facing.getOppositeFace().getDirection());
            return attachedTo.equals(blockLocation);
        }
        
        return false;
    }

    private boolean isMarkedAsCustomBlock(Location location, String blockType) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM custom_block_markers WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND block_type = ?")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());
            stmt.setString(5, blockType);

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking custom block marker: " + e.getMessage());
            return false;
        }
    }

    private void markLocationAsCustomBlock(Location location, String blockType) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO custom_block_markers (world_name, x, y, z, block_type) VALUES (?, ?, ?, ?, ?)")) {
                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());
                stmt.setString(5, blockType);
                stmt.executeUpdate();
            }
        });
    }

    private void removeCustomBlockMarker(Location location) {
        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                try (PreparedStatement stmt = conn.prepareStatement(
                        "DELETE FROM custom_block_markers WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
                    stmt.setString(1, location.getWorld().getName());
                    stmt.setInt(2, location.getBlockX());
                    stmt.setInt(3, location.getBlockY());
                    stmt.setInt(4, location.getBlockZ());
                    stmt.executeUpdate();
                }
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Error removing custom block marker: " + e.getMessage());
        }
    }

    private String getBlockTypeFromItem(ItemStack item) {
        if (itemManager.isStorageServer(item)) return "STORAGE_SERVER";
        if (itemManager.isDriveBay(item)) return "DRIVE_BAY";
        if (itemManager.isMSSTerminal(item)) return "MSS_TERMINAL";
        if (itemManager.isSecurityTerminal(item)) return "SECURITY_TERMINAL";
        if (itemManager.isNetworkCable(item)) return "NETWORK_CABLE";
        if (itemManager.isExporter(item)) return "EXPORTER";
        return "UNKNOWN";
    }

    /**
     * Update drive bay network associations when networks change
     * This ensures that existing disks in drive bays get associated with the correct network
     */
    private void updateDriveBayNetworkAssociations(String networkId, Set<Location> driveBayLocations) {
        try {
            plugin.getDatabaseManager().executeTransaction(conn -> {
                for (Location driveBayLoc : driveBayLocations) {
                    try (PreparedStatement stmt = conn.prepareStatement(
                            "UPDATE drive_bay_slots SET network_id = ? WHERE world_name = ? AND x = ? AND y = ? AND z = ? AND disk_id IS NOT NULL")) {
                        
                        stmt.setString(1, networkId);
                        stmt.setString(2, driveBayLoc.getWorld().getName());
                        stmt.setInt(3, driveBayLoc.getBlockX());
                        stmt.setInt(4, driveBayLoc.getBlockY());
                        stmt.setInt(5, driveBayLoc.getBlockZ());
                        
                        int updated = stmt.executeUpdate();
                        if (updated > 0) {
                            plugin.debugLog("Updated " + updated + " drive bay slots to network " + networkId + " at " + driveBayLoc);
                        }
                    }
                }
            });
        } catch (Exception e) {
            plugin.getLogger().severe("Error updating drive bay network associations: " + e.getMessage());
        }
    }

    private List<Location> getAdjacentLocations(Location center) {
        List<Location> adjacent = new ArrayList<>();
        int[] offsets = {-1, 0, 1};

        for (int dx : offsets) {
            for (int dy : offsets) {
                for (int dz : offsets) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    // Only check face-adjacent blocks
                    int nonZero = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
                    if (nonZero == 1) {
                        adjacent.add(center.clone().add(dx, dy, dz));
                    }
                }
            }
        }

        return adjacent;
    }

    private ItemStack getCustomItemForBlock(Block block) {
        if (isCustomStorageServer(block)) {
            return itemManager.createStorageServer();
        } else if (isCustomDriveBay(block)) {
            return itemManager.createDriveBay();
        } else if (isCustomMSSTerminal(block)) {
            return itemManager.createMSSTerminal();
        } else if (cableManager.isCustomNetworkCable(block)) {
            return itemManager.createNetworkCable();
        } else if (isCustomExporter(block)) {
            return itemManager.createExporter();
        } else if (isCustomImporter(block)) {
            return itemManager.createImporter();
        } else if (isCustomSecurityTerminal(block)) {
            return itemManager.createSecurityTerminal();
        }
        return null;
    }

    /**
     * Display storage server information to the player
     * Enhanced to detect connected blocks even for invalid networks
     */
    private void displayStorageServerInfo(Player player, Location serverLocation) {
        try {
            // Get network ID if it exists
            String networkId = networkManager.getNetworkId(serverLocation);
            boolean isValidNetwork = networkId != null && networkManager.isNetworkValid(networkId);
            
            // Detect connected blocks manually (including invalid networks)
            NetworkInfo detectedNetwork = networkManager.detectNetwork(serverLocation);
            
            int driveBayCount = 0;
            int terminalCount = 0;
            int cableCount = 0;
            int exporterCount = 0;
            int importerCount = 0;
            int totalStorageCapacity = 0;
            int usedStorageCapacity = 0;
            
            if (detectedNetwork != null) {
                driveBayCount = detectedNetwork.getDriveBays().size();
                terminalCount = detectedNetwork.getTerminals().size();
                cableCount = detectedNetwork.getNetworkCables().size();
                
                // Count exporters and importers in the detected network
                exporterCount = getExporterCountInArea(detectedNetwork.getAllBlocks());
                importerCount = countNetworkImporters(new ArrayList<>(detectedNetwork.getAllBlocks()));
                
                // Get storage capacity from valid network only
                if (isValidNetwork) {
                    totalStorageCapacity = getTotalNetworkStorageCapacity(networkId);
                    usedStorageCapacity = getUsedNetworkStorageCapacity(networkId);
                }
            }
            
            // Get configuration limits
            int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
            int maxCables = plugin.getConfigManager().getMaxNetworkCables();
            int maxExporters = plugin.getConfigManager().getMaxExporters();
            
            // Calculate total connected blocks (excluding cables)
            int totalBlocks = driveBayCount + terminalCount + exporterCount + 1; // +1 for the server itself
            
            // Display comprehensive information
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.header"));
            
            if (networkId != null) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.network-id", "id", networkId.substring(0, Math.min(16, networkId.length())));
                player.sendMessage(message);
            } else {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.network-id-none"));
            }
            
            // Network Status
            Component statusMessage;
            if (isValidNetwork) {
                statusMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.status", "status", plugin.getMessageManager().getMessage(player, "storage-server-info.status-online"));
            } else if (detectedNetwork != null && detectedNetwork.isValid()) {
                statusMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.status", "status", plugin.getMessageManager().getMessage(player, "storage-server-info.status-offline-detected"));
            } else {
                statusMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.status", "status", plugin.getMessageManager().getMessage(player, "storage-server-info.status-offline"));
            }
            player.sendMessage(statusMessage);
            
            player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line
            
            // Connected Components
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.connected-components"));
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.drive-bays-count", "count", String.valueOf(driveBayCount)));
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.terminals-count", "count", String.valueOf(terminalCount)));
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.exporters-count", "count", String.valueOf(exporterCount)));
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.importers-count", "count", String.valueOf(importerCount)));
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.cables-count", "count", String.valueOf(cableCount)));
            
            player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line
            
            // Network Limits
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.network-limits"));
            
            Component blocksMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.blocks-limit", "current", String.valueOf(totalBlocks), "max", String.valueOf(maxBlocks));
            Component cablesMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.cables-limit", "current", String.valueOf(cableCount), "max", String.valueOf(maxCables));
            Component busMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.bus-limit", "current", String.valueOf(exporterCount), "max", String.valueOf(maxExporters));
            
            // Apply color coding based on limits
            if (totalBlocks > maxBlocks) blocksMessage = blocksMessage.color(NamedTextColor.RED);
            else blocksMessage = blocksMessage.color(NamedTextColor.GREEN);
            
            if (cableCount > maxCables) cablesMessage = cablesMessage.color(NamedTextColor.RED);
            else cablesMessage = cablesMessage.color(NamedTextColor.GREEN);
            
            if (exporterCount > maxExporters) busMessage = busMessage.color(NamedTextColor.RED);
            else busMessage = busMessage.color(NamedTextColor.GREEN);
            
            player.sendMessage(blocksMessage);
            player.sendMessage(cablesMessage);
            player.sendMessage(busMessage);
            
            // Storage Information (only for valid networks)
            if (isValidNetwork) {
                player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line
                if (totalStorageCapacity > 0) {
                    int usagePercent = (int) ((double) usedStorageCapacity / totalStorageCapacity * 100);
                    Component storageMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.storage-capacity", "used", String.valueOf(usedStorageCapacity), "total", String.valueOf(totalStorageCapacity), "percent", String.valueOf(usagePercent));
                    player.sendMessage(storageMessage);
                } else {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.storage-no-disks"));
                }
            }

        } catch (Exception e) {
            Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-storage-server-info", "error", e.getMessage());
            player.sendMessage(message);
            plugin.getLogger().severe("Error displaying storage server info: " + e.getMessage());
        }
    }

    /**
     * Count exporters in the detected network area
     */
    private int getExporterCountInArea(Set<Location> networkBlocks) {
        int exporterCount = 0;
        
        // Check all adjacent locations to network blocks for exporters
        Set<Location> checkedLocations = new HashSet<>();
        
        for (Location networkBlock : networkBlocks) {
            for (Location adjacent : getAdjacentLocations(networkBlock)) {
                if (checkedLocations.contains(adjacent)) continue;
                checkedLocations.add(adjacent);
                
                Block block = adjacent.getBlock();
                if (isCustomExporter(block)) {
                    exporterCount++;
                }
            }
        }
        
        return exporterCount;
    }

    private int countNetworkImporters(List<Location> networkBlocks) {
        int importerCount = 0;
        Set<Location> checkedLocations = new HashSet<>();
        
        for (Location networkBlock : networkBlocks) {
            for (Location adjacent : getAdjacentLocations(networkBlock)) {
                if (checkedLocations.contains(adjacent)) continue;
                checkedLocations.add(adjacent);
                
                Block block = adjacent.getBlock();
                if (isCustomImporter(block)) {
                    importerCount++;
                }
            }
        }
        
        return importerCount;
    }

    private int getTotalNetworkStorageCapacity(String networkId) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT SUM(sd.max_cells) FROM storage_disks sd " +
                     "JOIN drive_bay_slots dbs ON sd.disk_id = dbs.disk_id " +
                     "WHERE dbs.network_id = ? AND dbs.disk_id IS NOT NULL")) {
            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private int getUsedNetworkStorageCapacity(String networkId) throws SQLException {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT SUM(sd.used_cells) FROM storage_disks sd " +
                     "JOIN drive_bay_slots dbs ON sd.disk_id = dbs.disk_id " +
                     "WHERE dbs.network_id = ? AND dbs.disk_id IS NOT NULL")) {
            stmt.setString(1, networkId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Prevent MSS blocks from interacting with redstone
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockRedstone(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        
        // Check if this is a security terminal observer block
        if (block.getType() == Material.OBSERVER && isCustomSecurityTerminal(block)) {
            // Cancel the redstone event to prevent signal output
            event.setNewCurrent(0);
        }
        
        // Check if this is an MSS terminal crafter block
        if (block.getType() == Material.CRAFTER && isCustomMSSTerminal(block)) {
            // Cancel redstone events to prevent crafter activation
            event.setNewCurrent(0);
        }
    }

    /**
     * Prevent MSS blocks from responding to physics updates
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        
        // Check if this is a security terminal observer block
        if (block.getType() == Material.OBSERVER && isCustomSecurityTerminal(block)) {
            // Cancel physics updates to prevent observer detection behavior
            event.setCancelled(true);
        }
        
        // Check if this is an MSS terminal crafter block
        if (block.getType() == Material.CRAFTER && isCustomMSSTerminal(block)) {
            // Cancel physics updates to prevent crafter redstone behavior
            event.setCancelled(true);
        }
    }

    /**
     * Set the facing direction for directional blocks based on player placement direction (delayed)
     */
    private void setBlockDirectionDelayed(Block block, Player player, ItemStack item) {
        // Only set direction for MSS Terminal and Security Terminal
        if (!itemManager.isMSSTerminal(item) && !itemManager.isSecurityTerminal(item)) {
            return;
        }

        plugin.debugLog("Setting direction for " + block.getType() + " at " + block.getLocation());

        // Check if the block data implements Directional (both Crafter and Observer do)
        if (!(block.getBlockData() instanceof Directional directional)) {
            plugin.debugLog("Block is not directional: " + block.getBlockData().getClass().getName());
            return;
        }

        // Calculate player direction
        Vector playerDirection = player.getLocation().getDirection();
        
        // Get horizontal direction (ignore Y component for facing)
        double x = playerDirection.getX();
        double z = playerDirection.getZ();
        
        // Determine the closest cardinal direction (opposite of player direction to face toward player)
        org.bukkit.block.BlockFace facing;
        if (Math.abs(x) > Math.abs(z)) {
            facing = x > 0 ? org.bukkit.block.BlockFace.WEST : org.bukkit.block.BlockFace.EAST;
        } else {
            facing = z > 0 ? org.bukkit.block.BlockFace.NORTH : org.bukkit.block.BlockFace.SOUTH;
        }
        
        plugin.debugLog("Player direction: x=" + x + ", z=" + z + " -> facing=" + facing);
        plugin.debugLog("Available faces: " + directional.getFaces());
        plugin.debugLog("Current facing: " + directional.getFacing());
        
        // Set the facing direction if it's a valid option for this block
        if (directional.getFaces().contains(facing)) {
            directional.setFacing(facing);
            block.setBlockData(directional);
            plugin.debugLog("Set facing to: " + facing);
        } else {
            plugin.debugLog("Facing " + facing + " not available for this block");
        }
    }

    /**
     * Check if a network already has a security terminal using database lookup
     */
    private boolean hasExistingSecurityTerminalInNetwork(String networkId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM security_terminals WHERE network_id = ?")) {
            
            stmt.setString(1, networkId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                int count = rs.next() ? rs.getInt(1) : 0;
                plugin.getLogger().info("[Security Terminal Debug] Database query result: " + count + " security terminals in network " + networkId);
                return count > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error checking existing security terminals in network: " + e.getMessage());
            // If database check fails, allow placement to avoid blocking legitimate placements
            return false;
        }
    }

    /**
     * Check if there are any adjacent MSS blocks or cables
     */
    private boolean hasAdjacentMSSBlocks(Location location) {
        for (Location adjacent : getAdjacentLocations(location)) {
            Block block = adjacent.getBlock();
            if (isCustomNetworkBlock(block) || cableManager.isCustomNetworkCable(block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a player can place a cable at the given location without connecting to security terminals they don't own
     */
    private boolean canPlaceCableNearSecurityTerminals(Player player, Location cableLocation) {
        // Admin bypass
        if (player.hasPermission("mss.admin")) {
            return true;
        }

        // Check all adjacent locations for security terminals
        for (Location adjacent : getAdjacentLocations(cableLocation)) {
            Block adjacentBlock = adjacent.getBlock();
            
            // If there's a security terminal adjacent
            if (isCustomSecurityTerminal(adjacentBlock)) {
                // Check if player is the owner of this security terminal
                if (!plugin.getSecurityManager().isOwner(player, adjacent)) {
                    plugin.debugLog("Player " + player.getName() + " tried to place cable near security terminal at " + adjacent + " but doesn't own it");
                    return false;
                }
            }
        }
        
        return true;
    }
}