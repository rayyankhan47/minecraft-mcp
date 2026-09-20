# RUNBOOK — demo day

Everything on this page assumes you are in the repo root. Work top to bottom.

---

## T-30 minutes — preflight

```bash
./scripts/doctor.sh
```

All required checks must be green. Then confirm the API key is present:

```bash
grep -q 'ANTHROPIC_API_KEY=sk-' backend/.env && echo "key present" || echo "NO KEY"
```

Confirm what is guaranteed to work with no network at all:

```bash
curl -s http://127.0.0.1:8000/cache
```

If that list is empty or stale, re-seed it — **this is the single most important step
on this page**:

```bash
./scripts/seed-cache.sh
```

Every prompt it reports with a ✓ is now replayable forever, with no model, no key and
no network.

---

## T-10 minutes — bring it up

Three terminal tabs. Leave all three running.

**Tab 1 — backend**

```bash
./scripts/run-backend.sh
```

Do **not** pass `--reload`. An edit at the wrong moment drops the stream.

Verify:

```bash
curl -s http://127.0.0.1:8000/health
```

Expect `{"ok":true}`.

**Tab 2 — Minecraft server**

```bash
./scripts/run-server.sh
```

Wait for `Done (…)`. You should see the mcmcp banner and `backend is up: {"ok":true}`.
If it says the backend is NOT reachable, Tab 1 is not up.

**Tab 3 — spare**, for `./scripts/mc.sh` and log tailing.

**Client**

Launcher profile must be exactly **1.21.11**. Multiplayer → Direct Connection →
`localhost`.

---

## T-2 minutes — warm up OFF camera

Do this every time. It warms the HTTP connection, fills the prompt cache, and proves
the whole chain works before anyone is watching.

1. Fly somewhere clear, away from where you will demo.
2. Run one build: `/mc2p a small shed`
3. Watch it complete. Check the server console prints `first block placed … ms`.
4. Fly to your demo spot, facing an empty area with the sun behind you.
5. `/time set day` and `/weather clear`.

The first model call of a session pays for the prompt cache write. Do not let that be
the one on camera.

---

## Running the demo

Stand still, face empty ground, and type:

```
/mc2p a small medieval cottage with a stone chimney
```

The build appears about 3 blocks in front of you, in the direction you are facing.

**Talk over it.** The foundation lands almost immediately, walls assemble over a couple
of seconds, and it ends on the chimney going up one block at a time — that last part is
your closing beat, so save a sentence for it.

Good prompts, in rough order of reliability:

- `a small medieval cottage with a stone chimney`
- `a stone watchtower`
- `a japanese pagoda`
- `a tiny lighthouse on a rock`

---

## If something looks wrong

**Type this. Do not debug on stage.**

| Symptom | Command |
|---|---|
| Nothing happens after ~8s | `/mc2p cached cottage` |
| Backend is down or unreachable | `/mc2p local` |
| Build is ugly or half-formed | `/mc2p cached <name>` — a known-good one |
| Wrong spot / in the way | Fly elsewhere and run it again; a second `/mc2p` cancels the first |

`/mc2p local` needs **nothing** but the plugin — no backend, no key, no network. It is
the last line of defence and it cannot fail.

To see what is cached: `curl -s http://127.0.0.1:8000/cache`

---

## Why this should hold

Each of these is verified by a script in `scripts/`, not by hope:

- The model being unreachable **still produces a build** — the backend silently falls
  back to the nearest cached one and the player never sees an error
  (`verify-resilience.sh`)
- Malformed JSON, invented block names and oversized builds all degrade rather than
  fail (`verify-failures.sh`)
- Blocks appear while the response is still streaming (`verify-stream.sh`)
- `max-tick-time=-1` means the watchdog cannot kill the server mid-build
- The plugin↔backend hop is localhost, so venue wifi cannot touch it

---

## Teardown

Type `stop` in Tab 2. `Ctrl-C` in Tab 1.
