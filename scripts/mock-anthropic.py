#!/usr/bin/env python3
"""A fake Anthropic Messages endpoint, for exercising backend/llm.py without a key.

Why this exists
---------------
Every test up to now went through `/build?mock=1`, which returns a hardcoded build and
never touches `llm.py`. That left the single most important code path in the project —
the streaming client, the line buffer, the first-token guard — with no runtime coverage
at all. This server closes that gap: the anthropic SDK reads ANTHROPIC_BASE_URL from the
environment, so pointing it here runs the *real* client against a *fake* model, with no
test hooks in production code and no API spend.

The scenario is chosen by a `#scenario:<name>` marker in the user prompt, so a test is
an ordinary POST /build and nothing extra has to be plumbed through the backend.

Deliberately adversarial: text is chunked mid-JSON-object, because tokens do not align
with lines and that is exactly where a line buffer breaks.
"""

from __future__ import annotations

import asyncio
import json
import sys
import time
from collections.abc import AsyncIterator

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

app = FastAPI()

# Chunk size in characters. Prime, and small enough to land inside JSON objects rather
# than politely between them.
CHUNK = 17

COTTAGE = """{"type":"thought","text":"A small cottage - stone footing, oak walls, a pitched roof."}
{"type":"shape","op":"fill","phase":"foundation","speed":"instant","from":[-4,0,0],"to":[4,0,8],"block":"cobblestone"}
{"type":"shape","op":"walls","phase":"structure","speed":"fast","from":[-4,1,0],"to":[4,4,8],"block":"oak_planks"}
{"type":"shape","op":"fill","phase":"detail","speed":"fast","from":[-1,1,0],"to":[1,3,0],"block":"air"}
{"type":"shape","op":"fill","phase":"roof","speed":"instant","from":[-5,5,-1],"to":[5,5,9],"block":"oak_stairs[facing=north]"}
{"type":"shape","op":"line","phase":"detail","speed":"slow","from":[3,5,6],"to":[3,8,6],"block":"bricks"}
{"type":"shape","op":"set","phase":"detail","speed":"slow","pos":[-3,3,0],"block":"lantern[hanging=false]"}
{"type":"done","summary":"cottage"}
"""

GARBAGE = """{"type":"thought","text":"Interleaving junk with good lines."}
{"type":"shape","op":"fill","phase":"foundation","speed":"instant","from":[-4,0,0],"to":[4,0,8],"block":"cobblestone"}
{"type":"shape","op":"fill","truncated json
{"type":"shape","op":"teleport","block":"stone","from":[0,0,0],"to":[1,1,1]}
{"type":"shape","op":"walls","phase":"structure","speed":"fast","from":[-4,1,0],"to":[4,4,8],"block":"oak_planks"}
{"type":"shape","op":"fill","from":[0,0,0],"to":[1,1,1]}
not json at all
{"type":"shape","op":"set","phase":"detail","speed":"slow","pos":[-3,3,0],"block":"lantern"}
{"type":"done","summary":"survived the junk"}
"""

# Complete JSON on the final line but no trailing newline: only buffer.flush() recovers it.
TRUNCATED = """{"type":"thought","text":"No trailing newline on the last line."}
{"type":"shape","op":"fill","phase":"foundation","speed":"instant","from":[-4,0,0],"to":[4,0,8],"block":"cobblestone"}
{"type":"shape","op":"set","phase":"detail","speed":"slow","pos":[0,1,0],"block":"torch"}"""

FENCED = "```json\n" + COTTAGE + "```\n"

_requests: list[dict] = []


def sse(event: str, data: dict) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data)}\n\n".encode()


def message_start() -> bytes:
    return sse("message_start", {
        "type": "message_start",
        "message": {
            "id": "msg_mock", "type": "message", "role": "assistant",
            "model": "claude-haiku-4-5", "content": [],
            "stop_reason": None, "stop_sequence": None,
            "usage": {
                "input_tokens": 12, "output_tokens": 1,
                "cache_creation_input_tokens": 2375, "cache_read_input_tokens": 0,
            },
        },
    })


def block_start() -> bytes:
    return sse("content_block_start",
               {"type": "content_block_start", "index": 0,
                "content_block": {"type": "text", "text": ""}})


