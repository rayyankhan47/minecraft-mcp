#!/usr/bin/env bash
# Starts the Paper server in the foreground. Ctrl-C or type `stop` to shut down.
# Keep this running in its own terminal tab for the whole session.
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
require_java_21

SERVER_DIR="$REPO_ROOT/server"
JAR="paper-${MC_VERSION}-${PAPER_BUILD}.jar"

if [[ ! -f "$SERVER_DIR/$JAR" ]]; then
  echo "FATAL: $SERVER_DIR/$JAR missing. Run scripts/setup-server.sh first." >&2
  exit 1
fi

cd "$SERVER_DIR"
echo "Starting Paper ${MC_VERSION} (build ${PAPER_BUILD}) on Java 21 ..."
echo "Connect your Minecraft ${MC_VERSION} client to:  localhost"
echo

# -Xms == -Xmx avoids heap-resize pauses mid-build. Aikar's flags are overkill for a
# localhost flat world; a fixed 2G heap is plenty and starts faster.
exec java -Xms2G -Xmx2G -XX:+UseG1GC -Dcom.mojang.eula.agree=true \
  -jar "$JAR" --nogui
