#!/usr/bin/env bash
# Compile-checks the Windows helper on Linux/macOS with Mono and runs its pure-logic tests (JSON escaping, request parsing, key codes).
#
# Why: the helper builds on Windows with the Framework MSBuild, whose compiler is C# 5. `-langversion:5` makes Mono reject any C# 6+ syntax
# (?. $"" nameof => members) here, long before the Windows CI leg would. UI Automation is not available off Windows, so UiaStubs.cs stands in
# for it: this proves the code compiles and the logic is right, not that UI Automation behaves.
#
# Needs: mono-mcs and libmono-system-windows-forms4.0-cil, libmono-system-drawing4.0-cil, libmono-windowsbase4.0-cil (Debian/Ubuntu).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
refs="-r:System.Windows.Forms.dll -r:System.Drawing.dll -r:System.Core.dll -r:WindowsBase.dll"
echo ">> compile the helper (C# 5)"
mcs -langversion:5 -warn:4 -target:exe -out:"$out/helper.exe" $refs "$here"/../*.cs "$here/UiaStubs.cs"
echo ">> compile and run the logic tests"
mcs -langversion:5 -warn:4 -main:JsonTests -target:exe -out:"$out/tests.exe" $refs "$here"/../*.cs "$here/UiaStubs.cs" "$here/JsonTests.cs"
mono "$out/tests.exe"
echo ">> sources must be pure ASCII (no code page may misread them)"
if grep -nP '[^\x00-\x7F]' "$here"/../*.cs; then echo "non-ASCII characters found" >&2; exit 1; fi
echo "helper checks passed"
