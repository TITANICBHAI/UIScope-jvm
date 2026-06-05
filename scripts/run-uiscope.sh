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

# Use JAVA_HOME from the graalvm22.3 module if available
if command -v java &>/dev/null; then
  echo "→ Java: $(java -version 2>&1 | head -1)"
else
  echo "ERROR: java not found. Make sure the java-graalvm22.3 module is loaded in Replit." >&2
  exit 1
fi

echo "→ Working directory: $(pwd)"
echo "→ Launching via Gradle :app:run ..."
echo ""

# --no-daemon keeps the process in the foreground so Replit can manage it.
# JAVA_TOOL_OPTIONS sets the AWT rendering pipeline to the software renderer
# (Compose Desktop uses Skiko which renders via Swing/AWT on Linux).
exec env \
  JAVA_TOOL_OPTIONS="-Dawt.useSystemAAFontSettings=on -Dswing.aatext=true" \
  ./gradlew :app:run --no-daemon --console=plain 2>&1
