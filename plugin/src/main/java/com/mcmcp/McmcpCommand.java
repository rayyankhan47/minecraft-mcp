package com.mcmcp;

import com.mcmcp.model.BlockPlacement;
import com.mcmcp.model.Shape;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Handler for {@code /mc2p <description>}.
 *
 * <p>Registered through {@code plugin.yml} rather than Brigadier — simpler, and one
 * less thing to get subtly wrong under time pressure.
 */
public final class McmcpCommand implements CommandExecutor {

    /**
     * How far in front of the player the build starts. Far enough that the structure
     * does not materialise on top of them, close enough to stay in frame.
     */
    private static final int ORIGIN_OFFSET = 3;

    private final McmcpPlugin plugin;

    public McmcpCommand(McmcpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        long commandNanos = System.nanoTime();

        if (args.length == 0) {
            Chat.error(sender, "Tell me what to build.");
            Chat.detail(sender, "/mc2p a small medieval cottage with a stone chimney");
            return true;
        }

        // Console-only escape hatch: runs the hardcoded build at a fixed origin so the
        // placement pipeline can be exercised with no client attached. Not for players.
        if (!(sender instanceof Player) && args[0].equalsIgnoreCase("selftest")) {
            return selftest(sender, args, commandNanos);
        }

        if (!(sender instanceof Player player)) {
            Chat.error(sender, "/mc2p has to be run by a player — it builds where you are standing.");
            return true;
        }

        String prompt = String.join(" ", args).trim();
        Location origin = resolveOrigin(player);

        plugin.getLogger().info("/mc2p from " + player.getName() + ": \"" + prompt + "\""
                + "  origin=" + format(origin));

        Chat.info(player, "Building: " + prompt);

        PlacementEngine engine = PlacementEngine.forPlayer(plugin, player, commandNanos);
        plugin.registerBuild(player.getUniqueId(), engine);

        // "/mc2p local <anything>" bypasses the backend entirely and builds the
        // hardcoded cottage. This is the break-glass path: it works with no backend,
        // no network and no API key.
        if (args[0].equalsIgnoreCase("local")) {
            DemoBuild.thoughts().forEach(t -> Chat.thought(player, t));
            int queued = enqueueAll(engine, DemoBuild.cottage(), origin);
            Chat.detail(player, queued + " blocks queued at " + format(origin));
            engine.start();
            engine.signalStreamDone();
            return true;
        }

        // The engine starts draining immediately, before a single block has arrived.
        // Blocks are placed as they stream in rather than after the response closes —
        // that is the whole point of the design.
        engine.start();

        BuildSession.forPlayer(plugin, engine, player, prompt,
                        McmcpPlugin.BACKEND_URL + "/build",
                        origin.getBlockX(), origin.getBlockY(), origin.getBlockZ())
                .startAsync();

        return true;
    }

    /**
     * Builds near world spawn with no player attached, reporting only to the console.
     *
     * <p>{@code mc2p selftest} uses the hardcoded shape list; {@code mc2p selftest
     * stream <prompt>} goes through the real backend. Between them they exercise the
     * whole pipeline with no client connected.
     */
    private boolean selftest(CommandSender sender, String[] args, long commandNanos) {
        World world = Bukkit.getWorlds().get(0);
        Location origin = world.getSpawnLocation().clone().add(10, 0, 10);
        origin.setY(world.getHighestBlockYAt(origin) + 1);

        boolean streaming = args.length > 1 && args[1].equalsIgnoreCase("stream");
        boolean chaos = args.length > 2 && args[1].equalsIgnoreCase("chaos");
        plugin.getLogger().info("selftest: building at " + format(origin)
                + (streaming || chaos ? " via the backend" : " from the hardcoded list"));

        PlacementEngine engine = new PlacementEngine(plugin, world, null, commandNanos);
        engine.start();

        if (streaming || chaos) {
            String endpoint = McmcpPlugin.BACKEND_URL + "/build";
            String prompt = "a small medieval cottage";
            if (chaos) {
                // Failure-path testing only — makes the backend misbehave on purpose.
                endpoint += "?chaos=" + args[2];
                prompt = "chaos " + args[2];
            } else if (args.length > 2) {
                prompt = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            }
            new BuildSession(plugin, engine, null, "console", prompt, endpoint,
                    origin.getBlockX(), origin.getBlockY(), origin.getBlockZ())
                    .startAsync();
            sender.sendMessage("selftest: streaming build started at " + format(origin));
            plugin.getLogger().info("selftest: streaming build started at " + format(origin));
            return true;
        }

        int queued = enqueueAll(engine, DemoBuild.cottage(), origin);
        engine.signalStreamDone();
        sender.sendMessage("selftest: queued " + queued + " blocks at " + format(origin));
        plugin.getLogger().info("selftest: queued " + queued + " blocks at " + format(origin));
        return true;
    }

    /** Expands every shape against the origin and hands the blocks to the engine. */
    private int enqueueAll(PlacementEngine engine, List<Shape> shapes, Location origin) {
        int total = 0;
        for (Shape shape : shapes) {
            List<BlockPlacement> placements = ShapeExpander.expand(
                    shape, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
            engine.enqueue(placements);
            total += placements.size();
        }
        return total;
    }

    /**
     * Where {@code [0,0,0]} in the model's coordinate space lands in the world.
     *
     * <p>The player's block position, pushed {@value #ORIGIN_OFFSET} blocks in the
     * cardinal direction they are facing. {@code getFacing()} does the yaw-to-cardinal
     * snapping for us, which is exactly the rounding we want.
     */
    public static Location resolveOrigin(Player player) {
        Location eye = player.getLocation();
        BlockFace facing = player.getFacing();
        Vector push = facing.getDirection().multiply(ORIGIN_OFFSET);

        return new Location(
                player.getWorld(),
                eye.getBlockX() + push.getBlockX(),
                eye.getBlockY(),
                eye.getBlockZ() + push.getBlockZ());
    }

    private static String format(Location l) {
        return "(" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + ")";
    }
}
