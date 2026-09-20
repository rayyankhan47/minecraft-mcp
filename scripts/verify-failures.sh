#!/usr/bin/env bash
# Failure-path testing (plan step 6.2).
#
# Deliberately breaks things and asserts the only outcome that matters: the server
# stays up, no Asynchronous-block-modify exception ever appears, and the build
# degrades rather than dying.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

LOG="$(server_log)"
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

# run_case <name> <console command> <regex the log MUST match>
#
# The third argument is the point. Without it a case that silently did nothing — a
# stale backend serving the normal build, say — passes every generic check and tells
# you the failure path works when it was never exercised.
run_case() {
  local name="$1" cmd="$2" expect="${3:-}"
  echo
  echo "── $name"
  await "forceload add 0 0 32 32" >/dev/null
  await "fill 5 -60 5 25 -30 25 minecraft:air" >/dev/null

  local mark
  mark="$(wc -l < "$LOG" | tr -d '[:space:]')"; mark=$((mark + 1))

  send "$cmd"

  # Give it time to run and finish (or fail).
  for _ in $(seq 1 90); do
    tail -n +"$mark" "$LOG" | grep -qE 'build complete|build failed' && break
    sleep 0.5
  done

  CASE_LOG="$(tail -n +"$mark" "$LOG")"

  if pgrep -f "paper-${MC_VERSION}-${PAPER_BUILD}.jar" >/dev/null; then
    ok "server still running"
  else
    bad "SERVER DIED"
    return
  fi

  if grep -q 'Asynchronous' <<<"$CASE_LOG"; then
    bad "Asynchronous block modify exception — a world call escaped the main thread"
    grep 'Asynchronous' <<<"$CASE_LOG" | head -3 | sed 's/^/      /'
  else
    ok "no async block modify"
  fi

  if grep -qE 'build complete|build failed' <<<"$CASE_LOG"; then
    ok "session terminated cleanly"
    grep -E 'build complete|build failed|stream closed' <<<"$CASE_LOG" \
      | sed -E 's/.*\[mcmcp\] /      /' | head -3
  else
    bad "session never terminated — the engine may still be spinning"
  fi

  if [[ -n "$expect" ]]; then
    if grep -qE "$expect" <<<"$CASE_LOG"; then
      ok "the failure was actually exercised and handled"
    else
      bad "no evidence this case ran — expected /$expect/ in the log"
      echo "      (a stale backend is the usual cause: restart scripts/run-backend.sh)"
    fi
  fi
}

echo "Failure-path testing"

run_case "malformed JSON mid-stream" "mc2p selftest chaos malformed" \
         "unparseable line|unknown message type|shape line could not be read"
run_case "hallucinated block names"  "mc2p selftest chaos badblock" \
         "unresolvable, substituting|resolved loosely to|unknown blockstate properties"
run_case "oversized build"           "mc2p selftest chaos oversized" \
         "exceeds the 64-block limit|block limit, ignoring the rest|truncating"
run_case "backend dies mid-stream"   "mc2p selftest chaos die" \
         "build failed|stream closed"
run_case "backend returns nothing"   "mc2p selftest chaos empty" \
         "0 shapes, 0 blocks|build complete: 0 blocks"

# --- the real thing: kill the backend process mid-build ----------------------
echo
echo "── backend killed mid-build (real process kill)"
await "forceload add 0 0 32 32" >/dev/null
await "fill 5 -60 5 25 -30 25 minecraft:air" >/dev/null
mark="$(wc -l < "$LOG" | tr -d '[:space:]')"; mark=$((mark + 1))

send "mc2p selftest stream a small medieval cottage"
sleep 2.5
BACKEND_PID="$(pgrep -f 'uvicorn main:app' | head -1)"
if [[ -n "$BACKEND_PID" ]]; then
  echo "      killing backend pid $BACKEND_PID"
  kill -9 "$BACKEND_PID"
else
  echo "      (backend not found — is it running?)"
fi

for _ in $(seq 1 60); do
  tail -n +"$mark" "$LOG" | grep -qE 'build complete|build failed' && break
  sleep 0.5
done
CASE_LOG="$(tail -n +"$mark" "$LOG")"

pgrep -f "paper-${MC_VERSION}-${PAPER_BUILD}.jar" >/dev/null \
  && ok "server survived the backend dying" || bad "SERVER DIED"
grep -q 'Asynchronous' <<<"$CASE_LOG" && bad "async block modify" || ok "no async block modify"
grep -qE 'build complete|build failed' <<<"$CASE_LOG" \
  && ok "session terminated cleanly" || bad "session never terminated"
grep -E 'build failed|build complete|stream closed' <<<"$CASE_LOG" \
  | sed -E 's/.*\[mcmcp\] /      /' | head -3

echo
echo "Restart the backend before continuing:  ./scripts/run-backend.sh"
echo
echo "$pass passed, $fail failed"
(( fail == 0 ))
