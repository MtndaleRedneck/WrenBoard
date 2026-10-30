#!/usr/bin/env bash
# WrenBoard 1.2 checks on a booted emulator, driven by the DEBUG build's WBDBG logcat geometry
# (java/src/org/futo/inputmethod/latin/uix/WbDebug.kt): keys are tapped where the keyboard says they are,
# not where a pixel model guesses, so the checks survive the new skin.
# Usage: wb12-check.sh <debug apk> <ime-target apk>     (WB_LEGS=comma list, default all)
set -u
APK="$1"; TAPK="$2"
PKG=com.wrenboard.keyboard; TPKG=com.wrenboard.imetarget
LEGS=",${WB_LEGS:-setup,keyboard,period,caps,shift,clip,toolbar,emoji,clipboard,settings},"
mkdir -p shots; R=shots/results.txt; : > "$R"
FAILS=0
note() { echo "$*" | tee -a "$R"; }
pass() { note "PASS: $*"; }
fail() { note "FAIL: $*"; FAILS=$((FAILS+1)); }
leg() { case "$LEGS" in *",$1,"*) return 0;; *) return 1;; esac; }
shot() { adb exec-out screencap -p > "shots/$1.png"; note "shot $1"; }
dumpui() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb exec-out cat /sdcard/u.xml > "shots/$1.xml" 2>/dev/null; }
uitext() { sed 's/>/>\n/g' "shots/$1.xml" | grep -o 'text="[^"]*"' | sed 's/text="//;s/"$//' | grep -v '^$' | tr '\n' '|'; }
node_center() { # node_center <xmlname> <exact text>
  sed 's/>/>\n/g' "shots/$1.xml" | grep "text=\"$2\"" | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}
