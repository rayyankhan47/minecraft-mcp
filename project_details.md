# MC MCP — Project Details

> Spec document for coding agents. Read this in full before writing any code.
> The companion file `project_plan.md` contains the ordered build steps.

---

## 1. What we are building

A natural-language-to-Minecraft-build system. A player types a command in Minecraft chat:

```
/mc2p a small medieval cottage with a stone chimney
```

An AI agent interprets the request and the build materialises in the world in front of
the player, block by block, in a staged and visually deliberate way.

Two components:

1. **A Paper server plugin (Java)** — registers the `/mc2p` command, talks to the
   backend, and executes block placements on a paced scheduler.
2. **A backend service (Python/FastAPI)** — takes the natural-language prompt, calls an
   LLM, and streams back build instructions as newline-delimited JSON.

**The name "MC MCP" is branding only.** It is not an implementation of the Model Context
Protocol. Do not add MCP protocol code unless explicitly instructed.

---

## 2. Success criteria

This is a hackathon demo. Optimise for these, in priority order:

| # | Criterion | Target |
|---|-----------|--------|
| 1 | Time from command to **first block appearing** | ≤ 8s, ideally 4–6s |
| 2 | Never hard-fails in front of an audience | Always produces *something* |
| 3 | Total build completes | ≤ 25s for a typical structure |
| 4 | Build is recognisably what was asked for | "That's clearly a cottage" |
| 5 | The placement animation looks intentional and cool | See §7 |

Criterion 1 and 2 outrank build quality. A fast, beautiful, slightly-wrong house beats a
perfect house that takes 40 seconds.

---

## 3. Hard constraints

- **Development time: a few hours.** Prefer boring, well-trodden APIs over clever ones.
- **Dev machine: MacBook Pro M3 (arm64).** All tooling must be arm64-native.
- **The plugin ↔ backend link must be localhost.** Never depend on venue wifi for it.
  Only the backend ↔ LLM API hop touches the internet.
- **The plugin must have zero third-party dependencies.** Everything needed is in the
  Paper API plus the Java standard library. No shading, no relocation, no dependency
  resolution at runtime.

---

## 4. Environment and versions

### 4.1 Minecraft and Java — READ THIS CAREFULLY

Mojang changed versioning in 2026: versions are now year-based (`26.1`, `26.2`) instead
of `1.x.x`. The Java requirement changed with it.

| Minecraft line | Required Java |
|----------------|---------------|
| 1.20.5 – 1.21.x | **Java 21** |
| 26.1 and later | Java 25 |

**We target Minecraft 1.21.x on Java 21.** Do NOT use 26.x.

Rationale:
- Java 21 is the widely-installed LTS; Java 25 is an extra install and an extra failure mode.
- A 26.x server jar on Java 21 fails immediately with `unsupported class file major version`.
- The 1.21 Bukkit/Paper API surface is far better represented in coding-agent training
  data than anything newer. Fewer hallucinated method names.
- 26.x's advantage is ongoing security patching, which is irrelevant for a localhost demo.

Pin **one exact 1.21.x patch version** and use it consistently for the server jar, the
`paper-api` Gradle dependency, and the Minecraft launcher profile. Mismatched client and
server versions will refuse to connect.

Resolve the exact available build from Paper's downloads API rather than hardcoding a
build number that may not exist.

### 4.2 Toolchain

- **JDK 21** (Temurin, arm64). Verify with `java -version` → `21.x`.
- **Gradle** with the Kotlin DSL, Java toolchain pinned to 21.
- **Python 3.11+** with FastAPI + uvicorn for the backend.
- **Minecraft Java Edition client**, launcher profile set to the matching 1.21.x version.

### 4.3 Server configuration

Download the Paper jar for the pinned version into `server/`. First run generates
`eula.txt` — set `eula=true`.

`server.properties` — these values are not cosmetic, several prevent demo-time failures:

```properties
online-mode=false
gamemode=creative
difficulty=peaceful
level-type=minecraft:flat
allow-flight=true
spawn-protection=0
view-distance=16
simulation-distance=8
max-tick-time=-1
motd=MC MCP
```

