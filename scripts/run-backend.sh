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

# --reload watches backend/*.py and restarts on save. Useful while developing, and a
# liability during a demo — an edit at the wrong moment drops the connection — so it is
# opt-in rather than default:  ./scripts/run-backend.sh --reload
# Bound to 127.0.0.1 on purpose: the plugin-to-backend hop must never depend on the
# network. --no-access-log keeps the console readable; the app logs what matters.
#
# Two explicit branches rather than an args array: macOS ships bash 3.2, where
# expanding an empty array under `set -u` is an unbound-variable error.
if [[ "${1:-}" == "--reload" ]]; then
  echo "auto-reload ON — do not use this during the demo, an edit mid-build drops the stream"
  exec "$VENV/bin/python" -m uvicorn main:app \
    --host 127.0.0.1 --port 8000 --no-access-log --log-level info \
    --reload --reload-dir "$REPO_ROOT/backend"
fi

exec "$VENV/bin/python" -m uvicorn main:app \
  --host 127.0.0.1 --port 8000 --no-access-log --log-level info
