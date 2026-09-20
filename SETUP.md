# SETUP — what to run, and what to keep running

Everything here is localhost. Nothing depends on venue wifi except the backend's call
out to the LLM API.

> **This file grows as the project does.** Sections marked _(not built yet)_ are
> placeholders for steps still ahead in `project_plan.md`.

---

## 0. One-time, already done

You do not need to re-run these, but they are safe and idempotent if you ever do.

```bash
brew install openjdk@21 gradle
```

```bash
./scripts/setup-server.sh
```

`setup-server.sh` downloads Paper **1.21.11 build 132** into `server/`, verifies its
SHA-256, accepts the EULA, and installs `config/server.properties`. It refuses to run
against a 26.x version, which would need Java 25.

Sanity-check the whole toolchain at any time:

```bash
./scripts/doctor.sh
```

---

## 1. Terminal tabs to keep open

You want **two** tabs open while working, eventually three.

### Tab 1 — Paper server _(keep running)_

```bash
./scripts/run-server.sh
```

Leave it up. It prints the plugin's log output, which is your main debugging surface.
Type `stop` and press Enter for a clean shutdown; `Ctrl-C` also works.

Restart this whenever you rebuild the plugin.

### Tab 2 — Backend _(not built yet — step 4)_

Will be `./scripts/run-backend.sh`, serving `127.0.0.1:8000`.

### Tab 3 — Deploy loop

Builds the plugin jar and copies it to `server/plugins/mcmcp.jar`:

```bash
./scripts/deploy.sh
```

Then restart Tab 1 so the server picks it up. Or do both at once — this stops the
server, rebuilds, restarts it, and waits until it is genuinely ready:

```bash
./scripts/deploy.sh --restart
```

`--restart` detaches the server from Tab 1, so afterwards follow the log with:

```bash
tail -f server/logs/latest.log
```

---

## 2. Connecting the Minecraft client

**The launcher profile version must be exactly `1.21.11`.** Anything else — including
26.x — will be refused by the server with a version-mismatch message.

1. Open the Minecraft launcher.
2. **Installations → New installation**, set version to **release 1.21.11**, save,
   and launch it.
3. **Multiplayer → Direct Connection**, server address: `localhost`
4. Join.

`online-mode=false`, so it will not check your Mojang account. If Multiplayer is
greyed out, the launcher is still on a different version.

### What you should see on joining

- A **superflat** world — flat grass to the horizon, no hills or trees
- **Creative** mode (`force-gamemode=true` puts you there every join)
- **Flight works** — double-tap space

If any of those three are wrong, stop the server and tell me.

---

## 3. Handy in-game commands

```
/gamemode creative
/time set day
/weather clear
/kill @e[type=item]
```

---

## 4. Troubleshooting

| Symptom | Fix |
|---|---|
| `java: command not found` | Expected — JDK 21 is keg-only Homebrew. The scripts set `JAVA_HOME` themselves; always go through `scripts/`. |
| `unsupported class file major version` | A 26.x jar is being run on Java 21. Check `VERSION` says `1.21.11`. |
| Client can't connect, version mismatch | Launcher profile is not 1.21.11. |
| World is hilly, not flat | The world predates the flat setting. `rm -rf server/world*` and restart. |
| Server dies mid-build, "server thread dump" | `max-tick-time=-1` missing from `server/server.properties`. Re-run `scripts/setup-server.sh`. |
