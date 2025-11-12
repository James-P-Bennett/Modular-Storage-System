package org.jamesphbennett.modularstoragesystem.managers;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class NetworkSecurityManager implements org.jamesphbennett.modularstoragesystem.network.NetworkManager.NetworkUpdateListener {

    private final ModularStorageSystem plugin;

    public NetworkSecurityManager(ModularStorageSystem plugin) {
        this.plugin = plugin;
        // Register as a listener for network updates
        plugin.getNetworkManager().registerUpdateListener(this);
    }

    /**
     * Called when a network is updated/registered
     * No action needed - terminals are updated during network registration
     */
    @Override
    public void onNetworkUpdated(String networkId) {
        // Don't update on network creation/update - NetworkManager already handles this in registerNetwork()
        // Running updateSecurityTerminalNetworkAssignments() here causes race conditions
    }

    /**
     * Called when a network is removed/unregistered
     * Updates security terminal network assignments to handle disconnections
     */
    @Override
    public void onNetworkRemoved(String networkId) {
        // Only update when networks are removed, not on every update
        updateSecurityTerminalNetworkAssignments();
    }

    /**
     * Create a new security terminal at the specified location
     */
    public void createSecurityTerminal(Location location, Player owner, String networkId) throws SQLException {
        String terminalId = UUID.randomUUID().toString();

        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO security_terminals (terminal_id, world_name, x, y, z, owner_uuid, owner_name, network_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {

                stmt.setString(1, terminalId);
                stmt.setString(2, location.getWorld().getName());
                stmt.setInt(3, location.getBlockX());
                stmt.setInt(4, location.getBlockY());
                stmt.setInt(5, location.getBlockZ());
                stmt.setString(6, owner.getUniqueId().toString());
                stmt.setString(7, owner.getName());
                stmt.setString(8, networkId);

                stmt.executeUpdate();
            }
        });

    }

    /**
     * Remove a security terminal at the specified location
     */
    public void removeSecurityTerminal(Location location) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "DELETE FROM security_terminals WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
                
                stmt.setString(1, location.getWorld().getName());
                stmt.setInt(2, location.getBlockX());
                stmt.setInt(3, location.getBlockY());
                stmt.setInt(4, location.getBlockZ());
                
                stmt.executeUpdate();
            }
        });
        
    }

    /**
     * Get security terminal data at a location
     */
    public SecurityTerminalData getSecurityTerminal(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT terminal_id, owner_uuid, owner_name, network_id FROM security_terminals " +
                     "WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new SecurityTerminalData(
                        rs.getString("terminal_id"),
                        rs.getString("owner_uuid"),
                        rs.getString("owner_name"),
                        rs.getString("network_id")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error getting security terminal: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if a player has permission to access a network resource
     */
    public boolean hasPermission(Player player, String networkId, PermissionType permissionType) {
        return hasPermission(player, networkId, permissionType, null);
    }

    /**
     * Check if a player has permission to access a network resource at a specific location
     */
    public boolean hasPermission(Player player, String networkId, PermissionType permissionType, Location accessLocation) {
        
        // Admin bypass - mss.admin permission overrides all security restrictions
        if (player.hasPermission("mss.admin")) {
            return true;
        }
        
        // If no network ID, allow access (networks without security terminals are open)
        if (networkId == null) {
            return true;
        }
        
        // Find security terminal for this network
        SecurityTerminalData terminal = accessLocation != null ? 
            getSecurityTerminalForNetwork(networkId, accessLocation) : 
            getSecurityTerminalForNetworkLegacy(networkId);
        
        if (terminal == null) {
            // No security terminal for this network - allow access
            return true;
        }
        
        // Check if player is the owner
        if (player.getUniqueId().toString().equals(terminal.ownerUuid)) {
            return true;
        }
        
        // Check if player is trusted
        return isPlayerTrusted(terminal.terminalId, player.getUniqueId().toString(), permissionType);
    }

    /**
     * Check if a player is trusted with specific permissions
     */
    private boolean isPlayerTrusted(String terminalId, String playerUuid, PermissionType permissionType) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT drive_bay_access, block_modification_access FROM security_terminal_players " +
                     "WHERE terminal_id = ? AND player_uuid = ?")) {
            
            stmt.setString(1, terminalId);
            stmt.setString(2, playerUuid);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return switch (permissionType) {
                        case DRIVE_BAY_ACCESS -> rs.getBoolean("drive_bay_access");
                        case BLOCK_MODIFICATION -> rs.getBoolean("block_modification_access");
                    };
                }
            }
        } catch (SQLException ignored) {
        }
        return false;
    }

    /**
     * Legacy method: Get security terminal without connectivity check (for backward compatibility)
     */
    private SecurityTerminalData getSecurityTerminalForNetworkLegacy(String networkId) {
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT terminal_id, owner_uuid, owner_name, network_id, world_name, x, y, z FROM security_terminals")) {
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Get the terminal location
                    String worldName = rs.getString("world_name");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    
                    // Construct the location
                    org.bukkit.World world = plugin.getServer().getWorld(worldName);
                    if (world == null) continue;
                    
                    Location terminalLocation = new Location(world, x, y, z);
                    
                    // Check what network this terminal is ACTUALLY connected to right now
                    String actualNetworkId = plugin.getNetworkManager().getNetworkId(terminalLocation);
                    
                    // If this terminal is connected to the network we're checking
                    if (networkId.equals(actualNetworkId)) {
                        return new SecurityTerminalData(
                            rs.getString("terminal_id"),
                            rs.getString("owner_uuid"),
                            rs.getString("owner_name"),
                            actualNetworkId
                        );
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        
        return null;
    }

    /**
     * Get the security terminal for a specific network, but only if it's physically reachable
     * Uses connectivity check to see if the terminal can actually reach the block being accessed
     */
    private SecurityTerminalData getSecurityTerminalForNetwork(String networkId, Location accessLocation) {
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT terminal_id, owner_uuid, owner_name, network_id, world_name, x, y, z FROM security_terminals")) {
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Get the terminal location
                    String worldName = rs.getString("world_name");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");
                    
                    // Construct the location
                    org.bukkit.World world = plugin.getServer().getWorld(worldName);
                    if (world == null) continue;
                    
                    Location terminalLocation = new Location(world, x, y, z);
                    
                    // Check what network this terminal is ACTUALLY connected to right now
                    String actualNetworkId = plugin.getNetworkManager().getNetworkId(terminalLocation);
                    
                    // If this terminal is connected to the network we're checking
                    if (networkId.equals(actualNetworkId)) {
                        // Additional check: Can the terminal actually reach the access location?
                        if (isConnectedViaNetwork(terminalLocation, accessLocation)) {
                            return new SecurityTerminalData(
                                rs.getString("terminal_id"),
                                rs.getString("owner_uuid"),
                                rs.getString("owner_name"),
                                actualNetworkId
                            );
                        }
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        
        return null;
    }

    /**
     * Check if two locations are connected via network blocks/cables
     */
    private boolean isConnectedViaNetwork(Location from, Location to) {
        // Use BFS to check connectivity
        Set<Location> visited = new HashSet<>();
        Queue<Location> queue = new LinkedList<>();
        queue.add(from);
        visited.add(from);
        
        while (!queue.isEmpty()) {
            Location current = queue.poll();
            
            // If we reached the target location, they're connected
            if (current.equals(to)) {
                return true;
            }
            
            // Check all adjacent blocks
            for (Location adjacent : getAdjacentLocations(current)) {
                if (visited.contains(adjacent)) continue;
                
                Block block = adjacent.getBlock();
                // If it's a network block or cable, continue searching
                if (isNetworkBlock(block) || plugin.getCableManager().isCustomNetworkCable(block)) {
                    visited.add(adjacent);
                    queue.add(adjacent);
                }
            }
        }
        
        return false;
    }

    /**
     * Get adjacent locations (6 directions)
     */
    private List<Location> getAdjacentLocations(Location center) {
        List<Location> adjacent = new ArrayList<>();
        adjacent.add(center.clone().add(1, 0, 0));
        adjacent.add(center.clone().add(-1, 0, 0));
        adjacent.add(center.clone().add(0, 1, 0));
        adjacent.add(center.clone().add(0, -1, 0));
        adjacent.add(center.clone().add(0, 0, 1));
        adjacent.add(center.clone().add(0, 0, -1));
        return adjacent;
    }

    /**
     * Check if a block is a network block or cable
     */
    private boolean isNetworkBlock(Block block) {
        return plugin.getCableManager().isCustomNetworkBlockOrCable(block);
    }


    /**
     * Check if a player is the owner of a security terminal at a location
     */
    public boolean isOwner(Player player, Location location) {
        // Admin bypass - mss.admin permission grants owner privileges
        if (player.hasPermission("mss.admin")) {
            return true;
        }

        SecurityTerminalData terminal = getSecurityTerminal(location);
        if (terminal == null) {
            return false; // Terminal doesn't exist, player can't be owner
        }

        return player.getUniqueId().toString().equals(terminal.ownerUuid);
    }

    /**
     * Update the network ID for a security terminal
     */
    public void updateTerminalNetwork(Location location, String networkId) throws SQLException {
        plugin.getDatabaseManager().executeTransaction(conn -> {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE security_terminals SET network_id = ? WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
                
                stmt.setString(1, networkId);
                stmt.setString(2, location.getWorld().getName());
                stmt.setInt(3, location.getBlockX());
                stmt.setInt(4, location.getBlockY());
                stmt.setInt(5, location.getBlockZ());
                
                stmt.executeUpdate();
            }
        });
    }

    /**
     * Update security terminal network assignments
     * Similar to exporter update mechanism - scans all terminals and updates their network IDs
     * if they've become disconnected or if their stored network is invalid
     */
    public void updateSecurityTerminalNetworkAssignments() {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT terminal_id, network_id, world_name, x, y, z FROM security_terminals")) {

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String terminalId = rs.getString("terminal_id");
                    String currentNetworkId = rs.getString("network_id");
                    String worldName = rs.getString("world_name");
                    int x = rs.getInt("x");
                    int y = rs.getInt("y");
                    int z = rs.getInt("z");

                    // Construct the terminal location
                    org.bukkit.World world = plugin.getServer().getWorld(worldName);
                    if (world == null) continue;

                    Location terminalLocation = new Location(world, x, y, z);

                    // Check if current network is valid
                    boolean currentNetworkValid = currentNetworkId != null &&
                            plugin.getNetworkManager().isNetworkValid(currentNetworkId);

                    if (!currentNetworkValid) {
                        // Terminal has invalid network - try to find a new network
                        String newNetworkId = findAdjacentNetwork(terminalLocation);

                        if (newNetworkId != null && !newNetworkId.equals(currentNetworkId)) {
                            // Found a new valid network - reconnect
                            try {
                                updateTerminalNetwork(terminalLocation, newNetworkId);
                                plugin.debugLog("Updated security terminal " + terminalId +
                                    " from network " + currentNetworkId + " to " + newNetworkId);
                            } catch (SQLException e) {
                                plugin.getLogger().warning("Failed to reconnect security terminal " +
                                    terminalId + ": " + e.getMessage());
                            }
                        } else if (currentNetworkId != null) {
                            // No valid network found - set to null
                            try {
                                updateTerminalNetwork(terminalLocation, null);
                                plugin.debugLog("Disconnected security terminal " + terminalId +
                                    " from invalid network " + currentNetworkId);
                            } catch (SQLException e) {
                                plugin.getLogger().warning("Failed to disconnect security terminal " +
                                    terminalId + ": " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update security terminal network assignments: " + e.getMessage());
        }
    }

    /**
     * Find a valid network adjacent to the given location
     */
    private String findAdjacentNetwork(Location location) {
        // Check adjacent locations for network blocks or cables (6 face-adjacent blocks)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    // Only check face-adjacent blocks (not diagonal)
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

    public enum PermissionType {
        DRIVE_BAY_ACCESS,
        BLOCK_MODIFICATION
    }

    /**
     * Handle network invalidation by cleaning up security terminal associations
     */
    public void handleNetworkInvalidated(String networkId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE security_terminals SET network_id = NULL WHERE network_id = ?")) {
            
            stmt.setString(1, networkId);

        } catch (SQLException ignored) {
        }
    }

    /**
     * Clean up orphaned security terminal network associations
     * This removes network_id from security terminals that point to non-existent networks
     */
    public void cleanupOrphanedTerminals() {
        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // Find and clean up security terminals that reference non-existent networks
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE security_terminals SET network_id = NULL " +
                    "WHERE network_id IS NOT NULL " +
                    "AND network_id NOT IN (SELECT network_id FROM networks)")) {
                stmt.executeUpdate();
            }
        } catch (SQLException ignored) {
        }
    }


    public record SecurityTerminalData(String terminalId, String ownerUuid, String ownerName, String networkId) {
    }
}