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
# No -Dcom.mojang.eula.agree: setup-server.sh already writes eula.txt, and the flag
# makes Paper print three ERROR-level nag lines at every boot. The console is our only
# debugging surface during a demo — it should not cry wolf.
JAVA_ARGS=(-Xms2G -Xmx2G -XX:+UseG1GC -jar "$JAR" --nogui)

if [[ "${MCMCP_CONSOLE_FIFO:-0}" == "1" ]]; then
  # Detached mode: feed the server's stdin from a named pipe so commands can be sent
  # to the console without a terminal attached (see scripts/mc.sh). Opening the pipe
  # read-write on fd 3 pins it open — otherwise the server sees EOF the instant the
  # first writer disconnects and shuts itself down.
  FIFO="$SERVER_DIR/console.in"
  rm -f "$FIFO"
  mkfifo "$FIFO"
  exec 3<>"$FIFO"
  echo "Console pipe: $FIFO"
  exec java "${JAVA_ARGS[@]}" <&3
fi

# Interactive mode: stdin is your terminal, so you can type `stop` as usual.
exec java "${JAVA_ARGS[@]}"
