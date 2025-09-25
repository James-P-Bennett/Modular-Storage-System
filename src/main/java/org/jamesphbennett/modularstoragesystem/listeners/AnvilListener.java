package org.jamesphbennett.modularstoragesystem.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

public class AnvilListener implements Listener {

    private final ModularStorageSystem plugin;

    public AnvilListener(ModularStorageSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilPrepare(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        ItemStack firstItem = inventory.getItem(0);  // Left slot (item to rename)
        ItemStack secondItem = inventory.getItem(1); // Right slot (material/name tag)
        
        // Check if any MSS items are being used in the anvil
        if (isMSSItem(firstItem) || isMSSItem(secondItem)) {
            // Cancel the anvil operation by setting result to null
            event.setResult(null);
            
            // Send warning message to player
            if (event.getViewers().getFirst() instanceof Player player) {
                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.gui.anvil.mss-items-blocked"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvilClick(InventoryClickEvent event) {
        // Only handle anvil inventories
        if (event.getInventory().getType() != InventoryType.ANVIL) {
            return;
        }

        // Check if the click is targeting the anvil inventory itself (not player inventory)
        if (event.getClickedInventory() == event.getInventory()) {
            // This is a click directly on the anvil inventory
            ItemStack clickedItem = event.getCursor();
            
            // Block placing MSS items directly into anvil slots
            if (isMSSItem(clickedItem)) {
                event.setCancelled(true);
                
                if (event.getWhoClicked() instanceof Player player) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.gui.anvil.mss-items-blocked"));
                }
                return;
            }
        }

        // Block shift-clicking MSS items from player inventory into anvil
        if (event.isShiftClick() && event.getClickedInventory() != event.getInventory()) {
            ItemStack slotItem = event.getCurrentItem();
            
            if (isMSSItem(slotItem)) {
                event.setCancelled(true);
                
                if (event.getWhoClicked() instanceof Player player) {
                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.gui.anvil.mss-items-blocked"));
                }
            }
        }
        
        // All other clicks (player inventory, hotbar, etc.) are allowed
    }

    /**
     * Check if an ItemStack is an MSS item by checking for MSS persistent data keys
     */
    private boolean isMSSItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        // Use ItemManager methods to check for MSS items
        return plugin.getItemManager().isStorageServer(item) ||
               plugin.getItemManager().isDriveBay(item) ||
               plugin.getItemManager().isMSSTerminal(item) ||
               plugin.getItemManager().isNetworkCable(item) ||
               plugin.getItemManager().isStorageDisk(item) ||
               plugin.getItemManager().isExporter(item) ||
               plugin.getItemManager().isImporter(item) ||
               plugin.getItemManager().isSecurityTerminal(item) ||
               plugin.getItemManager().isDiskPlatter(item) ||
               plugin.getItemManager().isStorageDiskHousing(item);
    }
}