- `max-tick-time=-1` — **critical.** Disables Paper's watchdog. Heavy block placement can
  exceed the default tick-time threshold and the watchdog will kill the server mid-demo.
- `level-type=minecraft:flat` — clean canvas, builds read clearly on camera.
- `allow-flight=true` — you need to fly for camera angles during the demo.
- `online-mode=false` — removes auth as a dependency on venue network conditions.

---

## 5. Architecture

```
┌──────────────────────┐
│  Minecraft client    │   player types /mc2p <prompt>
│  (vanilla 1.21.x)    │
└──────────┬───────────┘
           │ (normal MC protocol)
┌──────────▼───────────────────────────────────────────────┐
│  Paper server 1.21.x  +  mcmcp plugin                    │
│                                                          │
│   McmcpCommand ──► BuildSession (async thread)           │
│        │                │                                │
│        │                │ HTTP POST, streaming NDJSON    │
│        │                ▼                                │
│        │          [ backend ]                            │
│        │                │                                │
│        │                ▼ one shape per line, as ready   │
│        │          ShapeExpander  → BlockPlacement list   │
│        │                │                                │
│        │                ▼                                │
│        │        ConcurrentLinkedQueue<BlockPlacement>    │
│        │                │                                │
│        └────────► PlacementEngine (sync repeating task,  │
│                   every 1 tick, drains with a budget)    │
│                            │                             │
│                            ▼                             │
│                     world.setBlockData(...)              │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  Backend (FastAPI, localhost:8000)                       │
│                                                          │
│   POST /build ──► prompt assembly ──► LLM (streaming)    │
│                          │                               │
│                          ▼                               │
│                   line buffer ──► validate ──► forward   │
│                          │                               │
│                          └──► write-through disk cache   │
│                                                          │
│   POST /build/cached/{name}   replay from disk           │
│   GET  /health                                           │
└──────────────────────────────────────────────────────────┘
```

### 5.1 Threading model — non-negotiable

Bukkit is **not** thread-safe. Violating this causes crashes or silent corruption.

- **All world mutation must happen on the main server thread.** Use
  `Bukkit.getScheduler().runTaskTimer(...)`.
- **All network I/O must happen off the main thread.** Use
  `Bukkit.getScheduler().runTaskAsynchronously(...)`.
- The only thing crossing between them is a `ConcurrentLinkedQueue` of plain data
  objects. Never pass `Block`, `World`, or `Player` handles across threads; pass
  coordinates and strings.

---

## 6. The Build DSL

The LLM emits **shape primitives**, not individual blocks. This is the single most
important design decision in the project: a 10×10 floor is one shape object instead of
one hundred, which is what makes the latency budget achievable.

### 6.1 Wire format

Newline-delimited JSON (NDJSON). One complete JSON object per line. No markdown fences,
no prose, no wrapping array.

### 6.2 Message types

**`thought`** — surfaced in Minecraft chat while the build proceeds.
```json
{"type":"thought","text":"Laying a cobblestone foundation"}
```

**`shape`** — a build primitive.
```json
{"type":"shape","phase":"foundation","speed":"instant","op":"fill",
 "from":[0,0,0],"to":[9,0,9],"block":"minecraft:cobblestone"}
```

**`done`** — terminal message.
```json
{"type":"done","summary":"Medieval cottage, 412 blocks"}
```

**`error`** — backend-side failure, surfaced to the player.
```json
{"type":"error","message":"generation failed"}
```

### 6.3 Shape operations

All coordinates are **integers, relative to the build origin** (see §6.5).

| `op` | Fields | Meaning |
|------|--------|---------|
| `fill` | `from`, `to`, `block` | Solid cuboid between two corners, inclusive |
| `hollow` | `from`, `to`, `block` | Cuboid shell — walls, floor and ceiling, hollow interior |
| `walls` | `from`, `to`, `block` | Four vertical walls only, no floor or ceiling |
| `line` | `from`, `to`, `block` | 3D line between two points |
| `set` | `pos`, `block` | Single block |
| `sphere` | `center`, `radius`, `block`, `hollow` | Sphere |
| `cylinder` | `center`, `radius`, `height`, `block`, `hollow` | Vertical cylinder |

