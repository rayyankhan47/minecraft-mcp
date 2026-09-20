#!/usr/bin/env bash
# Pre-seeds the cache with the builds you intend to demo (plan step 8.3.1).
#
# Run this once, before the demo, with the backend up and an API key present. Every
# prompt that completes is written to backend/cache/ and is then guaranteed to work
# forever after — with no model, no key and no network.
#
#   ./scripts/seed-cache.sh                      # the default demo set
#   ./scripts/seed-cache.sh "a stone lighthouse" # one specific prompt
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

BACKEND="http://127.0.0.1:8000"

DEFAULT_PROMPTS=(
  "a small medieval cottage with a stone chimney"
  "a stone watchtower"
  "a japanese pagoda"
  "a wooden bridge over a ravine"
  "a tiny lighthouse on a rock"
)

if (( $# > 0 )); then
  PROMPTS=("$@")
else
  PROMPTS=("${DEFAULT_PROMPTS[@]}")
fi

curl -s --max-time 5 "$BACKEND/health" >/dev/null || {
  echo "FATAL: backend not responding at $BACKEND. Start it with scripts/run-backend.sh" >&2
  exit 1
}

echo "Seeding ${#PROMPTS[@]} build(s). Each one is a real model call."
echo

ok=0; bad=0
for prompt in "${PROMPTS[@]}"; do
  printf '── %s\n' "$prompt"
  start=$(date +%s)

  body="$(printf '{"prompt":%s,"player":"seed"}' "$(python3 -c '
import json,sys; print(json.dumps(sys.argv[1]))' "$prompt")")"

  out="$(curl -sN --max-time 120 -X POST "$BACKEND/build" \
          -H 'Content-Type: application/json' -d "$body" || true)"

  elapsed=$(( $(date +%s) - start ))
  shapes="$(grep -c '"type":"shape"' <<<"$out" || true)"
  errors="$(grep -c '"type":"error"' <<<"$out" || true)"
  finished="$(grep -c '"type":"done"' <<<"$out" || true)"

  if (( errors > 0 )); then
    printf '   \033[31m✗\033[0m error returned — not cached\n'
    grep '"type":"error"' <<<"$out" | head -1 | sed 's/^/     /'
    bad=$((bad+1))
  elif (( finished > 0 && shapes > 0 )); then
    printf '   \033[32m✓\033[0m %d shapes in %ds\n' "$shapes" "$elapsed"
    ok=$((ok+1))
  else
    printf '   \033[31m✗\033[0m incomplete stream (%d shapes, no done)\n' "$shapes"
    bad=$((bad+1))
  fi
done

echo
echo "Cache now contains:"
curl -s "$BACKEND/cache" | python3 -m json.tool | sed 's/^/  /'
echo
echo "$ok seeded, $bad failed"
