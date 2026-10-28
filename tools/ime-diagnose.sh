#!/usr/bin/env bash
# Control-group IME registration diagnostic.
# Installs an APK on the booted emulator and reports ONLY whether the system
# registers its IME through the normal path (ime list / ime enable).
# Usage: ime-diagnose.sh <apk> <expected-package-prefix>
set -u
APK="$1"
PKG="$2"
R=shots/diagnose-$(echo "$PKG" | tr '.' '_').txt
: > "$R"

echo "== control install: $APK ==" | tee -a "$R"
adb install -r "$APK" >> "$R" 2>&1 || { echo "CONTROL-RESULT: install failed" | tee -a "$R"; exit 0; }
sleep 2
echo "--- ime list -a entries for $PKG ---" >> "$R"
LIST=$(adb shell ime list -a -s)
echo "$LIST" >> "$R"
IME=$(echo "$LIST" | grep "^$PKG/" | head -1)
if [ -z "$IME" ]; then
  echo "CONTROL-RESULT: NOT-REGISTERED (package absent from ime list -a)" | tee -a "$R"
  echo "--- logcat: InputMethodManagerService / package scan ---" >> "$R"
  adb logcat -d | grep -i "InputMethod\|$PKG" | tail -40 >> "$R" 2>&1
  exit 0
fi
echo "registered id: $IME" | tee -a "$R"
OUT=$(adb shell ime enable "$IME" 2>&1)
echo "$OUT" >> "$R"
if echo "$OUT" | grep -qi "unknown\|cannot"; then
  echo "CONTROL-RESULT: LISTED-BUT-NOT-ENABLEABLE" | tee -a "$R"
else
  echo "CONTROL-RESULT: REGISTERED-AND-ENABLED via normal ime command" | tee -a "$R"
  adb shell ime disable "$IME" >> "$R" 2>&1
fi
