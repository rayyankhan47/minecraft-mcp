package com.mcmcp;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Plugin entry point.
 *
 * <p>Deliberately thin. All it owns is the lifecycle: register the command on enable,
 * and make sure nothing is left running on disable. The interesting work lives in
 * {@code BuildSession} (async I/O) and {@code PlacementEngine} (sync block placement).
 *
 * <p>The one invariant this whole plugin is built around: <b>Bukkit is not thread
 * safe</b>. World mutation happens on the main server thread and nowhere else;
 * network I/O happens off it and nowhere else.
 */
public final class McmcpPlugin extends JavaPlugin implements Listener {

    /** Where the backend lives. Localhost by design — venue wifi cannot break this hop. */
    public static final String BACKEND_URL = "http://127.0.0.1:8000";

    private static McmcpPlugin instance;

    public static McmcpPlugin get() {
        return instance;
    }

    /**
     * At most one build per player, enforced here. A second {@code /mc2p} cancels the
     * first rather than interleaving two structures into the same space.
     */
    private final Map<UUID, PlacementEngine> activeBuilds = new ConcurrentHashMap<>();

    /** Registers a new build, cancelling whatever that player had running. */
    public void registerBuild(UUID playerId, PlacementEngine engine) {
        PlacementEngine previous = activeBuilds.put(playerId, engine);
        if (previous != null) {
            previous.cancel();
            getLogger().info("cancelled the previous build for " + playerId);
        }
    }

    /** Deregisters, but only if this engine is still the current one. */
    public void finishBuild(UUID playerId, PlacementEngine engine) {
        activeBuilds.remove(playerId, engine);
    }

    public void cancelAllBuilds() {
        activeBuilds.values().forEach(PlacementEngine::cancel);
        activeBuilds.clear();
    }

    /**
     * Stop building for someone who has left. Without this the engine keeps placing
     * blocks for an absent player and the session's HTTP read stays open — and on a
     * reconnect they would be sharing the world with a build they can no longer cancel.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlacementEngine engine = activeBuilds.remove(event.getPlayer().getUniqueId());
        if (engine != null) {
            engine.cancel();
            getLogger().info("cancelled the in-flight build for " + event.getPlayer().getName()
                    + " (disconnected)");
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        banner();

        registerCommand("mc2p", new McmcpCommand(this));
        getServer().getPluginManager().registerEvents(this, this);

        pingBackend();

        getLogger().info("Ready. Try: /mc2p a small medieval cottage");
    }

    @Override
    public void onDisable() {
        // An orphaned repeating task surviving a /reload would keep mutating the world
        // with no owner, so nothing is allowed to outlive the plugin.
        cancelAllBuilds();
        getLogger().info("mcmcp disabled.");
        instance = null;
    }

    /**
     * Warms the connection to the backend so the first real build does not pay for the
     * TCP handshake, and tells the console now — rather than mid-demo — if the backend
     * is not up.
     */
    private void pingBackend() {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(2))
                        .build();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(BACKEND_URL + "/health"))
                        .timeout(java.time.Duration.ofSeconds(2))
                        .GET()
                        .build();
                java.net.http.HttpResponse<String> response =
                        client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    getLogger().info("backend is up: " + response.body().trim());
                } else {
                    getLogger().warning("backend answered HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                getLogger().warning("backend is NOT reachable at " + BACKEND_URL
                        + " (" + e.getClass().getSimpleName() + ")."
                        + " Start it with scripts/run-backend.sh, or use /mc2p local.");
            }
        });
    }

    /**
     * Wires a command declared in {@code plugin.yml} to its executor.
     *
     * <p>Fails loudly rather than silently: a typo between here and {@code plugin.yml}
     * gives you a command that exists but does nothing, which is a miserable thing to
     * debug with an audience watching.
     */
    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        org.bukkit.command.PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command /" + name + " is not declared in plugin.yml — /" + name
                    + " will not work.");
            return;
        }
        cmd.setExecutor(executor);
        getLogger().info("Registered /" + name);
    }

    /**
     * The console is the only debugging surface during a demo, so make the startup
     * state impossible to miss.
     */
    private void banner() {
        Logger log = getLogger();
        log.info("");
        log.info("  ███╗   ███╗ ██████╗    ███╗   ███╗ ██████╗██████╗ ");
        log.info("  ████╗ ████║██╔════╝    ████╗ ████║██╔════╝██╔══██╗");
        log.info("  ██╔████╔██║██║         ██╔████╔██║██║     ██████╔╝");
        log.info("  ██║╚██╔╝██║██║         ██║╚██╔╝██║██║     ██╔═══╝ ");
        log.info("  ██║ ╚═╝ ██║╚██████╗    ██║ ╚═╝ ██║╚██████╗██║     ");
        log.info("  ╚═╝     ╚═╝ ╚═════╝    ╚═╝     ╚═╝ ╚═════╝╚═╝     ");
        log.info("");
        log.info("  version  " + getPluginMeta().getVersion());
        log.info("  server   " + getServer().getMinecraftVersion() + "  (Java " + Runtime.version().feature() + ")");
        log.info("  backend  " + BACKEND_URL);
        log.info("");
    }
}
