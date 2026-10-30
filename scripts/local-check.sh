#!/usr/bin/env bash
# Local stand-in for `./gradlew check` for environments where Gradle cannot download anything (no Maven Central / Gradle
# distribution). It compiles core and engine with kotlinc and runs every *Test class with JUnit 4. It does NOT replace Gradle:
# no detekt, no Compose, no `app`/`ui` modules, a Debian-packaged SQLite driver instead of the catalog's, JUnit 4 instead of JUnit 5, coroutines 1.5 instead of the catalog's 1.8.1.
#
# One-time setup (what was used when this was written; all of it was reachable without Maven Central):
#   KOTLINC_HOME : unzip https://github.com/JetBrains/kotlin/releases/download/v2.0.0/kotlin-compiler-2.0.0.zip   (gives kotlinc/ )
#   JUNIT_DIR    : apt-get download junit4 libhamcrest-java ; dpkg-deb -x each .deb into a folder ; use <folder>/usr/share/java
#   SNAKEYAML_JAR: apt-get download libyaml-snake-java ; dpkg-deb -x ; use its usr/share/java/snakeyaml.jar
#   SQLITE_JAR   : apt-get download libxerial-sqlite-jdbc-java libxerial-sqlite-jdbc-jni libslf4j-java ; dpkg-deb -x each ; use
#                  usr/share/java/xerial-sqlite-jdbc-*.jar, usr/share/java/slf4j-api.jar and usr/lib/*/jni (SQLITE_JNI_DIR)
#   A JRE 21 is enough (no javac needed).
#
# Usage:  bash scripts/local-check.sh [core|engine|all]          (default all; set UPDATE_GOLDEN=1 to rewrite golden files)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLINC_HOME="${KOTLINC_HOME:-/tmp/kotlinc}"
JUNIT_DIR="${JUNIT_DIR:-/tmp/apt/ex2/usr/share/java}"
SNAKEYAML_JAR="${SNAKEYAML_JAR:-$(ls /tmp/apt/ex3/usr/share/java/snakeyaml*.jar 2>/dev/null | head -1)}"
SQLITE_JAR="${SQLITE_JAR:-$(ls /tmp/apt/ex4/usr/share/java/xerial-sqlite-jdbc-*.jar 2>/dev/null | head -1)}"
SLF4J_JAR="${SLF4J_JAR:-$(ls /tmp/apt/ex5/usr/share/java/slf4j-api.jar 2>/dev/null | head -1)}"
SQLITE_JNI_DIR="${SQLITE_JNI_DIR:-$(ls -d /tmp/apt/ex4/usr/lib/*/jni 2>/dev/null | head -1)}"
OUT="${OUT:-/tmp/uiscope-local-check}"
L="$KOTLINC_HOME/lib"
for f in "$KOTLINC_HOME/bin/kotlinc" "$L/kotlinx-coroutines-core-jvm.jar" "$L/kotlin-test-junit.jar" "$JUNIT_DIR/junit4-4.13.2.jar" "$JUNIT_DIR/hamcrest-core.jar" "$SNAKEYAML_JAR" "$SQLITE_JAR" "$SLF4J_JAR"; do
  [ -f "$f" ] || { echo "Missing $f: see the setup notes at the top of this script." >&2; exit 2; }
done
LIBS="$L/kotlin-stdlib.jar:$L/kotlinx-coroutines-core-jvm.jar:$L/kotlin-test.jar:$L/kotlin-test-junit.jar:$JUNIT_DIR/junit4-4.13.2.jar:$JUNIT_DIR/hamcrest-core.jar:$SNAKEYAML_JAR:$SQLITE_JAR:$SLF4J_JAR"
rm -rf "$OUT"; mkdir -p "$OUT/gen/com/titanicbhai/uiscope"
VERSION="$(tr -d '[:space:]' < "$ROOT/version.txt")"; SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
cat > "$OUT/gen/com/titanicbhai/uiscope/BuildInfo.kt" <<EOT
package com.titanicbhai.uiscope

object BuildInfo {
    const val VERSION: String = "$VERSION"
    const val BUILD_DATE: String = "$(date +%F)"
    const val COMMIT: String = "$SHA"
}
EOT
compile() { # name classpath [flags and source folders...]
  local name=$1 cp=$2; shift 2; local srcs=() flags=()
  for d in "$@"; do if [[ "$d" == -* ]]; then flags+=("$d"); elif [ -d "$d" ]; then srcs+=("$d"); fi; done
  [ ${#srcs[@]} -eq 0 ] && return 0
  echo ">> kotlinc $name"
  "$KOTLINC_HOME/bin/kotlinc" -no-stdlib -no-reflect -cp "$cp" -jvm-target 21 "${flags[@]}" -d "$OUT/$name" "${srcs[@]}" 2>&1 | grep -v "^warning: .*classpath entry" | sed 's#^#   #'
  [ "${PIPESTATUS[0]}" -eq 0 ] || { echo "COMPILE FAILED: $name"; exit 1; }
}
MODE="${1:-all}"
compile core-main "$LIBS" "$ROOT/core/src/main/kotlin" "$OUT/gen"
compile core-fixtures "$LIBS:$OUT/core-main" "$ROOT/core/src/testFixtures/kotlin"
CORE_CP="$LIBS:$OUT/core-main:$OUT/core-fixtures"
compile core-test "$CORE_CP" "$ROOT/core/src/test/kotlin"
if [ "$MODE" != core ]; then
  compile engine-main "$CORE_CP" "$ROOT/engine/src/main/kotlin"
  compile engine-test "$CORE_CP:$OUT/engine-main" -Xfriend-paths="$OUT/engine-main" "$ROOT/engine/src/test/kotlin"
fi
run() { # module classes-dir classpath
  local module=$1 dir=$2 cp=$3
  [ -d "$dir" ] || return 0
  local classes; classes=$(cd "$dir" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort | tr '\n' ' ')
  [ -z "$classes" ] && return 0
  echo ">> JUnit $module"
  (cd "$ROOT/$module" && java -Xmx1g -Dorg.sqlite.lib.path="$SQLITE_JNI_DIR" -Dorg.sqlite.lib.name=libsqlitejdbc.so -cp "$cp:$dir:$ROOT/core/src/test/resources" org.junit.runner.JUnitCore $classes 2>&1 | grep -a -v "^\s*at \(org.junit\|java.base\|jdk.internal\|kotlin.test\)")
}
run core "$OUT/core-test" "$CORE_CP"
[ "$MODE" != core ] && run engine "$OUT/engine-test" "$CORE_CP:$OUT/engine-main"
exit 0
