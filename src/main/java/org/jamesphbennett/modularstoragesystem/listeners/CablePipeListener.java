package org.jamesphbennett.modularstoragesystem.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

/**
 * Re-spawns cable pipe displays when a chunk loads. Displays are non-persistent (see CablePipeRenderer), so they
 * vanish on unload and must be rebuilt here when the chunk comes back. No-ops when the feature is disabled.
 */
public class CablePipeListener implements Listener {

    private final ModularStorageSystem plugin;

    public CablePipeListener(ModularStorageSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (plugin.getCablePipeRenderer() != null) {
            plugin.getCablePipeRenderer().refreshChunk(event.getChunk());
        }
    }
}
