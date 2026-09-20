#!/usr/bin/env bash
# Toolchain preflight. Run this any time something behaves strangely, and once
# before demoing. Exits non-zero if anything required is missing.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

fail=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31m✗\033[0m %s\n' "$1"; fail=1; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }

echo "MC MCP toolchain check"
echo

echo "Java"
if [[ -x "$JAVA_HOME/bin/java" ]]; then
  jv="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  if [[ "$jv" == *'"21.'* ]]; then ok "$jv"; else bad "expected Java 21, got: $jv"; fi
else
  bad "no JDK at $JAVA_HOME (brew install openjdk@21)"
fi

echo "Gradle"
if command -v gradle >/dev/null 2>&1; then
  ok "$(gradle --version 2>/dev/null | grep '^Gradle' | head -1)"
else
  bad "gradle not found (brew install gradle)"
fi

echo "Python"
if command -v python3 >/dev/null 2>&1; then
  pv="$(python3 --version 2>&1)"
  py_major_minor="$(python3 -c 'import sys; print(f"{sys.version_info[0]}{sys.version_info[1]:02d}")')"
  if (( py_major_minor >= 311 )); then ok "$pv"; else bad "need Python 3.11+, got $pv"; fi
else
  bad "python3 not found"
fi

echo "Architecture"
[[ "$(uname -m)" == "arm64" ]] && ok "arm64" || warn "not arm64: $(uname -m)"

echo "Pinned Minecraft version"
if [[ -n "${MC_VERSION:-}" ]]; then
  ok "MC_VERSION=$MC_VERSION  PAPER_BUILD=${PAPER_BUILD:-?}"
  [[ "$MC_VERSION" == 1.21* ]] || bad "MC_VERSION must be 1.21.x on Java 21 (26.x needs Java 25)"
else
  warn "no VERSION file yet"
fi

echo "Paper server jar"
if [[ -n "${MC_VERSION:-}" && -f "$REPO_ROOT/server/paper-${MC_VERSION}-${PAPER_BUILD:-}.jar" ]]; then
  ok "server/paper-${MC_VERSION}-${PAPER_BUILD}.jar"
else
  warn "not downloaded yet (run scripts/setup-server.sh)"
fi

echo "Anthropic API key"
if [[ -f "$REPO_ROOT/backend/.env" ]] && grep -qE '^ANTHROPIC_API_KEY=.+' "$REPO_ROOT/backend/.env"; then
  ok "backend/.env has a key (contents never read or logged)"

  # Confirm the model id actually resolves. "claude-haiku-4-5" is an alias; if it is not
  # a live one the whole demo 404s on the first prompt, and that is not something to find
  # out on stage. One request, max_tokens=1, no system prompt — a fraction of a cent.
  echo "Model id"
  MODEL_CHECK="$("$REPO_ROOT/.venv/bin/python" - <<'PYCHK' 2>&1
import os, sys
sys.path.insert(0, os.path.join(os.getcwd(), "backend"))
from dotenv import load_dotenv
load_dotenv("backend/.env")
import anthropic, llm
try:
    anthropic.Anthropic(max_retries=0).messages.create(
        model=llm.MODEL, max_tokens=1, messages=[{"role": "user", "content": "hi"}])
    print(f"OK {llm.MODEL}")
except anthropic.NotFoundError:
    print(f"NOTFOUND {llm.MODEL}")
except Exception as exc:
    print(f"ERR {type(exc).__name__}: {exc}")
PYCHK
)"
  case "$MODEL_CHECK" in
    OK*)       ok "${MODEL_CHECK#OK } resolves" ;;
    NOTFOUND*) bad "${MODEL_CHECK#NOTFOUND } does not exist — set MCMCP_MODEL to a valid id (e.g. claude-haiku-4-5-20251001)" ;;
    *)         warn "could not check: $MODEL_CHECK" ;;
  esac
else
  warn "no backend/.env key — the backend will serve the offline build instead"
fi

echo
if (( fail )); then echo "FAILED"; exit 1; else echo "All required checks passed."; fi
