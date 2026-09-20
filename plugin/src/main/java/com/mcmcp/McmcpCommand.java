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
            return selftest(sender, commandNanos);
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

        // Step 3 checkpoint: the prompt is ignored and a hardcoded cottage is built.
        // Step 4 replaces this with the streamed response from the backend.
        PlacementEngine engine = PlacementEngine.forPlayer(plugin, player, commandNanos);
        plugin.registerBuild(player.getUniqueId(), engine);

        DemoBuild.thoughts().forEach(t -> Chat.thought(player, t));

        int queued = enqueueAll(engine, DemoBuild.cottage(), origin);
        Chat.detail(player, queued + " blocks queued at " + format(origin));

        engine.start();
        engine.signalStreamDone();
        return true;
    }

    /** Builds the hardcoded cottage near world spawn, reporting only to the console. */
    private boolean selftest(CommandSender sender, long commandNanos) {
        World world = Bukkit.getWorlds().get(0);
        Location origin = world.getSpawnLocation().clone().add(10, 0, 10);
        origin.setY(world.getHighestBlockYAt(origin) + 1);

        plugin.getLogger().info("selftest: building at " + format(origin));

        PlacementEngine engine = new PlacementEngine(plugin, world, null, commandNanos);
        int queued = enqueueAll(engine, DemoBuild.cottage(), origin);
        engine.start();
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
