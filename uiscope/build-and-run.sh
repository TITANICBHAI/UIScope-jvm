#!/usr/bin/env bash
# Quick build-and-run helper for UIScope.
# Run: bash uiscope/build-and-run.sh
set -euo pipefail

cd "$(dirname "$0")"

if ! command -v java &>/dev/null; then
  echo "ERROR: Java not found. Install JDK 17+ from https://adoptium.net" >&2
  exit 1
fi

echo "Java: $(java -version 2>&1 | head -1)"
echo "Gradle: $(gradle --version 2>&1 | grep '^Gradle' || echo 'using wrapper')"
echo ""
echo ">>> Building UIScope (first run downloads ~400 MB of dependencies)..."

GRADLE_CMD="gradle"
if [ ! -f "$GRADLE_CMD" ] && [ -f "./gradlew" ]; then
  GRADLE_CMD="./gradlew"
fi

$GRADLE_CMD :app:run --no-daemon
