#!/usr/bin/env bash
# Every block name the demo can place, checked against the running server (step 5.4).
#
# A name that does not resolve does not crash anything — BlockResolver falls back to
# stone and logs a warning. That is the right behaviour at runtime and exactly the wrong
# thing to discover on stage, because a cottage built out of grey stone still "works".
# So every literal in the worked examples, the offline demo build and the seeded cache
# is put through the server's own block parser here.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
set +e

LOG="$(server_log)"
[[ -f "$LOG" ]] || { echo "server is not running — start it with ./scripts/run-server.sh"; exit 1; }

send() { "$REPO_ROOT/scripts/mc.sh" "$@"; }
await() {
  local token="MC_$RANDOM$RANDOM"
  send "$1"; send "say $token"
  for _ in $(seq 1 100); do grep -q "\[Server\] $token" "$LOG" && return 0; sleep 0.2; done
  return 1
}

# Collect every block literal from the three places one can appear.
# Only `minecraft:`-prefixed literals: the Java source also contains op and phase names
# ("fill", "roof", "detail") which are not blocks and must not be checked as if they were.
BLOCKS="$(
  { grep -oE '"block":"[^"]+"' "$REPO_ROOT/backend/prompts.py" | sed -E 's/^"block":"//; s/"$//'
    grep -hoE '"block":"[^"]+"' "$REPO_ROOT"/backend/cache/*.ndjson 2>/dev/null | sed -E 's/^"block":"//; s/"$//'
    grep -oE '"minecraft:[^"]+"' "$REPO_ROOT/plugin/src/main/java/com/mcmcp"/*.java | sed -E 's/.*"(minecraft:[^"]+)"/\1/'
  } | sort -u
)"

echo "── checking $(wc -l <<<"$BLOCKS" | tr -d '[:space:]') distinct block names against the server"
sed 's/^/    /' <<<"$BLOCKS"
await "forceload add 0 0 16 16" >/dev/null

pass=0; fail=0
BAD=""

# Checked one at a time and keyed on vanilla's own success message. Scanning the whole
# tail for error text afterwards cannot attribute a failure to a specific name.
while read -r b; do
  [[ -z "$b" ]] && continue
  # Set a contrasting base first: "Changed the block" only appears on an actual change,
  # so testing air against air would report a failure for a perfectly valid name.
  if [[ "${b#minecraft:}" == "air" ]]; then base="minecraft:stone"; else base="minecraft:air"; fi
  await "setblock 8 -60 8 $base replace" >/dev/null
  before="$(wc -l < "$LOG" | tr -d '[:space:]')"
  await "setblock 8 -60 8 $b replace" >/dev/null
  if tail -n +"$((before + 1))" "$LOG" | grep -q "Changed the block"; then
    pass=$((pass+1))
  else
    printf '  \033[31m✗\033[0m %s\n' "$b"
    tail -n +"$((before + 1))" "$LOG" | grep -iE "Unknown|Expected|Can't" | head -1 | sed 's/^/      /'
    fail=$((fail+1)); BAD="$BAD $b"
  fi
done <<<"$BLOCKS"

await "setblock 8 -60 8 minecraft:air replace" >/dev/null

printf '  \033[32m✓\033[0m %d block names resolved\n' "$pass"
[[ "$fail" == 0 ]] || printf '  \033[31m✗\033[0m %d did not resolve:%s\n' "$fail" "$BAD"
echo
printf '\033[1m%d passed, %d failed\033[0m\n' "$pass" "$fail"
exit $(( fail > 0 ))
