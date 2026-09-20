# MC MCP — Build Plan

> Ordered execution plan. Read `project_details.md` first for the full specification.
> Work through this strictly in order. Each top-level step ends in a verifiable state —
> do not advance past a step that is not verified.

**Suggested time budget (~3.5h total):**

| Step | Budget | Cumulative |
|------|--------|------------|
| 1. Environment | 30 min | 0:30 |
| 2. Plugin skeleton | 30 min | 1:00 |
| 3. Placement engine | 45 min | 1:45 |
| 4. Backend + protocol | 30 min | 2:15 |
| 5. LLM layer | 30 min | 2:45 |
| 6. Integration | 20 min | 3:05 |
| 7. Polish | 20 min | 3:25 |
| 8. Resilience + rehearsal | 25 min | 3:50 |

If time runs short, steps 7 and the optional items in 3 are the ones to cut. Step 8 is
**not** optional — it is what prevents a repeat of the last demo failure.

---

## 1. Environment and server bootstrap

*Goal: a running Paper server on a flat world that a vanilla client can join.*

### 1.1 Verify and pin the toolchain

- **1.1.1** Run `java -version`. Confirm it reports 21.x. If not, install Temurin 21
  (arm64) and set `JAVA_HOME` explicitly for this project's shell session.
- **1.1.2** Confirm `python3 --version` is 3.11+, and that Gradle is available (wrapper
  is fine — generate it if absent).

### 1.2 Acquire and boot the Paper server

- **1.2.1** Query Paper's downloads API to resolve the latest available build for a
  1.21.x version. Pin that exact version string in a `VERSION` file at the repo root —
  every later step reads from it. Do **not** select a 26.x version; it requires Java 25
  and will not start on Java 21.
- **1.2.2** Create `server/`, drop the jar in, run it once, set `eula=true` in the
  generated `eula.txt`, then run it again and confirm it reaches "Done" in the console.

### 1.3 Configure the world for demo conditions

- **1.3.1** Stop the server. Write the `server.properties` values from §4.3 of the spec.
  `max-tick-time=-1` and `level-type=minecraft:flat` are the two that matter most —
  the first prevents the watchdog killing the server mid-build, the second gives a clean
  canvas. Delete any existing `world/` directory so the flat level type takes effect.
- **1.3.2** Restart, and connect from the Minecraft client with the launcher profile set
  to the exact pinned version. Confirm you spawn on a superflat world in creative mode
  and can fly.

### 1.4 Initialise the repository

- **1.4.1** Create the directory layout from §10 of the spec. Add a `.gitignore` covering
  `server/`, `build/`, `.gradle/`, `__pycache__/`, `.env`.
- **1.4.2** Commit this as the baseline. From here, commit after every top-level step —
  you want a known-good jar to roll back to if a later change breaks the build.

---

## 2. Plugin skeleton and command plumbing

*Goal: `/mc2p hello` echoes back in chat from a loaded plugin.*

### 2.1 Gradle project setup

- **2.1.1** Write `build.gradle.kts` with the PaperMC maven repository, a `compileOnly`
  dependency on `paper-api` for the pinned version, and a Java toolchain of 21. Do not
  add a shadow/shade plugin — there are no runtime dependencies to bundle.
- **2.1.2** Add a Gradle task (or a shell script `deploy.sh`) that builds the jar and
  copies it to `server/plugins/`. You will run this dozens of times; make it one command.

### 2.2 Plugin entry point

- **2.2.1** Write `plugin.yml` exactly as specified in §10.2, and `McmcpPlugin.java`
  extending `JavaPlugin` with `onEnable` logging a clear startup banner.
- **2.2.2** Build, deploy, restart the server. Confirm the banner appears in the console
  and `/plugins` lists `mcmcp` in green. **Do not proceed until this works** — every
  later problem is harder to diagnose through a plugin that is not loading.

### 2.3 Command handler

- **2.3.1** Write `McmcpCommand.java` implementing `CommandExecutor`. Reject console
  senders (needs a `Player` for location). Join the args into a single prompt string.
  Register it in `onEnable`.
- **2.3.2** Have it echo the received prompt and the resolved build origin back to the
  player in chat. Verify in-game with `/mc2p a test house`.

---

## 3. Block placement and pacing engine

*Goal: `/mc2p` builds a hardcoded structure with correct, good-looking pacing. No LLM,
no network.*

> This is the most important step in the plan. A fully working paced placement engine
> driven by a hardcoded shape list is the checkpoint that guarantees you have a demo at
> all. Everything after this is an upgrade to an already-working system.

### 3.1 Data model and block resolution

- **3.1.1** Write `Shape.java` (op, phase, speed, from/to/center/radius/height, block,
  hollow, replace) and `BlockPlacement.java` (absolute x/y/z, resolved `BlockData`,
  speed tier). Keep both as plain data holders.
