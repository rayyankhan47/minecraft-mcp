#!/usr/bin/env python
"""Proves the backend flushes per line rather than buffering the whole response.

Prints the arrival time of every NDJSON line relative to the request. If the deltas are
all ~0 and the last line lands at the same moment as the first, the response is being
buffered somewhere and the entire latency strategy is dead.

    ./.venv/bin/python scripts/test-stream.py "a small medieval cottage"
"""

from __future__ import annotations

import json
import sys
import time

import httpx

URL = "http://127.0.0.1:8000/build"


def main() -> int:
    prompt = sys.argv[1] if len(sys.argv) > 1 else "a small medieval cottage"
    endpoint = sys.argv[2] if len(sys.argv) > 2 else URL

    t0 = time.monotonic()
    first_shape_at: float | None = None
    counts: dict[str, int] = {}
    bad = 0

    print(f"POST {endpoint}  prompt={prompt!r}\n")
    print(f"{'at':>8}  {'delta':>7}  line")
    print("-" * 78)

    previous = t0
    with httpx.Client(timeout=60.0) as client:
        with client.stream("POST", endpoint, json={"prompt": prompt, "player": "test"}) as r:
            r.raise_for_status()
            for raw in r.iter_lines():
                if not raw.strip():
                    continue
                now = time.monotonic()
                try:
                    obj = json.loads(raw)
                except json.JSONDecodeError:
                    bad += 1
                    kind = "UNPARSEABLE"
                else:
                    kind = obj.get("type", "?")
                    counts[kind] = counts.get(kind, 0) + 1
                    if kind == "shape" and first_shape_at is None:
                        first_shape_at = now

                shown = raw if len(raw) <= 58 else raw[:55] + "..."
                print(f"{now - t0:7.3f}s  {now - previous:6.3f}s  {shown}")
                previous = now

    total = time.monotonic() - t0
    print("-" * 78)
    print(f"total {total:.3f}s   messages {counts}   unparseable {bad}")

    if first_shape_at is None:
        print("\nFAIL: no shape line was ever received.")
        return 1

    ttfs = first_shape_at - t0
    print(f"time to first shape: {ttfs:.3f}s")

    # The give-away for a buffered response: everything arrives in one lump at the end.
    if total > 0.5 and ttfs > total * 0.9:
        print("\nFAIL: the first shape arrived with the last line — the response is buffered.")
        return 1

    print("PASS: lines arrived progressively.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
