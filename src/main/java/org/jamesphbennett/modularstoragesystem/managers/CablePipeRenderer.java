package org.jamesphbennett.modularstoragesystem.managers;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;
import org.jamesphbennett.modularstoragesystem.blocks.MSSBlockType;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Renders placed network cables as connection-aware PIPES using one {@link ItemDisplay} entity per cable.
 *
 * <p>Why entities (not a blockstate override): connecting-pipe rendering needs a per-face connection state, which
 * only exists on vanilla connecting blocks (panes/bars/fences/chorus). Overriding any of those reskins EVERY vanilla
 * instance and fights physics, and chorus/mushroom bring x-ray / support quirks. A display entity instead shows a
 * NEW added model — no vanilla blockstate override, no x-ray, no survival quirk — and the plugin already computes
 * cable adjacency, so it has full control of the connection look.
 *
 * <p>The cable BLOCK stays a HEAVY_CORE (collision + interaction + the DB marker identity, non-occluding). This
 * overlays a cosmetic {@code item_display} at the block centre showing {@code network_cable_pipe_<mask>}, where mask
 * is the 6-bit set of connected neighbours. Displays are NON-persistent (not saved) and re-spawned on chunk load, so
 * they self-heal and never orphan.
 *
 * <p>Gated on {@code resource-pack.enabled} AND {@code resource-pack.cable-pipes} (opt-in). PROTOTYPE: modern
 * item_model path only (1.21.2+); the render transform constants below are tuned live.
 */
public class CablePipeRenderer {

    /** BIT ORDER MUST EQUAL tools/generate_pack.py PIPE_ARMS: bit0=DOWN bit1=UP bit2=NORTH bit3=SOUTH bit4=WEST bit5=EAST. */
    private static final BlockFace[] FACES = {
            BlockFace.DOWN, BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.WEST, BlockFace.EAST
    };

    /** The 4 pack-reskinned CORE cube roles a pipe joins to. Exporter/importer device heads connect too, but aren't
     *  in this list because they're PLAYER_HEADs (not MSSBlockType roles) — handled separately in {@link #connectsTo}. */
    private static final MSSBlockType[] CONNECT_ROLES = {
            MSSBlockType.STORAGE_SERVER, MSSBlockType.DRIVE_BAY, MSSBlockType.MSS_TERMINAL, MSSBlockType.SECURITY_TERMINAL
    };

    // --- live-tunable render transform (the pipe item model is authored in 0..16 = one block) -------------------
    private static final ItemDisplay.ItemDisplayTransform CONTEXT = ItemDisplay.ItemDisplayTransform.FIXED;
    private static final float SCALE = 2.0f;                       // FIXED renders items at 0.5 -> x2 fills the block
    private static final Vector3f TRANSLATION = new Vector3f(0f, 0f, 0f);

    private final ModularStorageSystem plugin;
    private final NamespacedKey markerKey;

