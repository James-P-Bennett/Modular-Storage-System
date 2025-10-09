package org.jamesphbennett.modularstoragesystem.utils;

import org.bukkit.Location;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared cache for block marker database queries to prevent DB spam
 * Used by BlockListener and PistonListener
 */
public class BlockMarkerCache {

    private final ModularStorageSystem plugin;

    // Block marker cache to prevent DB spam - 5 second TTL
    private final Map<String, Boolean> blockMarkerCache = new ConcurrentHashMap<>();
    private final Map<String, Long> blockMarkerCacheExpiry = new ConcurrentHashMap<>();
    private static final long BLOCK_MARKER_CACHE_DURATION_MS = 5000; // 5 seconds

    public BlockMarkerCache(ModularStorageSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Check if a location is marked as a specific custom block type
     * Uses caching to prevent excessive database queries
     *
     * @param location The location to check
     * @param blockType The block type to check for (STORAGE_SERVER, DRIVE_BAY, MSS_TERMINAL, etc.)
     * @return true if the location is marked as that block type
     */
    public boolean isMarkedAsCustomBlock(Location location, String blockType) {
        // Generate cache key from location and block type
        String cacheKey = location.getWorld().getName() + ":" +
                location.getBlockX() + ":" +
                location.getBlockY() + ":" +
                location.getBlockZ() + ":" + blockType;

        // Check cache first
        Long expiry = blockMarkerCacheExpiry.get(cacheKey);
        if (expiry != null && System.currentTimeMillis() < expiry) {
            Boolean cached = blockMarkerCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        // Cache miss - query database
        boolean result = queryDatabase(location, blockType);

        // Store in cache
        blockMarkerCache.put(cacheKey, result);
        blockMarkerCacheExpiry.put(cacheKey, System.currentTimeMillis() + BLOCK_MARKER_CACHE_DURATION_MS);

        return result;
    }

    /**
     * Check if a location is marked as ANY MSS block (without specific type)
     * Uses caching to prevent excessive database queries
     *
     * @param location The location to check
     * @return true if the location is marked as any MSS block
     */
    public boolean isMarkedAsMSSBlock(Location location) {
        // Generate cache key from location (no specific block type)
        String cacheKey = location.getWorld().getName() + ":" +
                location.getBlockX() + ":" +
                location.getBlockY() + ":" +
                location.getBlockZ() + ":ANY";

        // Check cache first
        Long expiry = blockMarkerCacheExpiry.get(cacheKey);
        if (expiry != null && System.currentTimeMillis() < expiry) {
            Boolean cached = blockMarkerCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        // Cache miss - query database (no block_type filter)
        boolean result = queryDatabaseAny(location);

        // Store in cache
        blockMarkerCache.put(cacheKey, result);
        blockMarkerCacheExpiry.put(cacheKey, System.currentTimeMillis() + BLOCK_MARKER_CACHE_DURATION_MS);

        return result;
    }

    /**
     * Invalidate cache for a specific location and block type
     */
    public void invalidateBlockMarkerCache(Location location, String blockType) {
        String cacheKey = location.getWorld().getName() + ":" +
                location.getBlockX() + ":" +
                location.getBlockY() + ":" +
                location.getBlockZ() + ":" + blockType;

        blockMarkerCache.remove(cacheKey);
        blockMarkerCacheExpiry.remove(cacheKey);
    }

    /**
     * Invalidate ALL cache entries for a specific location (all block types)
     */
    public void invalidateAllBlockTypesAtLocation(Location location) {
        // Invalidate cache for all possible block types at this location
        String[] blockTypes = {"STORAGE_SERVER", "DRIVE_BAY", "MSS_TERMINAL", "EXPORTER", "IMPORTER", "SECURITY_TERMINAL", "ANY"};
        for (String blockType : blockTypes) {
            invalidateBlockMarkerCache(location, blockType);
        }
    }

    /**
     * Query database for a specific block type at a location
     */
    private boolean queryDatabase(Location location, String blockType) {
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

    /**
     * Query database for ANY block type at a location
     */
    private boolean queryDatabaseAny(Location location) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM custom_block_markers WHERE world_name = ? AND x = ? AND y = ? AND z = ?")) {

            stmt.setString(1, location.getWorld().getName());
            stmt.setInt(2, location.getBlockX());
            stmt.setInt(3, location.getBlockY());
            stmt.setInt(4, location.getBlockZ());

            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking MSS block marker: " + e.getMessage());
            return false;
        }
    }
}
