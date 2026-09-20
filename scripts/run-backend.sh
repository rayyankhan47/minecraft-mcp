#!/usr/bin/env bash
# Starts the backend on 127.0.0.1:8000. Keep this running in its own terminal tab.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

VENV="$REPO_ROOT/.venv"
[[ -x "$VENV/bin/python" ]] || {
  echo "FATAL: no venv at $VENV. Create it with:" >&2
  echo "  python3 -m venv .venv && ./.venv/bin/pip install -r backend/requirements.txt" >&2
  exit 1
}

cd "$REPO_ROOT/backend"

# Bound to 127.0.0.1 on purpose: the plugin-to-backend hop must never depend on the
# network. --no-access-log keeps the console readable; the app logs what matters.
exec "$VENV/bin/python" -m uvicorn main:app \
  --host 127.0.0.1 --port 8000 \
  --no-access-log \
  --log-level info
