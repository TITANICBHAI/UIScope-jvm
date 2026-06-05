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

# ── Locate Java (prefers nix-installed jdk21 on PATH) ────────────────────────
JAVA_BIN=""

if command -v java &>/dev/null; then
  JAVA_BIN="$(command -v java)"
else
  # Fallback: search nix store for any jdk/openjdk with a java binary
  JAVA_BIN="$(find /nix/store -maxdepth 3 -name java -type f 2>/dev/null \
    | grep -E '/(jdk|openjdk|graalvm)[^/]*/bin/java$' | head -1 || true)"
  if [ -n "$JAVA_BIN" ]; then
    export PATH="$(dirname "$JAVA_BIN"):$PATH"
  fi
fi

if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
  echo "ERROR: java not found. Ensure jdk21 is listed in replit.nix deps." >&2
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
    -Dskiko.renderApi=SOFTWARE \
    2>&1
