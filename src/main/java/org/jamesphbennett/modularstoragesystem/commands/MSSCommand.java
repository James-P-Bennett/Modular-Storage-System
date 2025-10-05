package org.jamesphbennett.modularstoragesystem.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CommandAlias("mss")
@Description("Modular Storage System commands")
@SuppressWarnings("unused")
public class MSSCommand extends BaseCommand {

    private final ModularStorageSystem plugin;

    // Cooldown tracking: <PlayerUUID, <CommandName, ExpirationTime>>
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public MSSCommand(ModularStorageSystem plugin) {
        this.plugin = plugin;
    }

    /**
     * Check if a player is on cooldown for a command
     * @return true if on cooldown, false if ready
     */
    private boolean isOnCooldown(Player player, String commandName, int cooldownSeconds) {
        if (player.hasPermission("modularstoragesystem.bypass_cooldown")) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        Map<String, Long> playerCooldowns = cooldowns.get(playerId);

        if (playerCooldowns == null) {
            return false;
        }

        Long expirationTime = playerCooldowns.get(commandName);
        if (expirationTime == null) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime >= expirationTime) {
            // Cooldown expired, clean up
            playerCooldowns.remove(commandName);
            if (playerCooldowns.isEmpty()) {
                cooldowns.remove(playerId);
            }
            return false;
        }

