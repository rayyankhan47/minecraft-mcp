package com.mcmcp;

import org.bukkit.plugin.java.JavaPlugin;

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
public final class McmcpPlugin extends JavaPlugin {

    /** Where the backend lives. Localhost by design — venue wifi cannot break this hop. */
    public static final String BACKEND_URL = "http://127.0.0.1:8000";

    private static McmcpPlugin instance;

    public static McmcpPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        banner();

        getLogger().info("Ready. Try: /mc2p a small medieval cottage");
    }

    @Override
    public void onDisable() {
        // Nothing to tear down yet. Once BuildSession and PlacementEngine exist, every
        // in-flight session gets cancelled here — an orphaned repeating task surviving a
        // /reload would keep mutating the world with no owner.
        getLogger().info("mcmcp disabled.");
        instance = null;
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
