#!/usr/bin/env bash
# Evidence capture of the PUBLISHED WrenBoard 1.10 APK: real keyboard over the deterministic
# ime-target field, typing only the word "hello" through the ordinary input path. No network use by the test.
set -u
APK="$1"; TARGET_APK="$2"; PKG=com.wrenboard.keyboard; TPKG=com.wrenboard.imetarget
D=shots/keyboard; mkdir -p "$D"; R="$D/results.txt"; : > "$R"
note() { echo "$1" | tee -a "$R"; }
shot() { adb exec-out screencap -p > "$D/$1.png"; note "shot $1"; }
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb install -r "$APK" >> "$R" 2>&1 || { note "install failed"; exit 1; }
adb install -r "$TARGET_APK" >> "$R" 2>&1 || { note "target install failed"; exit 1; }
IME=""
for i in $(seq 1 30); do IME=$(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1); [ -n "$IME" ] && break; sleep 2; done
note "IME=$IME"
adb shell ime enable "$IME" >> "$R" 2>&1; adb shell ime set "$IME" >> "$R" 2>&1
adb shell settings put secure show_ime_with_hard_keyboard 1
sleep 15
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am start -n "$TPKG/.TestInput" >/dev/null 2>&1; sleep 4
B=$(adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb exec-out cat /sdcard/u.xml | sed 's/>/>\n/g' | grep 'android.widget.EditText' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
note "field bounds: $B"
nums=$(echo "$B" | grep -o '[0-9]*' | tr '\n' ' '); read -r x1 y1 x2 y2 <<< "$nums"
adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); sleep 4
shot k1_keyboard_empty
adb shell input text hello; sleep 2
shot k2_keyboard_typed_hello
note "default IME: $(adb shell settings get secure default_input_method)"
note "== done =="
