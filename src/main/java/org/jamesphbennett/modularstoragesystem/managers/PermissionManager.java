package org.jamesphbennett.modularstoragesystem.managers;

import org.bukkit.entity.Player;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

/**
 * Manages MSS permission checks for crafting and using items/blocks.
 * Provides centralized permission checking with support for wildcard permissions.
 */
public class PermissionManager {

    private final ModularStorageSystem plugin;

    // Permission node prefixes
    private static final String CRAFT_PREFIX = "mss.craft.";
    private static final String USE_PREFIX = "mss.use.";

    // Wildcard permissions
    private static final String CRAFT_WILDCARD = "mss.craft.*";
    private static final String USE_WILDCARD = "mss.use.*";

    public PermissionManager(ModularStorageSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Check if a player has permission to craft a specific MSS item
     * @param player The player to check
     * @param itemType The item type (e.g., "storage_server", "network_cable")
     * @return true if the player can craft this item
     */
    public boolean canCraft(Player player, String itemType) {
        // OPs bypass all permission checks
        if (player.isOp()) {
            return true;
        }

        // Check wildcard permission first
        if (player.hasPermission(CRAFT_WILDCARD)) {
            return true;
        }

        // Check specific permission
        return player.hasPermission(CRAFT_PREFIX + itemType);
    }

    /**
     * Check if a player has permission to use/place/interact with a specific MSS item
     * @param player The player to check
     * @param itemType The item type (e.g., "storage_server", "network_cable")
     * @return true if the player can use this item
     */
    public boolean canUse(Player player, String itemType) {
        // OPs bypass all permission checks
        if (player.isOp()) {
            return true;
        }

        // Check wildcard permission first
        if (player.hasPermission(USE_WILDCARD)) {
            return true;
        }

        // Check specific permission
        return player.hasPermission(USE_PREFIX + itemType);
    }

    /**
     * Send a localized permission denied message for crafting
     * @param player The player to send the message to
     * @param itemType The item type they tried to craft
     */
    public void sendCraftDeniedMessage(Player player, String itemType) {
        player.sendMessage(plugin.getMessageManager().getMessageComponent(
            player,
            "permissions.craft-denied",
            "item", getItemDisplayName(itemType)
        ));
    }

    /**
     * Send a localized permission denied message for using/placing/interacting
     * @param player The player to send the message to
     * @param itemType The item type they tried to use
     * @param action The action they tried to perform ("place", "open", "insert", etc.)
     */
    public void sendUseDeniedMessage(Player player, String itemType, String action) {
        String messageKey;

        // Determine appropriate message key based on action
        switch (action.toLowerCase()) {
            case "place":
                messageKey = "permissions.use-denied-place";
                break;
            case "open":
            case "gui":
                messageKey = "permissions.use-denied-open";
                break;
            case "insert":
            case "remove":
                messageKey = "permissions.use-denied-modify";
                break;
            default:
                messageKey = "permissions.use-denied";
                break;
        }

        player.sendMessage(plugin.getMessageManager().getMessageComponent(
            player,
            messageKey,
            "item", getItemDisplayName(itemType)
        ));
    }

    /**
     * Get a human-readable display name for an item type
     * @param itemType The item type identifier
     * @return A formatted display name
     */
    private String getItemDisplayName(String itemType) {
        // Try to get from lang file first using default language (en_US)
        String langKey = "items." + itemType;
        String message = plugin.getMessageManager().getMessage("en_US", langKey);

        // If no lang entry exists, format the item type nicely
        if (message != null && message.contains("Missing message")) {
            // Convert snake_case to Title Case
            String[] words = itemType.replace("_", " ").split(" ");
            StringBuilder result = new StringBuilder();
            for (String word : words) {
                if (!result.isEmpty()) result.append(" ");
                if (!word.isEmpty()) {
                    result.append(Character.toUpperCase(word.charAt(0)));
                    if (word.length() > 1) {
                        result.append(word.substring(1).toLowerCase());
                    }
                }
            }
            return result.toString();
        }

        return message != null ? message : itemType;
    }

    /**
     * Get the permission node for crafting an item
     * @param itemType The item type
     * @return The full permission node (e.g., "mss.craft.storage_server")
     */
    public String getCraftPermission(String itemType) {
        return CRAFT_PREFIX + itemType;
    }

    /**
     * Get the permission node for using an item
     * @param itemType The item type
     * @return The full permission node (e.g., "mss.use.storage_server")
     */
    public String getUsePermission(String itemType) {
        return USE_PREFIX + itemType;
    }
}
