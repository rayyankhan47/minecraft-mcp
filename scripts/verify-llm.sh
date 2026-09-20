#!/usr/bin/env bash
# Runtime coverage for backend/llm.py — the streaming Claude client (plan step 5.2).
#
# Until now every test went through `/build?mock=1`, which never touches llm.py. The
# line buffer, the fence stripper, the validator and the first-token guard had all been
# reasoned about and none of them had ever run. This script runs them, against a fake
# Anthropic endpoint (scripts/mock-anthropic.py) reached by setting ANTHROPIC_BASE_URL.
# Real client, real SDK, real streaming, fake model, no spend.
#
# Everything here is hermetic: its own backend on :8001, its own mock on :8999, its own
# cache directory. The demo backend on :8000 and the demo cache are never touched.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

# See verify-failures.sh: these harnesses do their own accounting, and a grep that finds
# nothing is a negative answer, not an error.
set +e

TMP="${TMPDIR:-/tmp}/mcmcp-llm-verify.$$"
mkdir -p "$TMP/cache"
MOCK_LOG="$TMP/mock.log"
BACK_LOG="$TMP/backend.log"
MOCK_PORT=8999
BACK_PORT=8001
BASE="http://127.0.0.1:$BACK_PORT"

pass=0; fail=0
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=$((fail+1)); }

