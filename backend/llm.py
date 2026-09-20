"""Streaming Claude client and the NDJSON line buffer.

The contract with the rest of the backend: `stream_build()` yields already-validated
message dicts, as early as physically possible. A line that does not parse or does not
validate is logged and dropped — never raised — because one malformed line must not
take down a build the player is already watching.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from collections.abc import AsyncIterator
from typing import Any

import anthropic
from dotenv import load_dotenv

from prompts import SYSTEM_PROMPT, user_prompt

load_dotenv()

log = logging.getLogger("mcmcp.llm")

# Claude Haiku 4.5. Measured on the three bake-off prompts (scripts/bakeoff.py):
#
#              first shape    total     shapes
#   Haiku 4.5   0.69-0.95s   3.5-5.1s   11-16
#   Sonnet 5     2.1-2.4s     ~10.5s    18-20
#
# Sonnet builds more detailed structures and is still inside the 8-second budget, but
# it doubles total build time and one bake-off prompt produced no token at all within
# 12s. Haiku never missed. Time-to-first-block is success criterion #1, so Haiku wins.
# To try the bigger model for one session, no code change needed:
#
#   MCMCP_MODEL=claude-sonnet-5 MCMCP_FIRST_TOKEN_TIMEOUT=20 ./scripts/run-backend.sh
MODEL = os.getenv("MCMCP_MODEL", "claude-haiku-4-5")

# A full build is 15-25 NDJSON lines; 8000 leaves plenty of headroom without
# encouraging the model to keep going.
MAX_TOKENS = int(os.getenv("MCMCP_MAX_TOKENS", "8000"))

# Hard ceiling on time to first token. If this fires we give up on the model entirely
# and the caller falls back to cache — a cached build on screen beats a live one that
# never arrives.
#
# 12s is already generous for Haiku, which reached its first shape in under a second on
# every bake-off prompt. The ceiling is deliberately not larger: it does not rescue a
# stalled request, it only decides how long the audience stares at nothing before the
# cached build appears.
FIRST_TOKEN_TIMEOUT = float(os.getenv("MCMCP_FIRST_TOKEN_TIMEOUT", "12"))


class FirstTokenTimeout(Exception):
    """The model produced nothing within FIRST_TOKEN_TIMEOUT seconds."""


def have_api_key() -> bool:
    return bool(os.getenv("ANTHROPIC_API_KEY") or os.getenv("ANTHROPIC_AUTH_TOKEN"))


_client: anthropic.AsyncAnthropic | None = None


def client() -> anthropic.AsyncAnthropic:
    global _client
    if _client is None:
        _client = anthropic.AsyncAnthropic(max_retries=1)
    return _client


# --------------------------------------------------------------------- validation

_OPS_REQUIRING_FROM_TO = {"fill", "hollow", "walls", "line"}
_OPS_REQUIRING_CENTER = {"sphere", "cylinder"}
_KNOWN_OPS = _OPS_REQUIRING_FROM_TO | _OPS_REQUIRING_CENTER | {"set"}


def _is_triple(value: Any) -> bool:
    return (
        isinstance(value, list)
        and len(value) == 3
        and all(isinstance(n, (int, float)) for n in value)
    )


def validate(msg: Any) -> bool:
    """Is this something the plugin can act on?

    Validating here rather than plugin-side keeps the defensive code in one place and
    means a nonsense shape never reaches the placement queue at all.
    """
    if not isinstance(msg, dict):
        return False

    kind = msg.get("type")

    if kind == "thought":
        return isinstance(msg.get("text"), str) and bool(msg["text"].strip())

    if kind in ("done", "error"):
        return True

    if kind != "shape":
        return False

    op = msg.get("op")
    if op not in _KNOWN_OPS:
        return False
    if not isinstance(msg.get("block"), str) or not msg["block"].strip():
        return False

    if op in _OPS_REQUIRING_FROM_TO:
        return _is_triple(msg.get("from")) and _is_triple(msg.get("to"))
    if op in _OPS_REQUIRING_CENTER:
        return _is_triple(msg.get("center")) and isinstance(msg.get("radius"), (int, float))
    if op == "set":
        return _is_triple(msg.get("pos"))

    return False


def strip_fence(line: str) -> str:
    """Models sometimes wrap output in markdown fences despite being told not to.

    Cheaper to strip them defensively than to throw away an otherwise good line.
    """
    s = line.strip()
    if s.startswith("```"):
        s = s.lstrip("`")
        # "```json" -> drop the language tag
        if s[:4].lower() == "json":
            s = s[4:]
        s = s.strip()
    if s.endswith("```"):
        s = s[:-3].strip()
    return s


class LineBuffer:
    """Accumulates characters off the token stream and emits complete lines.

    Tokens do not align with lines — a single token can end one JSON object and begin
    the next — so the boundary has to be found by hand.
    """

    def __init__(self) -> None:
        self._buf = ""
        self.dropped = 0

    def feed(self, chunk: str) -> list[dict]:
        """Returns whatever complete, valid messages this chunk completed."""
        self._buf += chunk
        out: list[dict] = []
        while "\n" in self._buf:
            raw, self._buf = self._buf.split("\n", 1)
            msg = self._parse(raw)
            if msg is not None:
                out.append(msg)
        return out

    def flush(self) -> list[dict]:
        """Whatever is left when the stream closes without a trailing newline."""
        remainder, self._buf = self._buf, ""
        msg = self._parse(remainder)
        return [msg] if msg is not None else []

    def _parse(self, raw: str) -> dict | None:
        text = strip_fence(raw)
        if not text:
            return None
        try:
            msg = json.loads(text)
        except json.JSONDecodeError:
            self.dropped += 1
            log.warning("dropped unparseable line: %s", text[:120])
            return None
        if not validate(msg):
            self.dropped += 1
            log.warning("dropped invalid message: %s", text[:120])
            return None
        return msg


# ------------------------------------------------------------------------ streaming


async def stream_build(description: str) -> AsyncIterator[dict]:
    """Streams validated build messages for a natural-language description.

    Raises FirstTokenTimeout if the model does not start within the budget, and lets
    anthropic's own exceptions propagate. The caller decides what to fall back to.
    """
    started = time.monotonic()
    buffer = LineBuffer()
    shapes = 0
    first_shape_at: float | None = None

    request = {
        "model": MODEL,
        "max_tokens": MAX_TOKENS,
        # A list with an explicit cache breakpoint: the prompt and its worked examples
        # are identical on every request, so after the first build they are served from
        # cache at a tenth of the cost and a fraction of the latency.
        "system": [
            {
                "type": "text",
                "text": SYSTEM_PROMPT,
                "cache_control": {"type": "ephemeral"},
            }
        ],
        "messages": [{"role": "user", "content": user_prompt(description)}],
        # No `thinking`. Haiku 4.5 does not do adaptive thinking, and for this workload
        # any deliberation is latency we cannot afford.
    }

    async with client().messages.stream(**request) as stream:
        iterator = stream.text_stream.__aiter__()

        # Guard only the *first* token. Once the model is producing, it is producing.
        try:
            first_chunk = await asyncio.wait_for(
                iterator.__anext__(), timeout=FIRST_TOKEN_TIMEOUT
            )
        except asyncio.TimeoutError as exc:
            raise FirstTokenTimeout(
                f"no token from {MODEL} within {FIRST_TOKEN_TIMEOUT}s"
            ) from exc
        except StopAsyncIteration:
            log.warning("model returned an empty stream")
            return

        log.info("first token after %.2fs", time.monotonic() - started)

        chunk: str | None = first_chunk
        while chunk is not None:
            for msg in buffer.feed(chunk):
                if msg.get("type") == "shape":
                    shapes += 1
                    if first_shape_at is None:
                        first_shape_at = time.monotonic()
                        log.info("first shape after %.2fs", first_shape_at - started)
                yield msg
            try:
                chunk = await iterator.__anext__()
            except StopAsyncIteration:
                chunk = None

        for msg in buffer.flush():
            if msg.get("type") == "shape":
                shapes += 1
            yield msg

        final = await stream.get_final_message()
        usage = final.usage
        log.info(
            "generation done in %.2fs: %d shapes, %d dropped lines, "
            "tokens in=%s out=%s cache_read=%s cache_write=%s",
            time.monotonic() - started,
            shapes,
            buffer.dropped,
            usage.input_tokens,
            usage.output_tokens,
            getattr(usage, "cache_read_input_tokens", None),
            getattr(usage, "cache_creation_input_tokens", None),
        )
