package org.jamesphbennett.modularstoragesystem.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SecurityTerminalGUI implements Listener {

    private final ModularStorageSystem plugin;
    private final String terminalId;
    private final String ownerUuid;
    private final Player viewer;
    private final Inventory inventory;
    
    private final List<TrustedPlayer> trustedPlayers = new ArrayList<>();
    private int currentPage = 0;
    private final int playersPerPage = 36; // 4 rows of 9 slots

    public SecurityTerminalGUI(ModularStorageSystem plugin, String terminalId, String ownerUuid, Player viewer) {
        this.plugin = plugin;
        this.terminalId = terminalId;
        this.ownerUuid = ownerUuid;
        this.viewer = viewer;
        
        // Create inventory with localized title
        Component title = plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.title");
        this.inventory = Bukkit.createInventory(null, 54, title);
        
        setupGUI();
        loadTrustedPlayers();
    }

    private void setupGUI() {
        // Fill bottom two rows with background
        ItemStack background = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta backgroundMeta = background.getItemMeta();
        backgroundMeta.displayName(Component.text(" "));
        background.setItemMeta(backgroundMeta);

        // Fill bottom two rows (slots 36-53)
        for (int i = 36; i < 54; i++) {
            inventory.setItem(i, background);
        }

        updateNavigationItems();
        updateControlItems();
    }

    private void updateNavigationItems() {
        int maxPages = getMaxPages();

        // Ensure currentPage is within valid bounds
        if (currentPage < 0) {
            currentPage = 0;
        } else if (currentPage >= maxPages) {
            currentPage = Math.max(0, maxPages - 1);
        }

        // Previous page button
        ItemStack prevPage = new ItemStack(Material.ARROW);
        ItemMeta prevMeta = prevPage.getItemMeta();
        prevMeta.displayName(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.navigation.previous-page"));
        List<Component> prevLore = new ArrayList<>();
        prevLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.navigation.page-info", "current", currentPage + 1, "max", maxPages));
        if (currentPage > 0) {
            prevLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.navigation.prev-available"));
        } else {
            prevLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.navigation.first-page"));
        }
        prevMeta.lore(prevLore);
        prevPage.setItemMeta(prevMeta);
        inventory.setItem(45, prevPage);

        // Next page button
        ItemStack nextPage = new ItemStack(Material.ARROW);
        ItemMeta nextMeta = nextPage.getItemMeta();
        nextMeta.displayName(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.navigation.next-page"));
        List<Component> nextLore = new ArrayList<>();
        nextLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.navigation.page-info", "current", currentPage + 1, "max", maxPages));
        if (currentPage < maxPages - 1) {
            nextLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.navigation.next-available"));
        } else {
            nextLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.navigation.last-page"));
        }
        nextMeta.lore(nextLore);
        nextPage.setItemMeta(nextMeta);
        inventory.setItem(53, nextPage);
    }

    private void updateControlItems() {
        // Add player button
        ItemStack addPlayer = new ItemStack(Material.EMERALD);
        ItemMeta addMeta = addPlayer.getItemMeta();
        addMeta.displayName(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.controls.add-player"));
        List<Component> addLore = new ArrayList<>();
        addLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.controls.add-instruction"));
        addLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.controls.add-offline-support"));
        addMeta.lore(addLore);
        addPlayer.setItemMeta(addMeta);
        inventory.setItem(48, addPlayer);

        // Info item
        ItemStack info = new ItemStack(Material.OBSERVER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.info.title"));
        List<Component> infoLore = new ArrayList<>();
        infoLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.info.owner", "owner", getOwnerName()));
        infoLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.info.trusted-count", "count", trustedPlayers.size()));
        infoLore.add(Component.empty());
        infoLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.info.permissions-header"));
        infoLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.info.gui-access"));
        infoLore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.info.block-modification"));
        infoMeta.lore(infoLore);
        info.setItemMeta(infoMeta);
        inventory.setItem(49, info);
    }

    private void loadTrustedPlayers() {
        trustedPlayers.clear();
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT player_uuid, player_name, drive_bay_access, block_modification_access " +
                     "FROM security_terminal_players WHERE terminal_id = ? ORDER BY player_name")) {
            
            stmt.setString(1, terminalId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String playerUuid = rs.getString("player_uuid");
                    String playerName = rs.getString("player_name");
                    boolean driveBayAccess = rs.getBoolean("drive_bay_access");
                    boolean blockModAccess = rs.getBoolean("block_modification_access");
                    
                    trustedPlayers.add(new TrustedPlayer(playerUuid, playerName, driveBayAccess, blockModAccess));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading trusted players: " + e.getMessage());
        }
        
        updateDisplayedPlayers();
    }

    private void updateDisplayedPlayers() {
        // Clear current player display (first 4 rows)
        for (int i = 0; i < 36; i++) {
            inventory.setItem(i, null);
        }

        // Calculate start and end indices for current page
        int startIndex = currentPage * playersPerPage;
        int endIndex = Math.min(startIndex + playersPerPage, trustedPlayers.size());

        // Display players for current page
        for (int i = startIndex; i < endIndex; i++) {
            TrustedPlayer trustedPlayer = trustedPlayers.get(i);
            int slot = i - startIndex;

            ItemStack playerSkull = createPlayerSkull(trustedPlayer);
            inventory.setItem(slot, playerSkull);
        }

        updateNavigationItems();
        updateControlItems();
    }

    private ItemStack createPlayerSkull(TrustedPlayer trustedPlayer) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        
        // Set player profile for skull texture
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(trustedPlayer.uuid));
        meta.setOwningPlayer(offlinePlayer);
        
        meta.displayName(Component.text(trustedPlayer.name, NamedTextColor.GREEN));
        
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.player.permissions-header"));
        
        // GUI Access (covers all GUI interactions)
        if (trustedPlayer.driveBayAccess) {
            lore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.player.access-granted"));
        } else {
            lore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.player.access-denied"));
        }
        
        // Block Modification Access
        if (trustedPlayer.blockModAccess) {
            lore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.player.building-granted"));
        } else {
            lore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.player.building-denied"));
        }
        
        lore.add(Component.empty());
        lore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.player.instructions.left-click"));
        lore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.player.instructions.right-click"));
        lore.add(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.player.instructions.shift-click"));
        
        meta.lore(lore);
        skull.setItemMeta(meta);
        
        return skull;
    }

    private int getMaxPages() {
        if (trustedPlayers.isEmpty()) {
            return 1;
        }
        return (int) Math.ceil((double) trustedPlayers.size() / playersPerPage);
    }

    private String getOwnerName() {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(UUID.fromString(ownerUuid));
        return owner.getName() != null ? owner.getName() : "Unknown";
    }

    public void open(Player player) {
        player.openInventory(inventory);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(inventory)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();

        // Handle navigation clicks
        if (slot == 45) { // Previous page
            if (currentPage > 0) {
                currentPage--;
                updateDisplayedPlayers();
            }
            return;
        }
        
        if (slot == 53) { // Next page
            int maxPages = getMaxPages();
            if (currentPage < maxPages - 1) {
                currentPage++;
                updateDisplayedPlayers();
            }
            return;
        }

        // Handle add player button
        if (slot == 48) {
            player.closeInventory();
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "gui.security-terminal.messages.add-prompt"));
            plugin.getGUIManager().registerPlayerInput(player, this);
            return;
        }

        // Handle player skull clicks (slots 0-35)
        if (slot >= 0 && slot < 36) {
            int playerIndex = currentPage * playersPerPage + slot;
            if (playerIndex < trustedPlayers.size()) {
                TrustedPlayer trustedPlayer = trustedPlayers.get(playerIndex);
                ClickType clickType = event.getClick();
                
                if (clickType == ClickType.SHIFT_LEFT || clickType == ClickType.SHIFT_RIGHT) {
                    // Remove player
                    removeTrustedPlayer(trustedPlayer);
                } else if (clickType == ClickType.LEFT) {
                    // Toggle all GUI access (terminal, drive bay, server, importer, exporter)
                    toggleGUIAccess(trustedPlayer);
                } else if (clickType == ClickType.RIGHT) {
                    // Toggle block modification access
                    toggleBlockModAccess(trustedPlayer);
                }
            }
        }
    }

    private void toggleGUIAccess(TrustedPlayer trustedPlayer) {
        trustedPlayer.driveBayAccess = !trustedPlayer.driveBayAccess;
        updatePlayerPermissions(trustedPlayer);
        updateDisplayedPlayers();
        
        String statusKey = trustedPlayer.driveBayAccess ? "gui.security-terminal.messages.access-granted" : "gui.security-terminal.messages.access-revoked";
        String status = trustedPlayer.driveBayAccess ? "Granted" : "Revoked";
        viewer.sendMessage(plugin.getMessageManager().getMessageComponent(viewer, statusKey, "status", status, "player", trustedPlayer.name));
    }

    private void toggleBlockModAccess(TrustedPlayer trustedPlayer) {
        trustedPlayer.blockModAccess = !trustedPlayer.blockModAccess;
        updatePlayerPermissions(trustedPlayer);
        updateDisplayedPlayers();
        
        String statusKey = trustedPlayer.blockModAccess ? "gui.security-terminal.messages.building-granted" : "gui.security-terminal.messages.building-revoked";
        String status = trustedPlayer.blockModAccess ? "Granted" : "Revoked";
        viewer.sendMessage(plugin.getMessageManager().getMessageComponent(viewer, statusKey, "status", status, "player", trustedPlayer.name));
    }

    private void updatePlayerPermissions(TrustedPlayer trustedPlayer) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "UPDATE security_terminal_players SET drive_bay_access = ?, block_modification_access = ? " +
                     "WHERE terminal_id = ? AND player_uuid = ?")) {
            
            stmt.setBoolean(1, trustedPlayer.driveBayAccess);
            stmt.setBoolean(2, trustedPlayer.blockModAccess);
            stmt.setString(3, terminalId);
            stmt.setString(4, trustedPlayer.uuid);
            
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error updating player permissions: " + e.getMessage());
        }
    }

    private void removeTrustedPlayer(TrustedPlayer trustedPlayer) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM security_terminal_players WHERE terminal_id = ? AND player_uuid = ?")) {
            
            stmt.setString(1, terminalId);
            stmt.setString(2, trustedPlayer.uuid);
            
            stmt.executeUpdate();
            
            trustedPlayers.remove(trustedPlayer);
            
            // Adjust current page if needed
            int maxPages = getMaxPages();
            if (currentPage >= maxPages && currentPage > 0) {
                currentPage = maxPages - 1;
            }
            
            updateDisplayedPlayers();
            
            viewer.sendMessage(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.messages.player-removed", "player", trustedPlayer.name));
            
        } catch (SQLException e) {
            plugin.getLogger().severe("Error removing trusted player: " + e.getMessage());
        }
    }

    public void addTrustedPlayer(String playerName) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerName);

        offlinePlayer.getUniqueId();

        String playerUuid = offlinePlayer.getUniqueId().toString();
        
        // Check if player is already trusted
        for (TrustedPlayer trusted : trustedPlayers) {
            if (trusted.uuid.equals(playerUuid)) {
                viewer.sendMessage(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.messages.player-already-trusted", "player", playerName));
                return;
            }
        }
        
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO security_terminal_players (terminal_id, player_uuid, player_name, drive_bay_access, block_modification_access) " +
                     "VALUES (?, ?, ?, ?, ?)")) {
            
            stmt.setString(1, terminalId);
            stmt.setString(2, playerUuid);
            stmt.setString(3, offlinePlayer.getName() != null ? offlinePlayer.getName() : playerName);
            stmt.setBoolean(4, false); // Default to no access
            stmt.setBoolean(5, false);
            
            stmt.executeUpdate();
            
            loadTrustedPlayers(); // Reload to get the new player
            
            viewer.sendMessage(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.messages.player-added", "player", playerName));
            
        } catch (SQLException e) {
            if (e.getMessage().contains("UNIQUE constraint failed")) {
                viewer.sendMessage(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.messages.player-already-trusted", "player", playerName));
            } else {
                plugin.getLogger().severe("Error adding trusted player: " + e.getMessage());
                viewer.sendMessage(plugin.getMessageManager().getMessageComponent(viewer, "gui.security-terminal.messages.error-adding", "error", e.getMessage()));
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!event.getInventory().equals(inventory)) return;

        // Unregister this listener
        InventoryClickEvent.getHandlerList().unregister(this);
        InventoryCloseEvent.getHandlerList().unregister(this);

        // Remove from GUI manager
        if (event.getPlayer() instanceof Player player) {
            plugin.getGUIManager().closeGUI(player);
        }
    }

    private static class TrustedPlayer {
        String uuid;
        String name;
        boolean driveBayAccess;
        boolean blockModAccess;

        TrustedPlayer(String uuid, String name, boolean driveBayAccess, boolean blockModAccess) {
            this.uuid = uuid;
            this.name = name;
            this.driveBayAccess = driveBayAccess;
            this.blockModAccess = blockModAccess;
        }
    }
}