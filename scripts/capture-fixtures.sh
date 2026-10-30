#!/usr/bin/env bash
# UIScope fixture capture (plan P0-7, Appendix K "Round 0"). macOS / Linux.
#
# Saves the RAW output of `adb exec-out uiautomator dump /dev/stdout` and `adb exec-out screencap -p` for a few screens.
# It calls adb directly and never any UIScope code, so the fixtures cannot depend on the code they will test.
#
# Usage:   bash scripts/capture-fixtures.sh [output-folder]
# Env:     ADB=/path/to/adb          (default: adb on PATH)
#          ANDROID_SERIAL=<serial>   (needed only if more than one device is attached)
#
# PRIVACY: dumps and screenshots contain everything visible on the screen. Capture screens with no personal data
# (use test accounts / empty chats), and look at the files before you share them.
set -euo pipefail

ADB="${ADB:-adb}"
OUT="${1:-captures/fixtures-$(date +%Y%m%d-%H%M%S)}"

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "ERROR: adb not found ('$ADB'). Install Android platform-tools and put adb on your PATH," >&2
  echo "       or run:  ADB=/full/path/to/adb bash scripts/capture-fixtures.sh" >&2
  exit 1
fi

"$ADB" start-server >/dev/null 2>&1 || true

attached=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')
count=$(printf '%s\n' "$attached" | grep -c . || true)
if [ "$count" -eq 0 ]; then
  echo "ERROR: no authorised device found. Turn on USB debugging, connect the phone, accept the" >&2
  echo "       'Allow USB debugging?' prompt on the phone, then run this script again. 'adb devices' shows:" >&2
  "$ADB" devices >&2
  exit 1
fi
if [ "$count" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
  echo "ERROR: $count devices attached. Pick one:  ANDROID_SERIAL=<serial> bash scripts/capture-fixtures.sh" >&2
  printf '%s\n' "$attached" >&2
  exit 1
fi
SERIAL="${ANDROID_SERIAL:-$attached}"
adb_dev() { "$ADB" -s "$SERIAL" "$@"; }

mkdir -p "$OUT"
prop() { adb_dev shell getprop "$1" | tr -d '\r'; }
MODEL="$(prop ro.product.model)"
MAKER="$(prop ro.product.manufacturer)"
RELEASE="$(prop ro.build.version.release)"
SDK="$(prop ro.build.version.sdk)"
SIZE="$(adb_dev shell wm size | tr -d '\r' | tail -n 1)"
DENSITY="$(adb_dev shell wm density | tr -d '\r' | tail -n 1)"
echo "Device: $MAKER $MODEL, Android $RELEASE (API $SDK), $SIZE, $DENSITY"
echo "Output: $OUT"
echo

names=(settings compose webview long-list dialog)
prompts=(
  "Open the Android Settings app on its main screen."
  "Open a Jetpack Compose app (skip if you have none)."
  "Open a web page inside an app that uses a WebView (a browser app is NOT a WebView)."
  "Open a long scrolling list (for example Contacts with several entries, or a settings list)."
  "Open any app and trigger a dialog over a screen (for example a permission prompt)."
)

captured=()
for i in "${!names[@]}"; do
  name="${names[$i]}"
  echo "[$((i + 1))/${#names[@]}] ${prompts[$i]}"
  read -r -p "    Press Enter when the screen is ready (or type s and Enter to skip): " answer
  if [ "$answer" = "s" ] || [ "$answer" = "S" ]; then echo "    skipped"; echo; continue; fi

  adb_dev exec-out uiautomator dump /dev/stdout > "$OUT/$name.dump.txt" || echo "    WARNING: dump command failed (exit $?). The file is kept for diagnosis."
  adb_dev exec-out screencap -p > "$OUT/$name.png" || echo "    WARNING: screencap command failed (exit $?). The file is kept for diagnosis."

  if grep -q "<hierarchy" "$OUT/$name.dump.txt"; then
    echo "    dump: $(wc -c < "$OUT/$name.dump.txt" | tr -d ' ') bytes, $(grep -o '<node ' "$OUT/$name.dump.txt" | wc -l | tr -d ' ') nodes"
  else
    echo "    WARNING: no <hierarchy> in the dump (screen secure/locked, or the tool printed an error). Raw output kept:"
    head -c 300 "$OUT/$name.dump.txt" | sed 's/^/        /'; echo
  fi
  if [ "$(head -c 4 "$OUT/$name.png" | tail -c 3)" != "PNG" ]; then
    echo "    WARNING: the screenshot is not a PNG (a secure app blocks screenshots). Raw output kept."
  fi
  captured+=("$name")
  echo
done

{
  echo "UIScope fixture capture"
  echo "Date (UTC):      $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "Device:          $MAKER $MODEL"
  echo "Android version: $RELEASE (API $SDK)"
  echo "Screen:          $SIZE, $DENSITY"
  echo "adb:             $("$ADB" version | head -n 1 | tr -d '\r')"
  echo "Command (dump):  adb exec-out uiautomator dump /dev/stdout"
  echo "Command (shot):  adb exec-out screencap -p"
  echo "Screens:         ${captured[*]:-none}"
  echo
  echo "Files are raw adb output, unmodified. They contain on-screen text: review before sharing."
} > "$OUT/README.txt"

echo "Done. Hand the folder '$OUT' to the agent (it contains README.txt with the device details)."
