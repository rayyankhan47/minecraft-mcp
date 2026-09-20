#!/usr/bin/env python3
"""Model bake-off (plan step 5.3). Requires ANTHROPIC_API_KEY — this one really calls Claude.

Answers one question: can we afford a better model than Haiku 4.5? Success criterion #1
is a block on screen within 8 seconds, so the number that decides it is time-to-first
-*shape*, not total time and not build quality. A nicer cottage that arrives at t=11s
loses the demo.

Quality is scored two ways. Objectively, every result goes through the same audit the
worked examples do — floating blocks, missing door, unsupported lanterns. Subjectively,
each result is written to disk so it can be replayed in-game and actually looked at;
the command to do that is printed at the end.

Usage:
    .venv/bin/python scripts/bakeoff.py                  # default candidates
    .venv/bin/python scripts/bakeoff.py claude-haiku-4-5 # just one
"""

from __future__ import annotations

import asyncio
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))
sys.path.insert(0, str(ROOT / "scripts"))

import llm  # noqa: E402

CANDIDATES = ["claude-haiku-4-5", "claude-sonnet-5"]

PROMPTS = [
    "a small medieval cottage",
    "a stone watchtower",
    "a bridge over a ravine",
]

OUT = ROOT / "bakeoff"


async def one_run(model: str, prompt: str) -> dict:
    llm.MODEL = model
    started = time.monotonic()
    first_shape: float | None = None
    shapes: list[dict] = []
    lines: list[str] = []
    err = ""

    try:
        async for msg in llm.stream_build(prompt):
            if msg.get("type") == "shape":
                if first_shape is None:
                    first_shape = time.monotonic() - started
                shapes.append(msg)
            lines.append(json.dumps(msg, separators=(",", ":")))
    except Exception as exc:  # noqa: BLE001 — a bake-off must survive a bad candidate
        err = f"{type(exc).__name__}: {exc}"

    total = time.monotonic() - started

    slug = f"{model}-{prompt}".lower().replace(" ", "-").replace(".", "")
    path = OUT / f"{slug}.ndjson"
    if lines:
        OUT.mkdir(exist_ok=True)
        path.write_text("\n".join(lines) + "\n")

    return {
        "model": model, "prompt": prompt,
        "first_shape": first_shape, "total": total,
        "shapes": len(shapes), "error": err, "path": path if lines else None,
    }


def audit_quietly(path: Path) -> str:
    """Objective quality: reuse the worked-example audit, report only what it rejects."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("chk", ROOT / "scripts" / "check-examples.py")
    chk = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(chk)

    msgs = [json.loads(l) for l in path.read_text().splitlines() if l.strip().startswith("{")]
    import io
    import contextlib
    buf = io.StringIO()
    before = chk.failed
    with contextlib.redirect_stdout(buf):
        chk.audit("x", msgs, strict_shape_count=False)
    strip_ansi = __import__("re").compile(r"\x1b\[[0-9;]*m")
    problems = [strip_ansi.sub("", l).strip().lstrip("✗").strip()
                for l in buf.getvalue().splitlines() if "✗" in l]
    chk.failed = before
    return "; ".join(problems) if problems else "clean"


async def main() -> int:
    if not llm.have_api_key():
        print("No ANTHROPIC_API_KEY. Put one in backend/.env and re-run.")
        return 1

    models = sys.argv[1:] or CANDIDATES
    print(f"candidates: {', '.join(models)}")
    print(f"prompts:    {len(PROMPTS)}")
    print(f"calls:      {len(models) * len(PROMPTS)}  (a few cents, mostly cached prompt)\n")

    rows = []
    for model in models:
        # The first call of each model pays the cache write; the rest read it. Flagged
        # rather than warmed up, because a warm-up is another paid call for no data.
        for i, prompt in enumerate(PROMPTS):
            r = await one_run(model, prompt)
            r["cold"] = (i == 0)
            rows.append(r)
            fs = f"{r['first_shape']:.2f}s" if r["first_shape"] else "—"
            status = r["error"] or f"{r['shapes']} shapes"
            print(f"  {model:<24} {prompt:<26} first-shape {fs:>7}  "
                  f"total {r['total']:>6.2f}s  {status}{'  (cold cache)' if r['cold'] else ''}")

    print(f"\n{'model':<24} {'prompt':<26} {'1st shape':>10} {'total':>8}  quality")
    print("-" * 96)
    for r in rows:
        if r["path"] is None:
            print(f"{r['model']:<24} {r['prompt']:<26} {'FAILED':>10} {'':>8}  {r['error']}")
            continue
        fs = f"{r['first_shape']:.2f}s" if r["first_shape"] else "—"
        flag = "" if (r["first_shape"] or 99) <= 8 else "  ← OVER 8s BUDGET"
        print(f"{r['model']:<24} {r['prompt']:<26} {fs:>10} {r['total']:>7.2f}s  "
              f"{audit_quietly(r['path'])}{flag}")

    ok_rows = [r for r in rows if r["first_shape"] and not r["cold"]]
    if ok_rows:
        best = min(ok_rows, key=lambda r: r["first_shape"])
        print(f"\nfastest warm first-shape: {best['model']} at {best['first_shape']:.2f}s")
        print(f"budget is 8.00s — {'PASS' if best['first_shape'] <= 8 else 'FAIL'}")

    print(f"\nResults written to {OUT}/")
    print("To look at one in-game:")
    print(f"  cp {OUT}/<file>.ndjson backend/cache/   &&   /mc2p cached <file>")
    print("Delete them from backend/cache/ before the demo — nearest() matches fuzzily.")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
