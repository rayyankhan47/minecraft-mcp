package com.mcmcp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mcmcp.model.BlockPlacement;
import com.mcmcp.model.Shape;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * One {@code /mc2p} invocation: talks to the backend and feeds the placement engine.
 *
 * <p><b>Thread contract.</b> Everything in {@link #run()} happens on an async thread —
 * the HTTP call, the JSON parsing, and the shape expansion, which is deliberate: that
 * is the expensive work and it must stay off the server thread. The only things that
 * hop back to the main thread are chat messages, via {@link #onMain}.
 *
 * <p>Nothing here touches the world. Blocks reach it through the engine's queue.
 */
public final class BuildSession {

    /** Shared across sessions — the client pools connections, which saves a handshake. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    /**
     * Backstop only. The real first-token timeout lives in the backend, which can fall
     * back to a cached build; this just stops the plugin waiting forever on a hung
     * socket. Generous, because a legitimate build can run to ~25 seconds.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    /** Hard ceiling per build, enforced here rather than trusted from the model. */
    private static final int MAX_BLOCKS_PER_BUILD = 8000;

    private final McmcpPlugin plugin;
    private final PlacementEngine engine;
    private final UUID playerId;
    private final String playerName;
    private final String prompt;
    private final String endpoint;
    private final int ox;
    private final int oy;
    private final int oz;

    /** Held so {@link #cancel()} can tear the read down from another thread. */
    private volatile Stream<String> openStream;

    private int blocksQueued;
    private int shapesSeen;
    private int badLines;
    private boolean truncated;

    /**
     * @param playerId who to report to, or null for a console-driven build — the
     *                 stream is consumed and the blocks are placed either way, there
     *                 is just nobody to narrate to
     */
    public BuildSession(McmcpPlugin plugin, PlacementEngine engine, UUID playerId, String playerName,
                        String prompt, String endpoint, int ox, int oy, int oz) {
        this.plugin = plugin;
        this.engine = engine;
        this.playerId = playerId;
        this.playerName = playerName;
        this.prompt = prompt;
        this.endpoint = endpoint;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
    }

    public static BuildSession forPlayer(McmcpPlugin plugin, PlacementEngine engine, Player player,
                                         String prompt, String endpoint, int ox, int oy, int oz) {
        return new BuildSession(plugin, engine, player.getUniqueId(), player.getName(),
                prompt, endpoint, ox, oy, oz);
    }

    /** Kicks the HTTP read off the main thread. Returns immediately. */
    public void startAsync() {
        // Cancelling the engine must also stop the read. Closing the stream makes the
        // blocking iterator throw, which unwinds run() immediately instead of waiting
        // for a backend that may never send another byte.
        engine.setCancelHook(this::closeStream);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::run);
    }

    private void closeStream() {
        Stream<String> s = openStream;
        openStream = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {
                // Already dead, or dying. Either is fine.
            }
        }
    }

    // ---- async thread ------------------------------------------------------

    private void run() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("prompt", prompt);
            body.addProperty("player", playerName);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();

            // ofLines gives us a lazily-populated stream: each element is one complete
            // line, delivered as it arrives off the socket. This is the whole trick.
            HttpResponse<Stream<String>> response = HTTP.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                fail("backend returned HTTP " + response.statusCode());
                return;
            }

            try (Stream<String> lines = response.body()) {
                openStream = lines;
                for (String raw : (Iterable<String>) lines::iterator) {
                    if (engine.isCancelled()) {
                        plugin.getLogger().info("session for " + playerName + " cancelled, stopping read");
                        return;
                    }
                    handleLine(raw);
                }
            }

            // The stream closed. Whether or not we saw an explicit `done`, no more
            // blocks are coming — let the engine finish what it has rather than
            // spinning forever on a queue nobody will refill.
            engine.signalStreamDone();

            plugin.getLogger().info("stream closed for " + playerName + ": "
                    + shapesSeen + " shapes, " + blocksQueued + " blocks, " + badLines + " bad lines");

        } catch (Throwable t) {
            // A cancelled session tearing down its own socket is expected, not a fault.
            if (engine.isCancelled()) {
                plugin.getLogger().info("read for " + playerName + " ended on cancellation");
            } else {
                fail(t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        } finally {
            openStream = null;
        }
    }

    /** One NDJSON line. A bad line is logged and skipped; it never aborts the build. */
    private void handleLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String text = stripFence(raw.trim());
        if (text.isEmpty()) {
            return;
        }

        JsonObject obj;
        try {
            obj = JsonParser.parseString(text).getAsJsonObject();
        } catch (Throwable t) {
            badLines++;
            if (badLines <= 5) {
                plugin.getLogger().warning("unparseable line, skipping: " + clip(text));
            }
            return;
        }

        String type = obj.has("type") && obj.get("type").isJsonPrimitive()
                ? obj.get("type").getAsString()
                : "";

        switch (type) {
            case "thought" -> {
                String t = obj.has("text") ? obj.get("text").getAsString() : "";
                if (!t.isBlank()) {
                    onMain(p -> Chat.thought(p, t));
                }
            }
            case "shape" -> handleShape(obj);
            case "done" -> {
                String summary = obj.has("summary") ? obj.get("summary").getAsString() : "";
                plugin.getLogger().info("backend done: " + summary);
                engine.signalStreamDone();
            }
            case "error" -> {
                String message = obj.has("message") ? obj.get("message").getAsString() : "generation failed";
                plugin.getLogger().warning("backend error: " + message);
                onMain(p -> Chat.error(p, message));
                engine.signalStreamDone();
            }
            default -> {
                badLines++;
                if (badLines <= 5) {
                    plugin.getLogger().warning("unknown message type \"" + type + "\", skipping");
                }
            }
        }
    }

    private void handleShape(JsonObject obj) {
        Shape shape = Shape.fromJson(obj);
        if (shape == null) {
            badLines++;
            plugin.getLogger().warning("shape line could not be read, skipping");
            return;
        }

        if (blocksQueued >= MAX_BLOCKS_PER_BUILD) {
            if (!truncated) {
                truncated = true;
                plugin.getLogger().warning("build hit the " + MAX_BLOCKS_PER_BUILD
                        + " block limit, ignoring the rest");
                onMain(p -> Chat.detail(p, "build truncated at " + MAX_BLOCKS_PER_BUILD + " blocks"));
            }
            return;
        }

        List<BlockPlacement> placements = ShapeExpander.expand(shape, ox, oy, oz);
        if (placements.isEmpty()) {
            return;
        }

        // Trim so the whole build stays under the cap, rather than overshooting by
        // however large the last shape happened to be.
        int room = MAX_BLOCKS_PER_BUILD - blocksQueued;
        if (placements.size() > room) {
            placements = placements.subList(0, room);
            truncated = true;
        }

        shapesSeen++;
        blocksQueued += placements.size();
        engine.enqueue(placements);
    }

    private void fail(String reason) {
        plugin.getLogger().warning("build failed for " + playerName + ": " + reason);
        onMain(p -> Chat.error(p, "Build failed: " + reason));
        // Let the engine drain and finish whatever already made it into the queue.
        engine.signalStreamDone();
    }

    /**
     * Hops to the main thread to talk to the player.
     *
     * <p>{@code Bukkit.getPlayer} and every send call must happen there, so the lookup
     * is inside the task rather than outside it.
     */
    private void onMain(Consumer<Player> action) {
        if (playerId == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                action.accept(player);
            }
        });
    }

    /**
     * Models sometimes wrap output in markdown fences despite being told not to.
     * Cheaper to strip them than to lose the line.
     */
    private static String stripFence(String s) {
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            String rest = nl >= 0 ? s.substring(nl + 1) : "";
            return rest.replace("```", "").trim();
        }
        if (s.equals("```")) {
            return "";
        }
        return s;
    }

    private static String clip(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }
}
