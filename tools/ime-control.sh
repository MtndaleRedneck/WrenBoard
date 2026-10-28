#!/usr/bin/env bash
# Control-group A/B for the default-IME flip. Same flow for any IME apk:
# install, normal-path registration, install ime-target, launch, tap field,
# sample default_input_method + enabled_input_methods once per second.
# No typing, no suite. Preserves full logs (no logcat -c).
set -u
APK="$1"; PKGX="$2"; TARGET_APK="$3"; LABEL="$4"
TPKG="com.wrenboard.imetarget"
D="shots/$LABEL"; mkdir -p "$D"
R="$D/results.txt"; : > "$R"
note() { echo "$1" | tee -a "$R"; }
note "== control variant: $LABEL pkg=$PKGX =="
adb install -r "$APK" >> "$R" 2>&1 || { note "CONTROL-RESULT: install failed"; exit 0; }
FACTORS="${FACTORS:-}"
note "FACTORS=$FACTORS"
has() { echo ",$FACTORS," | grep -q ",$1,"; }
if has grant || has all; then adb shell pm grant "$PKGX" android.permission.RECORD_AUDIO 2>/dev/null; note "factor: RECORD_AUDIO grant"; fi
if has wizard || has all; then
  adb shell monkey -p "$PKGX" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
  adb shell uiautomator dump /sdcard/w.xml >/dev/null 2>&1
  adb shell am force-stop "$PKGX"; note "factor: wizard launch+dump+force-stop"
fi
IME=""
for i in $(seq 1 30); do
  IME=$(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKGX/" | head -1)
  [ -n "$IME" ] && break; sleep 2
done
note "enumerated: $(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
[ -z "$IME" ] && { note "CONTROL-RESULT: IME never enumerated"; exit 0; }
adb shell settings put secure show_ime_with_hard_keyboard 1
REG=no
for i in 1 2 3; do
  adb shell ime enable "$IME" >> "$R" 2>&1
  adb shell ime set "$IME" >> "$R" 2>&1
  adb shell settings get secure default_input_method | grep -q "$PKGX" && { REG=yes; break; }
  sleep 3
done
note "NORMAL-REGISTRATION: $REG ($IME)"
[ "$REG" = yes ] || exit 0
if has back || has all; then adb shell input keyevent KEYCODE_BACK; note "factor: BACK key"; fi
if has poller || has all; then
  ( while true; do adb shell settings get secure default_input_method >/dev/null 2>&1; sleep 0.4; done ) &
  POLLPID=$!; trap "kill $POLLPID 2>/dev/null" EXIT; note "factor: 0.4s settings poller"
fi
adb shell dumpsys input_method > "$D/dumpsys_ime_pre.txt" 2>&1
adb shell settings get secure enabled_input_methods > "$D/enabled_pre.txt" 2>&1
sample() { # sample <tag> <seconds>
  local t
  for t in $(seq 1 "$2"); do
    local d e
    d=$(adb shell settings get secure default_input_method | tr -d '\r')
    e=$(adb shell settings get secure enabled_input_methods | tr -d '\r')
    echo "$(adb shell date +%H:%M:%S) [$1 t+${t}s] default=$d enabled=$e" >> "$D/timeline.txt"
    if ! echo "$d" | grep -q "$PKGX"; then
      note "FLIP in $1 at t+${t}s -> $d"
      adb shell dumpsys input_method > "$D/dumpsys_ime_at_flip_$1.txt" 2>&1
      adb logcat -d > "$D/logcat_at_flip_$1.txt" 2>&1
      adb logcat -b system -d > "$D/logcat_system_at_flip_$1.txt" 2>&1
      return 1
    fi
    sleep 1
  done
  note "NO FLIP in $1 (held $2 s)"; return 0
}
: > "$D/timeline.txt"
for c in 1 2 3 4; do
  adb uninstall "$TPKG" >/dev/null 2>&1
  adb install -r "$TARGET_APK" >> "$R" 2>&1
  adb shell am start -n "$TPKG/.TestInput" >/dev/null 2>&1
  sleep 3
  XY=$(adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb shell cat /sdcard/u.xml | tr '>' '>\n' | grep 'android.widget.EditText' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 | grep -o '[0-9]*' | tr '\n' ' ')
  read -r x1 y1 x2 y2 <<< "$XY"
  adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
  sample "cycle$c" 20 || { adb shell ime set "$IME" >> "$R" 2>&1; sleep 2; }
  adb exec-out screencap -p > "$D/cycle${c}.png"
done
adb logcat -d > "$D/logcat_full.txt" 2>&1
note "CONTROL-DONE $LABEL"