Optional on every shape:
- `"replace": "minecraft:air"` — only overwrite blocks matching this. Used for carving.
- To carve a door or window, emit a `fill` with `"block":"minecraft:air"`.

### 6.4 The `block` field

A full Minecraft blockstate string, e.g.:
- `"minecraft:oak_planks"`
- `"minecraft:oak_stairs[facing=north,half=bottom]"`
- `"minecraft:oak_log[axis=y]"`

Parse with `Bukkit.createBlockData(String)`, which accepts this syntax directly. This
gives rotated stairs and logs for free with no extra schema.

**Resolution order in the plugin (must be defensive — the model will occasionally
hallucinate a block name):**
1. `Bukkit.createBlockData(str)` → use it.
2. On exception, strip the `[...]` state and retry.
3. On exception, `Material.matchMaterial(str)` → `getMaterial().createBlockData()`.
4. On failure, log once and substitute `minecraft:stone`. **Never throw.** A wrong block
   is a blemish; an exception is a dead demo.

### 6.5 Coordinates and origin

The model works in a local coordinate space where:
- `[0,0,0]` is the build origin
- `+Y` is up
- `+X` is right, `+Z` is forward (away from the player)

The plugin resolves the origin at command time: the player's location, rounded to block
coordinates, offset 3 blocks in the direction they are facing so the build does not
spawn on top of them.

**Optional (only if time allows):** rotate the relative coordinates by the player's yaw
so the build always faces them. Snap yaw to the nearest cardinal direction and apply a
90° rotation matrix to the X/Z components. Skip this if behind schedule — spawning the
build in a fixed orientation is perfectly demoable.

### 6.6 Safety limits (enforced plugin-side, not trusted from the model)

- Max **8000 blocks** per build. Truncate and warn beyond that.
- Clamp absolute Y to the range `-64 … 319`.
- Reject any shape whose bounding box exceeds 64 blocks on any axis.
- Cap concurrent builds at **1 per player**. A second `/mc2p` cancels the first.

---

## 7. The placement engine

This is what makes the demo feel good. It is worth real attention.

### 7.1 Speed tiers

A uniform 0.3s per block means a 400-block build takes two minutes. Instead, each shape
carries a `speed` hint and the engine drains the queue with a per-tick budget:

| `speed` | Blocks per tick | Feel | Use for |
|---------|----------------|------|---------|
| `instant` | 120 | Materialises at once | Foundations, floors, bulk fill |
| `fast` | 6 | Visible rapid assembly | Walls, structure |
| `slow` | 1 every 6 ticks (0.3s) | Deliberate, one at a time | Roofline, chimney, detail, final flourish |

The model chooses the tier per shape. Bias the system prompt toward `instant` for bulk
and `slow` for the **last** shape in the build, so the sequence ends on a visible beat
rather than trailing off.

### 7.2 Ordering within a shape

Expand shapes bottom-up: sort the block list by ascending Y, and within a Y layer by
distance from the shape's centre. This reads as *construction* rather than as a texture
being pasted in.

### 7.3 Implementation

One repeating task scheduled every **1 tick** (`runTaskTimer(plugin, task, 1L, 1L)`).
Each invocation:
1. Read a tick budget from the head element's speed tier.
2. Pop and place up to that many blocks.
3. Fire the visual/audio effects (§7.4).
4. Update the action bar.
5. If the queue is empty and the stream has closed, finish and cancel the task.

20 ticks = 1 second. A `slow` placement is a counter that only places on every 6th tick.

### 7.4 Polish — high value, low cost

- **Placement sound.** `world.playSound(loc, Sound.BLOCK_STONE_PLACE, 0.6f, 1.0f)` on
  each `slow` and `fast` placement. Throttle to at most ~4 per tick or it becomes noise.
  This has an outsized effect on how alive the build feels.