- **3.1.2** Write `BlockResolver.java` implementing the four-stage fallback chain from
  §6.4 — `createBlockData` → strip state and retry → `matchMaterial` → substitute stone.
  It must **never** throw. Unit-test it mentally against `minecraft:oak_stairs[facing=north]`,
  `oak_planks`, and a deliberately invalid name like `minecraft:fake_block`.

### 3.2 Shape expansion

- **3.2.1** Write `ShapeExpander.java` converting each op from §6.3 into a list of
  `BlockPlacement`. Start with `fill`, `hollow`, `walls`, and `set` — these cover a
  house. Add `line`, `sphere`, `cylinder` only once the rest of the pipeline works.
- **3.2.2** Sort the output bottom-up: ascending Y, then by distance from the shape's
  centre within each layer. Apply the origin offset and enforce the Y clamp and size
  limits from §6.6 here, so no downstream code has to trust the input.

### 3.3 The placement engine

- **3.3.1** Write `PlacementEngine.java` holding a `ConcurrentLinkedQueue<BlockPlacement>`
  and a repeating sync task scheduled every 1 tick via `runTaskTimer(plugin, task, 1L, 1L)`.
  Each tick, read the head element's speed tier, pop and place up to that budget
  (`instant` 120, `fast` 6, `slow` 1 per 6 ticks). Place with physics disabled —
  `setBlockData(data, false)`. Wrap each placement in try/catch.
- **3.3.2** Track a completion state: when the queue empties *and* the producing stream
  has signalled done, cancel the task and send a completion message. Guard against a
  second `/mc2p` from the same player by cancelling the previous session first.

### 3.4 End-to-end verification with hardcoded input

- **3.4.1** Hardcode a small cottage as a Java list of `Shape` objects — an `instant`
  cobblestone floor, `fast` plank walls, an air `fill` for a doorway, and a `slow`
  roofline. Wire `/mc2p` to run it directly.
- **3.4.2** Run it in-game and watch. Verify: the floor appears at once, the walls
  assemble visibly, the roof places one block at a time, nothing falls or pops off, and
  the server does not stutter. **Tune the tier constants here** until the pacing looks
  right — this is the demo's visual signature and it is much easier to tune now than
  later. Commit.

---

## 4. Backend service and the NDJSON protocol

*Goal: a localhost endpoint that streams a hardcoded build as NDJSON, consumed live by
the plugin.*

### 4.1 FastAPI scaffold

- **4.1.1** Write `backend/main.py` with `GET /health` returning `{"ok": true}` and
  `POST /build` accepting `{prompt, player}`. Add `requirements.txt` and a venv.
- **4.1.2** Implement `/build` as a `StreamingResponse` with media type
  `application/x-ndjson` that yields a **hardcoded** sequence: a `thought`, three
  `shape` lines with `asyncio.sleep(1)` between them, and a `done`. The artificial delay
  simulates model latency so you can verify progressive rendering works.

### 4.2 Plugin-side stream consumer

- **4.2.1** Write `BuildSession.java`. On an async task, POST to the backend using
  `java.net.http.HttpClient` with `BodyHandlers.ofLines()`. Iterate the resulting stream
  — each element is one complete line, delivered as it arrives.
- **4.2.2** Parse each line, dispatch by `type`: `thought` → send to chat (via
  `runTask` to hop back to the main thread), `shape` → expand and enqueue, `done` →
  signal stream completion, `error` → surface to the player. Malformed lines are logged
  and skipped, never fatal.

### 4.3 Verify progressive streaming

- **4.3.1** Run the backend, then `/mc2p test` in-game. Confirm the first shape starts
  building roughly one second in, while later shapes are still arriving — blocks must be
  appearing *before* the stream closes.
- **4.3.2** If everything appears at once at the end, the response is being buffered.
  Fix it at the server side before continuing; the entire latency strategy depends on
  per-line flushing. Commit once verified.

---

## 5. LLM generation layer

*Goal: real natural-language prompts produce real builds.*

### 5.1 Prompt construction

- **5.1.1** Write `backend/prompts.py` containing the system prompt per §8.5: full DSL
  definition, 2–3 complete worked examples, hard instruction of one JSON object per line
  with no markdown, the foundation→structure→detail ordering rule, speed-tier guidance,
  and the coordinate/size constraints.
- **5.1.2** Make the very first instruction explicit: the first emitted shape must be a
  large `instant` fill so something appears immediately, and the final shape must be
  `slow` so the build ends on a visible beat.

### 5.2 Streaming client and line buffer

- **5.2.1** Write `backend/llm.py` with a streaming call to the chosen provider. Maintain
  a character buffer over the token stream; on each `\n`, strip any stray markdown fence,
  attempt `json.loads`, and on success yield the object immediately. On failure, log and
  discard that line only.
- **5.2.2** Add a 12-second timeout on time-to-first-token. Wire `/build` to use this in
  place of the hardcoded generator from 4.1.2, keeping the hardcoded path available
  behind a `?mock=1` query flag for offline debugging.

