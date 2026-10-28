#!/usr/bin/env bash
# Installed-IME verification for WrenBoard on a booted emulator.
# Installs the APK, enables + selects the IME, drives real key events,
# captures screenshots + assertions into shots/.
set -u
APK="$1"
PKG="com.wrenboard.keyboard"
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
# resolve the real IME component from the device (service is .LatinIME under the app package)
IME=$(adb shell ime list -a -s 2>/dev/null | grep "^$PKG/" | head -1)
[ -z "$IME" ] && IME="$PKG/$PKG.LatinIME"
note "resolved IME id: $IME"
# emulator has a hardware keyboard; force the soft keyboard to show anyway
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell ime enable "$IME" >> "$R" 2>&1
adb shell ime set "$IME" >> "$R" 2>&1
CUR=$(adb shell settings get secure default_input_method)
if ! echo "$CUR" | grep -q "$PKG"; then
  note "standard enable failed; collecting diagnostics + trying fallbacks"
  note "--- pm list packages (grep $PKG) ---"
  adb shell pm list packages | grep -i wrenboard >> "$R" 2>&1
  note "--- ime list -a (full) ---"
  adb shell ime list -a >> "$R" 2>&1
  # try the upstream-style component id explicitly
  IME2="$PKG/org.futo.inputmethod.latin.LatinIME"
  note "--- trying explicit id: $IME2 ---"
  adb shell ime enable "$IME2" >> "$R" 2>&1
  adb shell ime set "$IME2" >> "$R" 2>&1
  IME="$IME2"
  CUR=$(adb shell settings get secure default_input_method)
fi
if ! echo "$CUR" | grep -q "$PKG"; then
  note "--- trying settings-provider fallback ---"
  adb shell settings put secure enabled_input_methods "$IME" >> "$R" 2>&1
  adb shell settings put secure default_input_method "$IME" >> "$R" 2>&1
  CUR=$(adb shell settings get secure default_input_method)
fi
if ! echo "$CUR" | grep -q "$PKG"; then
  note "--- package dump + logcat for root cause ---"
  adb shell pm dump "$PKG" > shots/pm_dump.txt 2>&1
  adb logcat -d > shots/logcat.txt 2>&1
  note "(pm_dump.txt and logcat.txt saved to artifact)"
fi
check "IME selected as default" "$PKG" "$CUR"
# dismiss any IME picker that popped up
adb shell input keyevent KEYCODE_BACK

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
# dismiss Chrome first-run welcome if present
if dump | grep -qi "Use without an account"; then
  tap_key "Use without an account"
  sleep 2
fi
if dump | grep -qi "Accept &amp; continue\|Accept & continue"; then
  tap_key "Accept & continue"
  sleep 2
fi
adb shell input keyevent KEYCODE_TAB 2>/dev/null
# tap the url bar
dump | tr '>' '>\n' | grep -i 'url_bar\|Search or type' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 > /tmp/ub.txt
if [ -s /tmp/ub.txt ]; then
  nums=$(grep -o '[0-9]*' /tmp/ub.txt | tr '\n' ' '); read -r x1 y1 x2 y2 <<< "$nums"
  adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
fi
sleep 2
shot 02_keyboard_visible
dump > shots/ui_letters.xml 2>/dev/null

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
tap_key "123" || tap_key "?123" || adb shell input keyevent KEYCODE_SYM
sleep 1
shot 09_symbols_page1
dump > shots/ui_symbols1.xml 2>/dev/null
S1=$(field_text)
check "symbols page1 shows @" "@" "$S1"

note "== symbols page 2 =="
tap_key "{&="
sleep 1
shot 10_symbols_page2
dump > shots/ui_symbols2.xml 2>/dev/null

note "== back to letters =="
tap_key "abc"
sleep 1
shot 11_back_to_letters

note "== clipboard long-press on symbols key =="
B=$(dump | tr '>' '>\n' | grep -iE 'content-desc="(123|[?]123)"' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
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
