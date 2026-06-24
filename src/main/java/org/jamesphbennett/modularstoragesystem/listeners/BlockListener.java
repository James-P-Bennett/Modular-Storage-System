package org.jamesphbennett.modularstoragesystem.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
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
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;
import org.jamesphbennett.modularstoragesystem.blocks.MSSBlockType;
import org.jamesphbennett.modularstoragesystem.managers.ItemManager;
import org.jamesphbennett.modularstoragesystem.managers.ExporterManager;
import org.jamesphbennett.modularstoragesystem.managers.ImporterManager;

import static org.jamesphbennett.modularstoragesystem.managers.NetworkSecurityManager.PermissionType;
import org.jamesphbennett.modularstoragesystem.network.NetworkConnectivityManager;
import org.jamesphbennett.modularstoragesystem.network.NetworkManager;
import org.jamesphbennett.modularstoragesystem.network.NetworkInfo;
import org.jamesphbennett.modularstoragesystem.network.CableManager;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BlockListener implements Listener {

    private final ModularStorageSystem plugin;
    private final ItemManager itemManager;
    private final NetworkManager networkManager;
    private final CableManager cableManager;

    // Storage server info cache to prevent DB spam - 2 second TTL
    private final Map<String, StorageInfoCache> storageInfoCache = new ConcurrentHashMap<>();
    private static final long STORAGE_INFO_CACHE_DURATION_MS = 2000; // 2 seconds

    // Inner class to hold cached storage info
    private static class StorageInfoCache {
        final int totalCapacity;
        final int usedCapacity;
        final long expiry;

        StorageInfoCache(int totalCapacity, int usedCapacity) {
            this.totalCapacity = totalCapacity;
            this.usedCapacity = usedCapacity;
            this.expiry = System.currentTimeMillis() + STORAGE_INFO_CACHE_DURATION_MS;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expiry;
        }
    }

    public BlockListener(ModularStorageSystem plugin) {
        this.plugin = plugin;
        this.itemManager = plugin.getItemManager();
        this.networkManager = plugin.getNetworkManager();
        this.cableManager = plugin.getCableManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        Block block = event.getBlockPlaced();
        Location location = block.getLocation();

        // Placing a block next to a note-backed MSS block re-derives that block's instrument off our piglin key — re-pin
        // any adjacent MSS block so the pack texture doesn't revert to vanilla.
        repinAdjacentNoteBlocks(block);

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

        // Check item-specific placement permission
        String itemType = getItemTypeForPermission(item);
        if (itemType != null && !plugin.getPermissionManager().canUse(player, itemType)) {
            event.setCancelled(true);
            plugin.getPermissionManager().sendUseDeniedMessage(player, itemType, "place");
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

            // Enforce the per-network exporter role limit before placing (only when connecting to a network).
            if (nearbyNetworkId != null) {
                int maxExporters = plugin.getConfigManager().getMaxExporters();
                if (plugin.getExporterManager().getNetworkExporterCount(nearbyNetworkId) >= maxExporters) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.exporter-limit-reached", "max", String.valueOf(maxExporters)));
                    return;
                }
            }

            // If no network found, use placeholder - exporter will remain inactive until connected
            if (nearbyNetworkId == null) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.exporter-unconnected"));
            } else {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.exporter-connected"));
            }

            // Mark as custom block
            try {
                markLocationAsCustomBlock(location, "EXPORTER");

                // Create the exporter in the manager
                String exporterId = plugin.getExporterManager().createExporter(location, nearbyNetworkId);
                plugin.getLogger().info("Created exporter " + exporterId + " at " + location + " with network " + (nearbyNetworkId != null ? nearbyNetworkId : "UNCONNECTED"));

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.placement.block-error", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error creating exporter: " + e.getMessage());
                event.setCancelled(true);
                return;
            }
            // Re-join adjacent cable pipe displays so they plug into the new device head (opt-in; no-op when off).
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getCablePipeRenderer().refreshAround(location.getBlock()));
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

            // Enforce the per-network importer role limit before placing (only when connecting to a network).
            if (nearbyNetworkId != null) {
                int maxImporters = plugin.getConfigManager().getMaxImporters();
                if (plugin.getImporterManager().getNetworkImporterCount(nearbyNetworkId) >= maxImporters) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.importer-limit-reached", "max", String.valueOf(maxImporters)));
                    return;
                }
            }

            // If no network found, use placeholder - importer will remain inactive until connected
            if (nearbyNetworkId == null) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.importer-unconnected"));
            } else {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.importer-connected"));
            }

            // Mark as custom block
            try {
                markLocationAsCustomBlock(location, "IMPORTER");

                // Create the importer in the manager
                String importerId = plugin.getImporterManager().createImporter(location, nearbyNetworkId);
                plugin.getLogger().info("Created importer " + importerId + " at " + location + " with network " + (nearbyNetworkId != null ? nearbyNetworkId : "UNCONNECTED"));

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.placement.block-error", "error", e.getMessage());
                player.sendMessage(message);
                plugin.getLogger().severe("Error creating importer: " + e.getMessage());
                event.setCancelled(true);
                return;
            }
            // Re-join adjacent cable pipe displays so they plug into the new device head (opt-in; no-op when off).
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getCablePipeRenderer().refreshAround(location.getBlock()));
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

        // Store flags for what type of block was placed (item might change in player's hand before next tick)
        final boolean wasSecurityTerminal = itemManager.isSecurityTerminal(item);
        itemManager.isExporter(item);
        itemManager.isImporter(item);

        // Enforce the network-size limit SYNCHRONOUSLY so we can cancel the place event cleanly (the item simply
        // stays in the hand) instead of letting it place and refunding from a deferred task — which raced and could
        // lose or dupe the item. The block is already placed + marked above, so detectNetwork sees the full
        // (possibly just-merged) network here. Cables don't count toward this block limit.
        if (!itemManager.isNetworkCable(item)) {
            NetworkInfo placedNetwork = networkManager.detectNetwork(location);
            if (placedNetwork != null && placedNetwork.isValid()) {
                int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
                int blockCount = placedNetwork.getAllBlocks().size() - placedNetwork.getNetworkCables().size();
                if (blockCount > maxBlocks) {
                    event.setCancelled(true);
                    removeCustomBlockMarker(location);
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.placement.network-size-limit", "max", String.valueOf(maxBlocks)));
                    return;
                }
            }
        }

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
                if (wasSecurityTerminal) {
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
                        
                        // If we have a network, update the association immediately
                        if (networkId != null) {
                            plugin.getSecurityManager().updateTerminalNetwork(location, networkId);
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
                    }
                }

                if (network != null && network.isValid()) {
                    // Network-size limit is enforced synchronously at placement (see the pre-check above), so an
                    // over-limit placement is cancelled cleanly there — no post-placement break/drop here.

                    // Register the network with comprehensive drive bay restoration
                    networkManager.registerNetwork(network, player.getUniqueId());

                    // Update security terminal network association if this was a security terminal
                    if (itemManager.isSecurityTerminal(item)) {
                        try {
                            String networkId = networkManager.getNetworkId(location);
                            plugin.getSecurityManager().updateTerminalNetwork(location, networkId);
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
                    } else if (!itemManager.isNetworkCable(item)) {
                        // Cables are pure connectors — they don't function on their own, so skip the needs-connection nag.
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "success.placement.block-needs-connection"));
                    }
                }

                // Network updates are now handled via NetworkUpdateListener callbacks
                // Managers are automatically notified when registerNetwork() is called

                // Refresh terminals for the network (important for reconnections)
                if (network != null && network.isValid()) {
                    String finalNetworkId = networkManager.getNetworkId(location);
                    if (finalNetworkId != null) {
                        plugin.getGUIManager().refreshNetworkTerminals(finalNetworkId);
                        // Update drive bay network associations for reconnections
                        updateDriveBayNetworkAssociations(finalNetworkId, network.getDriveBays());
                    }
                }

                // Refresh connection-aware cable pipe displays for the placed block + its neighbours (opt-in; no-op
                // when the feature is off). Placing a cable OR a network block re-joins adjacent cables to it.
                plugin.getCablePipeRenderer().refreshAround(block);
                if (cableManager.isCustomNetworkCable(block)) dryCable(block); // a cable placed in water stays dry

            } catch (Exception e) {
                Component message = plugin.getMessageManager().getMessageComponent(player, "errors.network.connection-lost");
                player.sendMessage(message);
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error setting up network: " + e.getMessage(), e);
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location location = block.getLocation();

        // Breaking a block next to a note-backed MSS block re-derives that block's instrument off our piglin key —
        // re-pin any adjacent MSS block (handled before the early return, since the broken block usually isn't MSS).
        repinAdjacentNoteBlocks(block);

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

            // Clear a terminal's front screen-light before it's broken (read its facing from the still-present block).
            MSSBlockType brokenNoteRole = placedNoteRole(block);
            if ((brokenNoteRole == MSSBlockType.MSS_TERMINAL || brokenNoteRole == MSSBlockType.SECURITY_TERMINAL)
                    && block.getBlockData() instanceof org.bukkit.block.data.type.NoteBlock brokenNb) {
                removeTerminalLight(block, MSSBlockType.facingFromNote(brokenNb.getNote().getId()));
            }

            // Handle exporter removal
            if (isCustomExporter(block)) {
                try {
                    // Get the exporter data before removing
                    ExporterManager.ExporterData exporterData = plugin.getExporterManager().getExporterAtLocation(location);
                    if (exporterData != null) {
                        // Remove from exporter manager
                        plugin.getExporterManager().removeExporter(exporterData.exporterId);
                    }

                    // Drop the custom item
                    event.setDropItems(false);
                    ItemStack customItem = plugin.getItemManager().createExporter();
                    if (customItem != null) {
                        dropOrGive(location, customItem);
                    }

                    // Remove custom block marker
                    removeCustomBlockMarker(location);

                } catch (Exception e) {
                    Component message = plugin.getMessageManager().getMessageComponent(player, "errors.removal.error-exporter", "error", e.getMessage());
                    player.sendMessage(message);
                    plugin.getLogger().severe("Error removing exporter: " + e.getMessage());
                }
                // Re-join adjacent cable pipe displays once this device head is gone (opt-in; no-op when off).
                plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getCablePipeRenderer().refreshAround(block));
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
                    }

                    // Drop the custom item
                    event.setDropItems(false);
                    ItemStack customItem = plugin.getItemManager().createImporter();
                    if (customItem != null) {
                        dropOrGive(location, customItem);
                    }

                    // Remove custom block marker
                    removeCustomBlockMarker(location);

                } catch (Exception e) {
                    Component message = plugin.getMessageManager().getMessageComponent(player, "errors.removal.error-importer", "error", e.getMessage());
                    player.sendMessage(message);
                    plugin.getLogger().severe("Error removing importer: " + e.getMessage());
                }
                // Re-join adjacent cable pipe displays once this device head is gone (opt-in; no-op when off).
                plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getCablePipeRenderer().refreshAround(block));
                return; // Importers don't affect network topology, so return early
            }

            // Handle security terminal removal
            if (isCustomSecurityTerminal(block)) {
                try {
                    // Remove from security manager
                    plugin.getSecurityManager().removeSecurityTerminal(location);

                    // Drop the custom item
                    event.setDropItems(false);
                    ItemStack customItem = plugin.getItemManager().createSecurityTerminal();
                    if (customItem != null) {
                        dropOrGive(location, customItem);
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
                    plugin.getDisksManager().dropDriveBayContentsWithoutNetwork(location);
                }
            }

            // Always drop the custom item and prevent vanilla drops
            event.setDropItems(false);
            ItemStack customItem = getCustomItemForBlock(block);
            if (customItem != null) {
                dropOrGive(location, customItem);
            }

            // Remove custom block marker
            removeCustomBlockMarker(location);

            // Refresh cable pipe displays next tick (block is air by then): removes the broken cable's display and
            // re-joins its neighbours (opt-in feature; no-op when off).
            plugin.getServer().getScheduler().runTask(plugin, () -> plugin.getCablePipeRenderer().refreshAround(block));

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

                        // Network updates are now handled via NetworkUpdateListener callbacks
                        // Managers are automatically notified when registerNetwork()/unregisterNetwork() is called

                    } catch (Exception e) {
                        Component message = plugin.getMessageManager().getMessageComponent(player, "errors.removal.error-network-update", "error", e.getMessage());
                        player.sendMessage(message);
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Error updating network after block break: " + e.getMessage(), e);
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
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        plugin.getExplosionManager().handleExplosion(event.blockList());
    }

    /**
     * Handle block explosions (beds, respawn anchors, etc.) that destroy MSS blocks
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        plugin.getExplosionManager().handleExplosion(event.blockList());
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

        // MSS Wrench takes precedence over opening the block's GUI: right-click rotates a note-backed MSS block,
        // shift+right-click dismantles any MSS block to inventory.
        if (itemManager.isWrench(event.getItem())) {
            event.setCancelled(true);
            if (player.isSneaking()) wrenchDismantle(player, block);
            else wrenchRotate(player, block);
            return;
        }

        // Check if player is awaiting search input and cancel ALL interactions
        if (plugin.getGUIManager().isAwaitingSearchInput(player)) {
            // Player is in search mode - cancel the search and the interaction
            event.setCancelled(true);
            plugin.getGUIManager().cancelSearchInput(player);
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.terminal.search.search-cancelled"));
            return;
        }

        // Sneaking lets the player BUILD against MSS blocks / I/O instead of opening their GUI (vanilla
        // convention — like a chest or crafter). We don't cancel, so a block held in hand places normally;
        // GUI access stays on a plain non-sneak right-click. The wrench is handled above, so sneak+wrench
        // still dismantles rather than building.
        if (player.isSneaking()) {
            return;
        }

        // Handle Exporter interactions (only custom ones)
        if (isCustomExporter(block)) {
            event.setCancelled(true);

            if (plugin.getConfigManager().isRequireUsePermission() && !player.hasPermission("modularstoragesystem.use")) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.interaction.no-use-permission-exporters"));
                return;
            }

            // Check item-specific permission
            if (!plugin.getPermissionManager().canUse(player, "exporter")) {
                plugin.getPermissionManager().sendUseDeniedMessage(player, "exporter", "open");
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

            // Check item-specific permission
            if (!plugin.getPermissionManager().canUse(player, "importer")) {
                plugin.getPermissionManager().sendUseDeniedMessage(player, "importer", "open");
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

            // Check item-specific permission
            if (!plugin.getPermissionManager().canUse(player, "mss_terminal")) {
                plugin.getPermissionManager().sendUseDeniedMessage(player, "mss_terminal", "open");
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

            // Check item-specific permission
            if (!plugin.getPermissionManager().canUse(player, "storage_server")) {
                plugin.getPermissionManager().sendUseDeniedMessage(player, "storage_server", "open");
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

            // Check item-specific permission
            if (!plugin.getPermissionManager().canUse(player, "security_terminal")) {
                plugin.getPermissionManager().sendUseDeniedMessage(player, "security_terminal", "open");
                return;
            }

            try {
                // Check if player is NOT the owner
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
                plugin.getGUIManager().openSecurityTerminalGUI(player, terminalData.terminalId(), terminalData.ownerUuid());

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

            // Check item-specific permission
            if (!plugin.getPermissionManager().canUse(player, "drive_bay")) {
                plugin.getPermissionManager().sendUseDeniedMessage(player, "drive_bay", "open");
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

    private boolean isCustomNetworkBlock(Block block) {
        return isCustomStorageServer(block) || isCustomDriveBay(block) || isCustomMSSTerminal(block) || isCustomSecurityTerminal(block);
    }

    private boolean isCustomStorageServer(Block block) {
        if (!MSSBlockType.STORAGE_SERVER.accepts(block.getType())) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "STORAGE_SERVER");
    }

    private boolean isCustomDriveBay(Block block) {
        if (!MSSBlockType.DRIVE_BAY.accepts(block.getType())) return false;
        return isMarkedAsCustomBlock(block.getLocation(), "DRIVE_BAY");
    }

    private boolean isCustomMSSTerminal(Block block) {
        if (!MSSBlockType.MSS_TERMINAL.accepts(block.getType())) return false;
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
        if (!MSSBlockType.SECURITY_TERMINAL.accepts(block.getType())) return false;
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

    /**
     * Check if a block is marked as a custom MSS block in the database
     * Uses shared cache to prevent DB spam from player clicks
     */
    private boolean isMarkedAsCustomBlock(Location location, String blockType) {
        return plugin.getBlockMarkerCache().isMarkedAsCustomBlock(location, blockType);
    }

    private void markLocationAsCustomBlock(Location location, String blockType) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "REPLACE INTO custom_block_markers (world_name, x, y, z, block_type) VALUES (?, ?, ?, ?, ?)")) {
                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());
                stmt.setString(5, blockType);
                stmt.executeUpdate();
            }
        });

        // Invalidate cache after marking block — clear ALL type keys incl. ':ANY' (used by piston checks),
        // not just the typed key, so a freshly-placed block is never read as non-MSS from a stale ':ANY'.
        plugin.getBlockMarkerCache().invalidateAllBlockTypesAtLocation(location);
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

            // Invalidate cache for all block types at this location
            plugin.getBlockMarkerCache().invalidateAllBlockTypesAtLocation(location);
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
                        
                        stmt.executeUpdate();
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
     * Runs async with caching to prevent DB spam
     */
    private void displayStorageServerInfo(Player player, Location serverLocation) {
        // Run on the main thread (next tick): this reads LIVE block state — detectNetwork and the
        // exporter/importer adjacency scans call block.getType()/getBlock(), which is only legal on the
        // main thread and throws IllegalStateException (async block access) on Paper 26.x if run async.
        // Detection is cache-backed now, so the main-thread cost of this one-off interact is small.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
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

                    // Count exporters/importers from the authoritative DB count (matches the enforced
                    // per-network role limit) when the network has an id; the adjacency scan would miss
                    // devices daisy-chained off another device (those aren't in getAllBlocks).
                    if (networkId != null) {
                        exporterCount = plugin.getExporterManager().getNetworkExporterCount(networkId);
                        importerCount = plugin.getImporterManager().getNetworkImporterCount(networkId);
                    } else {
                        exporterCount = getExporterCountInArea(detectedNetwork.getAllBlocks());
                        importerCount = countNetworkImporters(new ArrayList<>(detectedNetwork.getAllBlocks()));
                    }

                    // Get storage capacity from valid network only (with caching)
                    if (isValidNetwork) {
                        StorageInfoCache cache = storageInfoCache.get(networkId);
                        if (cache != null && !cache.isExpired()) {
                            // Use cached values
                            totalStorageCapacity = cache.totalCapacity;
                            usedStorageCapacity = cache.usedCapacity;
                        } else {
                            // Query database and cache result
                            totalStorageCapacity = getTotalNetworkStorageCapacity(networkId);
                            usedStorageCapacity = getUsedNetworkStorageCapacity(networkId);
                            storageInfoCache.put(networkId, new StorageInfoCache(totalStorageCapacity, usedStorageCapacity));
                        }
                    }
                }

                // Get configuration limits
                int maxBlocks = plugin.getConfigManager().getMaxNetworkBlocks();
                int maxCables = plugin.getConfigManager().getMaxNetworkCables();
                int maxExporters = plugin.getConfigManager().getMaxExporters();
                int maxImporters = plugin.getConfigManager().getMaxImporters();

                // Calculate total connected blocks (excluding cables)
                // Counted network blocks = server + drive bays + terminals + security terminal (NOT cables,
                // and NOT exporters/importers — those have their own role limits). Mirror the placement
                // limit's basis exactly: networkBlocks = getAllBlocks() minus cables.
                int totalBlocks = (detectedNetwork != null) ? (detectedNetwork.getAllBlocks().size() - cableCount) : 0;

                // Capture final variables for use in sync task
                final int finalDriveBayCount = driveBayCount;
                final int finalTerminalCount = terminalCount;
                final int finalCableCount = cableCount;
                final int finalExporterCount = exporterCount;
                final int finalImporterCount = importerCount;
                final int finalTotalStorageCapacity = totalStorageCapacity;
                final int finalUsedStorageCapacity = usedStorageCapacity;
                final boolean finalIsValidNetwork = isValidNetwork;
                final String finalNetworkId = networkId;
                final NetworkInfo finalDetectedNetwork = detectedNetwork;

                // Send messages on main thread
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        // Display comprehensive information
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.header"));

                        if (finalNetworkId != null) {
                            Component message = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.network-id", "id", finalNetworkId.substring(0, Math.min(16, finalNetworkId.length())));
                            player.sendMessage(message);
                        } else {
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.network-id-none"));
                        }

                        // Network Status
                        Component statusMessage;
                        if (finalIsValidNetwork) {
                            statusMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.status", "status", plugin.getMessageManager().getMessage(player, "storage-server-info.status-online"));
                        } else if (finalDetectedNetwork != null && finalDetectedNetwork.isValid()) {
                            statusMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.status", "status", plugin.getMessageManager().getMessage(player, "storage-server-info.status-offline-detected"));
                        } else {
                            statusMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.status", "status", plugin.getMessageManager().getMessage(player, "storage-server-info.status-offline"));
                        }
                        player.sendMessage(statusMessage);

                        player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line

                        // Connected Components
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.connected-components"));
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.drive-bays-count", "count", String.valueOf(finalDriveBayCount)));
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.terminals-count", "count", String.valueOf(finalTerminalCount)));
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.exporters-count", "count", String.valueOf(finalExporterCount)));
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.importers-count", "count", String.valueOf(finalImporterCount)));
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.cables-count", "count", String.valueOf(finalCableCount)));

                        player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line

                        // Network Limits
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.network-limits"));

                        Component blocksMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.blocks-limit", "current", String.valueOf(totalBlocks), "max", String.valueOf(maxBlocks));
                        Component cablesMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.cables-limit", "current", String.valueOf(finalCableCount), "max", String.valueOf(maxCables));
                        Component exporterMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.exporter-limit", "current", String.valueOf(finalExporterCount), "max", String.valueOf(maxExporters));
                        Component importerMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.importer-limit", "current", String.valueOf(finalImporterCount), "max", String.valueOf(maxImporters));

                        // Apply color coding based on limits
                        if (totalBlocks > maxBlocks) blocksMessage = blocksMessage.color(NamedTextColor.RED);
                        else blocksMessage = blocksMessage.color(NamedTextColor.GREEN);

                        if (finalCableCount > maxCables) cablesMessage = cablesMessage.color(NamedTextColor.RED);
                        else cablesMessage = cablesMessage.color(NamedTextColor.GREEN);

                        if (finalExporterCount > maxExporters) exporterMessage = exporterMessage.color(NamedTextColor.RED);
                        else exporterMessage = exporterMessage.color(NamedTextColor.GREEN);

                        if (finalImporterCount > maxImporters) importerMessage = importerMessage.color(NamedTextColor.RED);
                        else importerMessage = importerMessage.color(NamedTextColor.GREEN);

                        player.sendMessage(blocksMessage);
                        player.sendMessage(cablesMessage);
                        player.sendMessage(exporterMessage);
                        player.sendMessage(importerMessage);

                        // Storage Information (only for valid networks)
                        if (finalIsValidNetwork) {
                            player.sendMessage(Component.text("", NamedTextColor.WHITE)); // Empty line
                            if (finalTotalStorageCapacity > 0) {
                                int usagePercent = (int) ((double) finalUsedStorageCapacity / finalTotalStorageCapacity * 100);
                                Component storageMessage = plugin.getMessageManager().getMessageComponent(player, "storage-server-info.storage-capacity", "used", String.valueOf(finalUsedStorageCapacity), "total", String.valueOf(finalTotalStorageCapacity), "percent", String.valueOf(usagePercent));
                                player.sendMessage(storageMessage);
                            } else {
                                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "storage-server-info.storage-no-disks"));
                            }
                        }
                    } catch (Exception e) {
                        Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-storage-server-info", "error", e.getMessage());
                        player.sendMessage(message);
                        plugin.getLogger().severe("Error displaying storage server info (sync): " + e.getMessage());
                    }
                });

            } catch (Exception e) {
                // Send error message on main thread
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Component message = plugin.getMessageManager().getMessageComponent(player, "errors.access.error-storage-server-info", "error", e.getMessage());
                    player.sendMessage(message);
                });
                plugin.getLogger().severe("Error displaying storage server info (async): " + e.getMessage());
            }
        });
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

        // Note-backed MSS blocks (pack mode): cancel block-physics so the note block's `instrument` can't be
        // re-derived from the block below (which would change the state the resource pack keys off). Gated on the
        // note-block Material + marker so real note blocks are unaffected.
        if (placedNoteRole(block) != null) {
            event.setCancelled(true);
            return;
        }

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

        // Check if this is an importer or exporter player head
        if (block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD) {
            if (isCustomImporter(block) || isCustomExporter(block)) {
                // Cancel physics updates to prevent water/lava from breaking the heads
                event.setCancelled(true);
            }
        }
    }

    /**
     * Prevent water/lava from flowing into importers and exporters
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();

        // Check if fluid is trying to flow into an importer or exporter
        if (toBlock.getType() == Material.PLAYER_HEAD || toBlock.getType() == Material.PLAYER_WALL_HEAD) {
            if (isCustomImporter(toBlock) || isCustomExporter(toBlock)) {
                // Cancel fluid flow to protect importers/exporters
                event.setCancelled(true);
            }
        }

        // Keep cable conduits dry: cancel water flowing into a cable's block so it can't be waterlogged → can't
        // activate as a conduit (cables repel water).
        if (event.getBlock().getType() == Material.WATER && cableManager.isCustomNetworkCable(toBlock)) {
            event.setCancelled(true);
        }
    }

    /** Cancel emptying a water bucket onto a cable — cables repel water so they can't be waterlogged/activated. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        if (event.getBucket() != Material.WATER_BUCKET) return;
        if (cableManager.isCustomNetworkCable(event.getBlock()) || cableManager.isCustomNetworkCable(event.getBlockClicked())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getMessageManager().getMessageComponent(event.getPlayer(), "errors.placement.cable-repels-water"));
        }
    }

    /**
     * Set the placed-block state for an MSS block (delayed, next tick after placement).
     */
    private void setBlockDirectionDelayed(Block block, Player player, ItemStack item) {
        MSSBlockType role = MSSBlockType.byMarker(getBlockTypeFromItem(item));
        if (role == null) return; // not one of the reskinnable blocks (e.g. exporter/importer heads)

        // Pack mode: the placed block is a note block. Stamp its note (role + facing) so the resource pack renders
        // this role's model, ORIENTED so the FRONT face (the _north texture) points toward the player who placed it.
        // Note blocks don't derive state from neighbours, so there is no face-merge to fight — the only mutable input
        // is `instrument` (from the block below), which onBlockPhysics freezes.
        if (role.isNoteBacked() && block.getType() == MSSBlockType.NOTE) {
            int facing = facingIndexForFront(getBlockFace(player));
            stampNoteState(block, role, facing);
            if (role == MSSBlockType.MSS_TERMINAL || role == MSSBlockType.SECURITY_TERMINAL) {
                placeTerminalLight(block, facing);
            }
            return;
        }

        // Vanilla mode (pack off): keep the player-facing orientation for the Directional terminal/security blocks
        // (Crafter/Observer). Storage server / drive bay / cable are non-directional and need no post-placement state.
        if ((role == MSSBlockType.MSS_TERMINAL || role == MSSBlockType.SECURITY_TERMINAL)
                && block.getBlockData() instanceof Directional directional) {
            BlockFace facing = getBlockFace(player);
            if (directional.getFaces().contains(facing)) {
                directional.setFacing(facing);
                block.setBlockData(directional);
            }
        }
    }

    /** Stamp a note block's state (instrument=piglin, note=role+facing, powered=false) so the pack renders this role. */
    void stampNoteState(Block block, MSSBlockType role, int facing) {
        if (block.getType() != MSSBlockType.NOTE) return;
        block.setBlockData(org.bukkit.Bukkit.createBlockData(
                "minecraft:note_block[instrument=piglin,note=" + role.noteFor(facing) + ",powered=false]"), false);
    }

    /** The MSS role of a placed note block (resolved by marker), or null if not a marked note-backed MSS block. */
    MSSBlockType placedNoteRole(Block block) {
        if (block.getType() != MSSBlockType.NOTE) return null;
        for (MSSBlockType t : MSSBlockType.values()) {
            if (isMarkedAsCustomBlock(block.getLocation(), t.markerType)) return t;
        }
        return null;
    }

    private static final BlockFace[] SIX_FACES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    /**
     * Re-pin every note-backed MSS block AT or ADJACENT to a just-changed block. A note block re-derives its
     * {@code instrument} (off our {@code piglin} key) on ANY neighbour update via vanilla shape-updates, which
     * {@link BlockPhysicsEvent} can't reliably cancel — so placing/breaking next to (or stacking on top of) an MSS
     * block reverts its pack texture to vanilla. We include the changed block ITSELF, so a freshly-placed block at the
     * TOP of a stack — which has nothing placed above it later to re-trigger a fix and so can't rely on its neighbours —
     * is covered too, and re-pin both next tick and a few ticks later (the re-derive can land after the placement
     * stamp). Cheap getType()!=NOTE short-circuit means nothing is scheduled when no MSS note block is near.
     */
    private void repinAdjacentNoteBlocks(Block changed) {
        java.util.Set<Block> noteBlocks = new java.util.HashSet<>();
        if (placedNoteRole(changed) != null) noteBlocks.add(changed);
        for (BlockFace face : SIX_FACES) {
            Block neighbour = changed.getRelative(face);
            if (placedNoteRole(neighbour) != null) noteBlocks.add(neighbour);
        }
        scheduleRepin(noteBlocks);
    }

    /** Re-pin the given MSS note blocks to instrument=piglin next tick AND a few ticks later — the late pass catches a
     *  vanilla block-update re-derive that lands after the first pass (placement stamp, stack or piston settle).
     *  Idempotent (already-pinned no-ops); nothing scheduled for an empty set. */
    private void scheduleRepin(java.util.Set<Block> noteBlocks) {
        if (noteBlocks.isEmpty()) return;
        for (long delay : new long[]{1L, 6L}) {
            plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> noteBlocks.forEach(this::pinNoteBlockToPiglin), delay);
        }
    }

    /** Restore {@code instrument=piglin} on a marked note-backed MSS block whose instrument drifted (a neighbour
     *  update re-derived it), so the pack skin returns. No-op if it's not ours or already pinned; applyPhysics=false
     *  so we don't re-trigger neighbour updates. */
    private void pinNoteBlockToPiglin(Block block) {
        if (placedNoteRole(block) != null
                && block.getBlockData() instanceof org.bukkit.block.data.type.NoteBlock nb
                && nb.getInstrument() != org.bukkit.Instrument.PIGLIN) {
            nb.setInstrument(org.bukkit.Instrument.PIGLIN);
            block.setBlockData(nb, false);
        }
    }

    /**
     * A piston that MOVES a block next to a note-backed MSS block — pulling the block out from under it, or pushing a
     * block above/below it — fires a vanilla shape-update on that note block, which re-derives its instrument off our
     * piglin key and reverts the pack skin. {@link PistonListener} already blocks the MSS block ITSELF from being
     * moved, so here we only re-pin MSS blocks adjacent to the disturbance. MONITOR + ignoreCancelled so we act only
     * when the piston actually fires (after PistonListener's cancel).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtendRepin(BlockPistonExtendEvent event) {
        repinAroundPistonMove(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetractRepin(BlockPistonRetractEvent event) {
        repinAroundPistonMove(event.getBlock(), event.getBlocks(), event.getDirection());
    }

    /**
     * Re-pin note-backed MSS blocks adjacent to anything a piston move disturbs. The affected MSS blocks are resolved
     * NOW (at event time, before the move) from every disturbed cell — each moved block's source and either
     * destination (±dir, agnostic to extend vs retract), plus the piston body and head — then re-pinned on a short
     * delay AND after the ~2-tick piston animation settles, since a destination-side revert only lands once the moving
     * block solidifies. Idempotent (already-pinned no-ops), so the two passes never fight; nothing is scheduled when
     * the piston is nowhere near an MSS block.
     */
    private void repinAroundPistonMove(Block piston, java.util.List<Block> moved, BlockFace dir) {
        java.util.Set<Block> disturbed = new java.util.HashSet<>();
        disturbed.add(piston);
        disturbed.add(piston.getRelative(dir));            // piston head extends/clears along the axis only
        disturbed.add(piston.getRelative(dir.getOppositeFace()));
        // Expand each moved block by ALL 6 faces, not just ±dir: slime/honey blocks drag neighbours PERPENDICULAR to
        // the push, so a moved block's destination can be on any axis. This catches an MSS block adjacent to it
        // regardless of drag direction (idempotent + non-NOTE short-circuit keeps the extra cells cheap).
        for (Block b : moved) {
            disturbed.add(b);
            for (BlockFace f : SIX_FACES) disturbed.add(b.getRelative(f));
        }
        java.util.Set<Block> noteBlocks = new java.util.HashSet<>();
        for (Block d : disturbed) {
            for (BlockFace face : SIX_FACES) {
                Block n = d.getRelative(face);
                if (placedNoteRole(n) != null) noteBlocks.add(n);
            }
        }
        scheduleRepin(noteBlocks); // 1 tick = immediate source-side revert; later pass = destination-side after settle
    }

    /** When set during a (synthetic) BlockBreakEvent, the broken block's custom item goes to this player's inventory
     *  instead of dropping on the ground — the MSS Wrench's "dismantle to inventory". */
    private Player dismantleRecipient;

    private void dropOrGive(Location loc, ItemStack item) {
        if (item == null) return;
        if (dismantleRecipient != null) {
            dismantleRecipient.getInventory().addItem(item).values()
                    .forEach(overflow -> loc.getWorld().dropItemNaturally(loc, overflow));
        } else {
            loc.getWorld().dropItemNaturally(loc, item);
        }
    }

    /** MSS Wrench right-click: rotate a note-backed MSS block 90° (its _north front cycles N->E->S->W). */
    private void wrenchRotate(Player player, Block block) {
        MSSBlockType role = placedNoteRole(block);
        if (role == null) return; // only the note-backed cube blocks rotate
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.NoteBlock nb)) return;
        int oldFacing = MSSBlockType.facingFromNote(nb.getNote().getId());
        int next = (oldFacing + 1) % MSSBlockType.FACINGS;
        boolean terminal = role == MSSBlockType.MSS_TERMINAL || role == MSSBlockType.SECURITY_TERMINAL;
        if (terminal) removeTerminalLight(block, oldFacing); // clear the old front light before rotating
        stampNoteState(block, role, next);
        if (terminal) placeTerminalLight(block, next);       // light the new front
        player.playSound(block.getLocation(), org.bukkit.Sound.ITEM_SPYGLASS_USE, 0.6f, 1.4f);
    }

    /** MSS Wrench shift+right-click: dismantle an MSS block to the player's inventory. Reuses the full break-cleanup
     *  path via a synthetic BlockBreakEvent (so markers/network/drive-bay disks are handled and security is honoured),
     *  with the block's item routed to the player; then consumes 1 durability. */
    private void wrenchDismantle(Player player, Block block) {
        if (!isCustomNetworkBlockOrCableOrExporterOrImporter(block)) return;
        dismantleRecipient = player;
        try {
            BlockBreakEvent breakEvent = new BlockBreakEvent(block, player);
            plugin.getServer().getPluginManager().callEvent(breakEvent);
            if (breakEvent.isCancelled()) return; // protection / security denied — leave the block intact
            block.setType(Material.AIR, false);
            // Audible feedback that the wrench picked the block up — same spyglass sound as the rotate action.
            player.playSound(block.getLocation(), org.bukkit.Sound.ITEM_SPYGLASS_USE, 0.6f, 1.4f);
            damageWrench(player, block.getLocation());
        } finally {
            dismantleRecipient = null;
        }
    }

    /** Damage the held wrench by 1; break it (with a sound) when it runs out. No-op in creative. */
    private void damageWrench(Player player, Location at) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        ItemStack wrench = player.getInventory().getItemInMainHand();
        if (!(wrench.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg)) return;
        dmg.setDamage(dmg.getDamage() + 1);
        if (dmg.getDamage() >= wrench.getType().getMaxDurability()) {
            wrench.setAmount(wrench.getAmount() - 1);
            player.playSound(at, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        } else {
            wrench.setItemMeta(dmg);
        }
    }

    /**
     * Map the desired FRONT direction to the note-block facing index (0-3). At facing 0 the model's {@code _north}
     * face points world-NORTH; each step rotates the model 90° clockwise via the blockstate (y = facing*90), so
     * facings 0..3 point the front N, E, S, W. {@code _north} is the front texture for the reskinned cube blocks.
     */
    private static int facingIndexForFront(BlockFace front) {
        return switch (front) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0; // NORTH (and any non-horizontal fallback)
        };
    }

    /** Inverse of {@link #facingIndexForFront}: the world direction a terminal's FRONT (_north face) points for facing 0..3. */
    private static BlockFace frontFace(int facing) {
        return switch (facing) {
            case 1 -> BlockFace.EAST;
            case 2 -> BlockFace.SOUTH;
            case 3 -> BlockFace.WEST;
            default -> BlockFace.NORTH;
        };
    }

    /** The light level emitted in front of a terminal screen (0-15). Place + remove share this so they never drift. */
    private static final int TERMINAL_LIGHT_LEVEL = 14;

    /** Place an invisible LIGHT in the air block in front of a terminal's screen — if the feature is enabled and that
     *  block is air/light. Only MSS + security terminals call this. */
    private void placeTerminalLight(Block terminal, int facing) {
        if (!plugin.getConfigManager().isTerminalLightEnabled()) return;
        Block front = terminal.getRelative(frontFace(facing));
        if (front.getType() != Material.AIR && front.getType() != Material.LIGHT) return;
        org.bukkit.block.data.type.Light light = (org.bukkit.block.data.type.Light) Material.LIGHT.createBlockData();
        light.setLevel(TERMINAL_LIGHT_LEVEL);
        front.setBlockData(light, false);
    }

    /** Remove OUR front light in front of a terminal — used on wrench-rotate (old front) and break. */
    private void removeTerminalLight(Block terminal, int facing) {
        Block front = terminal.getRelative(frontFace(facing));
        if (front.getType() == Material.LIGHT
                && front.getBlockData() instanceof org.bukkit.block.data.type.Light l && l.getLevel() == TERMINAL_LIGHT_LEVEL) {
            front.setType(Material.AIR, false);
        }
    }

    /**
     * Force a cable's conduit non-waterlogged so it can never be "in water" — the prerequisite for activating as a
     * conduit. Cables never hold water: flow in is cancelled ({@link #onBlockFromTo}), a water bucket onto a cable is
     * cancelled ({@link #onPlayerBucketEmpty}), and a cable placed in water is dried here.
     */
    void dryCable(Block cable) {
        if (cable.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) {
            wl.setWaterlogged(false);
            cable.setBlockData(wl, false); // applyPhysics=false: don't re-trigger flow
        }
    }

    private static @NotNull BlockFace getBlockFace(Player player) {
        Vector playerDirection = player.getLocation().getDirection();

        // Get horizontal direction (ignore Y component for facing)
        double x = playerDirection.getX();
        double z = playerDirection.getZ();

        // Determine the closest cardinal direction (opposite of player direction to face toward player)
        BlockFace facing;
        if (Math.abs(x) > Math.abs(z)) {
            facing = x > 0 ? BlockFace.WEST : BlockFace.EAST;
        } else {
            facing = z > 0 ? BlockFace.NORTH : BlockFace.SOUTH;
        }
        return facing;
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
                if (plugin.getSecurityManager().isOwner(player, adjacent)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Get the item type for permission checking from an ItemStack
     * @param item The item being placed
     * @return The item type for permission checking, or null if not an MSS item
     */
    private String getItemTypeForPermission(ItemStack item) {
        if (itemManager.isStorageServer(item)) {
            return "storage_server";
        } else if (itemManager.isDriveBay(item)) {
            return "drive_bay";
        } else if (itemManager.isMSSTerminal(item)) {
            return "mss_terminal";
        } else if (itemManager.isNetworkCable(item)) {
            return "network_cable";
        } else if (itemManager.isExporter(item)) {
            return "exporter";
        } else if (itemManager.isImporter(item)) {
            return "importer";
        } else if (itemManager.isSecurityTerminal(item)) {
            return "security_terminal";
        }
        return null;
    }
}