- **Break particles.** `world.spawnParticle(Particle.BLOCK, loc, 8, blockData)`.
- **Ghost-then-swap.** For `slow` placements, first set the block to a coloured
  concrete, then swap to the real blockdata 2 ticks later. Reuses the colour-overlay
  idea from Git for Minecraft and makes each placement legible on camera.
- **Action bar progress.** `player.sendActionBar("⛏ 142 / 412 blocks")`.
- **Thoughts in chat.** Print each `thought` message as it arrives, prefixed and
  coloured, so there is something happening during the generation wait.

---

## 8. The backend

### 8.1 Stack

FastAPI + uvicorn on `127.0.0.1:8000`. Single file is fine. Use
`StreamingResponse` with `media_type="application/x-ndjson"`.

### 8.2 Endpoints

**`POST /build`**
```json
{"prompt": "a small medieval cottage", "player": "Rayyan"}
```
Returns a streaming NDJSON body. Each line is a message from §6.2.

**`POST /build/cached/{name}`** — replays a saved NDJSON file from `cache/` with
realistic pacing. This is demo insurance; see §9.

**`GET /health`** — returns `{"ok": true}`. The plugin pings this on enable to warm the
connection.

### 8.3 Streaming behaviour

Critical: **flush each line the instant it is complete.** Do not buffer the whole
generation. Disable any response buffering. The entire latency strategy depends on the
first `shape` line reaching the plugin while the model is still writing the rest.

Maintain a line buffer over the model's token stream: accumulate characters, and on each
`\n`, attempt to parse the accumulated text as JSON.
- Parses and validates → forward it downstream immediately.
- Fails to parse → **drop it, log it, continue.** Never abort the stream for one bad line.

Strip markdown code fences defensively — models sometimes wrap output in ```json despite
instructions.

### 8.4 Model selection

Requirements: fast time-to-first-token, reliable structured output, good spatial
reasoning. This points at the small/fast tier — Claude Haiku 4.5 or Google's current
Flash-Lite tier are both built for exactly this profile.

**Do not pick on reputation. Run a bake-off.** Build a tiny script that fires the same
three prompts at each candidate and records:
- time to first complete `shape` line
- total generation time
- how many lines failed validation
- subjective quality of the resulting build

Pick the winner on *your* prompts. The last demo was lost to a latency surprise;
measuring is cheap insurance. Budget 15 minutes for this, no more.

Set a **hard client-side timeout of 12 seconds** on the first token. If it fires, fall
back to cache (§9).

### 8.5 System prompt requirements

The system prompt must:
- Define the full DSL from §6, with 2–3 worked examples of complete small builds.
- State emphatically: one JSON object per line, nothing else, no markdown, no commentary.
- Instruct ordering: **foundation first, then structure, then detail.** The first shape
  emitted should always be a large `instant` fill so something appears immediately.
- Instruct speed-tier assignment per §7.1, ending on a `slow` shape.
- Constrain to the coordinate space and size limits from §6.5–6.6.
- Require `thought` lines interleaved between shapes, phrased as an agent narrating its
  own work.

Put the examples in the system prompt, not the user turn — they are cacheable and they
dominate output reliability.

---

## 9. Demo insurance

The previous iteration of this project lost on a live latency failure. Treat resilience
as a feature, not a nicety.

1. **Write-through cache.** Every successful `/build` writes its full NDJSON stream to
   `cache/<slug>.ndjson`. Builds are replayable forever after.
2. **Automatic fallback.** If the LLM call errors or exceeds the timeout, the backend
   silently serves the nearest cached build instead of failing. The player sees a build,
   not a stack trace.
3. **Manual replay.** `/mc2p cached cottage` plays a known-good stream. This is the
   break-glass path if the network is unusable.
4. **Pre-seed the cache.** Before demoing, run 4–5 prompts you intend to show and
   confirm each produces a good build. They are now cached and guaranteed.
5. **Localhost only** for plugin ↔ backend. Venue wifi cannot break that hop.

---

## 10. Repository layout

```
mcmcp/
├── plugin/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/
│       ├── java/com/mcmcp/
│       │   ├── McmcpPlugin.java        # JavaPlugin entry, onEnable/onDisable
│       │   ├── McmcpCommand.java       # /mc2p handler
│       │   ├── BuildSession.java       # async HTTP + NDJSON line reader
│       │   ├── ShapeExpander.java      # DSL → ordered BlockPlacement list
│       │   ├── PlacementEngine.java    # sync repeating task, budget draining
│       │   ├── BlockResolver.java      # defensive blockstate parsing
│       │   └── model/
│       │       ├── Shape.java
│       │       └── BlockPlacement.java
│       └── resources/
│           └── plugin.yml
├── backend/
│   ├── main.py                         # FastAPI app
│   ├── llm.py                          # provider clients + streaming
│   ├── prompts.py                      # system prompt + examples
│   ├── cache/                          # pre-seeded .ndjson builds
│   └── requirements.txt
└── server/                             # Paper server, gitignored
    ├── paper-1.21.x.jar
    ├── eula.txt
    ├── server.properties
    └── plugins/mcmcp.jar
