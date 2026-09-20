package com.mcmcp;

import com.mcmcp.model.BlockPlacement;
import com.mcmcp.model.Speed;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drains queued blocks into the world at a watchable pace.
 *
 * <p><b>Thread contract.</b> {@link #enqueue} and {@link #signalStreamDone} are called
 * from the async producer. Everything else — and every single world call — happens on
 * the main server thread inside {@link #tick()}. The {@link ConcurrentLinkedQueue} is
 * the only thing crossing between them, and it carries plain data.
 *
 * <p>One repeating task, every tick. Each invocation reads the speed tier off the head
 * of the queue and places up to that tier's budget, stopping early if the tier changes
 * mid-drain — otherwise a run of {@code instant} blocks followed by {@code slow} ones
 * would dump 110 slow blocks in a single tick and destroy the pacing.
 */
public final class PlacementEngine {

    private final McmcpPlugin plugin;

    /**
     * Who to report to. Null for a console-driven build, which is how the pipeline is
     * tested without a client attached — the engine still runs, it just has nobody to
     * talk to.
     */
    private final UUID playerId;

    private final World world;

    private final ConcurrentLinkedQueue<BlockPlacement> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean streamDone = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicInteger enqueued = new AtomicInteger(0);

    private BukkitTask task;

    /**
     * Run when this engine is cancelled. {@code BuildSession} attaches the teardown of
     * its HTTP stream here, so cancelling a build actually stops the network read
     * rather than leaving a thread parked on a socket until the backend happens to
     * send another line.
     */
    private volatile Runnable cancelHook;

    private int tickCounter;
    private int placed;
    private int failed;

    /**
     * Sound is the single highest-impact thing here — it is what makes a build feel
     * alive rather than pasted in. It is also what turns into noise fastest, so no
     * more than this many per tick however much is landing.
     */
    private static final int MAX_SOUNDS_PER_TICK = 4;

    /** Ticks between a ghost block appearing and the real one replacing it. */
    private static final long GHOST_SWAP_TICKS = 2L;

    /**
     * The "under construction" marker for slow placements. A saturated colour that
     * exists almost nowhere in a real build, so each placement reads clearly on camera
     * before it resolves.
     */
    private static final BlockData GHOST = Material.LIGHT_BLUE_CONCRETE.createBlockData();

    private int soundsThisTick;
    private int lastActionBarCount = -1;

    /** When the player hit enter. The number that matters is command to first block. */
    private final long commandNanos;
    private long firstBlockNanos = -1L;

    public PlacementEngine(McmcpPlugin plugin, World world, UUID playerId, long commandNanos) {
        this.plugin = plugin;
        this.world = world;
        this.playerId = playerId;
        this.commandNanos = commandNanos;
    }

    public static PlacementEngine forPlayer(McmcpPlugin plugin, Player player, long commandNanos) {
        return new PlacementEngine(plugin, player.getWorld(), player.getUniqueId(), commandNanos);
    }

    // ---- producer side (async thread) --------------------------------------

    public void enqueue(List<BlockPlacement> placements) {
        if (cancelled.get() || placements == null || placements.isEmpty()) {
            return;
        }
        queue.addAll(placements);
        enqueued.addAndGet(placements.size());
    }

    /** No more blocks are coming. The engine finishes once the queue drains. */
    public void signalStreamDone() {
        streamDone.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void setCancelHook(Runnable hook) {
        this.cancelHook = hook;
    }

    public int totalEnqueued() {
        return enqueued.get();
    }

    // ---- lifecycle ---------------------------------------------------------

    /** Must be called on the main thread. */
    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Safe from any thread. Idempotent. */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        queue.clear();

        BukkitTask t = task;
        if (t != null) {
            t.cancel();
            task = null;
        }

        Runnable hook = cancelHook;
        if (hook != null) {
            cancelHook = null;
            try {
                hook.run();
            } catch (Throwable ignored) {
                // Teardown is best-effort; it must never propagate into a caller that
                // is just trying to start a new build.
            }
        }
    }

    // ---- consumer side (main thread only) ----------------------------------

    private void tick() {
        if (cancelled.get()) {
            return;
        }

        BlockPlacement head = queue.peek();
        if (head == null) {
            // Nothing to do. If the producer has also finished, so have we.
            if (streamDone.get()) {
                finish();
            }
            return;
        }

        tickCounter++;
        soundsThisTick = 0;

        Speed tier = head.speed;

        // The slow tier is a counter: it only acts on every Nth tick.
        if (tier.tickInterval > 1 && tickCounter % tier.tickInterval != 0) {
            return;
        }

        for (int i = 0; i < tier.blocksPerTick; i++) {
            BlockPlacement next = queue.peek();
            // Stop at a tier boundary so the next tier gets its own budget.
            if (next == null || next.speed != tier) {
                break;
            }
            place(queue.poll());
        }

        updateActionBar();
    }

    /**
     * A progress readout on the action bar. Cheap, and it turns "blocks are appearing"
     * into "something is working through a plan".
     */
    private void updateActionBar() {
        if (playerId == null || placed == lastActionBarCount) {
            return;
        }
        lastActionBarCount = placed;

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        player.sendActionBar(Component.text("\u26cf ", NamedTextColor.AQUA)
                .append(Component.text(placed + " / " + enqueued.get() + " blocks",
                        NamedTextColor.WHITE)));
    }

    /** One block. Wrapped, because one bad block must never stop a build. */
    private void place(BlockPlacement p) {
        try {
            Block block = world.getBlockAt(p.x, p.y, p.z);

            // "replace" restricts a shape to overwriting one specific material. This is
            // how carving works — a fill of air that only eats stone, say.
            if (p.replace != null && block.getType() != p.replace.getMaterial()) {
                return;
            }

            // applyPhysics=false is not optional. With physics on, sand falls, torches
            // pop off, water spreads, and a half-built structure collapses as you watch.
            if (p.speed == Speed.SLOW && p.data.getMaterial() != Material.AIR) {
                ghostThenSwap(block, p.data);
            } else {
                block.setBlockData(p.data, false);
            }

            if (p.speed != Speed.INSTANT) {
                effects(block, p.data);
            }

            placed++;
            if (firstBlockNanos < 0) {
                firstBlockNanos = System.nanoTime();
                plugin.getLogger().info(String.format(
                        "first block placed %.0f ms after command",
                        (firstBlockNanos - commandNanos) / 1_000_000.0));
            }
        } catch (Throwable t) {
            failed++;
            if (failed <= 5) {
                plugin.getLogger().warning("placement failed at " + p + ": " + t);
            }
        }
    }

    /**
     * Places a coloured marker first and the real block two ticks later.
     *
     * <p>At one block every 0.3 seconds a slow placement is the thing the camera is on,
     * and a two-beat placement reads far better than a single pop.
     */
    private void ghostThenSwap(Block block, BlockData real) {
        block.setBlockData(GHOST, false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // The build may have been cancelled in the two ticks since. Leaving a stray
            // ghost block behind is much better than writing into a cancelled build.
            if (cancelled.get()) {
                return;
            }
            try {
                block.setBlockData(real, false);
            } catch (Throwable ignored) {
                // A chunk unloaded underneath us. Not worth a log line.
            }
        }, GHOST_SWAP_TICKS);
    }

    /** Sound and particles. Skipped for the instant tier, which would be a wall of noise. */
    private void effects(Block block, BlockData data) {
        try {
            if (soundsThisTick < MAX_SOUNDS_PER_TICK) {
                soundsThisTick++;
                // The block's own placement sound, so stone thuds and wood knocks.
                Sound sound;
                try {
                    sound = data.getSoundGroup().getPlaceSound();
                } catch (Throwable ignored) {
                    sound = Sound.BLOCK_STONE_PLACE;
                }
                world.playSound(block.getLocation(), sound, 0.6f, 1.0f);
            }
            world.spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5),
                    8, 0.25, 0.25, 0.25, 0.0, data);
        } catch (Throwable ignored) {
            // Effects are decoration. They must never be able to stop a build.
        }
    }

    private void finish() {
        long totalMs = (System.nanoTime() - commandNanos) / 1_000_000L;
        long firstMs = firstBlockNanos < 0 ? -1 : (firstBlockNanos - commandNanos) / 1_000_000L;

        cancel();
        if (playerId != null) {
            plugin.finishBuild(playerId, this);
        }

        plugin.getLogger().info(String.format(
                "build complete: %d blocks placed, %d failed, first block %d ms, total %d ms",
                placed, failed, firstMs, totalMs));

        Player player = playerId == null ? null : Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            Chat.success(player, "Built " + placed + " blocks in "
                    + String.format("%.1f", totalMs / 1000.0) + "s");
            Chat.detail(player, "first block after " + firstMs + "ms");
            if (failed > 0) {
                Chat.detail(player, failed + " block(s) could not be placed");
            }
            player.sendActionBar(Component.text("\u2713 ", NamedTextColor.GREEN)
                    .append(Component.text(placed + " blocks", NamedTextColor.WHITE)));
            world.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.4f);
        }
    }
}