        // Still on cooldown
        long remainingSeconds = (expirationTime - currentTime) / 1000;
        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cooldown", "seconds", remainingSeconds));
        return true;
    }

    /**
     * Set a cooldown for a player
     */
    private void setCooldown(Player player, String commandName, int cooldownSeconds) {
        if (player.hasPermission("modularstoragesystem.bypass_cooldown")) {
            return;
        }

        UUID playerId = player.getUniqueId();
        long expirationTime = System.currentTimeMillis() + (cooldownSeconds * 1000L);

        cooldowns.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                 .put(commandName, expirationTime);
    }

    @Default
    @Subcommand("help")
    @Description("Display help information")
    public void onHelp(CommandSender sender) {
        Player player = sender instanceof Player ? (Player) sender : null;

        sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.header"));
        sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.main-help"));

        if (sender.hasPermission("modularstoragesystem.admin")) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.recovery"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.give"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.info"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.cleanup"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.recipes"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.recipe"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.reload"));
        }
    }


    @Subcommand("give")
    @Description("Give MSS items to a player")
    @CommandPermission("modularstoragesystem.admin")
    @CommandCompletion("@items @players")
    @Syntax("<item> [player]")
    public void onGive(CommandSender sender, String itemName, @Optional @Flags("other") Player target) {
        if (target == null) {
            if (sender instanceof Player) {
                target = (Player) sender;
            } else {
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(null, "commands.give.console-needs-player"));
                return;
            }
        }

        ItemStack item = switch (itemName.toLowerCase()) {
            // Network blocks
            case "server", "storage_server" -> plugin.getItemManager().createStorageServer();
            case "bay", "drive_bay" -> plugin.getItemManager().createDriveBay();
            case "terminal", "mss_terminal" -> plugin.getItemManager().createMSSTerminal();
            case "cable", "network_cable" -> plugin.getItemManager().createNetworkCable();
            case "exporter" -> plugin.getItemManager().createExporter();
            case "importer" -> plugin.getItemManager().createImporter();
            case "security", "security_terminal" -> plugin.getItemManager().createSecurityTerminal();

            // Storage disks
            case "disk", "storage_disk", "disk1k" -> plugin.getItemManager().createStorageDisk(
                    target.getUniqueId().toString(), target.getName());
            case "disk4k" -> plugin.getItemManager().createStorageDisk4k(
                    target.getUniqueId().toString(), target.getName());
            case "disk16k" -> plugin.getItemManager().createStorageDisk16k(
                    target.getUniqueId().toString(), target.getName());
            case "disk64k" -> plugin.getItemManager().createStorageDisk64k(
                    target.getUniqueId().toString(), target.getName());

            // Components
            case "housing", "disk_housing" -> plugin.getItemManager().createStorageDiskHousing();
            case "platter1k", "platter_1k" -> plugin.getItemManager().createDiskPlatter("1k");
            case "platter4k", "platter_4k" -> plugin.getItemManager().createDiskPlatter("4k");
            case "platter16k", "platter_16k" -> plugin.getItemManager().createDiskPlatter("16k");
            case "platter64k", "platter_64k" -> plugin.getItemManager().createDiskPlatter("64k");

            default -> null;
        };

        Player senderPlayer = sender instanceof Player ? (Player) sender : null;

        if (item == null) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(senderPlayer, "commands.give.invalid-item"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(senderPlayer, "commands.give.available-blocks"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(senderPlayer, "commands.give.available-disks"));
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(senderPlayer, "commands.give.available-components"));
            return;
        }

        if (target.getInventory().firstEmpty() == -1) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(senderPlayer, "commands.give.success-dropped", "player", target.getName()));
        } else {
            target.getInventory().addItem(item);
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(senderPlayer, "commands.give.success-inventory", "player", target.getName()));
        }

        if (!sender.equals(target)) {
            target.sendMessage(plugin.getMessageManager().getMessageComponent(target, "commands.give.received", "item", itemName, "sender", sender.getName()));
        }
    }

    @Subcommand("info")
    @Description("Display system information")
    @CommandPermission("modularstoragesystem.admin")
    public void onInfo(CommandSender sender) {
        Player player = sender instanceof Player ? (Player) sender : null;

        // Check cooldown (only for players)
        if (player != null) {
            int cooldownSeconds = plugin.getConfigManager().getInfoCooldown();
            if (isOnCooldown(player, "info", cooldownSeconds)) {
                return;
            }
            setCooldown(player, "info", cooldownSeconds);
        }

        // Display banner immediately
        sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.header"));

        // Run database queries async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = plugin.getDatabaseManager().getConnection()) {
                // Count networks
                int networkCount;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM networks");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    networkCount = rs.getInt(1);
                }

                // Count storage disks
                int diskCount;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM storage_disks");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    diskCount = rs.getInt(1);
                }

                // Count stored items
                int itemTypes;
                long totalItems;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*), SUM(quantity) FROM storage_items");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    itemTypes = rs.getInt(1);
                    totalItems = rs.getLong(2);
                }

                // Count network cables
                int cableCount;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM network_blocks WHERE block_type = 'NETWORK_CABLE'");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    cableCount = rs.getInt(1);
                }

                // Count exporters
                int exporterCount;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM exporters");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    exporterCount = rs.getInt(1);
                }

                // Count importers
                int importerCount;
                try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM importers");
                     ResultSet rs = stmt.executeQuery()) {
                    rs.next();
                    importerCount = rs.getInt(1);
                }

                // Recipe information (not DB-dependent, safe to run here)
                int recipeCount = plugin.getRecipeManager().getRegisteredRecipeCount();
                Set<String> totalRecipes = plugin.getConfigManager().getRecipeNames();
                boolean recipesEnabled = plugin.getConfigManager().areRecipesEnabled();

                // Return to main thread to send messages
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.networks", "count", networkCount));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.disks", "count", diskCount));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.item-types", "types", itemTypes));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.total-items", "total", totalItems));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.cables", "count", cableCount));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.exporters", "count", exporterCount));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.importers", "count", importerCount));

                    String recipeKey = recipesEnabled ? "commands.info.recipes-enabled" : "commands.info.recipes-disabled";
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, recipeKey, "registered", recipeCount, "total", totalRecipes.size()));
                });

            } catch (Exception e) {
                // Return to main thread to send error message
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.error", "error", e.getMessage())));
            }
        });
    }

    @Subcommand("cleanup")
    @Description("Clean up orphaned data")
    @CommandPermission("modularstoragesystem.admin")
    public void onCleanup(CommandSender sender) {
        Player player = sender instanceof Player ? (Player) sender : null;

        // Check cooldown (only for players)
        if (player != null) {
            int cooldownSeconds = plugin.getConfigManager().getCleanupCooldown();
            if (isOnCooldown(player, "cleanup", cooldownSeconds)) {
                return;
            }
            setCooldown(player, "cleanup", cooldownSeconds);
        }

        // Run cleanup operations async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // Clean up orphaned storage items (items without valid disks)
                int deletedItems;
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                             "DELETE FROM storage_items WHERE disk_id NOT IN (SELECT disk_id FROM storage_disks)")) {
                    deletedItems = stmt.executeUpdate();
                }

                // Clean up empty storage disks with no items
                int updatedDisks;
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                             "UPDATE storage_disks SET used_cells = 0 WHERE disk_id NOT IN (SELECT DISTINCT disk_id FROM storage_items)")) {
                    updatedDisks = stmt.executeUpdate();
                }

                // Return to main thread to send messages
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cleanup.orphaned-items", "count", deletedItems));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cleanup.reset-disks", "count", updatedDisks));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cleanup.success"));
                });

            } catch (Exception e) {
                // Return to main thread to send error message
                plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cleanup.error", "error", e.getMessage())));
            }
        });
    }

    @Subcommand("recipes")
    @Description("List all available recipes")
    @CommandPermission("modularstoragesystem.admin")
    public void onRecipes(Player player) {
        plugin.getRecipeManager().listRecipes(player);
    }

    @Subcommand("recipe")
    @Description("View details of a specific recipe")
    @CommandPermission("modularstoragesystem.admin")
    @CommandCompletion("@recipes")
    @Syntax("<recipe_name>")
    public void onRecipe(Player player, String recipeName) {
        plugin.getRecipeManager().sendRecipeInfo(player, recipeName);
    }

    @Subcommand("reload")
    @Description("Reload plugin configuration")
    @CommandPermission("modularstoragesystem.admin")
    @CommandCompletion("config|recipes|lang|all")
    @Syntax("[config|recipes|lang|all]")
    public void onReload(CommandSender sender, @Default("all") String what) {
        Player player = sender instanceof Player ? (Player) sender : null;

        switch (what.toLowerCase()) {
            case "config":
                try {
                    plugin.getConfigManager().loadConfig();
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.config-success"));
                } catch (Exception e) {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.config-error", "error", e.getMessage()));
                }
                break;

            case "recipes":
                try {
                    plugin.getRecipeManager().reloadRecipes();
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.recipes-success"));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.recipes-note"));
                } catch (Exception e) {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.recipes-error", "error", e.getMessage()));
                }
                break;

            case "lang":
            case "language":
                try {
                    plugin.getMessageManager().reloadLanguages();
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.lang-success"));
                } catch (Exception e) {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.lang-error", "error", e.getMessage()));
                }
                break;

            case "all":
                try {
                    plugin.getConfigManager().reloadConfig();
                    plugin.getRecipeManager().reloadRecipes();
                    plugin.getMessageManager().reloadLanguages();
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.all-success"));
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.recipes-note"));
                } catch (Exception e) {
                    sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.all-error", "error", e.getMessage()));
                }
                break;

            default:
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.reload.usage"));
                break;
        }
    }

    @Subcommand("recovery")
    @Description("Recover a lost storage disk")
    @CommandPermission("modularstoragesystem.recovery")
    @CommandCompletion("@nothing")
    @Syntax("<disk_id> [confirm]")
    public void onRecovery(Player player, String diskId, @Optional String confirmation) {
        // Check cooldown
        int cooldownSeconds = plugin.getConfigManager().getRecoveryCooldown();
        if (isOnCooldown(player, "recovery", cooldownSeconds)) {
            return;
        }
        setCooldown(player, "recovery", cooldownSeconds);

        boolean forceConfirm = "confirm".equalsIgnoreCase(confirmation);

        // Run database queries async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // First check if disk is currently active in a drive bay
                if (!forceConfirm) {
                    try (Connection conn = plugin.getDatabaseManager().getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT dbs.network_id, dbs.world_name, dbs.x, dbs.y, dbs.z, dbs.slot_number " +
                                 "FROM drive_bay_slots dbs WHERE dbs.disk_id = ?")) {

                        stmt.setString(1, diskId.toUpperCase());

                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                // Disk is active - show warning and require confirmation
                                String networkId = rs.getString("network_id");
                                String world = rs.getString("world_name");
                                int x = rs.getInt("x");
                                int y = rs.getInt("y");
                                int z = rs.getInt("z");
                                int slot = rs.getInt("slot_number");

                                // Return to main thread to send messages
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-warning"));
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-network", "network_id", networkId));
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-location", "world", world, "x", x, "y", y, "z", z, "slot", slot));
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-confirm"));
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-usage", "disk_id", diskId));
                                });
                                return;
                            }
                        }
                    }
                }

                // Look up disk in database
                try (Connection conn = plugin.getDatabaseManager().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                             "SELECT crafter_uuid, crafter_name, used_cells, max_cells, tier FROM storage_disks WHERE disk_id = ?")) {

                    stmt.setString(1, diskId.toUpperCase());

                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            // Return to main thread to send error
                            plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.not-found", "disk_id", diskId)));
                            return;
                        }

                        String crafterUUID = rs.getString("crafter_uuid");
                        String crafterName = rs.getString("crafter_name");
                        int usedCells = rs.getInt("used_cells");
                        int maxCells = rs.getInt("max_cells");
                        rs.getString("tier");

                        // If this was a forced recovery, remove the disk from drive bay slots
                        if (forceConfirm) {
                            try (PreparedStatement removeStmt = conn.prepareStatement(
                                    "DELETE FROM drive_bay_slots WHERE disk_id = ?")) {
                                removeStmt.setString(1, diskId.toUpperCase());

                                // Store removal count for later message
                                final int removedCount = removeStmt.executeUpdate();

                                // Return to main thread for inventory operations
                                plugin.getServer().getScheduler().runTask(plugin, () -> {
                                    // Create the storage disk with the original ID
                                    ItemStack recoveredDisk = plugin.getItemManager().createStorageDiskWithId(diskId.toUpperCase(), crafterUUID, crafterName);
                                    recoveredDisk = plugin.getItemManager().updateStorageDiskLore(recoveredDisk, usedCells, maxCells);

                                    // Give to player (MUST be on main thread)
                                    if (player.getInventory().firstEmpty() == -1) {
                                        player.getWorld().dropItemNaturally(player.getLocation(), recoveredDisk);
                                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.success-dropped"));
                                    } else {
                                        player.getInventory().addItem(recoveredDisk);
                                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.success-inventory"));
                                    }

                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.disk-info", "disk_id", diskId));
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.crafter-info", "crafter", crafterName));
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.cells-info", "used", usedCells, "max", maxCells));

                                    if (removedCount > 0) {
                                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.recovery.disk-removed-from-bay"));
                                    }
                                });
                            }
                        } else {
                            // Return to main thread for inventory operations
                            plugin.getServer().getScheduler().runTask(plugin, () -> {
                                // Create the storage disk with the original ID
                                ItemStack recoveredDisk = plugin.getItemManager().createStorageDiskWithId(diskId.toUpperCase(), crafterUUID, crafterName);
                                recoveredDisk = plugin.getItemManager().updateStorageDiskLore(recoveredDisk, usedCells, maxCells);

                                // Give to player (MUST be on main thread)
                                if (player.getInventory().firstEmpty() == -1) {
                                    player.getWorld().dropItemNaturally(player.getLocation(), recoveredDisk);
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.success-dropped"));
                                } else {
                                    player.getInventory().addItem(recoveredDisk);
                                    player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.success-inventory"));
                                }

                                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.disk-info", "disk_id", diskId));
                                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.crafter-info", "crafter", crafterName));
                                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.cells-info", "used", usedCells, "max", maxCells));
                            });
                        }
                    }
                }

            } catch (Exception e) {
                // Return to main thread to send error message
                plugin.getServer().getScheduler().runTask(plugin, () -> player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.error", "error", e.getMessage())));
                plugin.getLogger().severe("Error during disk recovery: " + e.getMessage());
            }
        });
    }
}
