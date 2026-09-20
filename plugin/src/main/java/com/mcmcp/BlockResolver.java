package com.mcmcp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Turns a blockstate string from the model into real {@link BlockData}.
 *
 * <p>The model <i>will</i> occasionally invent a block name. When it does, the correct
 * behaviour is to place something slightly wrong and carry on. A wrong block is a
 * blemish; an exception is a dead demo. So this class <b>never throws</b>.
 *
 * <p>Four stages, in order:
 * <ol>
 *   <li>{@code Bukkit.createBlockData(str)} — handles the full syntax including
 *       {@code [facing=north,half=bottom]}, which is what gives us rotated stairs and
 *       logs for free with no extra schema.</li>
 *   <li>Strip the {@code [...]} state and retry — catches a real block with a
 *       hallucinated property.</li>
 *   <li>{@code Material.matchMaterial} — catches loose naming.</li>
 *   <li>Substitute stone.</li>
 * </ol>
 *
 * <p>Called from the async expander thread. {@code createBlockData} reads the block
 * registry, which is immutable once the server has started, so this is safe off the
 * main thread — and doing the parsing there is the point.
 */
public final class BlockResolver {

    private BlockResolver() {
    }

    /** What we place when everything else fails. */
    private static final Material FALLBACK = Material.STONE;

    /**
     * Resolving the same string hundreds of times per build is pure waste, and the
     * latency budget is tight. Sharing instances is safe: nothing here ever mutates
     * BlockData, and the server copies it on the way into the world.
     */
    private static final Map<String, BlockData> CACHE = new ConcurrentHashMap<>();

    /** Warn once per bad name, not once per block. 400 identical lines helps nobody. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public static BlockData resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return warnAndFallback("<empty>");
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);

        BlockData hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }

        BlockData resolved = resolveUncached(key);
        CACHE.put(key, resolved);
        return resolved;
    }

    private static BlockData resolveUncached(String key) {

        // 1. The happy path — full blockstate syntax.
        try {
            return Bukkit.createBlockData(key);
        } catch (Throwable ignored) {
            // fall through
        }

        // 2. Real block, invented property. Drop the state and keep the block.
        int bracket = key.indexOf('[');
        if (bracket > 0) {
            String bare = key.substring(0, bracket).trim();
            try {
                BlockData data = Bukkit.createBlockData(bare);
                warnOnce(key, "unknown blockstate properties, using plain " + bare);
                return data;
            } catch (Throwable ignored) {
                // fall through
            }
        }

        // 3. Loose naming — matchMaterial is more forgiving than createBlockData.
        try {
            String bare = bracket > 0 ? key.substring(0, bracket).trim() : key;
            Material material = Material.matchMaterial(bare);
            if (material != null && material.isBlock()) {
                warnOnce(key, "resolved loosely to " + material);
                return material.createBlockData();
            }
        } catch (Throwable ignored) {
            // fall through
        }

        // 4. Give up gracefully.
        return warnAndFallback(key);
    }

    private static BlockData warnAndFallback(String key) {
        warnOnce(key, "unresolvable, substituting " + FALLBACK);
        return FALLBACK.createBlockData();
    }

    private static void warnOnce(String key, String message) {
        if (!WARNED.add(key)) {
            return;
        }
        Logger log = logger();
        if (log != null) {
            log.warning("block \"" + key + "\": " + message);
        }
    }

    /** Null-safe: this may be called before the plugin is enabled, or from a test. */
    private static Logger logger() {
        McmcpPlugin plugin = McmcpPlugin.get();
        return plugin == null ? null : plugin.getLogger();
    }

    /** Test/diagnostic hook. */
    public static int cacheSize() {
        return CACHE.size();
    }
}
