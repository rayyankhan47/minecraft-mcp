#!/usr/bin/env bash
# Resilience verification (plan step 8.2.2).
#
# The scenario that lost the previous demo: the model is unreachable. A build must
# still appear, and the player must not see an error.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

# These scripts do their own pass/fail accounting, so `set -e` (inherited from env.sh)
# is actively wrong here: a `grep` that finds nothing is a NEGATIVE ANSWER, not an
# error, and `var=$(grep ...)` or `grep ... && flag=1` would abort the whole run. That
# failure mode is silent and timing-dependent — it passes whenever the log line happens
# to already be there — so it is disabled deliberately rather than papered over with
# `|| true` at each call site.
set +e

require_backend

LOG="$(server_log)"
BACKEND_LOG="${MCMCP_BACKEND_LOG:-/tmp/mcmcp-backend.log}"
send() { "$REPO_ROOT/scripts/mc.sh" "$@"; }

await() {
  local token="MC_$RANDOM$RANDOM"
  send "$1"; send "say $token"
  for _ in $(seq 1 80); do grep -q "\[Server\] $token" "$LOG" && return 0; sleep 0.2; done
  return 1
}

pass=0; fail=0
ok()  { printf '  \033[32m✓\033[0m %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=$((fail+1)); }

start_backend() {
  pkill -f 'uvicorn main:app' 2>/dev/null || true
  sleep 1
  if [[ -n "${1:-}" ]]; then
    ANTHROPIC_API_KEY="$1" nohup "$REPO_ROOT/scripts/run-backend.sh" > "$BACKEND_LOG" 2>&1 &
  else
    nohup "$REPO_ROOT/scripts/run-backend.sh" > "$BACKEND_LOG" 2>&1 &
  fi
  for _ in $(seq 1 60); do
    curl -s --max-time 2 http://127.0.0.1:8000/health >/dev/null 2>&1 && return 0
    sleep 0.5
  done
  echo "backend failed to start; see $BACKEND_LOG" >&2
  return 1
}

build_and_report() {
  local label="$1" cmd="$2"
  echo
  echo "── $label"
  await "forceload add 0 0 32 32" >/dev/null
  await "fill 5 -60 5 25 -30 25 minecraft:air" >/dev/null

  local mark
  mark="$(wc -l < "$LOG" | tr -d '[:space:]')"; mark=$((mark + 1))
  send "$cmd"

  for _ in $(seq 1 120); do
    tail -n +"$mark" "$LOG" | grep -qE 'build complete|build failed' && break
    sleep 0.5
  done
  CASE_LOG="$(tail -n +"$mark" "$LOG")"

  local blocks
  blocks="$(grep -oE 'build complete: [0-9]+ blocks' <<<"$CASE_LOG" | grep -oE '[0-9]+' | head -1)"
  blocks="${blocks:-0}"

  if (( blocks > 0 )); then
    ok "a build appeared ($blocks blocks)"
  else
    bad "NO BUILD APPEARED — this is the failure that loses the demo"
  fi

  if grep -q 'backend error' <<<"$CASE_LOG"; then
    bad "an error was surfaced to the player"
    grep 'backend error' <<<"$CASE_LOG" | sed 's/^/      /' | head -2
  else
    ok "no error shown to the player"
  fi
}

echo "Resilience verification"

echo
echo "Cached builds currently available:"
curl -s http://127.0.0.1:8000/cache 2>/dev/null | sed 's/^/  /' || echo "  (backend down)"

# 1. Explicit replay — the break-glass path.
echo
echo "── explicit cached replay via the backend"
CACHED_NAME="$(curl -s http://127.0.0.1:8000/cache | sed -E 's/.*"builds":\["([^"]*)".*/\1/')"
if [[ -n "$CACHED_NAME" && "$CACHED_NAME" != *"{"* ]]; then
  if curl -sN --max-time 40 -X POST "http://127.0.0.1:8000/build/cached/$CACHED_NAME" \
       | grep -q '"type":"done"'; then
    ok "replay of '$CACHED_NAME' streamed to completion"
  else
    bad "replay of '$CACHED_NAME' did not complete"
  fi
else
  bad "no cached build to replay"
fi

# 2. The real scenario: model unreachable.
echo
echo "── model unreachable (invalid credentials — same path as no network)"
start_backend "sk-ant-deliberately-invalid-for-testing" || exit 1
build_and_report "live build with an unreachable model" "mc2p selftest stream a cosy cottage"

if grep -q 'falling back to cached build' "$BACKEND_LOG"; then
  ok "backend silently fell back to cache"
else
  bad "no cache fallback happened"
fi

# Leave the backend in a sane state — and assert it, rather than hoping. This script
# deliberately kills the backend, so a silent failure to bring it back would report
# all-green while leaving nothing running, which is worse than the failure it tests.
echo
echo "Restoring the backend with the normal environment ..."
start_backend || true
if curl -s --max-time 3 http://127.0.0.1:8000/health >/dev/null 2>&1; then
  ok "backend restored and answering"
else
  bad "backend did NOT come back — start it with ./scripts/run-backend.sh"
fi

echo
echo "$pass passed, $fail failed"
(( fail == 0 ))
