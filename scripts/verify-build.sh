#!/usr/bin/env bash
# Headless end-to-end check of the placement pipeline.
#
# Runs the hardcoded cottage from the server console, then asserts against real world
# blocks. Verifies fill, walls, air-carving and ordering without anyone joining.
#
# Two things this has to do that are not obvious:
#   * forceload the area. With no players online the chunks unload, and a vanilla
#     `execute if block` against an unloaded chunk fails silently — indistinguishable
#     from a failed assertion.
#   * clear the area first. The selftest origin comes from getHighestBlockYAt, so a
#     second run would otherwise stack its cottage on top of the first one's roof.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

# These scripts do their own pass/fail accounting, so `set -e` (inherited from env.sh)
# is actively wrong here: a `grep` that finds nothing is a NEGATIVE ANSWER, not an
# error, and `var=$(grep ...)` or `grep ... && flag=1` would abort the whole run. That
# failure mode is silent and timing-dependent — it passes whenever the log line happens
# to already be there — so it is disabled deliberately rather than papered over with
# `|| true` at each call site.
set +e

LOG="$(server_log)"

send() { "$REPO_ROOT/scripts/mc.sh" "$@"; }

# Sends a command and waits for a uniquely-tokened `say` to come back, so we never
# guess about whether the console actually processed it.
await() {
  local token="MC_$RANDOM$RANDOM"
  send "$1"
  send "say $token"
  for _ in $(seq 1 60); do
    grep -q "\[Server\] $token" "$LOG" && return 0
    sleep 0.2
  done
  echo "  (timed out waiting for the console to acknowledge: $1)" >&2
  return 1
}

echo "Preparing the area ..."
await "forceload add 0 0 32 32"
await "fill 5 -60 5 25 -30 25 minecraft:air"

MARK="$(wc -l < "$LOG" | tr -d '[:space:]')"
MARK=$((MARK + 1))

echo "Running selftest ..."
send "mc2p selftest"

for _ in $(seq 1 120); do
  tail -n +"$MARK" "$LOG" | grep -q 'build complete' && break
  sleep 0.5
done

origin_line="$(tail -n +"$MARK" "$LOG" | grep -m1 'selftest: building at')"
if [[ -z "$origin_line" ]]; then
  echo "FAILED: selftest never started. Recent log:" >&2
  tail -n +"$MARK" "$LOG" | tail -30 >&2
  exit 1
fi

read -r OX OY OZ <<<"$(sed -E 's/.*\(([-0-9]+), ([-0-9]+), ([-0-9]+)\).*/\1 \2 \3/' <<<"$origin_line")"
echo "origin = ($OX, $OY, $OZ)"
echo

pass=0; fail=0

# check <label> <dx> <dy> <dz> <expected>
# Asks both `if` and `unless`, so a command that never ran is distinguishable from an
# assertion that is genuinely false.
check() {
  local label="$1" dx="$2" dy="$3" dz="$4" want="$5"
  local x=$((OX + dx)) y=$((OY + dy)) z=$((OZ + dz))
  local yes="Y_$RANDOM$RANDOM" no="N_$RANDOM$RANDOM"

  send "execute if block $x $y $z $want run say $yes"
  send "execute unless block $x $y $z $want run say $no"

  for _ in $(seq 1 40); do
    grep -qE "\[Server\] ($yes|$no)" "$LOG" && break
    sleep 0.2
  done

  if grep -q "\[Server\] $yes" "$LOG"; then
    printf '  \033[32m✓\033[0m %-28s (%+d,%+d,%+d) is %s\n' "$label" "$dx" "$dy" "$dz" "$want"
    pass=$((pass+1))
  elif grep -q "\[Server\] $no" "$LOG"; then
    printf '  \033[31m✗\033[0m %-28s (%+d,%+d,%+d) is NOT %s\n' "$label" "$dx" "$dy" "$dz" "$want"
    fail=$((fail+1))
  else
    printf '  \033[33m?\033[0m %-28s no answer from the console — command lost\n' "$label"
    fail=$((fail+1))
  fi
}

echo "Asserting world state:"
check "foundation corner"    0 0 0  minecraft:cobblestone
check "foundation centre"    4 0 4  minecraft:cobblestone
check "wall"                 0 1 0  minecraft:oak_planks
check "walls left interior"  4 2 4  minecraft:air
check "doorway carved"       4 1 0  minecraft:air
check "doorway above lintel" 4 3 0  minecraft:oak_planks
check "window carved"        2 2 0  minecraft:air
check "ceiling"              4 4 4  minecraft:oak_planks
check "parapet is lit"       0 5 0  minecraft:sea_lantern
check "parapet side"         8 5 4  minecraft:cobblestone
check "chimney base"         7 5 7  minecraft:cobblestone
check "chimney top"          7 9 7  minecraft:cobblestone
check "nothing above roof"   4 10 4 minecraft:air

echo
echo "Engine report:"
tail -n +"$MARK" "$LOG" | grep -E 'first block placed|build complete' | sed 's/^/  /'

echo
echo "Exceptions during the build:"
if tail -n +"$MARK" "$LOG" | grep -qE 'Asynchronous|Exception|SEVERE'; then
  tail -n +"$MARK" "$LOG" | grep -E 'Asynchronous|Exception|SEVERE' | sed 's/^/  /'
  fail=$((fail+1))
else
  echo "  none"
fi

echo
echo "$pass passed, $fail failed"
(( fail == 0 ))
