#!/usr/bin/env bash
# Downloads the pinned Paper server jar into server/ and verifies its checksum.
# Idempotent: if the jar is already present and its SHA-256 matches, it does nothing.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_java_21

: "${MC_VERSION:?VERSION file did not define MC_VERSION}"
: "${PAPER_BUILD:?VERSION file did not define PAPER_BUILD}"

if [[ "$MC_VERSION" != 1.21* ]]; then
  echo "FATAL: MC_VERSION=$MC_VERSION. Only 1.21.x runs on Java 21 — 26.x needs Java 25." >&2
  exit 1
fi

SERVER_DIR="$REPO_ROOT/server"
JAR="$SERVER_DIR/paper-${MC_VERSION}-${PAPER_BUILD}.jar"
VERSION_API="https://fill.papermc.io/v3/projects/paper/versions/${MC_VERSION}"
BUILD_API="${VERSION_API}/builds/${PAPER_BUILD}"

mkdir -p "$SERVER_DIR"

# The Java requirement is a property of the version, not of the individual build.
echo "Resolving Paper ${MC_VERSION} from ${VERSION_API}"
vmeta="$(curl -fsS --max-time 30 "$VERSION_API")" || { echo "FATAL: Paper version API request failed." >&2; exit 1; }
read -r min_java latest_build <<<"$(printf '%s' "$vmeta" | python3 -c '
import sys, json
d = json.load(sys.stdin)
builds = d.get("builds") or [0]
print(d["version"]["java"]["version"]["minimum"], max(builds))
')"

echo "  minimum Java for ${MC_VERSION}: ${min_java}   (latest build available: ${latest_build})"
if [[ "$min_java" != "21" ]]; then
  echo "FATAL: ${MC_VERSION} wants Java ${min_java}, we have 21. Wrong MC_VERSION." >&2
  exit 1
fi
if [[ "$PAPER_BUILD" != "$latest_build" ]]; then
  echo "  note: pinned to build ${PAPER_BUILD}, but ${latest_build} is now available."
fi

echo "Resolving build ${PAPER_BUILD} from ${BUILD_API}"
meta="$(curl -fsS --max-time 30 "$BUILD_API")" || { echo "FATAL: Paper build API request failed." >&2; exit 1; }
read -r url sha <<<"$(printf '%s' "$meta" | python3 -c '
import sys, json
dl = json.load(sys.stdin)["downloads"]["server:default"]
print(dl["url"], dl["checksums"]["sha256"])
')"

verify() { [[ -f "$JAR" ]] && [[ "$(shasum -a 256 "$JAR" | cut -d' ' -f1)" == "$sha" ]]; }

if verify; then
  echo "  jar already present and checksum matches — nothing to do."
else
  echo "  downloading $(basename "$url") ..."
  curl -fSL --max-time 600 -o "$JAR.part" "$url"
  mv "$JAR.part" "$JAR"
  if verify; then
    echo "  SHA-256 verified."
  else
    echo "FATAL: checksum mismatch — refusing to use this jar." >&2
    rm -f "$JAR"
    exit 1
  fi
fi

ls -lh "$JAR"
