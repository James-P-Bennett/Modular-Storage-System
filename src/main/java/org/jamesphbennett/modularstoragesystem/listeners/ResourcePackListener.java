package org.jamesphbennett.modularstoragesystem.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

/** Pushes the MSS resource pack to players on join, when MSS is configured to handle delivery. */
public class ResourcePackListener implements Listener {

    private final ModularStorageSystem plugin;

    public ResourcePackListener(ModularStorageSystem plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.getResourcePackManager() != null) {
            plugin.getResourcePackManager().sendTo(event.getPlayer());
        }
    }
}
