package com.mcmcp.model;

/**
 * How fast a shape's blocks are allowed to land.
 *
 * <p>This is the demo's visual signature. A uniform pace of one block every 0.3s turns
 * a 400-block build into a two-minute wait; placing everything at once looks like a
 * texture being pasted rather than a thing being built. So each shape carries a hint
 * and the engine drains the queue with a per-tick budget.
 *
 * <p>20 ticks = 1 second.
 */
public enum Speed {

    /** Materialises at once. Foundations, floors, bulk fill. */
    INSTANT(120, 1),

    /** Visible rapid assembly. Walls, structure. */
    FAST(6, 1),

    /** Deliberate, one at a time. Roofline, chimney, detail, the final flourish. */
    SLOW(1, 6);

    /** How many blocks may be placed on a tick this tier acts on. */
    public final int blocksPerTick;

    /** Act only on every Nth tick. 1 = every tick, 6 = every 0.3 seconds. */
    public final int tickInterval;

    Speed(int blocksPerTick, int tickInterval) {
        this.blocksPerTick = blocksPerTick;
        this.tickInterval = tickInterval;
    }

    /**
     * Never throws. An unrecognised tier falls back to {@link #FAST}, which looks
     * acceptable for anything — the wrong pace is a blemish, an exception is a dead
     * demo.
     */
    public static Speed parse(String raw) {
        if (raw == null) {
            return FAST;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "instant" -> INSTANT;
            case "slow" -> SLOW;
            case "fast" -> FAST;
            default -> FAST;
        };
    }
}
