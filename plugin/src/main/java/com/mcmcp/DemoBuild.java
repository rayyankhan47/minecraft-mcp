package com.mcmcp;

import com.mcmcp.model.Shape;
import com.mcmcp.model.Speed;

import java.util.ArrayList;
import java.util.List;

/**
 * A hardcoded cottage, used to exercise the whole placement pipeline with no network
 * and no model in the loop.
 *
 * <p>This exists because a working {@code /mc2p} that always builds the same stone box
 * is a far better checkpoint than a half-finished LLM integration — and because when
 * something breaks later, it isolates instantly whether the fault is in the model layer
 * or the placement layer.
 *
 * <p>It also encodes the pacing the model is later told to imitate: bulk fill lands
 * {@code instant}, structure assembles {@code fast}, and the build ends on a
 * {@code slow} shape so the sequence finishes on a visible beat rather than trailing
 * off. Roughly 300 blocks, about five seconds.
 */
public final class DemoBuild {

    private DemoBuild() {
    }

    public static List<Shape> cottage() {
        List<Shape> shapes = new ArrayList<>();

        // Foundation — appears at once, so something is on screen immediately.
        shapes.add(fill("foundation", Speed.INSTANT, "minecraft:cobblestone",
                0, 0, 0, 8, 0, 8));

        // Walls — visibly assembling is the point of this tier.
        shapes.add(walls("structure", Speed.FAST, "minecraft:oak_planks",
                0, 1, 0, 8, 3, 8));

        // Carve the openings. An air fill is how you subtract.
        shapes.add(fill("openings", Speed.FAST, "minecraft:air", 4, 1, 0, 4, 2, 0));
        shapes.add(fill("openings", Speed.FAST, "minecraft:air", 2, 2, 0, 2, 2, 0));
        shapes.add(fill("openings", Speed.FAST, "minecraft:air", 6, 2, 0, 6, 2, 0));

        shapes.add(fill("roof", Speed.INSTANT, "minecraft:oak_planks", 0, 4, 0, 8, 4, 8));
        shapes.add(walls("roof", Speed.FAST, "minecraft:cobblestone", 0, 5, 0, 8, 5, 8));

        // The finale. Slow, small, and last — this is the beat the build ends on.
        shapes.add(fill("detail", Speed.SLOW, "minecraft:cobblestone", 7, 5, 7, 7, 9, 7));
        shapes.add(fill("detail", Speed.SLOW, "minecraft:sea_lantern", 0, 5, 0, 8, 5, 0));

        return shapes;
    }

    /** Narration to accompany the build, one line per phase. */
    public static List<String> thoughts() {
        return List.of(
                "Laying a cobblestone foundation",
                "Raising the oak walls",
                "Cutting a doorway and two windows",
                "Closing the roof in",
                "Stacking the chimney",
                "Lighting the front edge");
    }

    private static Shape fill(String phase, Speed speed, String block,
                              int x1, int y1, int z1, int x2, int y2, int z2) {
        Shape s = base("fill", phase, speed, block);
        s.from = new int[]{x1, y1, z1};
        s.to = new int[]{x2, y2, z2};
        return s;
    }

    private static Shape walls(String phase, Speed speed, String block,
                               int x1, int y1, int z1, int x2, int y2, int z2) {
        Shape s = base("walls", phase, speed, block);
        s.from = new int[]{x1, y1, z1};
        s.to = new int[]{x2, y2, z2};
        return s;
    }

    private static Shape base(String op, String phase, Speed speed, String block) {
        Shape s = new Shape();
        s.op = op;
        s.phase = phase;
        s.speed = speed;
        s.block = block;
        return s;
    }
}
