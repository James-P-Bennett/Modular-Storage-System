package org.jamesphbennett.modularstoragesystem.blocks;

import org.bukkit.Material;

/**
 * Single source of truth for the MSS blocks whose PLACED appearance can be reskinned by the resource pack.
 *
 * <p>Each role maps to TWO materials: the {@link #vanillaMaterial} used when the resource pack is OFF, and the
 * {@link #packMaterial} used when the pack is ON. The four cube blocks (server / drive bay / terminal / security)
 * swap to a {@code NOTE_BLOCK} so the pack can model the <em>placed</em> block. The network cable does NOT swap
 * (packMaterial == vanillaMaterial == HEAVY_CORE) — a full-cube model on it would x-ray and it would have
 * full-block collision; HEAVY_CORE is a native non-occluding small model, so cables are reskinned at the
 * inventory-icon level only.
 *
 * <p><b>Why note_block (not mushroom).</b> A note block is the industry-standard custom-block base: a true full
 * opaque cube (no x-ray), no block entity, and its state does NOT re-merge from adjacency the way mushroom face
 * booleans do — so two MSS blocks placed touching never clobber each other.
 *
 * <p><b>Collision avoidance (verified against Oraxen source + the IA jar).</b> There is NO note-block state a player
 * genuinely can't reach: every instrument is settable (the block below, or a mob HEAD on top), note cycles 0-24 by
 * right-click tuning, powered by redstone. Oraxen/IA stay collision-free by FORCE-RESETTING player-placed note blocks
 * to vanilla, which degrades vanilla note-block music server-wide. We do NOT do that — we key our blocks on the RARE
 * mob-head instrument {@code piglin}, which a note block only gets from a piglin head placed ON TOP (no loot-chest
 * source; essentially never by accident). So every ordinary note block (any other instrument) is left fully
 * functional. Trade-off: a player who DELIBERATELY puts a piglin head on a note block and tunes it could cosmetically
 * see our skin — but it is never a FUNCTIONAL MSS block (identity is the marker, below). Our own blocks carry no head,
 * so their instrument would re-derive from the block below on a neighbour update — {@code BlockListener.onBlockPhysics}
 * cancels that to pin them to {@code piglin}.
 *
 * <p><b>Identity is Material-independent.</b> An MSS block is identified by its {@code custom_block_markers} row
 * (coords + block_type), never by its Material — so detection must accept EITHER material for a role
 * ({@link #accepts(Material)}), which keeps a world correct across a {@code resource-pack.enabled} toggle and in
 * mixed builds. {@link #placedMaterial(boolean)} chooses the material when creating items.
 *
 * <p><b>Pack rendering.</b> Each role uses note {@code ordinal*4 + facing + 1} (1..16) with {@code instrument=piglin},
 * {@code powered=false}; the pack overrides {@code assets/minecraft/blockstates/note_block.json} mapping those 16 notes
 * to the role's model with a 90°·facing Y-rotation, and EVERY other state (all other instruments + the reserved
 * note/powered values) to the vanilla note block. {@code powered} and the note range are left free for future on/off
 * (network status) + animated states.
 */
public enum MSSBlockType {
    STORAGE_SERVER   ("STORAGE_SERVER",    Material.CHISELED_TUFF,        Material.NOTE_BLOCK),
    DRIVE_BAY        ("DRIVE_BAY",         Material.CHISELED_TUFF_BRICKS, Material.NOTE_BLOCK),
    MSS_TERMINAL     ("MSS_TERMINAL",      Material.CRAFTER,              Material.NOTE_BLOCK),
    SECURITY_TERMINAL("SECURITY_TERMINAL", Material.OBSERVER,             Material.NOTE_BLOCK),
    // Cable is a CONDUIT: its small model floats at the block CENTRE, so it lines up inside the connection-aware
    // pipe display instead of poking out like the old bottom-anchored HEAVY_CORE nub. The block stays visible (no
    // ghost), so collision is honest client+server — no rubberbanding / anticheat. accepts() still allows legacy
    // HEAVY_CORE cables placed before this swap.
    NETWORK_CABLE    ("NETWORK_CABLE",     Material.CONDUIT,             Material.CONDUIT);

    /** The note block the cube roles use when the pack is on. */
    public static final Material NOTE = Material.NOTE_BLOCK;
    /** Horizontal rotations the wrench cycles through (note encodes role*4 + facing). */
    public static final int FACINGS = 4;

    public final String markerType;
    public final Material vanillaMaterial;
    public final Material packMaterial;

    MSSBlockType(String markerType, Material vanillaMaterial, Material packMaterial) {
        this.markerType = markerType;
        this.vanillaMaterial = vanillaMaterial;
        this.packMaterial = packMaterial;
    }

    /** True if {@code m} is either the vanilla OR the pack material for this role (the toggle-safe identity test). */
    public boolean accepts(Material m) {
        if (m == vanillaMaterial || m == packMaterial) return true;
        return this == NETWORK_CABLE && m == Material.HEAVY_CORE; // legacy cables placed before the conduit swap
    }

    /** The Material to USE when creating/placing this block, given whether the resource pack is enabled. */
    public Material placedMaterial(boolean resourcePackEnabled) {
        return resourcePackEnabled ? packMaterial : vanillaMaterial;
    }

    /** True if this role swaps to the note block when the pack is on (everything except the cable). */
    public boolean isNoteBacked() {
        return packMaterial == NOTE;
    }

    /**
     * The note (1..16) the pack keys this role+facing on. note=0 (the item/placement default) is never used, so a
     * hand-placed note block can never accidentally match one of our blocks.
     */
    public int noteFor(int facing) {
        return ordinal() * FACINGS + Math.floorMod(facing, FACINGS) + 1;
    }

    /** Decode the facing (0..3) from a note value (role comes from the marker, not the note); 0 if out of range. */
    public static int facingFromNote(int note) {
        if (note < 1 || note > FACINGS * 4) return 0;
        return (note - 1) % FACINGS;
    }

    /** Lookup by marker / block_type string (e.g. {@code "STORAGE_SERVER"}); {@code null} if not one of the roles. */
    public static MSSBlockType byMarker(String markerType) {
        if (markerType == null) return null;
        for (MSSBlockType t : values()) {
            if (t.markerType.equals(markerType)) return t;
        }
        return null;
    }
}
