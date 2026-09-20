#!/usr/bin/env bash
# Builds the plugin jar and drops it into server/plugins/.
# This is the inner loop — you will run it constantly.
#
#   ./scripts/deploy.sh            build + copy
#   ./scripts/deploy.sh --restart  build + copy + bounce the running server
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_java_21

PLUGIN_DIR="$REPO_ROOT/plugin"
DEST="$REPO_ROOT/server/plugins"
JAR="$PLUGIN_DIR/build/libs/mcmcp.jar"

mkdir -p "$DEST"

echo "Building plugin against paper-api ${PAPER_API_VERSION} ..."
( cd "$PLUGIN_DIR" && gradle jar --quiet --console=plain )

[[ -f "$JAR" ]] || { echo "FATAL: expected jar at $JAR but it is not there." >&2; exit 1; }

cp "$JAR" "$DEST/mcmcp.jar"
echo "Deployed -> $DEST/mcmcp.jar  ($(du -h "$DEST/mcmcp.jar" | cut -f1))"

if [[ "${1:-}" == "--restart" ]]; then
  before_inode="$(server_log_inode)"

  pid="$(pgrep -f "paper-${MC_VERSION}-${PAPER_BUILD}.jar" || true)"
  if [[ -n "$pid" ]]; then
    echo "Stopping server (pid $pid) ..."
    kill -TERM "$pid"
    while kill -0 "$pid" 2>/dev/null; do sleep 0.3; done
    echo "Stopped."
  fi

  echo "Starting server ..."
  mkdir -p "$REPO_ROOT/server/logs"
  MCMCP_CONSOLE_FIFO=1 nohup "$REPO_ROOT/scripts/run-server.sh" \
      > "$REPO_ROOT/server/logs/nohup.out" 2>&1 &

  # Block until it is genuinely up, so callers never read a stale log.
  wait_for_server_ready "$before_inode"
  echo "Server ready."
else
  echo
  echo "Now restart the server so it picks the jar up (type 'stop' in its tab, then re-run scripts/run-server.sh)."
fi