def delta(text: str) -> bytes:
    return sse("content_block_delta",
               {"type": "content_block_delta", "index": 0,
                "delta": {"type": "text_delta", "text": text}})


def closing() -> list[bytes]:
    return [
        sse("content_block_stop", {"type": "content_block_stop", "index": 0}),
        sse("message_delta", {"type": "message_delta",
                              "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                              "usage": {"output_tokens": 420}}),
        sse("message_stop", {"type": "message_stop"}),
    ]


async def emit(body: str, *, pre_delay: float = 0.0,
               abort_after: int | None = None) -> AsyncIterator[bytes]:
    yield message_start()
    yield block_start()

    if pre_delay:
        await asyncio.sleep(pre_delay)

    sent = 0
    for i in range(0, len(body), CHUNK):
        yield delta(body[i:i + CHUNK])
        sent += 1
        if abort_after is not None and sent >= abort_after:
            # Hard stop: no content_block_stop, no message_stop. The SDK sees the
            # connection end mid-message, which is what a dropped API call looks like.
            raise RuntimeError("mock: connection dropped mid-stream")
        await asyncio.sleep(0.01)

    for chunk in closing():
        yield chunk


async def emit_empty() -> AsyncIterator[bytes]:
    yield message_start()
    yield block_start()
    for chunk in closing():
        yield chunk


def scenario_of(payload: dict) -> str:
    try:
        text = payload["messages"][0]["content"]
    except (KeyError, IndexError, TypeError):
        return "normal"
    if not isinstance(text, str):
        text = json.dumps(text)
    marker = "#scenario:"
    if marker in text:
        return text.split(marker, 1)[1].split()[0].strip()
    return "normal"


@app.post("/v1/messages")
async def messages(request: Request):
    payload = await request.json()
    name = scenario_of(payload)

    system = payload.get("system")
    cached = False
    system_chars = 0
    if isinstance(system, list) and system:
        cached = any(b.get("cache_control") for b in system if isinstance(b, dict))
        system_chars = sum(len(b.get("text", "")) for b in system if isinstance(b, dict))

    _requests.append({
        "scenario": name,
        "model": payload.get("model"),
        "max_tokens": payload.get("max_tokens"),
        "stream": payload.get("stream"),
        "system_cache_control": cached,
        "system_chars": system_chars,
        "has_thinking": "thinking" in payload,
        "at": time.time(),
    })
    print(f"[mock] scenario={name} model={payload.get('model')} "
          f"cache_control={cached} system_chars={system_chars}", file=sys.stderr, flush=True)

    # A non-streaming request: doctor.sh uses one of these to confirm the model id
    # resolves before a demo. Answer it like the real API does.
    if not payload.get("stream"):
        if payload.get("model") == "does-not-exist":
            return JSONResponse(status_code=404,
                                content={"type": "error",
                                         "error": {"type": "not_found_error",
                                                   "message": "model: does-not-exist"}})
        return JSONResponse(content={
            "id": "msg_mock", "type": "message", "role": "assistant",
            "model": payload.get("model"), "content": [{"type": "text", "text": "hi"}],
            "stop_reason": "max_tokens", "stop_sequence": None,
            "usage": {"input_tokens": 8, "output_tokens": 1},
        })

    if name == "overloaded":
        return JSONResponse(status_code=529,
                            content={"type": "error",
                                     "error": {"type": "overloaded_error", "message": "Overloaded"}})

    media = "text/event-stream"
    if name == "stall":
        return StreamingResponse(emit(COTTAGE, pre_delay=20.0), media_type=media)
    if name == "empty":
        return StreamingResponse(emit_empty(), media_type=media)
    if name == "abort":
        return StreamingResponse(emit(COTTAGE, abort_after=14), media_type=media)
    if name == "fence":
        return StreamingResponse(emit(FENCED), media_type=media)
    if name == "garbage":
        return StreamingResponse(emit(GARBAGE), media_type=media)
    if name == "truncated":
        return StreamingResponse(emit(TRUNCATED), media_type=media)
    return StreamingResponse(emit(COTTAGE), media_type=media)


@app.get("/requests")
async def requests_seen() -> dict:
    return {"count": len(_requests), "requests": _requests}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8999, log_level="warning")
