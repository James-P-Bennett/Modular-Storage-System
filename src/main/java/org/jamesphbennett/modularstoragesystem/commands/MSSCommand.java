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
import java.util.Set;

@CommandAlias("mss")
@Description("Modular Storage System commands")
@SuppressWarnings("unused")
public class MSSCommand extends BaseCommand {

    private final ModularStorageSystem plugin;

    public MSSCommand(ModularStorageSystem plugin) {
        this.plugin = plugin;
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

        // Display banner and version header
        sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.help.header"));

        try (Connection conn = plugin.getDatabaseManager().getConnection()) {
            // Count networks
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM networks");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int networkCount = rs.getInt(1);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.networks", "count", networkCount));
            }

            // Count storage disks
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM storage_disks");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int diskCount = rs.getInt(1);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.disks", "count", diskCount));
            }

            // Count stored items
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*), SUM(quantity) FROM storage_items");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int itemTypes = rs.getInt(1);
                long totalItems = rs.getLong(2);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.item-types", "types", itemTypes));
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.total-items", "total", totalItems));
            }

            // Count network cables
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM network_blocks WHERE block_type = 'NETWORK_CABLE'");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int cableCount = rs.getInt(1);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.cables", "count", cableCount));
            }

            // Count exporters
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM exporters");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int exporterCount = rs.getInt(1);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.exporters", "count", exporterCount));
            }

            // Count importers
            try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM importers");
                 ResultSet rs = stmt.executeQuery()) {
                rs.next();
                int importerCount = rs.getInt(1);
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.importers", "count", importerCount));
            }

            // Recipe information
            int recipeCount = plugin.getRecipeManager().getRegisteredRecipeCount();
            Set<String> totalRecipes = plugin.getConfigManager().getRecipeNames();
            boolean recipesEnabled = plugin.getConfigManager().areRecipesEnabled();

            String recipeKey = recipesEnabled ? "commands.info.recipes-enabled" : "commands.info.recipes-disabled";
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, recipeKey, "registered", recipeCount, "total", totalRecipes.size()));

        } catch (Exception e) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.info.error", "error", e.getMessage()));
        }
    }

    @Subcommand("cleanup")
    @Description("Clean up orphaned data")
    @CommandPermission("modularstoragesystem.admin")
    public void onCleanup(CommandSender sender) {
        Player player = sender instanceof Player ? (Player) sender : null;

        try {
            // Clean up orphaned storage items (items without valid disks)
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM storage_items WHERE disk_id NOT IN (SELECT disk_id FROM storage_disks)")) {
                int deletedItems = stmt.executeUpdate();
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cleanup.orphaned-items", "count", deletedItems));
            }

            // Clean up empty storage disks with no items
            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "UPDATE storage_disks SET used_cells = 0 WHERE disk_id NOT IN (SELECT DISTINCT disk_id FROM storage_items)")) {
                int updatedDisks = stmt.executeUpdate();
                sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cleanup.reset-disks", "count", updatedDisks));
            }

            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cleanup.success"));

        } catch (Exception e) {
            sender.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.cleanup.error", "error", e.getMessage()));
        }
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
        boolean forceConfirm = "confirm".equalsIgnoreCase(confirmation);

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

                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-warning"));
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-network", "network_id", networkId));
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-location", "world", world, "x", x, "y", y, "z", z, "slot", slot));
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-confirm"));
                            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.active-disk-usage", "disk_id", diskId));
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
                        player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.not-found", "disk_id", diskId));
                        return;
                    }

                    String crafterUUID = rs.getString("crafter_uuid");
                    String crafterName = rs.getString("crafter_name");
                    int usedCells = rs.getInt("used_cells");
                    int maxCells = rs.getInt("max_cells");
                    rs.getString("tier");

                    // Create the storage disk with the original ID
                    ItemStack recoveredDisk = plugin.getItemManager().createStorageDiskWithId(diskId.toUpperCase(), crafterUUID, crafterName);
                    recoveredDisk = plugin.getItemManager().updateStorageDiskLore(recoveredDisk, usedCells, maxCells);

                    // Give to player
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

                    // If this was a forced recovery, remove the disk from drive bay slots
                    if (forceConfirm) {
                        try (PreparedStatement removeStmt = conn.prepareStatement(
                                "DELETE FROM drive_bay_slots WHERE disk_id = ?")) {
                            removeStmt.setString(1, diskId.toUpperCase());
                            int removed = removeStmt.executeUpdate();
                            if (removed > 0) {
                                player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "errors.recovery.disk-removed-from-bay"));
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            player.sendMessage(plugin.getMessageManager().getMessageComponent(player, "commands.recovery.error", "error", e.getMessage()));
            plugin.getLogger().severe("Error during disk recovery: " + e.getMessage());
        }
    }
}
