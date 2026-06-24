package org.jamesphbennett.modularstoragesystem.managers;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jamesphbennett.modularstoragesystem.ModularStorageSystem;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Optional resource-pack delivery for the MSS custom item models.
 *
 * <p>When {@code resource-pack.enabled} and {@code resource-pack.handled-by-mss} are both true this either
 * self-hosts the pack over a tiny built-in HTTP server (no external host needed) or points clients at a
 * configured external URL, then pushes it to each player on join. When delivery is left to the server or
 * another plugin (handled-by-mss false) this manager stays dormant and {@link #sendTo(Player)} is a no-op.
 */
public class ResourcePackManager {

    private static final String PACK_PATH = "/modular-storage-system.zip";

    private final ModularStorageSystem plugin;

    private HttpServer httpServer;
    private volatile String url;   // effective URL sent to clients; null when delivery is off/misconfigured
    private volatile String sha1;  // hex SHA-1 of the pack ("" if unknown)
    private volatile UUID packId;
    private Component prompt;
    private boolean required;

    public ResourcePackManager(ModularStorageSystem plugin) {
        this.plugin = plugin;
    }

    public boolean isActive() {
        return url != null;
    }

    /** Initialise delivery: load the pack, compute its hash, and (if self-hosting) start the HTTP server. */
    public void start() {
        ConfigManager cfg = plugin.getConfigManager();
        if (!cfg.isResourcePackEnabled() || !cfg.isResourcePackHandledByMss()) {
            return; // MSS isn't responsible for delivering the pack
        }
        this.required = cfg.isResourcePackRequired();
        this.prompt = MiniMessage.miniMessage().deserialize(cfg.getResourcePackPrompt());

        try {
            if (cfg.isResourcePackSelfHost()) {
                startSelfHosted(cfg);
            } else {
                startExternal(cfg);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Resource pack delivery disabled: " + e.getMessage());
            stop();
            url = null;
        }

        if (url != null) {
            this.packId = stableId(url, sha1);
            plugin.getLogger().info("Resource pack delivery active (" + url + ")");
        }
    }

    private void startSelfHosted(ConfigManager cfg) throws Exception {
        File packFile = new File(plugin.getDataFolder(), cfg.getResourcePackFile());
        if (!packFile.isFile()) {
            plugin.getLogger().warning("Resource pack self-host enabled but no pack found at " + packFile.getPath()
                    + " - place your built pack there. Skipping delivery.");
            return;
        }
        byte[] bytes = Files.readAllBytes(packFile.toPath());
        this.sha1 = sha1Hex(bytes);

        int port = cfg.getResourcePackPort();
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/", exchange -> {
            try {
                boolean wantsPack = "GET".equalsIgnoreCase(exchange.getRequestMethod())
                        && exchange.getRequestURI().getPath().endsWith(".zip");
                if (!wantsPack) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } finally {
                exchange.close();
            }
        });
        httpServer.start();

        this.url = "http://" + resolvePublicAddress(cfg) + ":" + port + PACK_PATH;
    }

    private void startExternal(ConfigManager cfg) {
        String configured = cfg.getResourcePackUrl();
        if (configured == null || configured.isBlank()) {
            plugin.getLogger().warning("Resource pack handled-by-mss with self-host off, but no 'url' set. Skipping delivery.");
            return;
        }
        this.url = configured.trim();
        this.sha1 = cfg.getResourcePackSha1();
        if (sha1 == null || sha1.isBlank()) {
            // Compute the hash off the main thread by downloading the pack once.
            final String u = this.url;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (var in = URI.create(u).toURL().openStream()) {
                    sha1 = sha1Hex(in.readAllBytes());
                    packId = stableId(u, sha1);
                    plugin.getLogger().info("Computed resource pack sha1 from url.");
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not auto-compute resource pack sha1 from url (clients will re-download each join): " + e.getMessage());
                    sha1 = "";
                }
            });
        }
    }

    private String resolvePublicAddress(ConfigManager cfg) {
        String configured = cfg.getResourcePackPublicAddress();
        if (configured != null && !configured.isBlank()) return configured.trim();
        String serverIp = Bukkit.getServer().getIp();
        if (serverIp != null && !serverIp.isBlank()) return serverIp;
        plugin.getLogger().warning("Resource pack self-host has no public-address set and the server IP is unset; "
                + "falling back to 127.0.0.1 (only reachable by local clients). Set resource-pack.self-host.public-address.");
        return "127.0.0.1";
    }

    /** Push the pack to a player (call on join). No-op when delivery isn't active. */
    public void sendTo(Player player) {
        String u = url;
        if (u == null) return;
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(packId, URI.create(u), sha1 == null ? "" : sha1);
        ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .prompt(prompt)
                .required(required)
                .replace(true)
                .build();
        player.sendResourcePacks(request);
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        // Clear delivery state so isActive()/sendTo() correctly report "off" after a stop. Without this, a later
        // start() that early-returns (because resource-pack.enabled is now false) would leave a stale url and keep
        // pushing the pack — the exact "disabling didn't stop delivery" bug.
        url = null;
        packId = null;
        sha1 = null;
    }

    /**
     * Re-apply delivery after a {@code /mss reload}: tear down, re-read config, then reconcile currently-online
     * players so toggling {@code resource-pack.enabled} (or handled-by-mss / url / the pack file) takes effect
     * immediately instead of only on the next join. Disabling removes the pack from online clients; enabling — or a
     * changed pack id — pushes the new one; an unchanged active pack is left as-is (no needless client re-download).
     */
    public void reload() {
        UUID previousPackId = this.packId;
        boolean wasActive = isActive();
        stop();
        start();
        if (isActive()) {
            if (!java.util.Objects.equals(previousPackId, this.packId)) {
                for (Player p : Bukkit.getOnlinePlayers()) sendTo(p);
            }
        } else if (wasActive && previousPackId != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.removeResourcePacks(previousPackId);
        }
    }

    private static UUID stableId(String url, String sha1) {
        return UUID.nameUUIDFromBytes((url + ":" + (sha1 == null ? "" : sha1)).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha1Hex(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
