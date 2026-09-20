# MC MCP

Natural language to Minecraft builds. Type a description in chat, watch the structure
assemble itself in front of you, block by block.

```
/mc2p a small medieval cottage with a stone chimney
```

> The name is branding. This is **not** an implementation of the Model Context Protocol.

## How it works

A Paper plugin sends the prompt to a local FastAPI backend, which streams an LLM's
response back as newline-delimited JSON. The model emits **shape primitives** — a
10×10 floor is one `fill` object, not a hundred block placements — which is what makes
the sub-8-second time-to-first-block achievable. The plugin expands each shape as it
arrives and drains the resulting blocks through a tick-budgeted scheduler, so the build
paces itself: foundations snap in instantly, walls assemble visibly, the roofline
places one block at a time.

```
Minecraft client
      │  /mc2p <prompt>
      ▼
Paper 1.21.11  +  mcmcp plugin
      │  async HTTP, streaming NDJSON          ┌──────────────────┐
      ├───────────────────────────────────────►│ backend :8000    │
      │  one shape per line, as it is ready    │  → LLM (stream)  │
      ◄────────────────────────────────────────┤  → disk cache    │
      │                                        └──────────────────┘
      ▼
ShapeExpander → ConcurrentLinkedQueue → PlacementEngine (1 tick, budgeted)
                                              │
                                              ▼  main thread only
                                        world.setBlockData(…)
```

The one rule the whole design bends around: **Bukkit is not thread-safe.** All world
mutation happens on the main server thread, all network I/O happens off it, and the
only thing crossing between them is a queue of plain coordinates and strings.

## Getting started

See **[SETUP.md](SETUP.md)** for the terminal commands and the Minecraft client setup.

```bash
./scripts/doctor.sh          # verify the toolchain
./scripts/setup-server.sh    # download + configure Paper
./scripts/run-server.sh      # start the server
```

## Layout

| Path | What |
|---|---|
| `plugin/` | Paper plugin, Java 21, zero runtime dependencies |
| `backend/` | FastAPI service, streams NDJSON |
| `backend/cache/` | Pre-seeded builds — replayable with no network |
| `config/` | Canonical `server.properties` (the live one is gitignored) |
| `scripts/` | Setup, run and deploy helpers |
| `server/` | Paper install — gitignored, rebuilt by `setup-server.sh` |
| `VERSION` | The pinned Minecraft version. Everything reads from it. |

## Versions

Minecraft **1.21.11** on **Java 21**. Not negotiable in either direction: 26.x requires
Java 25, and your launcher profile must match the server exactly or it will refuse to
connect. See the comments in [`VERSION`](VERSION).

## Docs

- [`project_details.md`](project_details.md) — the full specification
- [`project_plan.md`](project_plan.md) — the ordered build plan
- [`SETUP.md`](SETUP.md) — what to run and keep running
