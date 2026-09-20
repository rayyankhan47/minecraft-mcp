package com.mcmcp;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

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

        // A build needs a location and a facing, so it needs a real player.
        if (!(sender instanceof Player player)) {
            Chat.error(sender, "/mc2p has to be run by a player — it builds where you are standing.");
            return true;
        }

        if (args.length == 0) {
            Chat.error(player, "Tell me what to build.");
            Chat.detail(player, "/mc2p a small medieval cottage with a stone chimney");
            return true;
        }

        String prompt = String.join(" ", args).trim();
        Location origin = resolveOrigin(player);

        plugin.getLogger().info("/mc2p from " + player.getName() + ": \"" + prompt + "\""
                + "  origin=" + format(origin));

        // Step 2 checkpoint: echo only. Steps 3 and 4 replace this with a real build.
        Chat.info(player, "Building: " + prompt);
        Chat.detail(player, "origin " + format(origin) + "  facing " + player.getFacing());

        return true;
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
