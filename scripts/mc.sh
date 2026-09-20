#!/usr/bin/env bash
# Sends a command to the running server's console.
#
#   ./scripts/mc.sh "say hello"
#   ./scripts/mc.sh "time set day"
#
# Only works when the server was started in detached mode (scripts/deploy.sh --restart),
# which sets up the console pipe. If you started it interactively, just type into that
# terminal instead.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

FIFO="$REPO_ROOT/server/console.in"
[[ -p "$FIFO" ]] || { echo "No console pipe at $FIFO — is the server running detached?" >&2; exit 1; }

printf '%s\n' "$*" > "$FIFO"
