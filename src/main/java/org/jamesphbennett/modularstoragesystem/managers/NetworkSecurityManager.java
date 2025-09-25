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

public class NetworkSecurityManager {

    private final ModularStorageSystem plugin;

    public NetworkSecurityManager(ModularStorageSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Create a new security terminal at the specified location
     */
    public String createSecurityTerminal(Location location, Player owner, String networkId) throws SQLException {
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
        
        return terminalId;
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
        boolean isTrusted = isPlayerTrusted(terminal.terminalId, player.getUniqueId().toString(), permissionType);
        return isTrusted;
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
        } catch (SQLException e) {
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
        } catch (SQLException e) {
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
                        } else {
                        }
                    }
                }
            }
        } catch (SQLException e) {
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
     * Clean up a security terminal's network association
     */
    private void cleanupTerminalAssociation(Location terminalLocation) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE security_terminals SET network_id = NULL WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {
            
            stmt.setString(1, terminalLocation.getWorld().getName());
            stmt.setInt(2, terminalLocation.getBlockX());
            stmt.setInt(3, terminalLocation.getBlockY());
            stmt.setInt(4, terminalLocation.getBlockZ());
            
            int updated = stmt.executeUpdate();
            if (updated > 0) {
            }
        } catch (SQLException e) {
        }
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
        return terminal != null && player.getUniqueId().toString().equals(terminal.ownerUuid);
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
            int updated = stmt.executeUpdate();
            
            if (updated > 0) {
            }
        } catch (SQLException e) {
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
                
                int updated = stmt.executeUpdate();
                if (updated > 0) {
                }
            }
        } catch (SQLException e) {
        }
    }


    public static class SecurityTerminalData {
        public final String terminalId;
        public final String ownerUuid;
        public final String ownerName;
        public final String networkId;

        public SecurityTerminalData(String terminalId, String ownerUuid, String ownerName, String networkId) {
            this.terminalId = terminalId;
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.networkId = networkId;
        }
    }
}