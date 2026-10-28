#!/usr/bin/env bash
# Real user setup flow gate: install -> open app -> wizard "Open Input Method Settings" -> enable in
# system settings -> pick input method as a user would -> focus a field -> keyboard shown.
# NO ime enable/set, NO settings-provider writes, NO force-stop. Every screen is captured and its
# visible texts logged, so unknown steps are reported, not guessed.
set -u
APK="$1"; TARGET_APK="$2"
PKG="com.wrenboard.keyboard"; TPKG="com.wrenboard.imetarget"
D=shots/setup; mkdir -p "$D"; R="$D/results.txt"; : > "$R"
note() { echo "$1" | tee -a "$R"; }
STEP=0
dumpui() { adb shell rm -f /sdcard/u.xml; for i in 1 2 3; do adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1 && break; sleep 1; done; adb shell cat /sdcard/u.xml | tr -d '\r'; }
texts() { echo "$1" | tr '>' '>\n' | grep -o '\(text\|content-desc\)="[^"]\+"' | sed 's/^[a-z-]*=//' | sort -u | tr '\n' '|'; }
snap() { STEP=$((STEP+1)); local n; n=$(printf "%02d_%s" $STEP "$1"); adb exec-out screencap -p > "$D/$n.png"; local x; x=$(dumpui); echo "$x" > "$D/$n.xml"; note "SCREEN $n texts: $(texts "$x")"; }
# click first node whose text/content-desc matches regex $1 (case-insens). returns 0 if clicked.
click_re() {
  local x b; x=$(dumpui)
  b=$(echo "$x" | tr '>' '>\n' | grep -i -E "(text|content-desc)=\"($1)\"" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
  [ -z "$b" ] && return 1
  local nums; nums=$(echo "$b" | grep -o '[0-9]*' | tr '\n' ' '); set -- $nums
  adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 1.5; return 0
}
default_now() { adb shell settings get secure default_input_method | tr -d '\r'; }
( : > "$D/flipwatch.txt"; prev=""; while true; do c=$(default_now); [ "$c" != "$prev" ] && echo "$(adb shell date +%H:%M:%S) default_input_method -> $c" >> "$D/flipwatch.txt"; prev=$c; sleep 0.5; done ) &
WPID=$!; trap "kill $WPID 2>/dev/null" EXIT

note "== install (user-style: adb install, then launch from launcher) =="
adb install -r "$APK" >> "$R" 2>&1 || { note "FAIL: install"; exit 1; }
note "initial default IME: $(default_now)"
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
snap wizard_step1
note "== wizard step 1: tap 'Open Input Method Settings' =="
click_re "Open Input Method Settings" && note "tapped wizard button" || note "FAIL: wizard button not found"
sleep 2; snap system_settings_keyboards
note "== enable WrenBoard toggle in system settings =="
if click_re "WrenBoard"; then
  sleep 1.5; snap after_toggle_tap
  click_re "OK|Ok|Accept|Continue" && note "confirmed security dialog" || note "no confirm dialog found (or none shown)"
  sleep 1; snap after_confirm
else
  note "FAIL: no WrenBoard entry on the keyboards settings screen"
fi
note "enabled IMEs now: $(adb shell settings get secure enabled_input_methods | tr -d '\r')"
adb shell input keyevent KEYCODE_BACK; sleep 2; snap back_in_wizard_a
for step in 2 3 4; do
  note "== wizard step $step: look for the next action =="
  if click_re "Switch Input Methods|Switch input methods|Switch Input Method|Select WrenBoard|Choose WrenBoard|Choose Input Method|Select Input Method|Switch|Choose|Select"; then
    sleep 2; snap wizard_action_$step
    if click_re "WrenBoard"; then note "picked WrenBoard in picker"; sleep 2; snap after_pick_$step; fi
  else
    note "no known action button on this screen (see SCREEN line for visible texts)"
  fi
  [ "$(default_now)" = "$PKG/org.futo.inputmethod.latin.LatinIME" ] && break
  adb shell input keyevent KEYCODE_BACK >/dev/null 2>&1; sleep 1; snap back_in_wizard_b$step
done
note "default IME after user-style flow: $(default_now)"
echo "$(default_now)" | grep -q "$PKG" && note "SETUP-FLOW: WrenBoard became default via UI" || note "SETUP-FLOW: INCOMPLETE - WrenBoard not default via UI (see screens)"
note "== focus a field and look for the keyboard =="
adb install -r "$TARGET_APK" >> "$R" 2>&1
adb shell am start -n "$TPKG/.TestInput" >/dev/null 2>&1; sleep 3
x=$(dumpui); b=$(echo "$x" | tr '>' '>\n' | grep 'android.widget.EditText' | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1)
nums=$(echo "$b" | grep -o '[0-9]*' | tr '\n' ' '); set -- $nums
[ -n "${1:-}" ] && adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 ))
for i in 1 2 3 4 5 6 7 8 9 10; do sleep 1; c=$(default_now); echo "$c" | grep -q "$PKG" || { note "DEFAULT FLIPPED at t+${i}s -> $c"; break; }; done
sleep 1; snap keyboard_visible
note "focus: $(adb shell dumpsys window | grep -i mCurrentFocus | head -1 | tr -s ' ')"
note "ime-state: $(adb shell dumpsys input_method | grep -E 'mInputShown|mCurId' | tr -s ' ' | tr '\n' ' ')"
adb shell dumpsys input_method > "$D/dumpsys_ime.txt" 2>&1
adb logcat -d > "$D/logcat_full.txt" 2>&1
note "final default IME: $(default_now)"
note "== done =="
