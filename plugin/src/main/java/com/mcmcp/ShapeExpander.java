package com.mcmcp;

import com.mcmcp.model.BlockPlacement;
import com.mcmcp.model.Shape;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Turns one {@link Shape} into an ordered list of {@link BlockPlacement}.
 *
 * <p>Runs on the async thread. It is also where every safety limit is enforced —
 * deliberately, so that nothing downstream has to trust the model's output. The engine
 * that actually touches the world receives only coordinates that have already been
 * bounds-checked.
 *
 * <p>Ordering matters as much as content. Blocks come out bottom-up and, within each
 * Y layer, centre-outward. That reads as <i>construction</i>; the natural iteration
 * order reads as a texture being pasted in.
 */
public final class ShapeExpander {

    private ShapeExpander() {
    }

    /** World height limits for a 1.21 overworld. Blocks outside this are dropped. */
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;

    /** No shape may exceed this on any axis. Rejected outright, not truncated. */
    public static final int MAX_SHAPE_EXTENT = 64;

    /** Hard ceiling on a single shape, so a pathological fill cannot exhaust memory. */
    public static final int MAX_BLOCKS_PER_SHAPE = 8000;

    /**
     * @param ox,oy,oz the build origin in absolute world coordinates — the model's
     *                 {@code [0,0,0]}
     * @return placements in the order they should be applied; never null, possibly empty
     */
    public static List<BlockPlacement> expand(Shape shape, int ox, int oy, int oz) {
        List<BlockPlacement> out = new ArrayList<>();
        if (shape == null || shape.op == null) {
            return out;
        }

        try {
            BlockData data = BlockResolver.resolve(shape.block);
            BlockData replace = shape.replace == null ? null : BlockResolver.resolve(shape.replace);

            switch (shape.op) {
                case "fill" -> box(out, shape, data, replace, ox, oy, oz, Fill.SOLID);
                case "hollow" -> box(out, shape, data, replace, ox, oy, oz, Fill.SHELL);
                case "walls" -> box(out, shape, data, replace, ox, oy, oz, Fill.WALLS);
                case "set" -> single(out, shape, data, replace, ox, oy, oz);
                default -> warn("unknown op \"" + shape.op + "\", skipping shape");
            }
        } catch (Throwable t) {
            // An exception here would kill one shape. Letting it escape would kill the
            // build, and the async thread with it.
            warn("failed to expand " + shape + ": " + t);
            return new ArrayList<>();
        }

        sortForConstruction(out);
        return out;
    }

    private enum Fill {
        /** Every block in the box. */
        SOLID,
        /** The box's surface: walls, floor and ceiling, hollow inside. */
        SHELL,
        /** Four vertical walls only — no floor, no ceiling. */
        WALLS
    }

    private static void box(List<BlockPlacement> out, Shape shape, BlockData data, BlockData replace,
                            int ox, int oy, int oz, Fill mode) {

        if (shape.from == null || shape.to == null) {
            warn(shape.op + " needs both \"from\" and \"to\", skipping");
            return;
        }

        int x1 = Math.min(shape.from[0], shape.to[0]);
        int y1 = Math.min(shape.from[1], shape.to[1]);
        int z1 = Math.min(shape.from[2], shape.to[2]);
        int x2 = Math.max(shape.from[0], shape.to[0]);
        int y2 = Math.max(shape.from[1], shape.to[1]);
        int z2 = Math.max(shape.from[2], shape.to[2]);

        if (tooBig(shape.op, x2 - x1 + 1, y2 - y1 + 1, z2 - z1 + 1)) {
            return;
        }

        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                for (int z = z1; z <= z2; z++) {

                    boolean onXEdge = (x == x1 || x == x2);
                    boolean onZEdge = (z == z1 || z == z2);
                    boolean onYEdge = (y == y1 || y == y2);

                    boolean keep = switch (mode) {
                        case SOLID -> true;
                        case SHELL -> onXEdge || onZEdge || onYEdge;
                        case WALLS -> onXEdge || onZEdge;
                    };
                    if (!keep) {
                        continue;
                    }

                    if (!add(out, x + ox, y + oy, z + oz, data, shape, replace)) {
                        return; // hit the per-shape cap
                    }
                }
            }
        }
    }

    private static void single(List<BlockPlacement> out, Shape shape, BlockData data, BlockData replace,
                               int ox, int oy, int oz) {
        if (shape.pos == null) {
            warn("set needs \"pos\", skipping");
            return;
        }
        add(out, shape.pos[0] + ox, shape.pos[1] + oy, shape.pos[2] + oz, data, shape, replace);
    }

    /**
     * Appends one placement after bounds-checking it.
     *
     * @return false once the per-shape cap is reached, meaning the caller should stop
     */
    private static boolean add(List<BlockPlacement> out, int x, int y, int z,
                               BlockData data, Shape shape, BlockData replace) {
        if (out.size() >= MAX_BLOCKS_PER_SHAPE) {
            warn(shape.op + " exceeded " + MAX_BLOCKS_PER_SHAPE + " blocks, truncating");
            return false;
        }
        // Out of world bounds: drop the block rather than clamping it. Clamping would
        // squash several Y layers onto one and produce a visibly wrong structure.
        if (y < MIN_Y || y > MAX_Y) {
            return true;
        }
        out.add(new BlockPlacement(x, y, z, data, shape.speed, replace));
        return true;
    }

    private static boolean tooBig(String op, int dx, int dy, int dz) {
        if (dx > MAX_SHAPE_EXTENT || dy > MAX_SHAPE_EXTENT || dz > MAX_SHAPE_EXTENT) {
            warn(op + " bounding box " + dx + "x" + dy + "x" + dz
                    + " exceeds the " + MAX_SHAPE_EXTENT + "-block limit, rejecting shape");
            return true;
        }
        return false;
    }

    /**
     * Bottom-up, then centre-outward within each layer.
     *
     * <p>Centre is computed from the placements themselves rather than from the shape
     * definition, so it is correct for every op without special-casing.
     */
    static void sortForConstruction(List<BlockPlacement> list) {
        if (list.size() < 2) {
            return;
        }

        long sx = 0;
        long sz = 0;
        for (BlockPlacement p : list) {
            sx += p.x;
            sz += p.z;
        }
        final double cx = (double) sx / list.size();
        final double cz = (double) sz / list.size();

        list.sort(Comparator
                .comparingInt((BlockPlacement p) -> p.y)
                .thenComparingDouble(p -> {
                    double dx = p.x - cx;
                    double dz = p.z - cz;
                    return dx * dx + dz * dz;
                }));
    }

    private static void warn(String message) {
        McmcpPlugin plugin = McmcpPlugin.get();
        if (plugin != null) {
            Logger log = plugin.getLogger();
            log.warning("[expander] " + message);
        }
    }
}
