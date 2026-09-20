#!/usr/bin/env python3
"""Static audit of the worked examples inside the system prompt (plan step 5.4).

The worked examples are not documentation — they are the strongest signal the model
gets about what a good build looks like. Whatever is wrong in them gets copied into
every build. So they are treated as code and tested like code.

The expander here mirrors plugin/src/main/java/com/mcmcp/ShapeExpander.java exactly,
including the (r + 0.5)^2 radius test and the rule that a hollow cylinder keeps its
floor and cap. If that file changes, this one has to change with it.
"""

from __future__ import annotations

import json
import re
import sys
from collections import deque
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))
import llm  # noqa: E402
from prompts import SYSTEM_PROMPT  # noqa: E402

AIR = {"minecraft:air", "air", "minecraft:cave_air", "cave_air"}

# Blocks that need something to attach to. Value is which neighbours can support them.
NEEDS_SUPPORT = {
    "lantern": "below_or_above",
    "torch": "below",
    "soul_torch": "below",
    "wall_torch": "side",
    "ladder": "side",
    "vine": "side",
    "sign": "below",
    "rail": "below",
    "carpet": "below",
    "flower_pot": "below",
    "pressure_plate": "below",
    "button": "side_or_below_or_above",
    "snow": "below",
    "sapling": "below",
    "candle": "below",
}

MAX_AXIS = 64
MAX_BLOCKS = 3000
SHAPE_RANGE = (12, 20)


def bare(block: str) -> str:
    b = block.split("[", 1)[0].strip()
    return b[len("minecraft:"):] if b.startswith("minecraft:") else b


def support_kind(block: str) -> str | None:
    name = bare(block)
    for suffix, kind in NEEDS_SUPPORT.items():
        if name == suffix or name.endswith("_" + suffix):
            return kind
    return None


# ------------------------------------------------------------------ the expander


def expand(shape: dict) -> list[tuple[int, int, int]]:
    op = shape["op"]
    out: list[tuple[int, int, int]] = []

    if op in ("fill", "hollow", "walls"):
        f, t = shape["from"], shape["to"]
        x1, x2 = sorted((int(f[0]), int(t[0])))
        y1, y2 = sorted((int(f[1]), int(t[1])))
        z1, z2 = sorted((int(f[2]), int(t[2])))
        for y in range(y1, y2 + 1):
            for x in range(x1, x2 + 1):
                for z in range(z1, z2 + 1):
                    xe, ze, ye = x in (x1, x2), z in (z1, z2), y in (y1, y2)
                    keep = True if op == "fill" else (xe or ze or ye) if op == "hollow" else (xe or ze)
                    if keep:
                        out.append((x, y, z))

    elif op == "set":
        p = shape["pos"]
        out.append((int(p[0]), int(p[1]), int(p[2])))

    elif op == "line":
        f, t = shape["from"], shape["to"]
        steps = max(abs(int(t[i]) - int(f[i])) for i in range(3)) or 1
        for s in range(steps + 1):
            out.append(tuple(round(int(f[i]) + (int(t[i]) - int(f[i])) * s / steps) for i in range(3)))

    elif op in ("sphere", "cylinder"):
        c, r = shape["center"], int(shape["radius"])
        outer, inner = (r + 0.5) ** 2, (r - 0.5) ** 2
        hollow = bool(shape.get("hollow"))
        if op == "sphere":
            for dy in range(-r, r + 1):
                for dx in range(-r, r + 1):
                    for dz in range(-r, r + 1):
                        d2 = dx * dx + dy * dy + dz * dz
                        if d2 > outer or (hollow and d2 < inner):
                            continue
                        out.append((c[0] + dx, c[1] + dy, c[2] + dz))
        else:
            h = max(1, int(shape.get("height", 1)))
            for y in range(h):
                for dx in range(-r, r + 1):
                    for dz in range(-r, r + 1):
                        d2 = dx * dx + dz * dz
                        if d2 > outer:
                            continue
                        if hollow and d2 < inner and y not in (0, h - 1):
                            continue
                        out.append((c[0] + dx, c[1] + y, c[2] + dz))

    return out


def extent(shape: dict) -> tuple[int, int, int]:
    cells = expand(shape)
    if not cells:
        return (0, 0, 0)
    xs, ys, zs = zip(*cells)
    return (max(xs) - min(xs) + 1, max(ys) - min(ys) + 1, max(zs) - min(zs) + 1)


# ------------------------------------------------------------------- extraction