dbg() { adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd "$1" >/dev/null 2>&1; sleep "${2:-0.6}"; }
geom() { adb logcat -c; dbg geom 1.0; adb logcat -d -s WBDBG:I > shots/geom_$1.txt; [ "${2:-use}" = use ] && cp shots/geom_$1.txt shots/geom.txt; return 0; }
kxy() { python3 tools/wb12_kv.py shots/geom.txt xy KEY "$1" "$2"; }
tapk() { local xy; xy=$(kxy label "$1") || { fail "no key labelled '$1' in geometry"; return 1; }; adb shell input tap $xy; sleep 0.35; }
tapcode() { local xy; xy=$(kxy code "$1") || { fail "no key with code $1 in geometry"; return 1; }; adb shell input tap $xy; sleep 0.35; }
type_word() { local w="$1" i; for ((i=0;i<${#w};i++)); do tapk "${w:$i:1}" || return 1; done; }
field() { sleep 0.4; adb logcat -d -s IMETARGET:I 2>/dev/null | tr -d '\r' | grep 'FIELD=\|CLEARED' | tail -1 | sed -n 's/.*IMETARGET: FIELD=//p'; }
clear_field() { adb shell am broadcast -a com.wrenboard.imetarget.CLEAR -p "$TPKG" >/dev/null 2>&1; sleep 0.8; }
sugg() { adb logcat -d -s WBDBG:I | tr -d '\r' > shots/_s.txt; python3 tools/wb12_kv.py shots/_s.txt sugg; }
elem() { geom "$1" keep >/dev/null; python3 tools/wb12_kv.py shots/geom_$1.txt kbd elementId; }
focus_target() {
  adb shell am start -S -n "$TPKG/.TestInput" >/dev/null 2>&1; sleep 3
  dumpui target
  local b; b=$(sed 's/>/>\n/g' shots/target.xml | grep 'class="android.widget.EditText"' | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}')
  [ -n "$b" ] && adb shell input tap $b
  sleep 3
}
longpress_slide() { # longpress_slide <x> <y> <hold s> <x2> <y2>  (down, hold, slide in 3 steps, up)
  adb shell input motionevent DOWN "$1" "$2"; sleep "$3"
  local mx=$(( ($1+$4)/2 )) my=$(( ($2+$5)/2 ))
  adb shell input motionevent MOVE "$mx" "$my"; sleep 0.15
  adb shell input motionevent MOVE "$4" "$5"; sleep 0.3
}
release() { adb shell input motionevent UP "$1" "$2"; sleep 0.6; }

adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
adb shell wm size 1080x2316; adb shell wm density 420
note "display: $(adb shell wm size | tr -d '\r' | tr '\n' ' ') $(adb shell wm density | tr -d '\r' | tr '\n' ' ')"
note "== install =="
adb install -r "$APK" >> "$R" 2>&1 || { fail "install WrenBoard"; exit 1; }
adb install -r "$TAPK" >> "$R" 2>&1 || { fail "install ime-target"; exit 1; }
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
IME=""
for i in $(seq 1 30); do IME=$(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1); [ -n "$IME" ] && break; sleep 2; done
note "ime id: $IME"

if leg setup; then
  note "== SETUP: no wall, settings usable before WrenBoard is on =="
  adb shell ime disable "$IME" >/dev/null 2>&1
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  shot s01_settings_not_enabled; dumpui s01; T=$(uitext s01); note "texts: ${T:0:400}"
  echo "$T" | grep -q "WrenBoard is turned off" && pass "banner when not enabled" || fail "banner when not enabled"
  echo "$T" | grep -q "Step 1\|Step 2\|Open Input Method Settings" && fail "old setup wall still shown" || pass "no setup wall (not enabled)"
  echo "$T" | grep -qi "Keyboard\|Languages\|Typing" && pass "settings items reachable while not enabled" || fail "settings items not visible while not enabled"
  # a settings item is usable: open the first navigation row that is not the banner
  XY=$(node_center s01 "Languages &amp; Models"); [ -z "$XY" ] && XY=$(node_center s01 "Keyboard &amp; Typing")
  if [ -n "$XY" ]; then adb shell input tap $XY; sleep 2; shot s02_settings_subpage_not_enabled; dumpui s02; note "subpage texts: $(uitext s02 | cut -c1-300)"; adb shell input keyevent KEYCODE_BACK; sleep 1; fi
  adb shell ime enable "$IME" >/dev/null 2>&1
  adb shell am force-stop "$PKG"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  shot s03_settings_enabled_not_active; dumpui s03; T=$(uitext s03); note "texts: ${T:0:300}"
  echo "$T" | grep -q "WrenBoard is not your keyboard" && pass "banner when enabled but not active" || fail "banner when enabled but not active"
  echo "$T" | grep -q "Step 2" && fail "Step 2 wall still shown" || pass "no Step 2 wall"
  XY=$(node_center s03 "Switch")
  if [ -n "$XY" ]; then adb shell input tap $XY; sleep 2; shot s04_switch_picker; dumpui s04; P=$(uitext s04); note "picker texts: ${P:0:300}"
    echo "$P" | grep -qi "keyboard\|input method\|WrenBoard" && pass "Switch opens the keyboard picker" || fail "Switch did not open the picker"
    adb shell input keyevent KEYCODE_BACK; sleep 1
  else fail "no Switch button found"; fi
  adb shell ime set "$IME" >/dev/null 2>&1
  adb shell am force-stop "$PKG"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  shot s05_settings_active; dumpui s05; T=$(uitext s05)
  echo "$T" | grep -q "WrenBoard is turned off\|WrenBoard is not your keyboard" && fail "banner still shown when active" || pass "no banner when active"
  adb shell input keyevent KEYCODE_HOME
fi

adb shell ime enable "$IME" >/dev/null 2>&1; adb shell ime set "$IME" >/dev/null 2>&1
note "default IME: $(adb shell settings get secure default_input_method | tr -d '\r')"
adb logcat -c
focus_target
shot k01_keyboard_empty_field
geom letters
KBDY=$(python3 tools/wb12_kv.py shots/geom.txt kbd y); KBDH=$(python3 tools/wb12_kv.py shots/geom.txt kbd h)
note "keyboard view y=$KBDY h=$KBDH; keys: $(python3 tools/wb12_kv.py shots/geom.txt list KEY | wc -l)"
python3 tools/wb12_kv.py shots/geom.txt list KEY > shots/keys_letters.txt

if leg keyboard; then
  note "== KEYBOARD: starter words, 123 hint =="
  S=$(sugg); note "suggestions in empty field: [$S]"
  [ -n "$S" ] && pass "starter words shown in an empty field ($S)" || fail "empty field shows no starter words"
  XY=$(kxy code -3 2>/dev/null || true); note "123 key at: $XY"
  clear_field
  type_word hello; F=$(field); case "$F" in hello|Hello) pass "typing hello ($F)";; *) fail "typing hello (got '$F')";; esac
  clear_field
fi

if leg shift; then
  note "== SHIFT: mid-word shift changes only the next letter =="
  clear_field; type_word ok; tapcode 32
  type_word y; tapcode -1; type_word o; type_word u
  F=$(field); case "$F" in *" yOu") pass "y shift ou = yOu ('$F')";; *) fail "mid-word shift (wanted '... yOu', got '$F')";; esac
  clear_field
fi

