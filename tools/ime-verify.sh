#!/usr/bin/env bash
# Installed-IME verification for WrenBoard on a booted emulator.
# Installs the APK, enables + selects the IME, drives real key events,
# captures screenshots + assertions into shots/.
set -u
APK="$1"
PKG="com.wrenboard.keyboard"
IME="$PKG/org.futo.inputmethod.latin.LatinIME"
mkdir -p shots
R=shots/results.txt
: > "$R"

shot() { adb exec-out screencap -p > "shots/$1.png"; echo "shot $1" >> "$R"; }
note() { echo "$1" | tee -a "$R"; }
check() { # check <name> <expected-substring> <haystack>
  if echo "$3" | grep -qi "$2"; then note "PASS: $1"; else note "FAIL: $1 (wanted '$2' in '${3:0:120}')"; fi
}

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }

# tap_key <content-desc-or-text>: tap center of first matching node
tap_key() {
  local needle="$1" bounds
  bounds=$(dump | tr '>' '>\n' | grep -i "content-desc=\"$needle\"\|text=\"$needle\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
  if [ -z "$bounds" ]; then note "FAIL: key '$needle' not found in hierarchy"; return 1; fi
  local nums x1 y1 x2 y2
  nums=$(echo "$bounds" | grep -o '[0-9]*' | tr '\n' ' ')
  read -r x1 y1 x2 y2 <<< "$nums"
  adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
  sleep 0.4
}

field_text() { dump | tr '>' '>\n' | grep -o 'text="[^"]*"' | sed 's/text="//;s/"$//' | tr '\n' '|'; }

note "== install =="
adb install -r "$APK" >> "$R" 2>&1 || { note "FAIL: install"; exit 1; }
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
adb shell ime enable "$IME" >> "$R" 2>&1
adb shell ime set "$IME" >> "$R" 2>&1
CUR=$(adb shell settings get secure default_input_method)
check "IME selected as default" "$PKG" "$CUR"

note "== first run / setup screen =="
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
shot 01_first_run
FT=$(field_text)
check "first-run shows setup content" "keyboard\|setup\|enable\|WrenBoard" "$FT"
adb shell am force-stop "$PKG"

note "== open Chrome url bar for typing =="
adb shell am start -a android.intent.action.VIEW -d "https://example.com" >/dev/null 2>&1
sleep 6
adb shell input keyevent KEYCODE_TAB 2>/dev/null
# tap the url bar
dump | tr '>' '>\n' | grep -i 'url_bar\|Search or type' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 > /tmp/ub.txt
if [ -s /tmp/ub.txt ]; then
  nums=$(grep -o '[0-9]*' /tmp/ub.txt | tr '\n' ' '); read -r x1 y1 x2 y2 <<< "$nums"
  adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
fi
sleep 2
shot 02_keyboard_visible

note "== lowercase typing =="
for k in h e l l o; do adb shell input keyevent KEYCODE_${k^^}; sleep 0.15; done
sleep 1
shot 03_typed_hello
check "typed hello" "hello" "$(field_text)"

note "== shift single letter =="
adb shell input keyevent KEYCODE_MOVE_HOME
adb shell input keyevent KEYCODE_SHIFT_LEFT
sleep 0.3
shot 04_shift_single_state
adb shell input keyevent KEYCODE_H
sleep 0.5
shot 05_shift_typed
check "shift produced capital H" "Hello" "$(field_text)"

note "== caps lock via shift long-press =="
adb shell input keyevent --longpress KEYCODE_SHIFT_LEFT
sleep 0.6
shot 06_capslock_state
for k in W O R L D; do adb shell input keyevent KEYCODE_${k}; sleep 0.12; done
sleep 0.6
shot 07_capslock_typed
check "caps lock typed WORLD" "WORLD" "$(field_text)"

note "== whole-word hold backspace =="
adb shell input keyevent --longpress KEYCODE_DEL
sleep 2
shot 08_after_word_delete
note "(word-delete outcome inspected visually + next checks)"

note "== symbols page 1 =="
tap_key "?123" || adb shell input keyevent KEYCODE_SYM
sleep 1
shot 09_symbols_page1
S1=$(field_text)
check "symbols page1 shows @" "@" "$S1"

note "== symbols page 2 =="
tap_key "{&=" || tap_key "=\\<"
sleep 1
shot 10_symbols_page2

note "== back to letters =="
tap_key "abc"
sleep 1
shot 11_back_to_letters

note "== clipboard long-press on symbols key =="
B=$(dump | tr '>' '>\n' | grep -i 'content-desc="?123"' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
if [ -n "$B" ]; then
  nums=$(echo "$B" | grep -o '[0-9]*' | tr '\n' ' '); read -r x1 y1 x2 y2 <<< "$nums"
  X=$(( (x1+x2)/2 )); Y=$(( (y1+y2)/2 ))
  adb shell input swipe $X $Y $X $Y 1500
  sleep 1
  shot 12_clipboard_longpress
else
  note "SKIP: ?123 bounds for clipboard long-press"
fi

note "== toolbar visible =="
shot 13_toolbar

note "== dictation tap =="
tap_key "Voice input" || tap_key "Dictation" || tap_key "Microphone"
sleep 3
shot 14_dictation_state

note "== done =="
exit 0
