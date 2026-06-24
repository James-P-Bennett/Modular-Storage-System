package org.jamesphbennett.modularstoragesystem.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;
import org.jamesphbennett.modularstoragesystem.blocks.MSSBlockType;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ItemManager {

    private final ModularStorageSystem plugin;
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySerializer;

    // Namespace keys for identifying custom items
    private final NamespacedKey STORAGE_SERVER_KEY;
    private final NamespacedKey DRIVE_BAY_KEY;
    private final NamespacedKey MSS_TERMINAL_KEY;
    private final NamespacedKey NETWORK_CABLE_KEY;
    private final NamespacedKey WRENCH_KEY;
    private final NamespacedKey STORAGE_DISK_KEY;
    private final NamespacedKey EXPORTER_KEY;
    private final NamespacedKey IMPORTER_KEY;
    private final NamespacedKey SECURITY_TERMINAL_KEY;
    private final NamespacedKey DISK_ID_KEY;
    private final NamespacedKey DISK_CRAFTER_UUID_KEY;
    private final NamespacedKey DISK_CRAFTER_NAME_KEY;
    private final NamespacedKey DISK_USED_CELLS_KEY;
    private final NamespacedKey DISK_MAX_CELLS_KEY;
    private final NamespacedKey DISK_TIER_KEY;
    private final NamespacedKey DISK_GENERATION_KEY;

    // Component namespace keys
    private final NamespacedKey DISK_PLATTER_KEY;
    private final NamespacedKey DISK_PLATTER_TIER_KEY;
    private final NamespacedKey STORAGE_DISK_HOUSING_KEY;

    // The item_model component API (ItemMeta.hasItemModel/getItemModel) exists only on MC 1.21.2+; look the
    // methods up reflectively (once) so this plugin still compiles against, and runs on, 1.21.0 / 1.21.1.
    private final java.lang.reflect.Method hasItemModelMethod;
    private final java.lang.reflect.Method getItemModelMethod;
    private final java.lang.reflect.Method setItemModelMethod;

    public ItemManager(ModularStorageSystem plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.legacySerializer = LegacyComponentSerializer.legacySection();

        STORAGE_SERVER_KEY = new NamespacedKey(plugin, "storage_server");
        DRIVE_BAY_KEY = new NamespacedKey(plugin, "drive_bay");
        MSS_TERMINAL_KEY = new NamespacedKey(plugin, "mss_terminal");
        NETWORK_CABLE_KEY = new NamespacedKey(plugin, "network_cable");
        WRENCH_KEY = new NamespacedKey(plugin, "mss_wrench");
        STORAGE_DISK_KEY = new NamespacedKey(plugin, "storage_disk");
        EXPORTER_KEY = new NamespacedKey(plugin, "exporter");
        IMPORTER_KEY = new NamespacedKey(plugin, "importer");
        SECURITY_TERMINAL_KEY = new NamespacedKey(plugin, "security_terminal");
        DISK_ID_KEY = new NamespacedKey(plugin, "disk_id");
        DISK_CRAFTER_UUID_KEY = new NamespacedKey(plugin, "disk_crafter_uuid");
        DISK_CRAFTER_NAME_KEY = new NamespacedKey(plugin, "disk_crafter_name");
        DISK_USED_CELLS_KEY = new NamespacedKey(plugin, "disk_used_cells");
        DISK_MAX_CELLS_KEY = new NamespacedKey(plugin, "disk_max_cells");
        DISK_TIER_KEY = new NamespacedKey(plugin, "disk_tier");
        DISK_GENERATION_KEY = new NamespacedKey(plugin, "disk_generation");

        // Component keys
        DISK_PLATTER_KEY = new NamespacedKey(plugin, "disk_platter");
        DISK_PLATTER_TIER_KEY = new NamespacedKey(plugin, "disk_platter_tier");
        STORAGE_DISK_HOUSING_KEY = new NamespacedKey(plugin, "storage_disk_housing");

        java.lang.reflect.Method hasM = null, getM = null, setM = null;
        try {
            hasM = org.bukkit.inventory.meta.ItemMeta.class.getMethod("hasItemModel");
            getM = org.bukkit.inventory.meta.ItemMeta.class.getMethod("getItemModel");
        } catch (NoSuchMethodException ignored) {
            // item_model read API not present (<1.21.2) — disk hashing simply omits the item model on those versions.
        }
        try {
            setM = org.bukkit.inventory.meta.ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
        } catch (NoSuchMethodException ignored) {
            // item_model SETTER not present (<1.21.3) — custom_model_data alone tags items on those versions.
        }
        hasItemModelMethod = hasM;
        getItemModelMethod = getM;
        setItemModelMethod = setM;
    }

    /**
     * Tag an MSS item so a resource pack can give it a distinct model, version-safely:
     *  - custom_model_data (int): compiles at the 1.21 baseline, works 1.21 -> latest, and falls back to the
     *    plain vanilla look when no pack is loaded. Legacy packs (<=1.21.3) key off this via model overrides.
     *  - item_model (namespaced id): set reflectively (the setter exists on the 1.21.3+ API); modern packs key off it.
     * No-ops entirely when the resource pack is disabled in config, so items stay pure-vanilla in that mode.
     */
    private void applyCustomModel(ItemMeta meta, String modelId, int customModelData) {
        if (!plugin.getConfigManager().isResourcePackEnabled()) return;
        meta.setCustomModelData(customModelData);
        if (setItemModelMethod != null) {
            try {
                setItemModelMethod.invoke(meta, new NamespacedKey(plugin, modelId));
            } catch (ReflectiveOperationException ignored) {
                // setter present but failed — custom_model_data still tags the item
            }
        }
    }

    /**
     * Build the cosmetic ItemStack shown by a cable's pipe display entity (see CablePipeRenderer): a HEAVY_CORE
     * carrying the connection-mask item_model {@code network_cable_pipe_<mask>} (mask 0..63). Uses the same
     * cross-version custom_model_data + item_model tagging as every other MSS item; no-ops on the model tags when
     * the resource pack is disabled (the renderer is gated on that too, so it never spawns a raw HEAVY_CORE).
     */
    public ItemStack createCablePipeDisplayItem(int mask) {
        ItemStack item = new ItemStack(Material.HEAVY_CORE);
        ItemMeta meta = item.getItemMeta();
        applyCustomModel(meta, "network_cable_pipe_" + mask, 1100 + mask);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * The Material an MSS block should be created/placed as right now: the role's {@code packMaterial} when the
     * resource pack is enabled (a mushroom block for the cube roles so the pack can model the placed block; still
     * HEAVY_CORE for the cable), else the role's vanilla Material. Item-side identity is the PDC marker, not the
     * Material, so this swap is invisible to every is*(ItemStack) check.
     */
    private Material placedMaterial(MSSBlockType type) {
        return type.placedMaterial(plugin.getConfigManager().isResourcePackEnabled());
    }

    public ItemStack createStorageServer() {
        ItemStack item = new ItemStack(placedMaterial(MSSBlockType.STORAGE_SERVER));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.storage_server.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.storage_server.lore"));

        meta.getPersistentDataContainer().set(STORAGE_SERVER_KEY, PersistentDataType.BOOLEAN, true);

        applyCustomModel(meta, "storage_server", 1001);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createDriveBay() {
        ItemStack item = new ItemStack(placedMaterial(MSSBlockType.DRIVE_BAY));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.drive_bay.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.drive_bay.lore"));

        meta.getPersistentDataContainer().set(DRIVE_BAY_KEY, PersistentDataType.BOOLEAN, true);

        applyCustomModel(meta, "drive_bay", 1002);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createMSSTerminal() {
        ItemStack item = new ItemStack(placedMaterial(MSSBlockType.MSS_TERMINAL));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.mss_terminal.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.mss_terminal.lore"));

        meta.getPersistentDataContainer().set(MSS_TERMINAL_KEY, PersistentDataType.BOOLEAN, true);

        applyCustomModel(meta, "mss_terminal", 1003);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createNetworkCable() {
        ItemStack item = new ItemStack(placedMaterial(MSSBlockType.NETWORK_CABLE));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.network_cable.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.network_cable.lore"));

        meta.getPersistentDataContainer().set(NETWORK_CABLE_KEY, PersistentDataType.BOOLEAN, true);

        // The held cable item is a CONDUIT (placedMaterial) carrying a custom 3D model (network_cable_item) so it
        // reads as a cable in hand/inventory; the PLACED conduit floats inside the pipe display in-world.
        applyCustomModel(meta, "network_cable", 1004);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createExporter() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();

        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.exporter.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.exporter.lore"));

        // Apply BurningFurnace player skin texture
        try {
            applyPlayerSkinTexture(meta);
            // Texture applied successfully - removed verbose console output
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply BurningFurnace player texture to exporter: " + e.getMessage());
        }

        meta.getPersistentDataContainer().set(EXPORTER_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createImporter() {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) item.getItemMeta();

        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.importer.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.importer.lore"));

        // Apply custom player skin texture using UUID
        try {
            applyImporterPlayerSkinTexture(meta);
            // Texture applied successfully - removed verbose console output
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply custom player texture to importer: " + e.getMessage());
        }

        meta.getPersistentDataContainer().set(IMPORTER_KEY, PersistentDataType.BOOLEAN, true);

        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createSecurityTerminal() {
        ItemStack item = new ItemStack(placedMaterial(MSSBlockType.SECURITY_TERMINAL));
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.security_terminal.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.security_terminal.lore"));

        meta.getPersistentDataContainer().set(SECURITY_TERMINAL_KEY, PersistentDataType.BOOLEAN, true);

        applyCustomModel(meta, "security_terminal", 1005);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Apply player skin texture to skull meta for exporter (from config)
     */
    private void applyPlayerSkinTexture(SkullMeta skullMeta) {
        String base64Texture = plugin.getConfig().getString("skull_textures.exporter");
        if (base64Texture == null || base64Texture.isEmpty()) {
            plugin.getLogger().warning("Exporter skull texture not found in config! Using default Steve head.");
            return;
        }
        applyBase64Texture(skullMeta, "exporter", base64Texture);
    }

    /**
     * Apply player skin texture to skull meta for importer (from config)
     */
    private void applyImporterPlayerSkinTexture(SkullMeta skullMeta) {
        String base64Texture = plugin.getConfig().getString("skull_textures.importer");
        if (base64Texture == null || base64Texture.isEmpty()) {
            plugin.getLogger().warning("Importer skull texture not found in config! Using default Steve head.");
            return;
        }
        applyBase64Texture(skullMeta, "importer", base64Texture);
    }

    /**
     * Apply custom skull texture from base64 encoded texture value
     */
    private void applyBase64Texture(SkullMeta skullMeta, String profileName, String base64Texture) {
        // Create PlayerProfile with custom texture using consistent UUID for stacking
        var profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(profileName.getBytes()));

        try {
            // Decode base64 to get the texture URL
            String decodedJson = new String(java.util.Base64.getDecoder().decode(base64Texture));
            com.google.gson.JsonObject textureData = com.google.gson.JsonParser.parseString(decodedJson).getAsJsonObject();

            if (textureData.has("textures") && textureData.getAsJsonObject("textures").has("SKIN")) {
                String skinUrl = textureData.getAsJsonObject("textures")
                        .getAsJsonObject("SKIN")
                        .get("url")
                        .getAsString();

                // Apply the texture URL to the profile
                var textures = profile.getTextures();
                textures.setSkin(new java.net.URI(skinUrl).toURL());
                profile.setTextures(textures);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to set " + profileName + " texture: " + e.getMessage());
        }

        skullMeta.setPlayerProfile(profile);
    }


    /**
     * Create a Disk Platter for the specified tier
     */
    public ItemStack createDiskPlatter(String tier) {
        Material material = getDiskPlatterMaterial(tier);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        // Tier label (1k/4k/16k/64k) substituted into the lang name
        String tierLabel = switch (tier.toLowerCase()) {
            case "4k" -> "4k";
            case "16k" -> "16k";
            case "64k" -> "64k";
            default -> "1k";
        };
        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.disk_platter.name", "tier", tierLabel));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.disk_platter.lore"));


        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(DISK_PLATTER_KEY, PersistentDataType.BOOLEAN, true);
        pdc.set(DISK_PLATTER_TIER_KEY, PersistentDataType.STRING, tier);

        applyCustomModel(meta, "disk_platter_" + tierLabel, switch (tierLabel) {
            case "4k" -> 1012;
            case "16k" -> 1013;
            case "64k" -> 1014;
            default -> 1011;
        });
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Create a Storage Disk Housing
     */
    /** The MSS Wrench: a skinnable IRON_AXE tool. Right-click rotates a placed MSS block; shift+right-click
     *  dismantles an MSS block to inventory (costs durability). Identity is the WRENCH_KEY PDC marker. */
    public ItemStack createWrench() {
        ItemStack item = new ItemStack(Material.IRON_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.mss_wrench.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.mss_wrench.lore"));
        meta.getPersistentDataContainer().set(WRENCH_KEY, PersistentDataType.BOOLEAN, true);
        applyCustomModel(meta, "mss_wrench", 1007);

        // Kill the iron-axe attack cooldown (the slow recharge looks odd on a tool): override attack speed to a high
        // value (instant recharge) in the main hand, and hide the attribute line from the tooltip.
        org.bukkit.attribute.Attribute attackSpeed = resolveAttackSpeed();
        if (attackSpeed != null) {
            meta.addAttributeModifier(attackSpeed, new org.bukkit.attribute.AttributeModifier(
                    new NamespacedKey(plugin, "wrench_no_cooldown"), 1000.0,
                    org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER,
                    org.bukkit.inventory.EquipmentSlotGroup.MAINHAND));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        }

        item.setItemMeta(meta);
        return item;
    }

    /** Resolve the attack-speed Attribute via the registry, trying the modern ("attack_speed") then legacy
     *  ("generic.attack_speed") keys. The enum constant AND its key were renamed across 1.21.x, so a direct
     *  Attribute.GENERIC_ATTACK_SPEED reference (compiled on 1.21) would NoSuchFieldError on newer servers (§10). */
    private static org.bukkit.attribute.Attribute resolveAttackSpeed() {
        for (String key : new String[]{"attack_speed", "generic.attack_speed"}) {
            try {
                org.bukkit.attribute.Attribute a = org.bukkit.Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                if (a != null) return a;
            } catch (Throwable ignored) {
                // registry shape differs on this version — fall through to the next key / give up gracefully
            }
        }
        return null;
    }

    public boolean isWrench(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(WRENCH_KEY, PersistentDataType.BOOLEAN);
    }

    public ItemStack createStorageDiskHousing() {
        ItemStack item = new ItemStack(Material.BAMBOO_PRESSURE_PLATE);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.disk_housing.name"));
        meta.lore(plugin.getMessageManager().getMessageComponentList(null, "item.disk_housing.lore"));

        meta.getPersistentDataContainer().set(STORAGE_DISK_HOUSING_KEY, PersistentDataType.BOOLEAN, true);

        applyCustomModel(meta, "disk_housing", 1006);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Get material for disk platter based on tier
     */
    private Material getDiskPlatterMaterial(String tier) {
        return switch (tier.toLowerCase()) {
            case "4k" -> Material.GOLD_NUGGET;
            case "16k" -> Material.CONDUIT;
            case "64k" -> Material.HEART_OF_THE_SEA;
            default -> Material.STONE_BUTTON;
        };
    }


    /**
     * Check if an item is a disk platter component
     */
    public boolean isDiskPlatter(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(DISK_PLATTER_KEY, PersistentDataType.BOOLEAN);
    }

    /**
     * Check if an item is a storage disk housing component
     */
    public boolean isStorageDiskHousing(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(STORAGE_DISK_HOUSING_KEY, PersistentDataType.BOOLEAN);
    }

    /**
     * Check if an item is any MSS component
     */
    public boolean isMSSComponent(ItemStack item) {
        return isDiskPlatter(item) || isStorageDiskHousing(item);
    }

    /**
     * Get the tier of a disk platter
     */
    public String getDiskPlatterTier(ItemStack item) {
        if (!isDiskPlatter(item)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(DISK_PLATTER_TIER_KEY, PersistentDataType.STRING);
    }


    /**
     * Create a 1k storage disk (base tier)
     */
    public ItemStack createStorageDisk(String crafterUUID, String crafterName) {
        return createStorageDiskWithTier(crafterUUID, crafterName, "1k");
    }

    /**
     * Create a 4k storage disk
     */
    public ItemStack createStorageDisk4k(String crafterUUID, String crafterName) {
        return createStorageDiskWithTier(crafterUUID, crafterName, "4k");
    }

    /**
     * Create a 16k storage disk
     */
    public ItemStack createStorageDisk16k(String crafterUUID, String crafterName) {
        return createStorageDiskWithTier(crafterUUID, crafterName, "16k");
    }

    /**
     * Create a 64k storage disk
     */
    public ItemStack createStorageDisk64k(String crafterUUID, String crafterName) {
        return createStorageDiskWithTier(crafterUUID, crafterName, "64k");
    }

    /**
     * Core method to create any tier of storage disk
     */
    private ItemStack createStorageDiskWithTier(String crafterUUID, String crafterName, String tier) {
        String diskId = generateDiskId();
        return createStorageDiskWithIdAndTier(diskId, crafterUUID, crafterName, tier);
    }

    /**
     * Enhanced createStorageDiskWithId - now determines tier from database or defaults to 1k
     */
    public ItemStack createStorageDiskWithId(String diskId, String crafterUUID, String crafterName) {
        // Try to get tier from database first
        String tier = getTierFromDatabase(diskId);
        if (tier == null) {
            tier = "1k"; // Default fallback
        }
        ItemStack disk = createStorageDiskWithIdAndTier(diskId, crafterUUID, crafterName, tier);
        // Stamp the authoritative stored generation so a recreated-by-id disk (recovery / drive-bay drop)
        // carries the current generation. /mss recovery bumps the DB generation BEFORE calling this, which
        // is exactly what invalidates any older physical copy of the same id.
        return setDiskGeneration(disk, getGenerationFromDatabase(diskId));
    }

    /**
     * Create disk with specific ID and tier
     */
    private ItemStack createStorageDiskWithIdAndTier(String diskId, String crafterUUID, String crafterName, String tier) {
        Material material = getMaterialForTier(tier);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        // HARDCODED: All disks have 64 cells
        int defaultCells = 64;

        // Tier label (1k/4k/16k/64k) selects the per-tier coloured disk name key
        String nameTier = switch (tier) {
            case "4k", "16k", "64k" -> tier;
            default -> "1k";
        };
        meta.displayName(plugin.getMessageManager().getMessageComponent(null, "item.storage_disk.name_" + nameTier));

        meta.lore(buildDiskLore(crafterName, diskId, tier, 0, defaultCells));


        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(STORAGE_DISK_KEY, PersistentDataType.BOOLEAN, true);
        pdc.set(DISK_ID_KEY, PersistentDataType.STRING, diskId);
        pdc.set(DISK_CRAFTER_UUID_KEY, PersistentDataType.STRING, crafterUUID);
        pdc.set(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING, crafterName);
        pdc.set(DISK_TIER_KEY, PersistentDataType.STRING, tier);
        pdc.set(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER, 0);
        pdc.set(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER, 64); // HARDCODED: All disks have 64 cells
        pdc.set(DISK_GENERATION_KEY, PersistentDataType.INTEGER, 0); // Anti-dupe nonce: bumped by /mss recovery

        applyCustomModel(meta, "storage_disk_" + nameTier, switch (nameTier) {
            case "4k" -> 1022;
            case "16k" -> 1023;
            case "64k" -> 1024;
            default -> 1021;
        });
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Build the lang-driven lore (tooltip) shown on a storage disk.
     * Shared by disk creation (0 cells) and {@link #updateStorageDiskLore} so both paths stay identical.
     */
    private List<Component> buildDiskLore(String crafterName, String diskId, String tier, int usedCells, int maxCells) {
        int itemsPerCell = getItemsPerCellForTier(tier);
        int totalCapacity = maxCells * itemsPerCell;
        String usageColor = (usedCells >= maxCells) ? "<red>" :
                (usedCells >= maxCells * 0.8) ? "<yellow>" : "<green>";

        var mm = plugin.getMessageManager();
        List<Component> lore = new ArrayList<>();
        lore.add(mm.getMessageComponent(null, "gui.storage-disk.header"));
        lore.add(Component.empty());
        lore.add(mm.getMessageComponent(null, "gui.storage-disk.capacity", "capacity", String.format("%,d", itemsPerCell)));
        lore.add(miniMessage.deserialize(mm.getMessage("en_US", "gui.storage-disk.cells-used", "color", usageColor, "used", String.valueOf(usedCells), "max", String.valueOf(maxCells))));
        lore.add(mm.getMessageComponent(null, "gui.storage-disk.total-capacity", "total", String.format("%,d", totalCapacity)));
        lore.add(Component.empty());
        lore.add(mm.getMessageComponent(null, "gui.storage-disk.crafted-by", "crafter", crafterName));
        lore.add(mm.getMessageComponent(null, "gui.storage-disk.disk-id", "id", diskId));
        return lore;
    }

    /**
     * Updated updateStorageDiskLore method with tier support
     */
    public ItemStack updateStorageDiskLore(ItemStack disk, int usedCells, int maxCells) {
        if (!isStorageDisk(disk)) return disk;

        ItemStack newDisk = disk.clone();
        ItemMeta meta = newDisk.getItemMeta();

        String crafterName = meta.getPersistentDataContainer().get(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING);
        String diskId = meta.getPersistentDataContainer().get(DISK_ID_KEY, PersistentDataType.STRING);
        String tier = getDiskTier(disk);

        meta.lore(buildDiskLore(crafterName, diskId, tier, usedCells, maxCells));

        // Re-stamp the custom model here too: the drive bay rebuilds disks via its own builder that bypasses
        // applyCustomModel, so this lore-refresh is where we guarantee the resource-pack model survives a rebuild.
        String modelTier = switch (tier == null ? "1k" : tier) {
            case "4k", "16k", "64k" -> tier;
            default -> "1k";
        };
        applyCustomModel(meta, "storage_disk_" + modelTier, switch (modelTier) {
            case "4k" -> 1022;
            case "16k" -> 1023;
            case "64k" -> 1024;
            default -> 1021;
        });

        // Update persistent data
        meta.getPersistentDataContainer().set(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER, usedCells);
        meta.getPersistentDataContainer().set(DISK_MAX_CELLS_KEY, PersistentDataType.INTEGER, maxCells);

        newDisk.setItemMeta(meta);
        return newDisk;
    }


    /**
     * Get the tier of a storage disk
     */
    public String getDiskTier(ItemStack disk) {
        if (!isStorageDisk(disk)) return null;
        String tier = disk.getItemMeta().getPersistentDataContainer().get(DISK_TIER_KEY, PersistentDataType.STRING);
        return tier != null ? tier : "1k"; // Default to 1k if not set
    }

    /**
     * Get items per cell for a specific tier
     * HARDCODED VALUES:
     * 1k = 127 items per cell
     * 4k = 508 items per cell
     * 16k = 2,032 items per cell
     * 64k = 8,128 items per cell
     */
    public int getItemsPerCellForTier(String tier) {
        if (tier == null) return 127;
        return switch (tier.toLowerCase()) {
            case "4k" -> 508;
            case "16k" -> 2032;
            case "64k" -> 8128;
            default -> 127;
        };
    }

    /**
     * Get material for tier
     */
    private Material getMaterialForTier(String tier) {
        return switch (tier.toLowerCase()) {
            case "4k" -> Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
            case "16k" -> Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
            case "64k" -> Material.POLISHED_BLACKSTONE_PRESSURE_PLATE;
            default -> Material.ACACIA_PRESSURE_PLATE;
        };
    }

    /**
     * Get tier display name with color - returns MiniMessage string for embedding
     */
    public String getTierDisplayName(String tier) {
        return switch (tier.toLowerCase()) {
            case "4k" -> "<gold>4k";
            case "16k" -> "<green>16k";
            case "64k" -> "<light_purple>64k";
            default -> "<red>1k";
        };
    }

    /**
     * Get tier from database
     */
    private String getTierFromDatabase(String diskId) {
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT tier FROM storage_disks WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("tier");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error getting tier from database for disk " + diskId + ": " + e.getMessage());
        }
        return null;
    }


    public String getDiskCrafterName(ItemStack disk) {
        if (!isStorageDisk(disk)) return null;
        return disk.getItemMeta().getPersistentDataContainer().get(DISK_CRAFTER_NAME_KEY, PersistentDataType.STRING);
    }

    // Identification methods
    public boolean isStorageServer(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(STORAGE_SERVER_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isDriveBay(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(DRIVE_BAY_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isMSSTerminal(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(MSS_TERMINAL_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isNetworkCable(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(NETWORK_CABLE_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isStorageDisk(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(STORAGE_DISK_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isExporter(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(EXPORTER_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isImporter(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(IMPORTER_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isSecurityTerminal(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(SECURITY_TERMINAL_KEY, PersistentDataType.BOOLEAN);
    }

    public boolean isNetworkBlock(ItemStack item) {
        return isStorageServer(item) || isDriveBay(item) || isMSSTerminal(item) || isNetworkCable(item) || isExporter(item) || isImporter(item) || isSecurityTerminal(item);
    }

    /**
     * Check if an item is any MSS item (blocks, disks, or components)
     */
    public boolean isMSSItem(ItemStack item) {
        return isNetworkBlock(item) || isStorageDisk(item) || isMSSComponent(item);
    }

    public String getStorageDiskId(ItemStack disk) {
        if (!isStorageDisk(disk)) return null;
        return disk.getItemMeta().getPersistentDataContainer().get(DISK_ID_KEY, PersistentDataType.STRING);
    }

    /**
     * Anti-dupe generation nonce. New disks are generation 0; /mss recovery bumps the stored generation so
     * any older physical copy of the same disk_id becomes invalid. Missing key => 0 (backward compatible).
     */
    public int getStorageDiskGeneration(ItemStack disk) {
        if (disk == null || !disk.hasItemMeta()) return 0;
        Integer gen = disk.getItemMeta().getPersistentDataContainer().get(DISK_GENERATION_KEY, PersistentDataType.INTEGER);
        return gen != null ? gen : 0;
    }

    /**
     * Stamp a specific generation onto a disk item (used when recreating a disk by id).
     */
    public ItemStack setDiskGeneration(ItemStack disk, int generation) {
        if (disk == null) return null;
        ItemMeta meta = disk.getItemMeta();
        if (meta == null) return disk;
        meta.getPersistentDataContainer().set(DISK_GENERATION_KEY, PersistentDataType.INTEGER, generation);
        disk.setItemMeta(meta);
        return disk;
    }

    /**
     * The authoritative stored generation for a disk id (0 if not registered / on error).
     */
    private int getGenerationFromDatabase(String diskId) {
        try (java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement(
                     "SELECT generation FROM storage_disks WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("generation") : 0;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error reading disk generation for " + diskId + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * True if this disk item's generation matches the stored generation for its id (or the disk is not yet
     * registered). False means it is a stale copy whose id was recovered — it must not be usable. Fails open
     * on DB error so a transient hiccup never blocks a legitimate disk insert.
     */
    public boolean isDiskGenerationCurrent(ItemStack disk, String diskId) {
        int itemGen = getStorageDiskGeneration(disk);
        try (java.sql.Connection conn = plugin.getDatabaseManager().getConnection();
             java.sql.PreparedStatement stmt = conn.prepareStatement(
                     "SELECT generation FROM storage_disks WHERE disk_id = ?")) {
            stmt.setString(1, diskId);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                // Not registered: a pristine disk (gen 0) may first-insert, but a recovered copy (gen > 0) whose
                // row was since deleted (e.g. the live copy got recycled) must NOT be silently re-blessed back
                // into a working disk — that would dupe its crafting components.
                if (!rs.next()) return itemGen == 0;
                return rs.getInt("generation") == itemGen;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking disk generation for " + diskId + ": " + e.getMessage());
            return true;
        }
    }

    public String getDiskCrafterUUID(ItemStack disk) {
        if (!isStorageDisk(disk)) return null;
        return disk.getItemMeta().getPersistentDataContainer().get(DISK_CRAFTER_UUID_KEY, PersistentDataType.STRING);
    }

    private String generateDiskId() {
        try {
            String input = UUID.randomUUID().toString() + System.currentTimeMillis();
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.substring(0, 16).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to UUID if SHA-256 is not available
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        }
    }

    /**
     * Generate a unique hash for an ItemStack including all metadata
     */
    public String generateItemHash(ItemStack item) {
        try {
            StringBuilder builder = new StringBuilder();


            // Material type
            builder.append(item.getType().name());

            if (item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();

                // Display name
                if (meta.hasDisplayName()) {
                    Component displayName = meta.displayName();
                    if (displayName != null) {
                        builder.append("|displayName:").append(legacySerializer.serialize(displayName));
                    }
                }

                // Lore
                if (meta.hasLore() && meta.lore() != null) {
                    // Convert Adventure components back to legacy for hashing consistency
                    List<String> legacyLore = new ArrayList<>();
                    for (Component loreComponent : Objects.requireNonNull(meta.lore())) {
                        if (loreComponent != null) {
                            legacyLore.add(legacySerializer.serialize(loreComponent));
                        }
                    }
                    builder.append("|lore:").append(legacyLore);
                }

                // Item model (minecraft:item_model component) — API present only on MC 1.21.2+, accessed
                // reflectively so the plugin still compiles/runs on 1.21.0 / 1.21.1.
                if (hasItemModelMethod != null) {
                    try {
                        if (Boolean.TRUE.equals(hasItemModelMethod.invoke(meta))) {
                            Object itemModel = getItemModelMethod.invoke(meta);
                            if (itemModel != null) builder.append("|itemModel:").append(itemModel);
                        }
                    } catch (ReflectiveOperationException ignored) {
                    }
                }

                // Enchantments (for regular enchanted items)
                if (meta.hasEnchants()) {
                    builder.append("|enchants:").append(meta.getEnchants());
                }

                // Enchanted book-specific metadata (stored enchantments)
                if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta enchantMeta) {
                    if (enchantMeta.hasStoredEnchants()) {
                        builder.append("|storedEnchants:").append(enchantMeta.getStoredEnchants());
                    }
                }

                // Potion-specific metadata
                if (meta instanceof org.bukkit.inventory.meta.PotionMeta potionMeta) {
                    // Base potion type
                    if (potionMeta.getBasePotionType() != null) {
                        builder.append("|basePotionType:").append(potionMeta.getBasePotionType());
                    }
                    
                    // Custom effects
                    if (potionMeta.hasCustomEffects()) {
                        builder.append("|customEffects:").append(potionMeta.getCustomEffects());
                    }
                    
                    // Color (for custom potions)
                    if (potionMeta.hasColor()) {
                        builder.append("|potionColor:").append(potionMeta.getColor());
                    }
                }

                // Persistent data (excluding our own MSS keys to prevent issues)
                PersistentDataContainer pdc = meta.getPersistentDataContainer();
                for (NamespacedKey key : pdc.getKeys()) {
                    if (!key.getNamespace().equals(plugin.getName().toLowerCase())) {
                        builder.append("|pdc:").append(key).append("=");
                        // Try different data types
                        if (pdc.has(key, PersistentDataType.STRING)) {
                            builder.append(pdc.get(key, PersistentDataType.STRING));
                        } else if (pdc.has(key, PersistentDataType.INTEGER)) {
                            builder.append(pdc.get(key, PersistentDataType.INTEGER));
                        } else if (pdc.has(key, PersistentDataType.BOOLEAN)) {
                            builder.append(pdc.get(key, PersistentDataType.BOOLEAN));
                        }
                    }
                }
            }

            // Durability/damage
            if (item.getType().getMaxDurability() > 0) {
                if (item.hasItemMeta() && item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
                    builder.append("|damage:").append(damageable.getDamage());
                }
            }

            // Generate SHA-256 hash
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(builder.toString().getBytes());

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            // Fallback to simple string hash
            return String.valueOf(item.toString().hashCode());
        }
    }

    /**
     * Check if an item is blacklisted and should NOT be stored in the network
     */
    public boolean isItemBlacklisted(ItemStack item) {
        // Block storage disks ONLY when they hold data — empty disks (0 used cells) may be stored in the
        // network. Used cells are read from the item's PDC (never a DB query: this is a per-item hot path,
        // and a stored/loose disk's PDC is kept current by updateStorageDiskLore on every cell-count change).
        if (isStorageDisk(item)) {
            int usedCells = item.getItemMeta().getPersistentDataContainer()
                    .getOrDefault(DISK_USED_CELLS_KEY, PersistentDataType.INTEGER, 0);
            if (usedCells > 0) {
                return true;
            }
        }

        // Check configuration blacklist
        if (plugin.getConfigManager() != null &&
                plugin.getConfigManager().isItemBlacklisted(item.getType())) {
            return true;
        }

        // Block shulker boxes with contents
        if (item.getType().name().contains("SHULKER_BOX")) {
            if (item.hasItemMeta() && item.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta blockMeta) {
                if (blockMeta.getBlockState() instanceof org.bukkit.block.ShulkerBox shulkerBox) {
                    // Block shulker boxes with contents, allow empty ones
                    for (ItemStack content : shulkerBox.getInventory().getContents()) {
                        if (content != null && !content.getType().isAir()) {
                            return true;
                        }
                    }
                }
            }
        }

        // Block bundles with contents (including all colored variants)
        if (item.getType().name().contains("BUNDLE")) {
            if (item.hasItemMeta() && item.getItemMeta() instanceof org.bukkit.inventory.meta.BundleMeta bundleMeta) {
                // Block bundles with contents, allow empty ones
                return !bundleMeta.getItems().isEmpty();
            }
        }

        // All other items (including network blocks and components) are allowed by default
        return false;
    }

}