if leg period; then
  note "== PERIOD long-press (P0-1) =="
  PXY=$(kxy code 46) || fail "no period key"
  read -r PX PY <<< "$PXY"
  for mode in default quick; do
    if [ $mode = quick ]; then dbg "prefbool:pref_enable_alt_period_key=true" 2.0; focus_target; geom period_quick >/dev/null; PXY=$(kxy code 46); read -r PX PY <<< "$PXY"; fi
    clear_field; adb logcat -c
    adb shell input motionevent DOWN "$PX" "$PY"; sleep 1.2
    shot p_${mode}_popup_open
    adb logcat -d -s WBDBG:I | tr -d '\r' > shots/more_$mode.txt
    python3 tools/wb12_kv.py shots/more_$mode.txt list MORE > shots/more_${mode}_keys.txt
    release "$PX" "$PY"
    NM=$(wc -l < shots/more_${mode}_keys.txt); note "[$mode] popup keys ($NM): $(awk '{print $2}' shots/more_${mode}_keys.txt | tr '\n' ' ')"
    [ "$NM" -gt 0 ] || { fail "[$mode] period long-press showed no panel"; continue; }
    [ $mode = quick ] && { [ "$(awk '{print $2}' shots/more_${mode}_keys.txt | tr '\n' ' ')" = "! , . ? " ] && pass "[quick] popup reads ! , . ? left to right" || fail "[quick] popup order is not ! , . ?"; }
    i=0
    while read -r code label cx cy w h; do
      i=$((i+1)); [ $i -gt 6 ] && break
      clear_field
      longpress_slide "$PX" "$PY" 1.2 "$cx" "$cy"
      [ $i -eq 1 ] && shot p_${mode}_popup_on_first_item
      release "$cx" "$cy"
      F=$(field)
      want="$label"; [ "$label" = "_" ] && want=" "
      if [ "$F" = "$want" ]; then pass "[$mode] released on '$label' typed '$F'"; else fail "[$mode] released on '$label' typed '$F'"; fi
    done < shots/more_${mode}_keys.txt
  done
  clear_field
fi

if leg caps; then
  note "== CAPS LOCK word must not poison later suggestions (P0-2) =="
  clear_field; type_word ok; tapcode 32; clear_field
  E=$(elem c0); note "element at start: $E"
  for t in 1 2 3 4; do [ "$E" = "3" ] && break; tapcode -1; E=$(elem c$t); note "after shift tap $t: element $E"; done
  [ "$E" = "3" ] && pass "caps lock reached by tapping shift" || fail "caps lock not reached (element $E)"
  shot c01_caps_lock
  type_word nsnana   # letters tapped at their positions; caps lock makes them NSNANA
  for t in 1 2 3; do [ "$E" = "0" ] && break; tapcode -1; E=$(elem cx$t); done
  note "element after leaving caps lock: $E"
  tapcode 32; adb logcat -c
  type_word an; S1=$(sugg); note "after 'an': [$S1]"; shot c02_after_caps_an
  type_word d; S2=$(sugg); note "after 'and': [$S2]"
  F=$(field); note "field: '$F'"
  bad=$(echo "$S1|$S2" | tr '|' '\n' | grep -E '^[A-Z]{2,}$' | tr '\n' ' ')
  [ -z "$bad" ] && pass "no all-caps junk after a caps-lock word" || fail "all-caps suggestions after a caps-lock word: $bad"
  echo "$S2" | grep -qi '\band\b' && pass "'and' offered after typing and" || note "INFO: 'and' not in strip after typing and: [$S2]"
  clear_field
fi

if leg clip; then
  note "== HOLD 123 = clipboard, key shows the clipboard glyph (P0-4) =="
  focus_target; geom clip >/dev/null
  CXY=$(kxy code -3 2>/dev/null) || CXY=$(python3 tools/wb12_kv.py shots/geom.txt list KEY | awk '$2 ~ /123/ {print $3, $4; exit}')
  note "123 key at: $CXY"
  read -r CX CY <<< "$CXY"
  python3 - "$CX" "$CY" <<'PY'
import sys
from PIL import Image
x, y = int(sys.argv[1]), int(sys.argv[2])
im = Image.open("shots/k01_keyboard_empty_field.png")
im.crop((max(0, x-170), max(0, y-110), x+170, y+110)).resize((680, 440)).save("shots/k02_123_key_zoom.png")
PY
  note "shot k02_123_key_zoom"
  adb logcat -c
  adb shell input motionevent DOWN "$CX" "$CY"; sleep 1.4; adb shell input motionevent UP "$CX" "$CY"; sleep 1.5
  shot k03_hold123_clipboard
  W=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'WINDOW open' | tail -1); note "window: $W"
  echo "$W" | grep -q clipboard_history && pass "hold 123 opens the clipboard" || fail "hold 123 did not open the clipboard ($W)"
  dbg close 1.0
fi

note "== logcat crashes =="
adb logcat -d -b crash > shots/crash.txt 2>&1
C=$(grep -c "FATAL EXCEPTION" shots/crash.txt); [ "$C" -eq 0 ] && pass "no FATAL EXCEPTION in crash buffer" || fail "$C FATAL EXCEPTION(s) in crash buffer"
note "RESULT: $FAILS failure(s)"
[ "$FAILS" -eq 0 ]