def examples() -> list[tuple[str, list[dict]]]:
    out = []
    blocks = re.split(r"^# WORKED EXAMPLE .*$", SYSTEM_PROMPT, flags=re.M)[1:]
    for i, chunk in enumerate(blocks, 1):
        title = ""
        m = re.search(r"^User:\s*(.+)$", chunk, flags=re.M)
        if m:
            title = m.group(1).strip()
        msgs = []
        for raw in chunk.splitlines():
            raw = raw.strip()
            if raw.startswith('{"type"'):
                msgs.append(json.loads(raw))
        out.append((f"example {i}: {title}", msgs))
    return out


# ----------------------------------------------------------------------- checks

passed = failed = 0


def ok(msg: str) -> None:
    global passed
    passed += 1
    print(f"  \033[32m✓\033[0m {msg}")


def bad(msg: str) -> None:
    global failed
    failed += 1
    print(f"  \033[31m✗\033[0m {msg}")


def audit(title: str, msgs: list[dict]) -> None:
    print(f"\n── {title}")
    shapes = [m for m in msgs if m.get("type") == "shape"]

    invalid = [m for m in msgs if not llm.validate(m)]
    ok("every line passes validate()") if not invalid else bad(
        f"{len(invalid)} line(s) fail validate(): {invalid[:1]}")

    lo, hi = SHAPE_RANGE
    n = len(shapes)
    ok(f"{n} shapes (prompt asks for {lo}-{hi})") if lo <= n <= hi else bad(
        f"{n} shapes, prompt asks for {lo}-{hi} — the example contradicts the instruction")

    ok("ends with a done line") if msgs and msgs[-1].get("type") == "done" else bad("no trailing done line")

    if shapes:
        first, last = shapes[0], shapes[-1]
        ok("first shape is instant") if first.get("speed") == "instant" else bad(
            f"first shape is {first.get('speed')!r}, rule 5.1.2 says instant")
        ok("last shape is slow") if last.get("speed") == "slow" else bad(
            f"last shape is {last.get('speed')!r}, rule 5.1.2 says slow")

    oversize = [(s["op"], extent(s)) for s in shapes if max(extent(s)) > MAX_AXIS]
    ok(f"every shape within {MAX_AXIS} blocks per axis") if not oversize else bad(f"oversized: {oversize}")

    # ---- build the final voxel map, in order, with air carving
    world: dict[tuple[int, int, int], str] = {}
    for s in shapes:
        block = s["block"]
        for cell in expand(s):
            if bare(block) in {b.split(":")[-1] for b in AIR}:
                world.pop(cell, None)
            else:
                world[cell] = block

    total = len(world)
    ok(f"{total} blocks in the finished build") if total <= MAX_BLOCKS else bad(
        f"{total} blocks exceeds the {MAX_BLOCKS} the prompt promises")

    # ---- does it have a way in?
    ground = min(y for _, y, _ in world) if world else 0
    carves = [s for s in shapes if bare(s["block"]) == "air"]
    door = any(min(int(s["from"][1]), int(s["to"][1])) <= ground + 1
               for s in carves if s.get("op") in ("fill", "hollow", "walls") and s.get("from"))
    ok("cuts a doorway that reaches the floor") if door else bad(
        "no air carve reaches floor level — the build has no door")

    # ---- attachment blocks
    unsupported = []
    for (x, y, z), block in world.items():
        kind = support_kind(block)
        if kind is None:
            continue
        below = (x, y - 1, z) in world
        above = (x, y + 1, z) in world
        side = any((x + dx, y, z + dz) in world for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        held = {
            "below": below,
            "below_or_above": below or above,
            "side": side,
            "side_or_below_or_above": side or below or above,
        }[kind]
        if not held:
            unsupported.append(((x, y, z), bare(block)))
    ok("every attachment block has something to attach to") if not unsupported else bad(
        f"{len(unsupported)} unsupported: {unsupported}")

    # ---- floating masses
    seen: set[tuple[int, int, int]] = set()
    islands = []
    for start in world:
        if start in seen:
            continue
        comp, q = [], deque([start])
        seen.add(start)
        while q:
            cx, cy, cz = q.popleft()
            comp.append((cx, cy, cz))
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                nb = (cx + dx, cy + dy, cz + dz)
                if nb in world and nb not in seen:
                    seen.add(nb)
                    q.append(nb)
        if not any(y == ground for _, y, _ in comp):
            islands.append((len(comp), sorted(comp)[0], bare(world[comp[0]])))
    ok("nothing floats — one grounded mass") if not islands else bad(
        f"{len(islands)} floating piece(s): {islands}")


def main() -> int:
    exs = examples()
    if not exs:
        print("no worked examples found in SYSTEM_PROMPT")
        return 1
    for title, msgs in exs:
        audit(title, msgs)
    print(f"\n\033[1m{passed} passed, {failed} failed\033[0m")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
