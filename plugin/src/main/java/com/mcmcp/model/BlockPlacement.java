package com.mcmcp.model;

import org.bukkit.block.data.BlockData;

/**
 * One block, ready to be placed. Absolute world coordinates, already-resolved
 * blockdata, and the pace it should land at.
 *
 * <p>This is the only type that crosses the thread boundary. It deliberately holds no
 * {@code Block}, {@code World} or {@code Player} handle — just numbers and an immutable
 * {@link BlockData}, which is a value object rather than a live world reference.
 * {@code ShapeExpander} produces these on the async thread; {@code PlacementEngine}
 * consumes them on the main thread.
 */
public final class BlockPlacement {

    public final int x;
    public final int y;
    public final int z;
    public final BlockData data;
    public final Speed speed;

    /** If non-null, only overwrite the existing block when it matches this material. */
    public final BlockData replace;

    public BlockPlacement(int x, int y, int z, BlockData data, Speed speed, BlockData replace) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.data = data;
        this.speed = speed;
        this.replace = replace;
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + ")=" + (data == null ? "null" : data.getMaterial());
    }
}
