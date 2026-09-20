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

HEALTH="$(curl -s --max-time 5 "$BACKEND/health")" || {
  echo "FATAL: backend not responding at $BACKEND. Start it with scripts/run-backend.sh" >&2
  exit 1
}
[[ -n "$HEALTH" ]] || {
  echo "FATAL: backend not responding at $BACKEND. Start it with scripts/run-backend.sh" >&2
  exit 1
}

# Without a key /build silently serves the offline build. Seeding then writes the same
# plain cottage under every prompt name and reports success for each — and because
# nearest() matches fuzzily, every later fallback would replay that one box. A cache
# that looks full and is not is worse than an empty one, so refuse outright.
if ! grep -q '"live":true' <<<"$HEALTH"; then
  echo "FATAL: the backend has no API key, so /build would serve the offline build." >&2
  echo "       Seeding now would cache ${#PROMPTS[@]} copies of the same plain cottage" >&2
  echo "       under ${#PROMPTS[@]} different names, and report success for all of them." >&2
  echo >&2
  echo "       Put a key in backend/.env, restart the backend, and re-run." >&2
  exit 1
fi

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
    # 8.3.1 says to confirm each build looks good. The subjective half needs your eyes,
    # but floating blocks, a missing door and unsupported lanterns are all checkable —
    # and this is the build the audience sees when everything else has failed.
    slug="$(python3 -c '
import sys, re
s = re.sub(r"[^a-z0-9]+", "-", sys.argv[1].lower()).strip("-")
print(s)' "$prompt")"
    if [[ -f "$REPO_ROOT/backend/cache/$slug.ndjson" ]]; then
      verdict="$("$REPO_ROOT/.venv/bin/python" - "$REPO_ROOT/backend/cache/$slug.ndjson" <<'PYA'
import importlib.util, io, json, contextlib, re, sys, pathlib
root = pathlib.Path(__file__).resolve().parent if False else pathlib.Path.cwd()
spec = importlib.util.spec_from_file_location("chk", "scripts/check-examples.py")
chk = importlib.util.module_from_spec(spec); spec.loader.exec_module(chk)
msgs = [json.loads(l) for l in pathlib.Path(sys.argv[1]).read_text().splitlines()
        if l.strip().startswith("{")]
buf = io.StringIO()
with contextlib.redirect_stdout(buf):
    chk.audit("x", msgs, strict_shape_count=False)
bad = [re.sub(r"\x1b\[[0-9;]*m", "", l).strip().lstrip("✗").strip()
       for l in buf.getvalue().splitlines() if "✗" in l]
print("; ".join(bad) if bad else "clean")
PYA
)"
      if [[ "$verdict" == "clean" ]]; then
        printf '     quality: \033[32mclean\033[0m\n'
      else
        printf '     quality: \033[33m%s\033[0m  <- consider re-running this prompt\n' "$verdict"
      fi
    fi
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
