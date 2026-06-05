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

# ── Locate Java ──────────────────────────────────────────────────────────────
# Priority:
#   1. java already on PATH (set by Replit module or user)
#   2. Known GraalVM CE 22.3.1 path baked into gradle.properties
#   3. Any JDK found under /nix/store that contains a java binary
GRAALVM_NIX="/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1"

JAVA_BIN=""

if command -v java &>/dev/null; then
  JAVA_BIN="$(command -v java)"
elif [ -x "$GRAALVM_NIX/bin/java" ]; then
  JAVA_BIN="$GRAALVM_NIX/bin/java"
  export PATH="$GRAALVM_NIX/bin:$PATH"
else
  # Last-resort: search nix store for any graalvm/jdk with a java binary
  JAVA_BIN="$(find /nix/store -maxdepth 2 -name java -type f 2>/dev/null \
    | grep -E '/(graalvm|jdk|openjdk)[^/]*/bin/java$' | head -1 || true)"
  if [ -n "$JAVA_BIN" ]; then
    JDK_BIN_DIR="$(dirname "$JAVA_BIN")"
    export PATH="$JDK_BIN_DIR:$PATH"
  fi
fi

if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
  echo "ERROR: java not found." >&2
  echo "       Add 'pkgs.graalvmPackages.graalvm-ce' to replit.nix, or" >&2
  echo "       ensure the java-graalvm22.3 Replit module is enabled." >&2
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