```

### 10.1 Plugin build config

- `compileOnly("io.papermc.paper:paper-api:1.21.x-R0.1-SNAPSHOT")` from the PaperMC
  repository at `https://repo.papermc.io/repository/maven-public/`.
- Java toolchain 21, source and target compatibility 21.
- **No shadow/shade plugin.** There are no runtime dependencies to bundle.

### 10.2 plugin.yml

```yaml
name: mcmcp
version: 1.0.0
main: com.mcmcp.McmcpPlugin
api-version: '1.21'
commands:
  mc2p:
    description: Build something with AI
    usage: /mc2p <description>
```

Register the command in `plugin.yml`, not via Brigadier. Simpler, and it is what agents
reliably get right.

---

## 11. Explicitly out of scope

Do not build these unless the core loop is finished and rehearsed:

- Any actual Model Context Protocol implementation
- Multi-agent orchestration (one agent, one context, streaming — this is deliberate)
- A web dashboard or any UI outside Minecraft
- Persistence beyond the NDJSON file cache
- Undo/redo, build history, or world diffing
- Authentication, multi-tenancy, rate limiting
- Client-side mod code of any kind
- Mineflayer-style walking bot characters — blocks are placed directly by the server

---

## 12. Known failure modes and their fixes

| Symptom | Cause | Fix |
|---------|-------|-----|
| `unsupported class file major version` | Java/MC version mismatch | Confirm Java 21 and a 1.21.x jar |
| Server dies mid-build, "server thread dump" in logs | Watchdog tripped | `max-tick-time=-1` |
| Sand falls, torches pop, water spreads | Physics applied | `setType(mat, false)` / `setBlockData(data, false)` |
| `IllegalStateException: Asynchronous block modify` | World edit off main thread | Route all placement through the sync task |
| Blocks appear in a solid instant lump | Queue drained without budget | Enforce per-tick budget in the engine |
| First block takes 20s | Response buffered | Flush per line; confirm no buffering in the stack |
| Occasional missing blocks | Hallucinated block name threw | `BlockResolver` fallback chain, §6.4 |
| Client cannot connect | Launcher profile version ≠ server | Match them exactly |

---

## 13. Guidance for coding agents

- **Build the pipeline end-to-end with a hardcoded shape list before touching the LLM.**
  A working `/mc2p` that always builds the same stone box is a far better checkpoint than
  a half-finished LLM integration. It also isolates whether a later bug is in the model
  layer or the placement layer.
- Prefer explicit, verbose Java over clever abstraction. This code is read once, under
  time pressure, by a human who needs to debug it fast.
- Wrap every block placement in a try/catch. One bad block must never stop a build.
- Log to the server console generously — it is the only debugging surface during a demo.
- Do not refactor working code. Ship, then polish only what is visible on camera.
