#!/usr/bin/env bash
# Proves the plugin renders progressively: blocks from early shapes must be in the
# world while later shapes are still arriving over the wire.
#
# The backend's mock stream emits one shape per second, so at t≈4s the foundation
# (shape 1) must exist and the chimney (shape 8) must not. If both are missing the
# plugin is waiting for the stream to close; if both are present something is replaying
# a cached build rather than streaming.
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
send() { "$REPO_ROOT/scripts/mc.sh" "$@"; }

await() {
  local token="MC_$RANDOM$RANDOM"
  send "$1"; send "say $token"
  for _ in $(seq 1 60); do grep -q "\[Server\] $token" "$LOG" && return 0; sleep 0.2; done
  return 1
}

echo "Preparing the area ..."
await "forceload add 0 0 32 32"
await "fill 5 -60 5 25 -30 25 minecraft:air"

MARK="$(wc -l < "$LOG" | tr -d '[:space:]')"; MARK=$((MARK + 1))

echo "Starting a streaming build ..."
START=$(date +%s)
send "mc2p selftest stream a small medieval cottage"

# Find the origin as soon as the plugin reports it.
origin_line=""
for _ in $(seq 1 40); do
  origin_line="$(tail -n +"$MARK" "$LOG" | grep -m1 'selftest: building at')"
  [[ -n "$origin_line" ]] && break
  sleep 0.2
done
[[ -n "$origin_line" ]] || { echo "FAILED: selftest never started" >&2; tail -n +"$MARK" "$LOG" | tail -20 >&2; exit 1; }

read -r OX OY OZ <<<"$(sed -E 's/.*\(([-0-9]+), ([-0-9]+), ([-0-9]+)\).*/\1 \2 \3/' <<<"$origin_line")"
echo "origin = ($OX, $OY, $OZ)"

# --- the mid-stream snapshot -------------------------------------------------
# Wait until ~4s after the command, then ask both questions at once.
while (( $(date +%s) - START < 4 )); do sleep 0.2; done

FT="F_$RANDOM$RANDOM"; CT="C_$RANDOM$RANDOM"; SNAP="S_$RANDOM$RANDOM"
send "execute if block $OX $OY $OZ minecraft:cobblestone run say $FT"
send "execute if block $((OX+7)) $((OY+9)) $((OZ+7)) minecraft:cobblestone run say $CT"
send "say $SNAP"
for _ in $(seq 1 40); do grep -q "\[Server\] $SNAP" "$LOG" && break; sleep 0.2; done

stream_open=1
if tail -n +"$MARK" "$LOG" | grep -q 'build complete'; then stream_open=0; fi

foundation=0; chimney=0
if grep -q "\[Server\] $FT" "$LOG"; then foundation=1; fi
if grep -q "\[Server\] $CT" "$LOG"; then chimney=1; fi

echo
echo "Snapshot at t≈4s (stream still open: $([[ $stream_open == 1 ]] && echo yes || echo no)):"
echo "  foundation present : $([[ $foundation == 1 ]] && echo yes || echo no)   (expected yes)"
echo "  chimney present    : $([[ $chimney    == 1 ]] && echo yes || echo no)   (expected no)"

fail=0
if (( stream_open != 1 )); then echo "  ✗ the build had already finished — increase the mock delay"; fail=1; fi
if (( foundation != 1 )); then echo "  ✗ nothing was placed mid-stream: the plugin is waiting for the stream to close"; fail=1; fi
if (( chimney == 1 )); then echo "  ✗ the whole build was already present: this is not streaming"; fail=1; fi
(( fail == 0 )) && echo "  ✓ progressive rendering confirmed"

# --- wait it out and check the finished structure ----------------------------
echo
echo "Waiting for the build to finish ..."
for _ in $(seq 1 120); do
  tail -n +"$MARK" "$LOG" | grep -q 'build complete' && break
  sleep 0.5
done

FIN="E_$RANDOM$RANDOM"
send "execute if block $((OX+7)) $((OY+9)) $((OZ+7)) minecraft:cobblestone run say $FIN"
send "say DONE_$FIN"
for _ in $(seq 1 40); do grep -q "\[Server\] DONE_$FIN" "$LOG" && break; sleep 0.2; done

if grep -qE "\[Server\] $FIN( |\$)" "$LOG"; then
  echo "  ✓ chimney present after completion"
else
  echo "  ✗ chimney still missing after completion"
  fail=1
fi

echo
echo "Plugin report:"
tail -n +"$MARK" "$LOG" | grep -E 'stream closed|first block placed|build complete|backend done' | sed 's/^/  /'

echo
echo "Problems in the log:"
if tail -n +"$MARK" "$LOG" | grep -qE 'Asynchronous|Exception|SEVERE|unparseable'; then
  tail -n +"$MARK" "$LOG" | grep -E 'Asynchronous|Exception|SEVERE|unparseable' | sed 's/^/  /'
  fail=1
else
  echo "  none"
fi

echo
(( fail == 0 )) && echo "PASS" || echo "FAIL"
(( fail == 0 ))
