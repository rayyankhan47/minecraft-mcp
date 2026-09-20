package com.mcmcp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/**
 * All player-facing text goes through here.
 *
 * <p>The point is a consistent voice: the agent's narration should read as a distinct
 * speaker, not as ordinary chat. Adventure components ship with Paper, so this costs
 * us no dependency.
 */
public final class Chat {

    private Chat() {
    }

    private static final Component TAG =
            Component.text("mcmcp", NamedTextColor.AQUA, TextDecoration.BOLD)
                    .append(Component.text(" › ", NamedTextColor.DARK_GRAY));

    /** Neutral status line. */
    public static void info(CommandSender to, String text) {
        to.sendMessage(TAG.append(Component.text(text, NamedTextColor.GRAY)));
    }

    /** Something worked. */
    public static void success(CommandSender to, String text) {
        to.sendMessage(TAG.append(Component.text(text, NamedTextColor.GREEN)));
    }

    /** Something went wrong, in a way the player should know about. */
    public static void error(CommandSender to, String text) {
        to.sendMessage(TAG.append(Component.text(text, NamedTextColor.RED)));
    }

    /**
     * The agent narrating its own work. Visually distinct from everything else —
     * during the generation wait this is the only thing happening on screen, so it
     * needs to carry.
     */
    public static void thought(CommandSender to, String text) {
        to.sendMessage(
                Component.text("  ✦ ", NamedTextColor.LIGHT_PURPLE)
                        .append(Component.text(text, NamedTextColor.WHITE, TextDecoration.ITALIC)));
    }

    /** Dim detail — coordinates, counts, timings. */
    public static void detail(CommandSender to, String text) {
        to.sendMessage(Component.text("    " + text, NamedTextColor.DARK_GRAY));
    }
}
