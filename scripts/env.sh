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