    public CablePipeRenderer(ModularStorageSystem plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "cable_pipe");
    }

    public boolean isEnabled() {
        return plugin.getConfigManager().isResourcePackEnabled()
                && plugin.getConfigManager().isCablePipesEnabled();
    }

    /** Connection bitmask: bit i set when FACES[i] neighbour is a cable, a core MSS block, or an exporter/importer head. */
    private int computeMask(Block cable) {
        int mask = 0;
        for (int i = 0; i < FACES.length; i++) {
            if (connectsTo(cable.getRelative(FACES[i]))) {
                mask |= (1 << i);
            }
        }
        return mask;
    }

    /** A pipe joins to another cable, a core MSS block, or an exporter/importer device head (shows the cable
     *  plugging into the device). */
    private boolean connectsTo(Block b) {
        if (plugin.getCableManager().isCustomNetworkCable(b)) return true;
        for (MSSBlockType role : CONNECT_ROLES) {
            if (role.accepts(b.getType())
                    && plugin.getBlockMarkerCache().isMarkedAsCustomBlock(b.getLocation(), role.markerType)) {
                return true;
            }
        }
        // Exporter / importer heads are PLAYER_HEAD / PLAYER_WALL_HEAD identified only by their marker.
        if (b.getType() == Material.PLAYER_HEAD || b.getType() == Material.PLAYER_WALL_HEAD) {
            return plugin.getBlockMarkerCache().isMarkedAsCustomBlock(b.getLocation(), "EXPORTER")
                    || plugin.getBlockMarkerCache().isMarkedAsCustomBlock(b.getLocation(), "IMPORTER");
        }
        return false;
    }

    /** Ensure this block's pipe display matches reality: create/update it if a cable, remove it otherwise. */
    public void refresh(Block block) {
        if (!isEnabled()) return;
        if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) return;

        ItemDisplay existing = findDisplay(block);
        boolean isCable = plugin.getCableManager().isCustomNetworkCable(block);
        if (!isCable) {
            if (existing != null) existing.remove();
            return;
        }
        ItemStack item = plugin.getItemManager().createCablePipeDisplayItem(computeMask(block));
        if (existing != null) {
            existing.setItemStack(item);
        } else {
            spawn(block, item);
        }
    }

    /** Refresh a block and its 6 neighbours — call on any MSS block place/break so adjacent pipes re-join. */
    public void refreshAround(Block block) {
        if (!isEnabled()) return;
        refresh(block);
        for (BlockFace f : FACES) refresh(block.getRelative(f));
    }

    private void spawn(Block block, ItemStack item) {
        Location centre = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawn(centre, ItemDisplay.class, d -> {
            d.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            d.setPersistent(false); // not saved; re-spawned on chunk load so displays never orphan
            d.setItemDisplayTransform(CONTEXT);
            // item_display FIXED renders the model with a 180-degree yaw offset, which flips N<->S and E<->W so arms
            // point the opposite way (looked like they reached into air). Cancel it with a 180-degree Y rotation.
            d.setTransformation(new Transformation(
                    TRANSLATION, new Quaternionf().rotateY((float) Math.PI),
                    new Vector3f(SCALE, SCALE, SCALE), new Quaternionf()));
            d.setItemStack(item);
        });
    }

    private ItemDisplay findDisplay(Block block) {
        Location centre = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity e : block.getWorld().getNearbyEntities(centre, 0.2, 0.2, 0.2)) {
            if (e instanceof ItemDisplay d
                    && d.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)
                    && d.getLocation().distanceSquared(centre) < 0.01) {
                return d;
            }
        }
        return null;
    }

    /** Spawn/update displays for every cable in a chunk (on chunk load and the enable scan). One bounded DB query. */
    public void refreshChunk(Chunk chunk) {
        if (!isEnabled()) return;
        World world = chunk.getWorld();
        int baseX = chunk.getX() << 4, baseZ = chunk.getZ() << 4;
        try (Connection conn = plugin.getDatabaseManager().getConnection();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT x, y, z FROM custom_block_markers WHERE world_name = ? AND block_type = 'NETWORK_CABLE' "
                             + "AND x >= ? AND x < ? AND z >= ? AND z < ?")) {
            st.setString(1, world.getName());
            st.setInt(2, baseX);
            st.setInt(3, baseX + 16);
            st.setInt(4, baseZ);
            st.setInt(5, baseZ + 16);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    refresh(world.getBlockAt(rs.getInt("x"), rs.getInt("y"), rs.getInt("z")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Cable pipe chunk refresh failed: " + e.getMessage());
        }
    }

    /** On enable: render cables in all currently-loaded chunks. */
    public void renderLoaded() {
        if (!isEnabled()) return;
        for (World w : plugin.getServer().getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) refreshChunk(c);
        }
    }

    /** On disable / toggle-off: remove every pipe display we own in loaded chunks. */
    public void removeAll() {
        for (World w : plugin.getServer().getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e instanceof ItemDisplay d
                        && d.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE)) {
                    d.remove();
                }
            }
        }
    }
}
