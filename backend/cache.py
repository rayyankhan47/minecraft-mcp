"""Write-through cache of successful builds.

This is the demo's insurance policy, and it is the reason the previous iteration of
this project is not repeatable. Every successful generation is written to disk as it is
streamed, so any prompt that has worked once is guaranteed to work again forever — with
no model, no API key and no network at all.

The one rule: a file is only ever *finalised* if the stream reached `done`. A partial
generation is written to a temporary file and discarded, so a failure mid-stream can
never poison the cache with a half-built house.
"""

from __future__ import annotations

import asyncio
import difflib
import json
import logging
import os
import re
from collections.abc import AsyncIterator
from pathlib import Path

log = logging.getLogger("mcmcp.cache")

# Overridable so a test run can point at a scratch directory. The demo cache is a
# demo asset — nothing automated should be able to write into it by accident.
CACHE_DIR = Path(os.getenv("MCMCP_CACHE_DIR") or Path(__file__).parent / "cache")
SUFFIX = ".ndjson"

# Pacing for a replayed build. Slow enough to read as generation rather than a dump,
# fast enough that the player is not waiting. Roughly matches live model output.
REPLAY_SHAPE_DELAY = float(os.getenv("MCMCP_REPLAY_DELAY", "0.35"))
REPLAY_THOUGHT_DELAY = 0.05


def slugify(prompt: str) -> str:
    """A stable, filesystem-safe name for a prompt."""
    s = prompt.strip().lower()
    s = re.sub(r"[^a-z0-9]+", "-", s).strip("-")
    return (s or "build")[:80]


def path_for(name: str) -> Path:
    """Resolve a cache name to a path, refusing anything that escapes the directory."""
    safe = slugify(name)
    return CACHE_DIR / f"{safe}{SUFFIX}"


def list_cached() -> list[str]:
    if not CACHE_DIR.is_dir():
        return []
    return sorted(p.stem for p in CACHE_DIR.glob(f"*{SUFFIX}"))


def exists(name: str) -> bool:
    return path_for(name).is_file()


def nearest(prompt: str) -> str | None:
    """The cached build closest to what was asked for.

    Used when a live generation fails: serving *a* cottage when the player asked for a
    cottage is far better than serving a stack trace. Falls back to any cached build at
    all rather than nothing, because criterion #2 is never hard-failing.
    """
    names = list_cached()
    if not names:
        return None

    target = slugify(prompt)
    best = difflib.get_close_matches(target, names, n=1, cutoff=0.4)
    if best:
        return best[0]

    # No textual similarity. Try a word-overlap pass before giving up.
    wanted = set(target.split("-"))
    scored = sorted(
        names, key=lambda n: len(wanted & set(n.split("-"))), reverse=True
    )
    if scored and wanted & set(scored[0].split("-")):
        return scored[0]

    return names[0]


class WriteThrough:
    """Tees a live stream to disk, finalising only on a clean `done`."""

    def __init__(self, prompt: str) -> None:
        self.name = slugify(prompt)
        self.final = CACHE_DIR / f"{self.name}{SUFFIX}"
        self.partial = CACHE_DIR / f".{self.name}{SUFFIX}.partial"
        self._fh = None
        self._saw_done = False
        self._shapes = 0

    def __enter__(self) -> WriteThrough:
        try:
            CACHE_DIR.mkdir(parents=True, exist_ok=True)
            self._fh = self.partial.open("wb")
        except OSError as exc:
            # Caching is a nicety; a build must never fail because the disk is unhappy.
            log.warning("could not open cache file %s: %s", self.partial, exc)
            self._fh = None
        return self

    def feed(self, raw: bytes, msg: dict) -> None:
        kind = msg.get("type")
        if kind == "shape":
            self._shapes += 1
        elif kind == "done":
            self._saw_done = True
        elif kind == "error":
            # An error line means this generation is not worth keeping.
            self._saw_done = False
        if self._fh is not None:
            try:
                self._fh.write(raw)
            except OSError as exc:
                log.warning("cache write failed, giving up on caching: %s", exc)
                self._close_fh()

    def _close_fh(self) -> None:
        if self._fh is not None:
            try:
                self._fh.close()
            finally:
                self._fh = None

    def __exit__(self, exc_type, exc, tb) -> None:
        self._close_fh()

        # Only a stream that finished cleanly and actually built something is kept.
        keep = self._saw_done and self._shapes > 0 and exc_type is None
        try:
            if keep:
                self.partial.replace(self.final)
                log.info("cached %d shapes -> %s", self._shapes, self.final.name)
            else:
                self.partial.unlink(missing_ok=True)
                log.info(
                    "not caching %r (done=%s shapes=%d error=%s)",
                    self.name, self._saw_done, self._shapes,
                    exc_type.__name__ if exc_type else None,
                )
        except OSError as exc:
            log.warning("could not finalise cache file: %s", exc)


async def replay(name: str) -> AsyncIterator[bytes]:
    """Replays a cached build with pacing that reads like live generation."""
    p = path_for(name)
    if not p.is_file():
        yield (json.dumps({"type": "error", "message": f"no cached build named {name!r}"})
               + "\n").encode()
        return

    log.info("replaying cached build %s", p.name)
    for raw in p.read_bytes().splitlines():
        if not raw.strip():
            continue
        try:
            kind = json.loads(raw).get("type")
        except json.JSONDecodeError:
            continue
        if kind == "shape":
            await asyncio.sleep(REPLAY_SHAPE_DELAY)
        elif kind == "thought":
            await asyncio.sleep(REPLAY_THOUGHT_DELAY)
        yield raw + b"\n"
