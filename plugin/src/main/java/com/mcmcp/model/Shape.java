package com.mcmcp.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One build primitive, straight off the wire.
 *
 * <p>A plain data holder — no behaviour, no validation beyond "could this be read at
 * all". The whole reason the latency budget works is that the model emits these rather
 * than individual blocks: a 10x10 floor is one {@code fill} object instead of a hundred
 * placements.
 *
 * <p>Fields are nullable by design. Which ones are populated depends on {@link #op},
 * and {@code ShapeExpander} is what knows the rules. Nothing here is trusted.
 *
 * <p>Gson is used rather than a hand-rolled parser: it is a transitive dependency of
 * paper-api AND ships inside the Paper server, so there is nothing to shade and
 * nothing resolved at runtime. The zero-dependency rule is about not bundling jars,
 * and this bundles nothing.
 */
public final class Shape {

    /** One of: fill, hollow, walls, line, set, sphere, cylinder. Lowercased. */
    public String op;

    /** Free-text grouping from the model — "foundation", "walls", "roof". Log only. */
    public String phase;

    public Speed speed = Speed.FAST;

    /** Corner-to-corner ops: fill, hollow, walls, line. */
    public int[] from;
    public int[] to;

    /** Single-block op: set. */
    public int[] pos;

    /** Radial ops: sphere, cylinder. */
    public int[] center;
    public int radius = -1;
    public int height = -1;

    /** A full blockstate string, e.g. {@code minecraft:oak_stairs[facing=north]}. */
    public String block;

    /** For sphere and cylinder: shell only rather than solid. */
    public boolean hollow;

    /** If set, only overwrite blocks matching this. Used for carving. */
    public String replace;

    @Override
    public String toString() {
        return "Shape{" + op + " phase=" + phase + " speed=" + speed + " block=" + block + "}";
    }

    /**
     * Reads a shape out of one parsed NDJSON line.
     *
     * <p><b>Never throws.</b> Returns null for anything it cannot make sense of, and
     * the caller logs and skips. One bad line must never take down a build.
     */
    public static Shape fromJson(JsonObject o) {
        try {
            Shape s = new Shape();
            s.op = lower(str(o, "op"));
            if (s.op == null || s.op.isEmpty()) {
                return null;
            }
            s.phase = str(o, "phase");
            s.speed = Speed.parse(str(o, "speed"));
            s.from = ints(o, "from");
            s.to = ints(o, "to");
            s.pos = ints(o, "pos");
            s.center = ints(o, "center");
            s.radius = integer(o, "radius", -1);
            s.height = integer(o, "height", -1);
            s.block = str(o, "block");
            s.hollow = bool(o, "hollow");
            s.replace = str(o, "replace");
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- defensive readers -------------------------------------------------
    // Each one tolerates a missing key, a null, or the wrong JSON type.

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return null;
        }
        return e.getAsString();
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return false;
        }
        try {
            return e.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int integer(JsonObject o, String key, int fallback) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return fallback;
        }
        try {
            // getAsInt copes with 5, 5.0 and "5" — models emit all three.
            return e.getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /** Reads a {@code [x,y,z]} triple. Returns null unless it is exactly three numbers. */
    private static int[] ints(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonArray()) {
            return null;
        }
        JsonArray a = e.getAsJsonArray();
        if (a.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = a.get(i).getAsInt();
            } catch (Exception ignored) {
                return null;
            }
        }
        return out;
    }
}