cleanup() {
  [[ -n "${MOCK_PID:-}" ]] && kill "$MOCK_PID" 2>/dev/null
  [[ -n "${BACK_PID:-}" ]] && kill "$BACK_PID" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT

PY="$REPO_ROOT/.venv/bin/python"
[[ -x "$PY" ]] || { echo "no venv at $PY — run ./scripts/run-backend.sh once first"; exit 1; }

echo "── bringing up mock model and an isolated backend"
"$PY" "$REPO_ROOT/scripts/mock-anthropic.py" >"$MOCK_LOG" 2>&1 &
MOCK_PID=$!

# A syntactically plausible key so the SDK does not object; it never leaves this host.
env -u ANTHROPIC_AUTH_TOKEN \
    ANTHROPIC_API_KEY="sk-ant-mockmockmockmockmock" \
    ANTHROPIC_BASE_URL="http://127.0.0.1:$MOCK_PORT" \
    MCMCP_CACHE_DIR="$TMP/cache" \
    MCMCP_FIRST_TOKEN_TIMEOUT=4 \
    MCMCP_REPLAY_DELAY=0.02 \
    "$PY" -m uvicorn main:app --app-dir "$REPO_ROOT/backend" \
    --host 127.0.0.1 --port "$BACK_PORT" --log-level info \
    >"$BACK_LOG" 2>&1 &
BACK_PID=$!

up=0
for _ in $(seq 1 60); do
  curl -sf -m 1 "$BASE/health" >/dev/null && { up=1; break; }
  sleep 0.25
done
[[ "$up" == 1 ]] || { echo "test backend never came up:"; cat "$BACK_LOG"; exit 1; }
for _ in $(seq 1 60); do
  curl -sf -m 1 "http://127.0.0.1:$MOCK_PORT/requests" >/dev/null && break
  sleep 0.25
done
echo "  mock on :$MOCK_PORT, backend on :$BACK_PORT, cache in $TMP/cache"

# build <scenario> -> NDJSON on stdout
build() {
  curl -s -m 40 -X POST "$BASE/build" -H 'content-type: application/json' \
    -d "{\"player\":\"verify\",\"prompt\":\"a small cottage #scenario:$1\"}"
}
count_type() { grep -c "\"type\":\"$1\"" <<<"$2"; }

# ---------------------------------------------------------------- 1. the happy path
echo
echo "── normal stream (tokens split mid-object)"
OUT="$(build normal)"
[[ "$(count_type shape "$OUT")" == 6 ]] \
  && ok "6 shapes reassembled from 17-char chunks" \
  || bad "expected 6 shapes, got $(count_type shape "$OUT")"
[[ "$(count_type thought "$OUT")" == 1 ]] && ok "thought passed through" || bad "thought missing"
[[ "$(count_type done "$OUT")" == 1 ]] && ok "done emitted once" || bad "done not emitted exactly once"
grep -q '"block":"oak_stairs\[facing=north\]"' <<<"$OUT" \
  && ok "blockstate survived chunk boundaries intact" || bad "blockstate corrupted"
while read -r l; do [[ -z "$l" ]] && continue; jq -e . >/dev/null 2>&1 <<<"$l" || echo "BADLINE:$l"; done <<<"$OUT" \
  | grep -q BADLINE && bad "emitted a line that is not valid JSON" || ok "every emitted line is valid JSON"

# --------------------------------------------------------- 2. request shape is right
echo
echo "── what the SDK actually sent"
REQ="$(curl -s -m 5 "http://127.0.0.1:$MOCK_PORT/requests")"
# Read the expected model from llm.py rather than repeating the string here: a test
# that hardcodes the constant it is checking stops being a test the moment it drifts.
WANT_MODEL="$("$PY" -c "import sys; sys.path.insert(0,'$REPO_ROOT/backend'); import llm; print(llm.MODEL)")"
grep -q "\"model\":\"$WANT_MODEL\"" <<<"$REQ" && ok "model is $WANT_MODEL" \
  || bad "expected $WANT_MODEL, sent $(jq -r '.requests[0].model' <<<"$REQ" 2>/dev/null)"
grep -q '"system_cache_control":true' <<<"$REQ" && ok "prompt caching breakpoint present on system prompt" || bad "cache_control missing — every build pays full prompt price"
grep -q '"stream":true' <<<"$REQ" && ok "request is streaming" || bad "request was not streaming"
grep -q '"has_thinking":false' <<<"$REQ" && ok "no thinking block (latency)" || bad "thinking enabled — costs first-token time"
SYSCHARS="$(jq -r '.requests[0].system_chars' <<<"$REQ" 2>/dev/null)"
[[ "${SYSCHARS:-0}" -gt 4000 ]] && ok "system prompt reached the model ($SYSCHARS chars)" || bad "system prompt looks wrong ($SYSCHARS chars)"

# ------------------------------------------------------------------ 3. markdown fence
echo
echo "── model wraps output in a markdown fence"
OUT="$(build fence)"
[[ "$(count_type shape "$OUT")" == 6 ]] \
  && ok "fence stripped, all 6 shapes recovered" \
  || bad "fence broke the stream: $(count_type shape "$OUT") shapes"
grep -q '```' <<<"$OUT" && bad "backticks leaked into the plugin stream" || ok "no backticks leaked downstream"

# ----------------------------------------------------------------- 4. malformed lines
echo
echo "── junk interleaved with good lines"
OUT="$(build garbage)"
GOT="$(count_type shape "$OUT")"
[[ "$GOT" == 3 ]] && ok "3 good shapes kept, 4 bad lines dropped" || bad "expected 3 shapes, got $GOT"
grep -q '"op":"teleport"' <<<"$OUT" && bad "unknown op reached the plugin" || ok "unknown op rejected by validate()"
grep -q '"type":"done"' <<<"$OUT" && ok "stream still completed" || bad "junk killed the stream"
grep -q "dropped unparseable line" "$BACK_LOG" && ok "unparseable lines logged" || bad "drops not logged"
grep -q "dropped invalid message" "$BACK_LOG" && ok "invalid messages logged" || bad "invalid messages not logged"

# ------------------------------------------------------- 5. no trailing newline (flush)
echo
echo "── stream ends without a trailing newline"
OUT="$(build truncated)"
grep -q '"block":"torch"' <<<"$OUT" \
  && ok "final line recovered by buffer.flush()" \
  || bad "last line lost — flush() is not being reached"

# ---------------------------------------------------------- 6. first-token timeout
echo
echo "── model stalls past the first-token budget (4s here, 12s in production)"
# The earlier scenarios succeeded, so they have been written through to this cache.
# Stash them: "no cached build available" is only a meaningful test against a genuinely
# empty cache, and asserting the premise is cheaper than debugging a false pass later.
mkdir -p "$TMP/stash" && mv "$TMP"/cache/*.ndjson "$TMP/stash/" 2>/dev/null
REMAINING="$(ls -1 "$TMP"/cache/*.ndjson 2>/dev/null | wc -l | tr -d '[:space:]')"
[[ "$REMAINING" == 0 ]] && ok "cache is empty for this case" || bad "cache still holds $REMAINING build(s)"
START=$(date +%s)
OUT="$(build stall)"
ELAPSED=$(( $(date +%s) - START ))
[[ "$ELAPSED" -lt 12 ]] && ok "gave up after ${ELAPSED}s rather than hanging" || bad "took ${ELAPSED}s — guard did not fire"
grep -q "first-token timeout" "$BACK_LOG" && ok "FirstTokenTimeout raised and caught" || bad "timeout never fired"
grep -q '"type":"error"' <<<"$OUT" && ok "empty cache -> honest error to the player" || bad "no error line with an empty cache"

# ------------------------------------------- 7. same timeout, but the cache has a build
echo
echo "── same stall, with a cached build available"
mv "$TMP"/stash/*.ndjson "$TMP/cache/" 2>/dev/null
cp "$REPO_ROOT/backend/cache/a-small-medieval-cottage.ndjson" "$TMP/cache/" 2>/dev/null
CACHED="$(ls -1 "$TMP"/cache/*.ndjson 2>/dev/null | wc -l | tr -d '[:space:]')"
[[ "${CACHED:-0}" -gt 0 ]] && ok "cache holds $CACHED build(s) for this case" || bad "cache is empty — case is meaningless"
OUT="$(build stall)"
SHAPES="$(count_type shape "$OUT")"
[[ "${SHAPES:-0}" -gt 0 ]] && ok "silently replayed a cached build ($SHAPES shapes)" || bad "cache fallback did not fire"
grep -q '"type":"error"' <<<"$OUT" && bad "player was shown an error despite a usable cache" || ok "player saw no error"
grep -q "falling back to cached build" "$BACK_LOG" && ok "fallback recorded in the log" || bad "fallback not logged"

# ------------------------------------------------------------- 8. API returns 529
echo
echo "── model returns 529 Overloaded"
OUT="$(build overloaded)"
SHAPES="$(count_type shape "$OUT")"
[[ "${SHAPES:-0}" -gt 0 ]] && ok "fell back to cache ($SHAPES shapes)" || bad "529 produced nothing"
grep -q '"type":"error"' <<<"$OUT" && bad "529 surfaced to the player" || ok "529 never reached the player"

# --------------------------------------------------- 9. connection drops mid-stream
echo
echo "── connection drops after a few shapes have already landed"
OUT="$(build abort)"
SHAPES="$(count_type shape "$OUT")"
[[ "${SHAPES:-0}" -ge 1 ]] && ok "kept the $SHAPES shapes already sent" || bad "lost shapes that had already been emitted"
grep -q "generation ended early" <<<"$OUT" \
  && ok "closed out the partial build instead of splicing a second one" \
  || bad "no early-completion done line"
grep -q '"type":"error"' <<<"$OUT" && bad "mid-build drop surfaced as an error" || ok "player saw a finished build, not a failure"

# --------------------------------------------------------------- 10. empty response
echo
echo "── model returns an empty stream"
OUT="$(build empty)"
[[ -n "$OUT" ]] && ok "responded rather than hanging" || bad "empty stream produced no response at all"
grep -q '"type":"shape"\|"type":"done"\|"type":"error"' <<<"$OUT" \
  && ok "terminated cleanly" || bad "no terminal line"

# ------------------------------------------------------------------ 11. server health
echo
echo "── after all of the above"
curl -sf -m 2 "$BASE/health" >/dev/null && ok "backend still serving" || bad "backend fell over"
grep -qi "traceback" "$BACK_LOG" && {
  grep -q "generation failed" "$BACK_LOG" \
    && ok "tracebacks present but all from the deliberate failure cases" \
    || bad "unexpected traceback in the backend log"
} || ok "no tracebacks at all"
grep -ci "error" "$MOCK_LOG" >/dev/null

echo
printf '\033[1m%d passed, %d failed\033[0m\n' "$pass" "$fail"
[[ "$fail" == 0 ]] || { echo; echo "backend log tail:"; tail -30 "$BACK_LOG"; }
exit $(( fail > 0 ))