### 5.3 Model bake-off

- **5.3.1** Write a throwaway script that sends the same three prompts (a cottage, a
  tower, a bridge) to each candidate model — the fast tiers, e.g. Claude Haiku 4.5 and
  Google's current Flash-Lite — recording time-to-first-shape, total time, count of
  invalid lines, and a subjective quality note.
- **5.3.2** Pick the winner on these results and hardcode it. **Timebox this to 15
  minutes.** The goal is to avoid a latency surprise on stage, not to run a benchmark.

### 5.4 Iterate on build quality

- **5.4.1** Run 5–6 varied prompts in-game. Note the common failure patterns — floating
  blocks, no doorway, wrong scale, roof clipping through walls.
- **5.4.2** Fix these in the **system prompt examples**, not in code. One good worked
  example fixes more failure modes than any amount of output post-processing. Commit.

---

## 6. End-to-end integration and hardening

*Goal: the full loop is reliable and cannot crash the server.*

### 6.1 Session lifecycle

- **6.1.1** Ensure a second `/mc2p` from the same player cancels the in-flight session
  cleanly: stop the HTTP read, cancel the repeating task, clear the queue.
- **6.1.2** Handle plugin disable and player disconnect mid-build — cancel all tasks in
  `onDisable` so a reload does not leave orphaned schedulers running.

### 6.2 Failure path testing

- **6.2.1** Deliberately break things and confirm each degrades gracefully: kill the
  backend mid-build, return malformed JSON, return a hallucinated block name, and send a
  prompt that produces an oversized build. In every case the player must see a clear
  chat message and the server must stay up.
- **6.2.2** Confirm no `Asynchronous block modify` exceptions appear anywhere in the log
  under any of the above. If one does, a world call has escaped the main thread — find
  and fix it now.

### 6.3 Latency measurement

- **6.3.1** Instrument and log the two numbers that matter: milliseconds from command to
  first block placed, and total build duration. Print both to the console on completion.
- **6.3.2** Run ten prompts and check the first-block figure against the ≤8s target. If
  it is over, the lever is prompt length and forcing a smaller, simpler first shape —
  not a different model.

---

## 7. Demo polish

*Goal: the build is satisfying to watch. Cut this step before cutting step 8.*

### 7.1 Audio and particles

- **7.1.1** Add a placement sound on `slow` and `fast` placements, throttled to at most
  ~4 per tick. This is the single highest-impact polish item — it is what makes the
  build feel alive rather than pasted.
- **7.1.2** Add block-break particles at each placement.

### 7.2 Visual legibility

- **7.2.1** Implement ghost-then-swap for `slow` placements: set a coloured concrete
  block first, swap to the real blockdata two ticks later. This reuses the colour-overlay
  language from Git for Minecraft and makes individual placements readable on camera.
- **7.2.2** Add an action-bar progress readout (`⛏ 142 / 412 blocks`) updated each tick.

### 7.3 Chat presentation

- **7.3.1** Format `thought` messages with a consistent coloured prefix so the agent's
  narration reads as a distinct voice rather than as ordinary chat.
- **7.3.2** Send a formatted completion message with block count and elapsed time.

---

## 8. Resilience, caching and rehearsal

*Goal: the demo cannot fail. This step is not optional.*

### 8.1 Write-through cache

- **8.1.1** In `/build`, tee every line of a successful stream to
  `cache/<slugified-prompt>.ndjson` as it is sent. Only finalise the file if the stream
  reached `done`, so partial failures never poison the cache.
- **8.1.2** Add `POST /build/cached/{name}` that replays a cached file with the same
  pacing (small sleeps between lines), and a `/mc2p cached <name>` subcommand in the
  plugin to trigger it.

### 8.2 Automatic fallback

- **8.2.1** Wrap the LLM call so that on timeout or exception the backend silently falls
  back to the nearest cached build rather than returning an error. The player sees a
  build appear, not a failure message.
- **8.2.2** Test this by disconnecting from the network entirely and running `/mc2p`.
  A build must still appear. This is the scenario that lost the last demo — verify it
  directly.

### 8.3 Pre-seed and rehearse

- **8.3.1** Run the 4–5 prompts you intend to demo. Confirm each produces a good-looking
  build; re-run any that disappoint. They are now cached and guaranteed to work.
- **8.3.2** Do a full timed dry run: join the server, run the command, talk over the
  build as it goes. Check that the build finishes inside your speaking window and that
  the camera angle shows the structure well. Adjust speed tiers if the pacing fights
  your narration.

### 8.4 Demo-day checklist

- **8.4.1** Write a `RUNBOOK.md` with the exact startup sequence: start backend, verify
  `/health`, start Paper server, join client, fly to a clear area, run a warm-up build
  off camera. Include the fallback command to type if something looks wrong.
- **8.4.2** Confirm the API key is present in the environment, the backend is on
  localhost, screen recording is configured, and you have a cached build ready as the
  break-glass option. Commit everything.
