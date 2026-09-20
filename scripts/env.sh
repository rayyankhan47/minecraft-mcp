#!/usr/bin/env bash
# Shared toolchain environment for MC MCP.
# Source this from every other script:  source "$(dirname "$0")/env.sh"
#
# Why this file exists: JDK 21 is installed via Homebrew as `openjdk@21`, which is
# keg-only — it is NOT on PATH, so a bare `java -version` fails on this machine.
# Homebrew's `gradle` formula also drags in openjdk@25, which would silently compile
# against the wrong JDK. Pinning JAVA_HOME here makes both problems go away for good.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export REPO_ROOT

export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
export PATH="$JAVA_HOME/bin:$PATH"

# The one place the Minecraft version is defined. Everything reads it from here.
if [[ -f "$REPO_ROOT/VERSION" ]]; then
  # shellcheck disable=SC1091
  source "$REPO_ROOT/VERSION"
fi

require_java_21() {
  if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "FATAL: no JDK at $JAVA_HOME" >&2
    echo "       install it with:  brew install openjdk@21" >&2
    exit 1
  fi
  local v
  v="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  if [[ "$v" != *'"21.'* ]]; then
    echo "FATAL: expected Java 21, got: $v" >&2
    echo "       Minecraft 1.21.x requires Java 21. A 26.x jar would need Java 25." >&2
    exit 1
  fi
}

# Paper rotates logs/latest.log on every boot, so "is the server up?" cannot be
# answered by grepping that file alone — between the stop and the next start it still
# holds the PREVIOUS run's "Done (" line, and you will happily read stale output.
# Gate on the inode changing first, then on the readiness line.
server_log() { echo "$REPO_ROOT/server/logs/latest.log"; }

server_log_inode() { stat -f %i "$(server_log)" 2>/dev/null || echo 0; }

# wait_for_server_ready <inode-before-restart> [timeout-seconds]
wait_for_server_ready() {
  local before="${1:-0}" timeout="${2:-120}"
  local log deadline
  log="$(server_log)"
  deadline=$(( $(date +%s) + timeout ))
  while (( $(date +%s) < deadline )); do
    if [[ -f "$log" && "$(server_log_inode)" != "$before" ]] && grep -q 'Done (' "$log" 2>/dev/null; then
      return 0
    fi
    sleep 0.3
  done
  echo "TIMED OUT waiting for the server to report Done after ${timeout}s" >&2
  return 1
}

# Several verification scripts are meaningless without the backend. Without this check
# a backend that is simply not running looks exactly like a broken plugin.
require_backend() {
  if ! curl -s --max-time 3 http://127.0.0.1:8000/health >/dev/null 2>&1; then
    echo "FATAL: backend is not responding at http://127.0.0.1:8000" >&2
    echo "       start it with:  ./scripts/run-backend.sh" >&2
    exit 1
  fi
}
