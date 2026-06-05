#!/usr/bin/env bash
# UIScope — VNC launcher for Replit
# Replit sets up the virtual display automatically when outputType = "vnc".
# This script builds + runs the Compose Desktop app in place.
set -euo pipefail

cd "$(dirname "$0")/../uiscope"

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║           UIScope — starting in VNC mode             ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  First run downloads ~400 MB of Compose dependencies ║"
echo "║  Subsequent starts take a few seconds.               ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# Ensure the Gradle wrapper is executable
chmod +x gradlew 2>/dev/null || true

# Locate JAVA_HOME automatically from the running java binary
JAVA_BIN="$(command -v java 2>/dev/null || true)"
if [ -z "$JAVA_BIN" ]; then
  echo "ERROR: java not found. Make sure the java-graalvm22.3 module is loaded in Replit." >&2
  exit 1
fi

# Resolve symlinks to find the real JAVA_HOME
JAVA_REAL="$(readlink -f "$JAVA_BIN")"
export JAVA_HOME="${JAVA_REAL%/bin/java}"
echo "→ Java: $(java -version 2>&1 | head -1)"
echo "→ JAVA_HOME: $JAVA_HOME"

echo "→ Working directory: $(pwd)"

# Prefer the system Gradle if available (avoids wrapper download on first run).
if command -v gradle &>/dev/null; then
  GRADLE_CMD="gradle"
  echo "→ Using system Gradle: $(gradle --version 2>&1 | head -1)"
else
  GRADLE_CMD="./gradlew"
  echo "→ Using Gradle wrapper"
fi

echo "→ Launching via $GRADLE_CMD :app:run ..."
echo ""

# --no-daemon keeps the process in the foreground so Replit can manage it.
# org.gradle.java.installations.paths tells the toolchain resolver exactly
# where our JDK lives, bypassing auto-detection entirely.
exec env \
  JAVA_HOME="$JAVA_HOME" \
  JAVA_TOOL_OPTIONS="-Dawt.useSystemAAFontSettings=on -Dswing.aatext=true" \
  $GRADLE_CMD :app:run --no-daemon --console=plain \
    -Dorg.gradle.java.installations.paths="$JAVA_HOME" \
    2>&1
