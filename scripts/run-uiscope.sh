#!/usr/bin/env bash
# UIScope — VNC launcher for Replit
# Replit sets up the virtual display automatically when outputType = "vnc".
# This script self-heals the environment, then builds + runs the Compose Desktop app.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UISCOPE_DIR="$SCRIPT_DIR/../uiscope"
REQUIRED_JAVA=21

cd "$UISCOPE_DIR"

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║           UIScope — starting in VNC mode             ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  First run downloads ~400 MB of Compose dependencies ║"
echo "║  Subsequent starts take a few seconds.               ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# ── Ensure Gradle wrapper is executable ──────────────────────────────────────
chmod +x gradlew 2>/dev/null || true

# ═════════════════════════════════════════════════════════════════════════════
# PREFLIGHT: check everything needed and fix it if it's wrong
# ═════════════════════════════════════════════════════════════════════════════
echo "── Preflight checks ────────────────────────────────────────────────────"

# ── 1. Locate Java ───────────────────────────────────────────────────────────
JAVA_BIN=""
if command -v java &>/dev/null; then
  JAVA_BIN="$(command -v java)"
else
  JAVA_BIN="$(find /nix/store -maxdepth 3 -name java -type f 2>/dev/null \
    | grep -E '/(jdk|openjdk|graalvm)[^/]*/bin/java$' | head -1 || true)"
  if [ -n "$JAVA_BIN" ]; then
    export PATH="$(dirname "$JAVA_BIN"):$PATH"
  fi
fi

if [ -z "$JAVA_BIN" ] || [ ! -x "$JAVA_BIN" ]; then
  echo "  [FAIL] Java not found — add 'pkgs.jdk${REQUIRED_JAVA}' to replit.nix and restart." >&2
  exit 1
fi

JAVA_REAL="$(readlink -f "$JAVA_BIN")"
export JAVA_HOME="${JAVA_REAL%/bin/java}"

# ── 2. Check Java version matches what Gradle toolchains expect ───────────────
JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')
if [ "$JAVA_VERSION" -lt "$REQUIRED_JAVA" ] 2>/dev/null; then
  echo "  [WARN] Java $JAVA_VERSION found but $REQUIRED_JAVA is required." >&2
  echo "         Install jdk${REQUIRED_JAVA} in replit.nix for best results." >&2
else
  echo "  [OK]   Java $JAVA_VERSION at $JAVA_HOME"
fi

# ── 3. Check and fix jvmToolchain in all build.gradle.kts files ───────────────
TOOLCHAIN_OK=true
for kts in app/build.gradle.kts core/build.gradle.kts engine/build.gradle.kts ui/build.gradle.kts; do
  if [ ! -f "$kts" ]; then
    continue
  fi
  CURRENT=$(grep -oP 'jvmToolchain\(\K[0-9]+' "$kts" 2>/dev/null || true)
  if [ -z "$CURRENT" ]; then
    continue
  fi
  if [ "$CURRENT" != "$REQUIRED_JAVA" ]; then
    echo "  [FIX]  $kts: jvmToolchain($CURRENT) → jvmToolchain($REQUIRED_JAVA)"
    sed -i "s/jvmToolchain($CURRENT)/jvmToolchain($REQUIRED_JAVA)/g" "$kts"
    TOOLCHAIN_OK=false
  else
    echo "  [OK]   $kts: jvmToolchain($REQUIRED_JAVA)"
  fi
done
if [ "$TOOLCHAIN_OK" = false ]; then
  echo "  [INFO] Toolchain versions corrected."
fi

# ── 4. Check release.yml toolchain (informational only) ───────────────────────
RELEASE_YML="$SCRIPT_DIR/../.github/workflows/release.yml"
if [ -f "$RELEASE_YML" ]; then
  YML_JAVA=$(grep -oP "java-version: '\K[0-9]+" "$RELEASE_YML" 2>/dev/null | head -1 || true)
  if [ -n "$YML_JAVA" ] && [ "$YML_JAVA" != "$REQUIRED_JAVA" ]; then
    echo "  [FIX]  release.yml: java-version '$YML_JAVA' → '$REQUIRED_JAVA'"
    sed -i "s/java-version: '$YML_JAVA'/java-version: '$REQUIRED_JAVA'/g" "$RELEASE_YML"
  else
    echo "  [OK]   release.yml: java-version $REQUIRED_JAVA"
  fi
fi

echo "── Preflight complete ──────────────────────────────────────────────────"
echo ""

# ═════════════════════════════════════════════════════════════════════════════
# LAUNCH
# ═════════════════════════════════════════════════════════════════════════════
echo "→ Java:    $(java -version 2>&1 | head -1)"
echo "→ JAVA_HOME: $JAVA_HOME"
echo "→ Working directory: $(pwd)"

if command -v gradle &>/dev/null; then
  GRADLE_CMD="gradle"
  echo "→ Using system Gradle: $(gradle --version 2>&1 | head -1)"
else
  GRADLE_CMD="./gradlew"
  echo "→ Using Gradle wrapper"
fi

echo "→ Launching via $GRADLE_CMD :app:run ..."
echo ""

exec env \
  JAVA_HOME="$JAVA_HOME" \
  JAVA_TOOL_OPTIONS="-Dawt.useSystemAAFontSettings=on -Dswing.aatext=true" \
  $GRADLE_CMD :app:run --no-daemon --console=plain \
    -Dorg.gradle.java.installations.paths="$JAVA_HOME" \
    -Dskiko.renderApi=SOFTWARE \
    2>&1
