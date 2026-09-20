"""MC MCP backend.

Takes a natural-language prompt and streams build instructions back as
newline-delimited JSON, one complete object per line.

The single most important property of this service is that it **flushes every line the
instant it is complete**. The whole latency strategy depends on the first `shape` line
reaching the plugin while the model is still writing the rest of the build. Anything
that buffers the response — gzip middleware, a proxy, collecting the generator into a
list — defeats the entire design.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import AsyncIterator
from typing import Any

from fastapi import FastAPI, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

import llm

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-7s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("mcmcp")

app = FastAPI(title="MC MCP", docs_url=None, redoc_url=None)

NDJSON = "application/x-ndjson"

# Headers that tell anything in the path not to hold onto our bytes. Uvicorn does not
# buffer, but this costs nothing and rules out a whole category of confusing failure.
STREAM_HEADERS = {
    "Cache-Control": "no-cache, no-store",
    "X-Accel-Buffering": "no",
    "Connection": "keep-alive",
}


class BuildRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=2000)
    player: str = "unknown"


# --------------------------------------------------------------------------- wire


def line(obj: dict[str, Any]) -> bytes:
    """One NDJSON line. Compact, because every byte is latency."""
    return (json.dumps(obj, separators=(",", ":")) + "\n").encode()


def thought(text: str) -> bytes:
    return line({"type": "thought", "text": text})


def done(summary: str) -> bytes:
    return line({"type": "done", "summary": summary})


def error(message: str) -> bytes:
    return line({"type": "error", "message": message})


# --------------------------------------------------------------------------- mock

# A hardcoded cottage, matching the plugin's own DemoBuild. The artificial delays
# simulate model latency so progressive rendering can be verified without burning API
# calls — if blocks only appear once the stream closes, something is buffering.
MOCK_DELAY_SECONDS = 1.0

MOCK_BUILD: list[dict[str, Any]] = [
    {"type": "thought", "text": "Laying a cobblestone foundation"},
    {"type": "shape", "phase": "foundation", "speed": "instant", "op": "fill",
     "from": [0, 0, 0], "to": [8, 0, 8], "block": "minecraft:cobblestone"},

    {"type": "thought", "text": "Raising the oak walls"},
    {"type": "shape", "phase": "structure", "speed": "fast", "op": "walls",
     "from": [0, 1, 0], "to": [8, 3, 8], "block": "minecraft:oak_planks"},

    {"type": "thought", "text": "Cutting a doorway and two windows"},
    {"type": "shape", "phase": "openings", "speed": "fast", "op": "fill",
     "from": [4, 1, 0], "to": [4, 2, 0], "block": "minecraft:air"},
    {"type": "shape", "phase": "openings", "speed": "fast", "op": "fill",
     "from": [2, 2, 0], "to": [2, 2, 0], "block": "minecraft:air"},
    {"type": "shape", "phase": "openings", "speed": "fast", "op": "fill",
     "from": [6, 2, 0], "to": [6, 2, 0], "block": "minecraft:air"},

    {"type": "thought", "text": "Closing the roof in"},
    {"type": "shape", "phase": "roof", "speed": "instant", "op": "fill",
     "from": [0, 4, 0], "to": [8, 4, 8], "block": "minecraft:oak_planks"},
    {"type": "shape", "phase": "roof", "speed": "fast", "op": "walls",
     "from": [0, 5, 0], "to": [8, 5, 8], "block": "minecraft:cobblestone"},

    {"type": "thought", "text": "Stacking the chimney"},
    {"type": "shape", "phase": "detail", "speed": "slow", "op": "fill",
     "from": [7, 5, 7], "to": [7, 9, 7], "block": "minecraft:cobblestone"},

    {"type": "thought", "text": "Lighting the front edge"},
    {"type": "shape", "phase": "detail", "speed": "slow", "op": "fill",
     "from": [0, 5, 0], "to": [8, 5, 0], "block": "minecraft:sea_lantern"},
]


# --------------------------------------------------------------------------- chaos

# Deliberate misbehaviour, for failure-path testing. Every one of these must degrade
# into "the player sees something and the server stays up" — never a stack trace on a
# projector. Exercised by scripts/verify-failures.sh.
CHAOS_MODES = ("malformed", "badblock", "oversized", "die", "empty")


async def chaos_stream(mode: str) -> AsyncIterator[bytes]:
    log.warning("CHAOS MODE %r — deliberately misbehaving", mode)

    if mode == "empty":
        # Closes immediately having said nothing at all.
        return

    # Always lay a real foundation first, so we can tell "handled the bad input and
    # carried on" apart from "fell over before doing anything".
    yield thought(f"chaos mode: {mode}")
    yield line({"type": "shape", "phase": "foundation", "speed": "instant", "op": "fill",
                "from": [0, 0, 0], "to": [8, 0, 8], "block": "minecraft:cobblestone"})
    await asyncio.sleep(0.4)

    if mode == "malformed":
        yield b"this is not json at all\n"
        yield b'{"type":"shape","op":"fill","from":[0,1,0]\n'      # truncated
        yield b'{"type":"shape","op":"nonsense","block":"x"}\n'     # unknown op
        yield b"```json\n"                                          # stray fence
        yield b"\n"                                                 # empty line
        yield line({"type": "shape", "phase": "structure", "speed": "fast", "op": "walls",
                    "from": [0, 1, 0], "to": [8, 3, 8], "block": "minecraft:oak_planks"})

    elif mode == "badblock":
        for bad in ("minecraft:fake_block", "minecraft:unobtainium",
                    "minecraft:oak_stairs[facing=sideways,half=diagonal]",
                    "oak_planks", "", "minecraft:diamond_sword"):
            yield line({"type": "shape", "phase": "structure", "speed": "fast", "op": "fill",
                        "from": [0, 1, 0], "to": [8, 1, 8], "block": bad})
            await asyncio.sleep(0.1)

    elif mode == "oversized":
        # Exceeds the 64-block per-axis limit: must be rejected outright.
        yield line({"type": "shape", "phase": "structure", "speed": "instant", "op": "fill",
                    "from": [0, 1, 0], "to": [200, 40, 200], "block": "minecraft:stone"})
        # Within per-axis limits but enormous: must be truncated at the block cap.
        yield line({"type": "shape", "phase": "structure", "speed": "instant", "op": "fill",
                    "from": [0, 1, 0], "to": [60, 60, 60], "block": "minecraft:glass"})

    elif mode == "die":
        yield thought("about to fall over")
        await asyncio.sleep(0.3)
        # Abrupt death mid-stream, exactly as a crashed backend would look.
        raise RuntimeError("chaos: backend died mid-stream")

    yield done(f"chaos {mode} finished")


async def mock_stream(prompt: str) -> AsyncIterator[bytes]:
    """Replays the hardcoded cottage with realistic pacing."""
    started = time.monotonic()
    shapes = 0

    yield thought(f"Thinking about: {prompt}")

    for message in MOCK_BUILD:
        if message["type"] == "shape":
            shapes += 1
            # Pause before each shape, not after, so the first one still lands fast.
            await asyncio.sleep(MOCK_DELAY_SECONDS)
        yield line(message)

    elapsed = time.monotonic() - started
    log.info("mock stream finished: %d shapes in %.1fs", shapes, elapsed)
    yield done(f"Mock cottage, {shapes} shapes")


# ---------------------------------------------------------------------- endpoints


@app.get("/health")
async def health() -> dict[str, bool]:
    """Pinged by the plugin on enable, to warm the connection before it is needed."""
    return {"ok": True}


async def live_stream(prompt: str) -> AsyncIterator[bytes]:
    """The real thing: Claude, streamed, one validated line at a time."""
    started = time.monotonic()
    shapes = 0
    saw_done = False

    try:
        async for msg in llm.stream_build(prompt):
            if msg.get("type") == "shape":
                shapes += 1
            elif msg.get("type") == "done":
                saw_done = True
            yield line(msg)

    except llm.FirstTokenTimeout as exc:
        # Step 8.2 turns this into a silent fallback to the nearest cached build.
        log.warning("first-token timeout: %s", exc)
        yield error("the model took too long to start")
        return

    except Exception as exc:  # noqa: BLE001 - nothing may escape and 500 the stream
        log.exception("generation failed")
        yield error(f"generation failed: {type(exc).__name__}")
        return

    # The model is told to end with `done`, but it is not trusted to.
    if not saw_done:
        yield done(f"{shapes} shapes")

    log.info("live stream finished: %d shapes in %.1fs", shapes, time.monotonic() - started)


@app.post("/build")
async def build(
    req: BuildRequest,
    mock: int = Query(0, description="1 forces the hardcoded build, no model call"),
    chaos: str = Query("", description=f"failure-path testing: one of {CHAOS_MODES}"),
) -> StreamingResponse:
    if chaos:
        if chaos not in CHAOS_MODES:
            return StreamingResponse(
                iter([error(f"unknown chaos mode {chaos!r}")]),
                media_type=NDJSON, headers=STREAM_HEADERS,
            )
        return StreamingResponse(chaos_stream(chaos), media_type=NDJSON, headers=STREAM_HEADERS)

    use_mock = bool(mock) or not llm.have_api_key()

    if use_mock and not mock:
        log.warning("no ANTHROPIC_API_KEY found — serving the mock build. "
                    "Put one in backend/.env to use the model.")

    log.info("build request from %s: %r  (%s)",
             req.player, req.prompt, "mock" if use_mock else llm.MODEL)

    source = mock_stream(req.prompt) if use_mock else live_stream(req.prompt)
    return StreamingResponse(source, media_type=NDJSON, headers=STREAM_HEADERS)
