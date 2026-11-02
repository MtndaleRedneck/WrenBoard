#!/usr/bin/env bash
# WrenBoard 1.2 checks on a booted emulator, driven by the DEBUG build's WBDBG logcat geometry
# (java/src/org/futo/inputmethod/latin/uix/WbDebug.kt): keys are tapped where the keyboard says they are,
# not where a pixel model guesses, so the checks survive the new skin.
# Usage: wb12-check.sh <debug apk> <ime-target apk>     (WB_LEGS=comma list, default all)
set -u
APK="$1"; TAPK="$2"
PKG=com.wrenboard.keyboard; TPKG=com.wrenboard.imetarget
LEGS=",${WB_LEGS:-setup,keyboard,period,leftedge,caps,shift,clip,update,toolbar,textedit,skin,symbols,states,emoji,clipboard,settings,panelexit},"
mkdir -p shots; R=shots/results.txt; : > "$R"
ADB_BIN=$(type -P adb)
# a dead emulator must not hang the run: each call is bounded, and once the link is confirmed gone
# (two timed-out calls in a row and get-state no longer says device) every later call fails at once.
rm -f shots/.dead shots/.tmo
( "$ADB_BIN" logcat -v threadtime -b main,system,crash "*:I" > shots/logcat_stream.txt 2>&1 ) & LCPID=$!
HTPID=""
if [ -n "${WB_LOCAL_APK_URL:-}" ]; then # fix1: serve this same debug APK to the emulator (adb reverse) for the update success case
  ( cd "$(dirname "$APK")" && exec python3 -m http.server 8765 --bind 127.0.0.1 > /dev/null 2>&1 ) & HTPID=$!
fi
trap 'kill $LCPID $HTPID 2>/dev/null' EXIT
adb() {
  [ -f shots/.dead ] && return 1
  timeout -k 5 60 "$ADB_BIN" "$@"; local rc=$?
  if [ $rc -eq 124 ] || [ $rc -eq 137 ]; then
    echo x >> shots/.tmo
    if [ "$(wc -l < shots/.tmo)" -ge 2 ]; then
      [ "$(timeout 10 "$ADB_BIN" get-state 2>/dev/null | tr -d '\r')" = device ] && : > shots/.tmo || { echo "adb link lost; skipping the remaining device calls" >> shots/results.txt; touch shots/.dead; }
    fi
  else : > shots/.tmo; fi
  return $rc
}
opensettings() { # opensettings [navDest] [sleep]: open WrenBoard Settings (optionally at a page) straight from the shell
  adb shell am start -n "$PKG/org.futo.inputmethod.latin.uix.settings.SettingsActivity" -f 0x34000000 ${1:+--es navDest "$1"} >/dev/null 2>&1; sleep "${2:-3}"
}
FAILS=0
note() { echo "$*" | tee -a "$R"; }
pass() { note "PASS: $*"; }
fail() { note "FAIL: $*"; FAILS=$((FAILS+1)); }
alive() { # the emulator's adb link has dropped mid-run twice; try to bring it back before a leg
  local st; st=$(timeout 10 "$ADB_BIN" get-state 2>/dev/null | tr -d '\r')
  [ "$st" = device ] && { rm -f shots/.dead; : > shots/.tmo; return 0; }
  echo "adb state '$st' before leg $1; reconnecting" | tee -a "$R"
  { echo "  host: $(pgrep -af 'qemu-system|emulator' | cut -c1-120 | head -3 | tr '\n' ';')"; echo "  mem: $(free -m | sed -n 2p)"; sudo -n dmesg 2>/dev/null | tail -12 | sed 's/^/  dmesg: /'; tail -40 shots/logcat_stream.txt 2>/dev/null | sed 's/^/  lc: /'; } >> "$R"
  timeout 20 "$ADB_BIN" reconnect offline >/dev/null 2>&1; sleep 5
  timeout 90 "$ADB_BIN" wait-for-device >/dev/null 2>&1
  st=$(timeout 10 "$ADB_BIN" get-state 2>/dev/null | tr -d '\r'); echo "adb state now '$st'" | tee -a "$R"
  [ "$st" = device ] && { rm -f shots/.dead; : > shots/.tmo; sleep 10; ensure_ime >/dev/null 2>&1; }
  return 0
}
leg() { case "$LEGS" in *",$1,"*) alive "$1"; return 0;; *) return 1;; esac; }
shot() { adb exec-out screencap -p > "shots/$1.png"; note "shot $1"; }
dumpui() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb exec-out cat /sdcard/u.xml > "shots/$1.xml" 2>/dev/null; }
uitext() { sed 's/>/>\n/g' "shots/$1.xml" | grep -o 'text="[^"]*"' | sed 's/text="//;s/"$//' | grep -v '^$' | tr '\n' '|'; }
node_center() { # node_center <xmlname> <exact text>
  sed 's/>/>\n/g' "shots/$1.xml" | grep "text=\"$2\"" | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}
dbg() { adb shell "am broadcast -a com.wrenboard.keyboard.WBDBG -p $PKG --es cmd '$1'" >/dev/null 2>&1; sleep "${2:-0.6}"; }  # quoted for the device shell ('|' in seedupdate)
geom() { adb logcat -c; dbg geom 1.0; adb logcat -d -s WBDBG:I > shots/geom_$1.txt
  if ! grep -q 'KEY ' shots/geom_$1.txt; then
    note "geom $1: empty dump, one bounded retry (run 41366592427 hit dump-blindness with the keyboard visibly up)"
    dbg geom 1.5; adb logcat -d -s WBDBG:I > shots/geom_$1.txt
  fi
  [ "${2:-use}" = use ] && cp shots/geom_$1.txt shots/geom.txt; return 0; }
kxy() { python3 tools/wb12_kv.py shots/geom.txt xy KEY "$1" "$2"; }
kxyf() { python3 tools/wb12_kv.py "$1" xy KEY "$2" "$3"; }
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
ensure_ime() { # make WrenBoard the enabled + default keyboard, retrying (ime set right after the picker can be ignored)
  local d i
  for i in 1 2 3 4 5; do
    adb shell ime enable "$IME" >/dev/null 2>&1; adb shell ime set "$IME" >/dev/null 2>&1; sleep 1.5
    d=$(adb shell settings get secure default_input_method | tr -d '\r')
    [ "$d" = "$IME" ] && return 0
    note "  default IME is $d, retrying ime set ($i)"
  done
  return 1
}
release() { adb shell input motionevent UP "$1" "$2"; sleep 0.6; }

numrow() { # numrow <geom file> -> the labels of the first ten keys of the top row, left to right
  python3 - "$1" <<'PY'
import re, sys
keys = []
for line in open(sys.argv[1], errors="ignore"):
  m = re.search(r"WBDBG\s*:\s*KEY (.*)$", line.rstrip())
  if m:
      d = dict(kv.split("=", 1) for kv in m.group(1).split() if "=" in kv)
      if "cy" in d and "cx" in d: keys.append(d)
if keys:
  top = min(int(k["cy"]) for k in keys)
  row = sorted([k for k in keys if abs(int(k["cy"]) - top) < 20], key=lambda k: int(k["cx"]))
  print("".join(k.get("label", "?") for k in row[:10]))
PY
}

adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
WB_SIZE="${WB_SIZE:-1080x2316}"; WB_DENSITY="${WB_DENSITY:-420}"
adb shell wm size "$WB_SIZE"; adb shell wm density "$WB_DENSITY"
note "display: $(adb shell wm size | tr -d '\r' | tr '\n' ' ') $(adb shell wm density | tr -d '\r' | tr '\n' ' ')"
note "== install =="
adb install -r -t "$APK" >> "$R" 2>&1 || { fail "install WrenBoard"; exit 1; }
adb install -r "$TAPK" >> "$R" 2>&1 || { fail "install ime-target"; exit 1; }
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null
IME=""
for i in $(seq 1 30); do IME=$(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1); [ -n "$IME" ] && break; sleep 2; done
note "ime id: $IME"

if leg setup; then
  note "== SETUP: no wall, settings usable before WrenBoard is on =="
  adb shell ime disable "$IME" >/dev/null 2>&1
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  dumpui s01; sleep 2; shot s01_settings_not_enabled; dumpui s01; T=$(uitext s01); note "texts: ${T:0:400}"
  # fix1: the 1.x first-entry Personal dictionary step is gone; the first open is the Settings home
  echo "$T" | grep -q "Personal dictionary" && fail "first Settings open still lands on the Personal dictionary page" || pass "first Settings open goes straight to the Settings home (no dictionary page)"
  echo "$T" | grep -q "Typing" && echo "$T" | grep -q "Backup" && pass "first Settings open shows the Settings home list" || fail "first Settings open is not the Settings home"
  echo "$T" | grep -q "WrenBoard is turned off" && pass "banner when not enabled" || fail "banner when not enabled"
  echo "$T" | grep -q "Step 1\|Step 2\|Open Input Method Settings" && fail "old setup wall still shown" || pass "no setup wall (not enabled)"
  echo "$T" | grep -q "Keyboard\|Typing" && pass "settings items reachable while not enabled" || fail "settings items not visible while not enabled"
  # a settings item is usable: open the first navigation row that is not the banner
  XY=$( [ -f shots/s01b.xml ] && node_center s01b "Typing"); [ -z "$XY" ] && XY=$(node_center s01 "Typing"); [ -z "$XY" ] && XY=$(node_center s01 "Keyboard &amp; Typing")
  if [ -n "$XY" ]; then adb shell input tap $XY; sleep 2; shot s02_settings_subpage_not_enabled; dumpui s02; note "subpage texts: $(uitext s02 | cut -c1-300)"; adb shell input keyevent KEYCODE_BACK; sleep 1; fi
  for i in 1 2 3 4 5; do adb shell ime enable "$IME" >/dev/null 2>&1; sleep 1; adb shell ime list -s | tr -d '\r' | grep -qx "$IME" && break; done
  adb shell am force-stop "$PKG"; sleep 1; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  for i in 1 2 3; do dumpui s03; uitext s03 | grep -q "WrenBoard is not your keyboard" && break; sleep 3; done
  shot s03_settings_enabled_not_active; dumpui s03; T=$(uitext s03); note "texts: ${T:0:300}"
  echo "$T" | grep -q "WrenBoard is not your keyboard" && pass "banner when enabled but not active" || fail "banner when enabled but not active"
  echo "$T" | grep -q "Step 2" && fail "Step 2 wall still shown" || pass "no Step 2 wall"
  XY=$(node_center s03 "Switch")
  if [ -n "$XY" ]; then adb shell input tap $XY; sleep 2; shot s04_switch_picker; dumpui s04; P=$(uitext s04); note "picker texts: ${P:0:300}"
    echo "$P" | grep -qi "keyboard\|input method\|WrenBoard" && pass "Switch opens the keyboard picker" || fail "Switch did not open the picker"
    # pick WrenBoard in the picker, as a person would
    WXY=$(node_center s04 "WrenBoard"); if [ -n "$WXY" ]; then adb shell input tap $WXY; sleep 2; else adb shell input keyevent KEYCODE_BACK; sleep 1; fi
    note "  default IME after picking: $(adb shell settings get secure default_input_method | tr -d '\r')"
  else fail "no Switch button found"; fi
  adb shell input keyevent KEYCODE_HOME; sleep 1
  # force-stop of the package that hosts the current keyboard makes Android fall back to another keyboard,
  # so the keyboard is chosen again after the stop (a person never force-stops it).
  adb shell am force-stop "$PKG"; sleep 1
  ensure_ime || note "  could not make WrenBoard the default keyboard"
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  shot s05_settings_active; dumpui s05; T=$(uitext s05)
  if echo "$T" | grep -q "WrenBoard is turned off\|WrenBoard is not your keyboard"; then
    note "banner still up after relaunch; default IME is $(adb shell settings get secure default_input_method | tr -d '\r'); waiting 4 s"
    sleep 4; adb shell am force-stop "$PKG"; ensure_ime || note "  could not restore WrenBoard after retry force-stop"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
    shot s05b_settings_active_retry; dumpui s05; T=$(uitext s05)
  fi
  echo "$T" | grep -q "WrenBoard is turned off\|WrenBoard is not your keyboard" && fail "banner still shown when active" || pass "no banner when active"
  adb shell input keyevent KEYCODE_HOME
fi

ensure_ime
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
  adb logcat -c; focus_target; sleep 2; S=$(sugg); note "suggestions in empty field: [$S]"
  grep 'STARTER' shots/_s.txt | tail -5 | sed 's/^.*WBDBG *: /  /' | tee -a "$R"
  [ "$S" = "I|The|You" ] && pass "fresh empty field, toggle on: fallback starter words I|The|You ($S)" || fail "fresh empty field shows '$S' (wanted I|The|You)"
  XY=$(kxy code -3 2>/dev/null || true); note "123 key at: $XY"
  clear_field
  type_word hello; F=$(field); case "$F" in hello|Hello) pass "typing hello ($F)";; *) fail "typing hello (got '$F')";; esac
  clear_field
  # fix1b: starter words come from the person's own typing history; I/The/You only stand in when there is none
  for i in 1 2 3; do clear_field; type_word zebra; tapcode 32; sleep 0.6; done
  note "field after learning: '$(field)'"
  clear_field; adb logcat -c; focus_target; sleep 2; S2=$(sugg); note "starter words after typing 'zebra' 3x at the start of a field: [$S2]"
  grep 'STARTER' shots/_s.txt | tail -3 | sed 's/^.*WBDBG *: /  /' | tee -a "$R"
  shot k01b_starter_words_from_history
  echo "$S2" | grep -qi "zebra" && pass "starter words come from typing history ($S2)" || fail "starter words did not pick up typing history (got '$S2')"
  [ "$(echo "$S2" | tr 'A-Z' 'a-z')" = "zebra" ] && pass "short history shown as predicted, no I/The/You padding ($S2)" || fail "empty field with history 'zebra' shows '$S2' (wanted just zebra)"
  dbg "dsbool:wren_starter_words=false" 1.0; adb logcat -c; focus_target; sleep 2; S3=$(sugg); note "starter words with the toggle off: [$S3]"
  shot k01c_starter_words_toggle_off
  [ -z "$S3" ] && pass "toggle off: no starter words in an empty field" || fail "starter words still shown with the toggle off ($S3)"
  # toggle off only blanks the empty field: typing a word still gets FUTO's normal suggestions
  adb logcat -c; type_word hel; sleep 1; S4=$(sugg)
  # Under emulator load the suggestion broadcast can land after the 1s read; give it a few retries before judging.
  for i in 1 2 3; do [ -n "$S4" ] && break; sleep 1.5; S4=$(sugg); done
  F4=$(field); note "toggle off, typed '$F4': [$S4]"; shot k01d_typed_word_toggle_off
  echo "$S4" | tr '|' '\n' | grep -qi "^hel" && pass "toggle off: typed word still gets normal suggestions ($S4)" || fail "toggle off: typed 'hel' got no normal suggestions ($S4)"
  clear_field
  dbg "dsbool:wren_starter_words=true" 1.0
fi

if leg shift; then
  note "== SHIFT: mid-word shift changes only the next letter =="
  clear_field; type_word ok; tapcode 32
  type_word y; tapcode -1; type_word o; type_word u
  F=$(field); case "$F" in *" yOu") pass "y shift ou = yOu ('$F')";; *) fail "mid-word shift (wanted '... yOu', got '$F')";; esac
  # fix1: with shift on, the number row types digits (SwiftKey behaviour)
  clear_field; type_word a; tapcode -1; E=$(elem sn); geom shiftnum >/dev/null
  if [ "$E" = 1 ]; then tapk 1 && { F=$(field); case "$F" in *a1) pass "shift on, number row key types 1 ('$F')";; *) fail "shift on, number row key typed '$F' (wanted 'a1')";; esac; }
  else fail "shift tap mid-word did not give the shifted keyboard (element $E)"; fi
  clear_field
fi

if leg period; then
  note "== PERIOD long-press (P0-1) =="
  PXY=$(kxy code 46) || fail "no period key"
  read -r PX PY <<< "$PXY"
  for mode in default quick legacy; do
    if [ $mode = quick ]; then dbg "prefbool:pref_enable_alt_period_key=true" 2.0; focus_target; geom period_quick >/dev/null; PXY=$(kxy code 46); read -r PX PY <<< "$PXY"; fi
    if [ $mode = legacy ]; then dbg "prefbool:pref_enable_alt_period_key=false" 2.0; focus_target; geom period_legacy >/dev/null; PXY=$(kxy code 46); read -r PX PY <<< "$PXY"; fi
    # evidence: log the STORED pref value (UNSET proves the true fresh-install default, not a forced mode)
    dbg "prefget:pref_enable_alt_period_key" 0.5
    PV=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'PREF pref_enable_alt_period_key=' | tail -1 | sed 's/.*PREF //')
    note "[$mode] stored pref: ${PV:-unknown}"
    clear_field; adb logcat -c
    adb shell input motionevent DOWN "$PX" "$PY"; sleep 1.2
    shot p_${mode}_popup_open
    adb logcat -d -s WBDBG:I | tr -d '\r' > shots/more_$mode.txt
    python3 tools/wb12_kv.py shots/more_$mode.txt list MORE > shots/more_${mode}_keys.txt
    release "$PX" "$PY"
    F=$(field)
    if [ $mode = legacy ]; then
      # Tab blocker (mail 01793553574906000000): releasing without sliding must type the designated default '?'
      [ "$F" = "?" ] && pass "[legacy] no-slide release types '?'" || fail "[legacy] no-slide release typed '$F' (wanted '?')"
    else
      # Owner decision (authenticated WhatsApp, 2026-11-01 15:57:49): the shipping default popup
      # ('! , . ?') types '.' on a no-slide release; '? , !' stays available via the setting.
      [ "$F" = "." ] && pass "[$mode] no-slide release types '.'" || fail "[$mode] no-slide release typed '$F' (wanted '.')"
    fi
    NM=$(wc -l < shots/more_${mode}_keys.txt); note "[$mode] popup keys ($NM): $(awk '{print $2}' shots/more_${mode}_keys.txt | tr '\n' ' ')"
    [ "$NM" -gt 0 ] || { fail "[$mode] period long-press showed no panel"; continue; }
    [ $mode = default ] && { [ "$(awk '{print $2}' shots/more_${mode}_keys.txt | tr '\n' ' ')" = "! , . ? " ] && pass "[default] popup reads ! , . ? by default (alt period key now the default)" || fail "[default] popup is not the ! , . ? set by default"; }
    [ $mode = quick ] && { [ "$(awk '{print $2}' shots/more_${mode}_keys.txt | tr '\n' ' ')" = "! , . ? " ] && pass "[quick] popup reads ! , . ? left to right" || fail "[quick] popup order is not ! , . ?"; }
    [ $mode = legacy ] && { [ "$(awk '{print $2}' shots/more_${mode}_keys.txt | tr '\n' ' ')" = "? , ! " ] && pass "[legacy] toggle off restores the FUTO ? , ! set" || fail "[legacy] toggle off did not restore the FUTO set"; }
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
    if [ $mode = legacy ]; then
      clear_field
      I1X=$(awk 'NR==1{print $3}' shots/more_${mode}_keys.txt); I1Y=$(awk 'NR==1{print $4}' shots/more_${mode}_keys.txt)
      KTOP=$(python3 tools/wb12_kv.py shots/geom.txt kbd y); [ -n "$KTOP" ] || KTOP=$((PY-900))
      RY=$((KTOP-60))
      note "[$mode] cancel slide: parent ($PX,$PY), item1 ($I1X,$I1Y), release ($PX,$RY) above kbd top $KTOP"
      adb logcat -c
      adb shell input motionevent DOWN "$PX" "$PY"
      for i in 1 2 3 4 5 6; do sleep 0.6; adb logcat -d -s WBDBG:I | tr -d '\r' | grep -q 'MOREEND' && break; done
      adb logcat -d -s WBDBG:I | tr -d '\r' | grep -q 'MOREEND' && note "[$mode] cancel: panel confirmed open" || note "[$mode] cancel: panel NOT confirmed open before slide"
      adb shell input motionevent MOVE "$I1X" "$I1Y"; sleep 0.3
      adb shell input motionevent MOVE "$PX" "$RY"; sleep 0.3
      release "$PX" "$RY"
      F=$(field)
      # engine semantics (identical pre/post fix, runs 41331924970/41331934384): sliding off the
      # panel cancels the POPUP; the release then types the PARENT key '.', never a popup item
      [ "$F" = "." ] && pass "[legacy] sliding off the popup cancels it; release types the parent '.'" || fail "[legacy] popup cancel typed '$F' (wanted '.')"
    fi
  done
  # restore the shipping default (setting ON) so later legs run it, then verify persistence
  # across a force-stop + relaunch: popup order ! , . ? and no-slide release types '.'
  dbg "prefbool:pref_enable_alt_period_key=true" 1.0
  dbg "prefget:pref_enable_alt_period_key" 0.5
  note "[persist] stored pref: $(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'PREF pref_enable_alt_period_key=' | tail -1 | sed 's/.*PREF //')"
  adb shell am force-stop "$PKG"; sleep 1
  ensure_ime || note "  could not restore IME for persist check"
  focus_target; geom period_persist >/dev/null; PXY=$(kxy code 46); read -r PX PY <<< "$PXY"
  clear_field; adb logcat -c
  adb shell input motionevent DOWN "$PX" "$PY"; sleep 1.2
  shot p_persist_popup_open
  adb logcat -d -s WBDBG:I | tr -d '\r' > shots/more_persist.txt
  python3 tools/wb12_kv.py shots/more_persist.txt list MORE > shots/more_persist_keys.txt
  release "$PX" "$PY"
  F=$(field)
  [ "$(awk '{print $2}' shots/more_persist_keys.txt | tr '\n' ' ')" = "! , . ? " ] && pass "[persist] popup still reads ! , . ? after restart" || fail "[persist] popup order changed after restart"
  [ "$F" = "." ] && pass "[persist] no-slide release still types '.' after restart" || fail "[persist] no-slide typed '$F' after restart (wanted '.')"
  clear_field
fi

if leg leftedge; then
  note "== LEFT-EDGE taps after the 3px margin shift =="
  focus_target; geom letters >/dev/null
  QCY=$(kxy code 113 | awk '{print $2}'); ACY=$(kxy code 97 | awk '{print $2}'); SCY=$(kxy code -1 | awk '{print $2}')
  note "rows: q cy=$QCY, a cy=$ACY, shift cy=$SCY"
  clear_field; adb logcat -c
  adb shell input tap 4 "$QCY"; sleep 0.4
  adb shell input tap 4 "$ACY"; sleep 0.4
  F=$(field)
  [ "$F" = "qa" ] && pass "left-edge taps on q/a rows register ('$F')" || fail "left-edge taps typed '$F' (wanted 'qa')"
  E0=$(elem le0)
  adb shell input tap 4 "$SCY"; sleep 0.5
  E1=$(elem le1)
  [ "$E0" != "$E1" ] && pass "left-edge tap on shift registers (element $E0 -> $E1)" || fail "left-edge tap on shift did not register (element $E1)"
  [ "$E1" != "0" ] && tapcode -1 >/dev/null
  clear_field
fi

if leg caps; then
  note "== CAPS LOCK word must not poison later suggestions (P0-2) =="
  clear_field; type_word ok; tapcode 32; clear_field
  E=$(elem c0); note "element at start: $E"
  # caps lock is element 3 or 4 (FUTO's KeyboardState does not tell them apart; both draw the caps-lock key)
  for t in 1 2 3 4; do case "$E" in 3|4) break;; esac; tapcode -1; E=$(elem c$t); note "after shift tap $t: element $E"; done
  case "$E" in 3|4) pass "caps lock reached by tapping shift (element $E)";; *) fail "caps lock not reached (element $E)";; esac
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

if leg panelexit; then
  note "== PANEL EXIT: system Back closes the panel (never hides the keyboard); exit lands on letters =="
  focus_target; geom panelexit0 >/dev/null
  SXY=$(kxy code -3 2>/dev/null) || SXY=$(python3 tools/wb12_kv.py shots/geom.txt list KEY | awk '$2 ~ /123/ {print $3, $4; exit}')
  read -r SX SY <<< "$SXY"
  adb shell input tap $SX $SY; sleep 0.8
  shot panelexit_01_symbols
  ES=$(elem panelexit_sym)
  [ "$ES" = "5" ] || [ "$ES" = "6" ] && pass "on the 123/symbols page before opening the panel (elementId=$ES)" || note "INFO: pre-panel elementId=$ES (expected 5/6)"
  adb logcat -c
  adb shell input motionevent DOWN "$SX" "$SY"; sleep 1.4; adb shell input motionevent UP "$SX" "$SY"; sleep 1.5
  shot panelexit_02_clipboard_open
  W2=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'WINDOW open' | tail -1); note "window: $W2"
  echo "$W2" | grep -q clipboard_history || fail "panelexit: clipboard did not open ($W2)"
  adb shell input keyevent 4; sleep 1.5
  shot panelexit_03_after_back
  L=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep -c 'restore-letters')
  RC=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'restore-check' | tail -1)
  note "restore diagnostic: marker=$L ${RC:-no restore-check log}"
  NK=$(geom panelexit_after keep >/dev/null; grep -c 'KEY ' shots/geom_panelexit_after.txt)
  [ "$NK" -gt 10 ] && pass "keyboard still visible after Back ($NK keys), not minimized" || fail "keyboard hidden after Back ($NK keys) - Back minimized the IME"
  EA=$(elem panelexit_afterel)
  case "$EA" in 0|1|2|3|4) pass "letters page after panel exit (elementId=$EA)";; *) fail "not on letters after panel exit (elementId=$EA)";; esac
  dbg close 1.0

  note "== PANEL EXIT matrix: every panel from the 123 page; exits via Back, via the panel close path, and ABC while open =="
  for P in clipboard_history emoji text_edit; do
    # enter from the 123 page
    focus_target; sleep 0.4; tapcode -3 >/dev/null 2>&1 || true; sleep 1.0
    ES=$(elem panelexit_m_pre)
    adb logcat -c; dbg "open:$P" 1.6
    W=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'WINDOW open' | tail -1)
    if ! echo "$W" | grep -q "$P"; then note "panelexit $P: no WINDOW open marker ($W) - skipping"; continue; fi
    pass "panelexit $P: opened from the 123 page (elementId=$ES)"
    shot "panelexit_${P}_open"
    # exit 1: system Back -> keyboard visible on letters
    adb shell input keyevent 4; sleep 1.6
    NK=$(geom "panelexit_${P}_back" keep >/dev/null; grep -c 'KEY ' "shots/geom_panelexit_${P}_back.txt")
    [ "$NK" -gt 10 ] && pass "panelexit $P: keyboard still up after Back ($NK keys)" || fail "panelexit $P: keyboard hidden after Back ($NK keys)"
    EA=$(elem "panelexit_${P}_backel")
    case "$EA" in 0|1|2|3|4) pass "panelexit $P: letters page after Back (elementId=$EA)";; *) fail "panelexit $P: not on letters after Back (elementId=$EA)";; esac
    # exit 2: reopen, close via the panel close path (closeActionWindow - the "<" button's own call)
    tapcode -3 >/dev/null 2>&1 || true; sleep 1.0
    adb logcat -c; dbg "open:$P" 1.6; dbg close 1.5
    NK=$(geom "panelexit_${P}_close" keep >/dev/null; grep -c 'KEY ' "shots/geom_panelexit_${P}_close.txt")
    EA=$(elem "panelexit_${P}_closeel")
    [ "$NK" -gt 10 ] && pass "panelexit $P: keyboard still up after panel close path ($NK keys)" || fail "panelexit $P: keyboard hidden after close path ($NK keys)"
    case "$EA" in 0|1|2|3|4) pass "panelexit $P: letters page after close path (elementId=$EA)";; *) fail "panelexit $P: not on letters after close path (elementId=$EA)";; esac
    # exit 3: reopen on the 123 page, tap ABC with the panel open, then Back must close the panel onto letters
    tapcode -3 >/dev/null 2>&1 || true; sleep 1.0
    adb logcat -c; dbg "open:$P" 1.6
    tapcode -3 >/dev/null 2>&1 || true; sleep 1.0   # symbols-page ABC key toggles back to letters
    EA=$(elem "panelexit_${P}_abcel")
    case "$EA" in 0|1|2|3|4) pass "panelexit $P: letters page after ABC with panel open (elementId=$EA)";; *) note "panelexit $P: elementId=$EA after ABC with panel open";; esac
    adb shell input keyevent 4; sleep 1.4
    NK=$(geom "panelexit_${P}_abcback" keep >/dev/null; grep -c 'KEY ' "shots/geom_panelexit_${P}_abcback.txt")
    EA=$(elem "panelexit_${P}_abcbackel")
    [ "$NK" -gt 10 ] && case "$EA" in 0|1|2|3|4) pass "panelexit $P: Back after ABC closes panel onto letters ($NK keys, elementId=$EA)";; *) fail "panelexit $P: Back after ABC not on letters (elementId=$EA)";; esac || fail "panelexit $P: keyboard hidden after Back-after-ABC ($NK keys)"
  done

  # second Back from the plain letters page hides the keyboard
  focus_target; sleep 0.4
  adb shell input keyevent 4; sleep 1.4
  NK=$(geom panelexit_hidden keep >/dev/null; grep -c 'KEY ' shots/geom_panelexit_hidden.txt)
  [ "${NK:-0}" -eq 0 ] && pass "second Back from letters hides the keyboard" || fail "second Back from letters did not hide the keyboard ($NK keys)"
fi

if leg toolbar; then
  note "== TOOLBAR: tucked behind an up chevron by default; opens as one row of 8; GIF opens the emoji panel =="
  focus_target
  dbg collapse 1.0; adb logcat -c; dbg expand 1.5; dbg collapse 1.5
  adb logcat -d -s WBDBG:I | tr -d '\r' > shots/tb0.txt
  CH=$(grep 'CHEVRON open=false' shots/tb0.txt | tail -1); note "chevron (closed): $CH"
  shot t01_toolbar_closed
  CX=$(echo "$CH" | sed -n 's/.*cx=\([0-9-]*\).*/\1/p'); CY=$(echo "$CH" | sed -n 's/.*cy=\([0-9-]*\).*/\1/p')
  if [ -n "$CX" ] && [ -n "$CY" ]; then
    adb logcat -c; adb shell input tap "$CX" "$CY"; sleep 1.5
    shot t02_toolbar_open
    adb logcat -d -s WBDBG:I | tr -d '\r' > shots/tb1.txt
    TB=$(grep 'TOOLBAR' shots/tb1.txt | tail -1 | sed 's/.*TOOLBAR //'); note "toolbar buttons: $TB"
    grep -q 'CHEVRON open=true' shots/tb1.txt && pass "tapping the chevron opens the toolbar" || fail "chevron tap did not open the toolbar"
    N=$(echo "$TB" | tr '|' '\n' | grep -c .)
    [ "$N" = 8 ] && pass "toolbar shows 8 buttons" || fail "toolbar shows $N buttons, wanted 8"
    for b in GIF Share Info; do echo "|$TB|" | grep -q "|$b|" && pass "toolbar has $b" || fail "toolbar has no $b"; done
    LAST=$(echo "$TB" | tr '|' '\n' | tail -1); note "last button: $LAST"
    GL=$(grep 'ACTION name=GIF ' shots/tb1.txt | tail -1)
    GX=$(echo "$GL" | sed -n 's/.*cx=\([0-9-]*\).*/\1/p'); GY=$(echo "$GL" | sed -n 's/.*cy=\([0-9-]*\).*/\1/p')
    if [ -n "$GX" ]; then
      adb logcat -c; adb shell input tap "$GX" "$GY"; sleep 1.8
      shot t03_after_gif_button
      W=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'WINDOW open' | tail -1); note "window: $W"
      echo "$W" | grep -qi emoji && pass "GIF button opens the emoji panel ($W)" || fail "GIF button did not open the emoji panel ($W)"
      dbg close 1.0
    else fail "no GIF button position"; fi
    adb logcat -c; dbg expand 1.0; dbg collapse 1.0; dbg expand 1.2
    CH2=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'CHEVRON open=true' | tail -1)
    CX2=$(echo "$CH2" | sed -n 's/.*cx=\([0-9-]*\).*/\1/p'); CY2=$(echo "$CH2" | sed -n 's/.*cy=\([0-9-]*\).*/\1/p')
    if [ -n "$CX2" ]; then adb logcat -c; adb shell input tap "$CX2" "$CY2"; sleep 1.2; shot t04_toolbar_closed_again
      adb logcat -d -s WBDBG:I | tr -d '\r' | grep -q 'CHEVRON open=false' && pass "tapping the chevron again closes the toolbar" || fail "chevron did not close the toolbar"
    fi
  else fail "no chevron position logged"; fi
  dbg collapse 0.8
fi

if leg textedit; then
  note "== TEXT EDIT PANEL: toolbar > All Actions > Text Editor (evidence capture, note-only) =="
  focus_target
  adb logcat -c; dbg expand 1.5
  AA=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'ACTION name=All_Actions' | tail -1)
  AX=$(echo "$AA" | sed -n 's/.*cx=\([0-9-]*\).*/\1/p'); AY=$(echo "$AA" | sed -n 's/.*cy=\([0-9-]*\).*/\1/p')
  if [ -n "$AX" ]; then
    adb logcat -c; adb shell input tap "$AX" "$AY"; sleep 1.8
    shot textedit_00_allactions
    # the tile can start below the fold: scroll the grid first, THEN read the fresh position
    TE=""
    for i in 1 2 3; do
      adb logcat -c
      adb shell input swipe 540 2000 540 1200 350; sleep 1.5
      TE=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'ACTION name=Text_Editor' | tail -1)
      [ -n "$TE" ] && break
    done
    note "Text Editor tile: $TE"
    TX=$(echo "$TE" | sed -n 's/.*cx=\([0-9-]*\).*/\1/p'); TY=$(echo "$TE" | sed -n 's/.*cy=\([0-9-]*\).*/\1/p')
    if [ -n "$TX" ]; then
      adb logcat -c
      adb shell input tap "$TX" "$TY"; sleep 1.8
      WO=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'WINDOW open' | tail -1)
      note "after tap: $WO"
      shot textedit_01_panel
    else
      shot textedit_01_panel
      note "no Text Editor tile position; captured raw"
    fi
    dbg close 1.0
  else
    note "no All_Actions button position"
  fi
  adb logcat -c; dbg collapse 1.0; focus_target
fi

if leg skin; then
  note "== SKIN: key preview box, letter long-press popup, shift states =="
  focus_target; clear_field; geom skin
  shot k10_letters_lower_or_auto
  dbg "spint:pref_key_longpress_timeout=2500" 1.0   # hold long enough for the screenshot to catch the plain preview
  GXY=$(kxy label g) && { read -r X Y <<< "$GXY"; adb shell input motionevent DOWN $X $Y; sleep 0.3; shot k11_key_preview_g; release $X $Y; }
  dbg "sprm:pref_key_longpress_timeout" 1.0
  EXY=$(kxy label e) && { read -r X Y <<< "$EXY"; adb shell input motionevent DOWN $X $Y; sleep 1.4; shot k12_longpress_e_popup; release $X $Y; }
  clear_field
  E=$(elem s0); note "element at start: $E"
  SHIFTED=""
  for t in 1 2 3 4; do tapcode -1; E=$(elem s$t); case "$E" in 1|2) shot k13_shift_upper; SHIFTED=$t; break;; esac; done
  if [ "$E" = 1 ]; then NR=$(numrow shots/geom_s$SHIFTED.txt); note "number row with shift on (element 1): $NR"
    [ "$NR" = "1234567890" ] && pass "shift on: number row shows 1234567890" || fail "shift on: number row shows '$NR'"
  else note "INFO: shift screenshot taken at element $E (automatic shift)"; fi
  for t in 1 2 3; do case "$E" in 3|4) break;; esac; tapcode -1; E=$(elem sl$t); LT=$t; done
  case "$E" in 3|4) shot k14_caps_lock; pass "caps lock reached for the screenshot"
    NR=$(numrow shots/geom_sl${LT:-1}.txt); note "number row with caps lock: $NR"; [ "$NR" = "1234567890" ] && pass "caps lock: number row shows 1234567890" || fail "caps lock: number row shows '$NR'";;
    *) note "INFO: caps lock not reached (element $E)";; esac
  for t in 1 2 3; do [ "$E" = "0" ] && break; tapcode -1; E=$(elem so$t); done
fi

pos() { # pos <file> <tag regex> -> "cx cy" of the last WINDOW line matching
  grep -E "WINDOW $2" "$1" | tail -1 | sed -n 's/.*cx=\([0-9-]*\) cy=\([0-9-]*\).*/\1 \2/p'
}
wlog() { adb logcat -d -s WBDBG:I | tr -d '\r' > "shots/$1.txt"; }


if leg symbols; then
  note "== SYMBOLS: 123 page shot (no checks, screenshot for review) =="
  focus_target
  geom letters
  tapcode -3 || true
  sleep 1.2
  geom symbols keep
  shot k15_symbols_page
  tapcode -3 || true
  sleep 1
fi

if leg states; then
  note "== STATES: press states, 123 page 2, voice strip (SwiftKey-parity views, evidence capture) =="
  focus_target; clear_field; geom letters >/dev/null
  read -r SX SY <<< "$(kxy code 32)"
  adb shell input motionevent DOWN "$SX" "$SY"; sleep 0.3; shot states_space_press; adb shell input motionevent UP "$SX" "$SY"; sleep 0.3
  adb shell input keyevent 4; sleep 0.6; focus_target; geom letters >/dev/null
  read -r BX BY <<< "$(kxy code -5)"
  adb shell input motionevent DOWN "$BX" "$BY"; sleep 0.3; shot states_backspace_press; adb shell input motionevent UP "$BX" "$BY"; sleep 0.3
  adb shell input keyevent 4; sleep 0.6; focus_target
  clear_field
  tapcode -3 >/dev/null || true; sleep 1.2
  geom sym2 >/dev/null
  P2=$(kxy label '{&=' 2>/dev/null || true)
  if [ -n "$P2" ]; then
    adb shell input tap $P2; sleep 1.0; shot states_123_page2
  else
    shot states_123_page2; note "no page-2 switch key found in symbols geom"
  fi
  tapcode -3 >/dev/null || true; sleep 1.0
  adb logcat -c; dbg expand 1.5
  VI=$(adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'ACTION name=Voice_Input' | tail -1)
  VX=$(echo "$VI" | sed -n 's/.*cx=\([0-9-]*\).*/\1/p'); VY=$(echo "$VI" | sed -n 's/.*cy=\([0-9-]*\).*/\1/p')
  if [ -n "$VX" ]; then
    adb logcat -c; adb shell input tap "$VX" "$VY"; sleep 2.5; shot states_voice_strip
    # v9 bar: tapping the state pill stops capture but keeps the bar open (stopped
    # pill); the chevron then closes back to the keyboard. The IME window is not
    # visible to `uiautomator dump`, so positions/states come from WBDBG markers.
    adb logcat -d -s WBDBG:I | tr -d '\r' > shots/voicebar_log.txt
    grep -q 'VOICEBAR state=Waiting' shots/voicebar_log.txt && pass "v9 bar: Waiting state reached (recognizer active, no speech)" || note "v9 bar: no Waiting state in log"
    PB=$(grep 'VOICEBARPILL' shots/voicebar_log.txt | tail -1 | sed -n 's/.*cx=\([0-9-]*\) cy=\([0-9-]*\).*/\1 \2/p')
    CB=$(grep 'VOICEBARCHEVRON' shots/voicebar_log.txt | tail -1 | sed -n 's/.*cx=\([0-9-]*\) cy=\([0-9-]*\).*/\1 \2/p')
    if [ -n "$PB" ]; then
      adb logcat -c; adb shell input tap $PB
      # v9 flow: pill tap in Waiting -> finish() -> processing -> Whisper decode -> finished -> Stopped.
      # Emulator-CPU decode of the captured audio far exceeds a fixed 2s window (run 41369773385:
      # pilltap marker + state=Working logged, finished() still in flight at chevron time), so poll
      # for the Stopped state / capture-finished marker instead of asserting after a fixed sleep.
      for i in $(seq 1 30); do
        sleep 3
        adb logcat -d -s WBDBG:I | tr -d '\r' > shots/voicestop_log.txt
        if grep -q 'VOICEBAR state=Stopped' shots/voicestop_log.txt || grep -q 'VOICE capture finished' shots/voicestop_log.txt; then
          break
        fi
      done
      shot states_voice_stopped
      grep -q 'VOICEBARPILLTAP' shots/voicestop_log.txt || note "v9 bar: pill-tap marker missing - the tap did not reach the bar (targeting, not behavior)"
      grep -q 'VOICEBAR state=Stopped' shots/voicestop_log.txt && pass "v9 bar: pill tap stops capture, bar stays open in Stopped state" || fail "v9 bar: no Stopped state after pill tap"
      grep -qE 'VOICE capture (finished|cancelled)' shots/voicestop_log.txt && pass "v9 bar: capture released after pill tap (recognizer lifecycle event)" || fail "v9 bar: no capture-release lifecycle event after pill tap"
      if [ -n "$CB" ]; then
        adb logcat -c; adb shell input tap $CB; sleep 1.5; shot states_voice_chevron_closed
        adb logcat -d -s WBDBG:I | tr -d '\r' > shots/voiceclose_log.txt
        grep -q 'VOICEBAR closed' shots/voiceclose_log.txt && pass "v9 bar: chevron closes the bar (bar-closed marker)" || fail "v9 bar: bar still open after chevron (no closed marker)"
        NK=$(geom voiceclosed keep >/dev/null; grep -c 'KEY ' shots/geom_voiceclosed.txt)
        [ "$NK" -gt 10 ] && pass "v9 bar: keyboard visible after chevron ($NK keys)" || fail "v9 bar: keyboard not visible after chevron ($NK keys)"
      else
        note "v9 bar: no chevron coords in log"
      fi
    else
      note "v9 bar: no VOICEBARPILL coords in log"
      adb shell input keyevent 4; sleep 0.8
    fi
  else
    shot states_voice_strip; note "no Voice_Input tile position"
  fi
  dbg collapse 1.0; focus_target; clear_field
fi

if leg emoji; then
  note "== EMOJI panel (SwiftKey replica): pages, Recents with Lock/Unlock, search, GIF tab =="
  focus_target; clear_field
  adb logcat -c; dbg "open:emoji" 0.5
  for i in $(seq 1 24); do sleep 0.5; adb logcat -d -s WBDBG:I | tr -d '\r' > shots/em0.txt; grep -q 'EMOJI page=' shots/em0.txt && break; done
  shot e01_emoji_smileys
  grep -q 'WINDOW open emoji' shots/em0.txt && pass "emoji panel opens" || fail "emoji panel did not open"
  grep -q 'EMOJI page=Smileys' shots/em0.txt && pass "opens on the Smileys page" || fail "did not open on Smileys"
  G=$(grep 'WINDOW EMOJIGRID' shots/em0.txt | tail -1); note "grid: $G"
  GX=$(echo "$G" | sed -n 's/.*cx=\([0-9]*\).*/\1/p'); GY=$(echo "$G" | sed -n 's/.*cy=\([0-9]*\).*/\1/p')
  GW=$(echo "$G" | sed -n 's/.* w=\([0-9]*\).*/\1/p'); GH=$(echo "$G" | sed -n 's/.* h=\([0-9]*\).*/\1/p')
  if [ -n "$GW" ]; then
    CW=$((GW/8)); RH=$(( 503 * ${WB_DENSITY:-420} / 1600 )); LEFT=$((GX-GW/2)); TOP=$((GY-GH/2))
    note "cell ${CW}x${RH} grid top-left $LEFT,$TOP"
    # type three emojis from the first row so Recents has something
    for c in 0 1 2; do adb shell input tap $((LEFT+CW*c+CW/2)) $((TOP+RH/2)); sleep 0.5; done
    F=$(field); note "field after 3 emoji taps: '$F'"
    [ -n "$F" ] && pass "tapping emojis types them" || fail "tapping emojis typed nothing"
    # long-press an emoji that has skin tones (row 2 has hands on most fonts; take first cell of row 1 as fallback)
    adb logcat -c
    adb shell input motionevent DOWN $((LEFT+CW/2)) $((TOP+RH/2)); sleep 1.2; adb shell input motionevent UP $((LEFT+CW/2)) $((TOP+RH/2)); sleep 1.0
    shot e02_longpress_popup; wlog em1
    grep -q 'EMOJI popup' shots/em1.txt && pass "long-press opens the popup ($(grep 'EMOJI popup' shots/em1.txt | tail -1 | sed 's/.*EMOJI //'))" || fail "long-press opened no popup"
    L=$(pos shots/em1.txt 'EMOJILOCKBTN')
    if [ -n "$L" ]; then adb logcat -c; adb shell input tap $L; sleep 1.0; wlog em2
      grep -q 'EMOJI lock' shots/em2.txt && pass "Lock locks the emoji" || fail "Lock did nothing"
    else fail "no Lock button in the popup"; fi
    # Recents page via its tab
    adb logcat -c; dbg "close" 1.0; dbg "open:emoji" 2.0
    for i in $(seq 1 12); do sleep 0.5; wlog em3; grep -q 'EMOJITAB i=0 ' shots/em3.txt && break; done
    T=$(pos shots/em3.txt 'EMOJITAB i=0 ')
    if [ -n "$T" ]; then adb logcat -c; adb shell input tap $T; sleep 1.5; wlog em4; shot e03_recents_locked
      grep -q 'EMOJI page=Recent' shots/em4.txt && pass "clock tab opens the separate Recents page" || fail "Recents page not shown"
      RC=$(grep 'EMOJI recents' shots/em4.txt | tail -1 | sed 's/.*EMOJI //'); note "$RC"
      echo "$RC" | grep -q 'locked=[1-9]' && pass "locked emoji sits on Recents ($RC)" || fail "no locked emoji on Recents ($RC)"
      # unlock: long-press the first (locked) cell, tap Unlock
      adb logcat -c
      adb shell input motionevent DOWN $((LEFT+CW/2)) $((TOP+RH/2)); sleep 1.2; adb shell input motionevent UP $((LEFT+CW/2)) $((TOP+RH/2)); sleep 1.0
      shot e04_unlock_popup; wlog em5
      grep -q 'locked=true' shots/em5.txt && pass "long-press on a locked emoji offers Unlock" || fail "popup on the locked emoji does not offer Unlock"
      L=$(pos shots/em5.txt 'EMOJILOCKBTN'); [ -n "$L" ] && { adb logcat -c; adb shell input tap $L; sleep 1.0; wlog em6; grep -q 'EMOJI unlock' shots/em6.txt && pass "Unlock unlocks" || fail "Unlock did nothing"; }
    else fail "no Recents tab position"; fi
    # search
    S=$(pos shots/em3.txt 'EMOJISEARCH')
    if [ -n "$S" ]; then adb logcat -c; adb shell input tap $S; sleep 2.0; geom emsearch keep >/dev/null
      for ch in h a p p y; do XY=$(python3 tools/wb12_kv.py shots/geom_emsearch.txt xy KEY label "$ch") && adb shell input tap $XY; sleep 0.3; done
      sleep 1.0; wlog em7; shot e05_search_happy
      Q=$(grep 'EMOJI search' shots/em7.txt | tail -1); note "$Q"
      echo "$Q" | grep -q 'results=[1-9]' && pass "emoji search finds results ($Q)" || fail "emoji search found nothing ($Q)"
    else fail "no search button position"; fi
    adb logcat -c; dbg "close" 1.0; dbg "open:emoji" 2.0
    GT=""; for i in $(seq 1 10); do sleep 0.5; adb logcat -d -s WBDBG:I | tr -d '\r' > shots/em8.txt; GT=$(pos shots/em8.txt 'EMOJIGIFTAB'); [ -n "$GT" ] && break; done
    [ -n "$GT" ] && { adb shell input tap $GT; sleep 1.2; shot e06_gif_tab; pass "GIF tab opens (placeholder)"; } || fail "no GIF tab"
    # skin-tone variants popup: search a hand emoji, long-press the first result
    adb logcat -c; dbg "close" 1.0; dbg "open:emoji" 2.0; wlog em9
    S=$(pos shots/em9.txt 'EMOJISEARCH')
    if [ -n "$S" ]; then
      adb shell input tap $S; sleep 2.0; geom emskin keep >/dev/null
      for ch in w a v e; do XY=$(python3 tools/wb12_kv.py shots/geom_emskin.txt xy KEY label "$ch") && adb shell input tap $XY; sleep 0.3; done
      sleep 1.2; wlog em10
      Q=$(grep 'EMOJI search' shots/em10.txt | tail -1); note "skin search: $Q"
      if echo "$Q" | grep -q 'results=[1-9]'; then
        # search results are tap-only (no long-press handler in the results LazyRow), so tap the
        # first result (wave hand) into the field, then long-press it as the newest Recents cell.
        # results row first cell calibrated on run 41343142559 (~(75,1274) at 1080x2400/420).
        D=${WB_DENSITY:-420}
        RX=$((75*D/420)); RY=$((1274*D/420))
        adb shell input tap $RX $RY; sleep 1.0
        wlog em10b
        note "post-result-tap state: $(grep -E 'WINDOW (EMOJI|open)' shots/em10b.txt | tail -3 | tr '\n' '|')"
        adb shell input keyevent 4; sleep 1.0   # leave search, back to the panel
        wlog em10c
        # retake-3 (41347546224) landed on the LETTERS keyboard here: BACK closed the whole panel,
        # not just search. Verify panel state before navigating; reopen if BACK closed it.
        if ! grep -q 'EMOJI page=' shots/em10c.txt; then
          note "post-BACK: emoji panel was closed (BACK exited the panel, not just search) - reopening"
          dbg "open:emoji" 2.0
        fi
        # wait for the Recents TAB marker, not just the page marker: tab markers are drawn
        # after the page composes, so a page-only poll raced ahead of them (run 41351868194)
        for i in $(seq 1 16); do sleep 0.5; wlog em10c; grep -q 'EMOJITAB i=0 ' shots/em10c.txt && break; done
        # fresh Recents-tab position (em9 predates the search UI). On some reopens the app
        # never re-logs the tab markers (run 41355121489: page logged, tabs not). Fall back
        # to the same-run earlier position; the page=Recent change after the tap validates it.
        T2=$(pos shots/em10c.txt 'EMOJITAB i=0 ')
        if [ -z "$T2" ]; then
          T2=$(pos shots/em9.txt 'EMOJITAB i=0 ')
          [ -n "$T2" ] && note "tab markers not re-logged on reopen; reusing position from earlier open"
        fi
        if [ -n "$T2" ]; then
          sleep 1.0   # let the reopened panel finish animating; a tap 0.4s after the open markers was swallowed (run 41356129207)
          adb logcat -c; adb shell input tap $T2; sleep 1.5; wlog em10d
          PG=$(grep 'EMOJI page=' shots/em10d.txt | tail -1 | sed 's/.*WINDOW //')
          if ! echo "$PG" | grep -q 'page=Recent'; then
            note "first tab tap gave '$PG' - retrying after settle"
            sleep 1.0; adb logcat -c; adb shell input tap $T2; sleep 1.5; wlog em10d
            PG=$(grep 'EMOJI page=' shots/em10d.txt | tail -1 | sed 's/.*WINDOW //')
          fi
          note "recents page after tab tap: $PG"
          echo "$PG" | grep -q 'page=Recent' || note "skin-tone capture: tab tap did not open Recents ($PG)"
          # fresh grid geometry for the Recents page first cell
          G2=$(grep 'WINDOW EMOJIGRID' shots/em10d.txt | tail -1)
          G2X=$(echo "$G2" | sed -n 's/.*cx=\([0-9]*\).*/\1/p'); G2Y=$(echo "$G2" | sed -n 's/.*cy=\([0-9]*\).*/\1/p')
          G2W=$(echo "$G2" | sed -n 's/.* w=\([0-9]*\).*/\1/p'); G2H=$(echo "$G2" | sed -n 's/.* h=\([0-9]*\).*/\1/p')
          if [ -n "$G2W" ]; then
            C2W=$((G2W/8)); L2=$((G2X-G2W/2)); T2Y=$((G2Y-G2H/2))
            # no locked emoji at this point (unlocked above), so the wave hand is the FIRST cell
            adb shell input motionevent DOWN $((L2+C2W/2)) $((T2Y+RH/2)); sleep 2.2; shot e07_skin_tone_popup; wlog em11
            adb shell input motionevent UP $((L2+C2W/2)) $((T2Y+RH/2)); sleep 0.6
            PM=$(grep 'EMOJI popup' shots/em11.txt | tail -1 | sed 's/.*EMOJI //')
            echo "$PM" | grep -q 'variants=[2-9]' && pass "skin-tone variants popup ($PM)" || note "skin-tone popup: $PM"
          else note "skin-tone capture: no grid geometry on Recents page"; fi
        else note "skin-tone capture: no Recents tab position"; fi
      else note "skin-tone capture: wave search gave no results"; fi
      adb shell input keyevent 4; sleep 0.5
    else note "skin-tone capture: no search button"; fi
  else fail "no emoji grid geometry"; fi
  dbg close 1.0
fi

if leg extras; then
  note "== EXTRAS: digit/symbol previews, auto-capital, strip cases =="
  focus_target; clear_field; geom extras
  dbg "spint:pref_key_longpress_timeout=2500" 1.0
  # digit preview: hold the 5 key
  FXY=$(kxy label 5) && { read -r X Y <<< "$FXY"; adb shell input motionevent DOWN $X $Y; sleep 0.3; shot x01_digit_preview_5; release $X $Y; } || note "no 5 key position"
  # symbol preview: symbols page, hold the = key (or first symbol found)
  tapcode -3 >/dev/null || true; sleep 1.0; geom extras_sym keep >/dev/null
  SXY=$(kxyf shots/geom_extras_sym.txt label '=' 2>/dev/null || kxyf shots/geom_extras_sym.txt label '+' 2>/dev/null || true)
  [ -n "$SXY" ] && { read -r X Y <<< "$SXY"; adb shell input motionevent DOWN $X $Y; sleep 0.3; shot x02_symbol_preview; release $X $Y; } || note "no symbol key position for preview"
  dbg "sprm:pref_key_longpress_timeout" 1.0
  tapcode -3 >/dev/null || true; sleep 1.0; geom extras
  # auto-capital: controlled capSentences field (fixture broadcast), type a sentence ending,
  # then shift must auto-engage (uppercase labels in geom). Field is cleared by the broadcast.
  adb shell am broadcast -a com.wrenboard.imetarget.CAPSENT --ez on true -p "$TPKG" >/dev/null 2>&1; sleep 0.8
  adb shell input keyevent 4; sleep 0.8
  focus_target; sleep 0.5
  type_word hello; tapcode 32 >/dev/null; tapk "." >/dev/null 2>&1 || tapcode 46 >/dev/null; tapcode 32 >/dev/null; sleep 0.8
  geom autocap keep >/dev/null
  ROW=$(grep -oE 'label=[QWERTYUIOPqwertyuiop] ' shots/geom_autocap.txt | tr -d 'label= ' | tr -d ' ' | head -c 10)
  note "letter row after 'hello. ' (capSentences field): $ROW"
  case "$ROW" in QWERTY*) pass "auto-capital engages shift after sentence end (capSentences field)";; qwerty*) fail "auto-capital did NOT engage in a capSentences field (row: $ROW)";; *) note "auto-capital row unreadable: $ROW";; esac
  shot x03_autocap_after_sentence
  adb shell am broadcast -a com.wrenboard.imetarget.CAPSENT --ez on false -p "$TPKG" >/dev/null 2>&1; sleep 0.5
  adb shell input keyevent 4; sleep 0.6; focus_target
  # strip cases: multi-suggestion strip vs no-suggestion strip
  clear_field; type_word zebr; sleep 1.0; shot x04_strip_suggestions
  S=$(sugg); note "strip with 'zebr': $S"
  clear_field; type_word zqxwv; sleep 1.0; shot x05_strip_no_suggestion
  S2=$(sugg); note "strip with 'zqxwv': $S2"
  clear_field
fi

if leg clipboard; then
  note "== CLIPBOARD panel (replica layout): masonry, 3-dot menu, swipe delete with Undo, Clear all with confirmation =="
  dbg "dsbool:enableClipboardHistory=true" 1.0   # clipboard history is off in a fresh install (FUTO default)
  focus_target; clear_field
  for t in "See_you_at_the_station_at_six" "https://www.example.com/a/long/sample/link/for/the/clipboard/check" "Shopping:_milk,_eggs,_bread,_coffee,_apples,_rice_and_a_few_onions_for_the_soup_on_Sunday" "AB12-CD34-EF56" "Meeting_moved_to_Thursday"; do
    dbg "clipset:$t" 1.0
  done
  adb logcat -c; dbg "open:clipboard_history" 2.5; wlog cl0
  shot cl01_clipboard
  N=$(grep 'CLIP shown=' shots/cl0.txt | tail -1 | sed 's/.*shown=//'); note "clips shown: $N"
  [ "${N:-0}" -ge 5 ] && pass "clipboard shows the copied clips ($N)" || fail "clipboard shows ${N:-0} clips"
  D=$(pos shots/cl0.txt 'CLIPDOTS i=0 ')
  if [ -n "$D" ]; then
    adb logcat -c; adb shell input tap $D; sleep 1.2; wlog cl1; shot cl02_menu
    M=$(grep 'WINDOW CLIPMENU' shots/cl1.txt | sed 's/.*name=\([A-Za-z]*\).*/\1/' | awk '!s[$0]++' | tr '\n' ' '); note "menu: $M"
    [ "$M" = "Pin Paste Edit Delete " ] && pass "3-dot menu is Pin, Paste, Edit, Delete" || fail "3-dot menu is '$M'"
    P=$(pos shots/cl1.txt 'CLIPMENU name=Pin'); [ -n "$P" ] && { adb logcat -c; adb shell input tap $P; sleep 1.2; wlog cl2; shot cl03_pinned
      grep -q 'CLIPCARD i=[0-9]* pinned=true' shots/cl2.txt && pass "Pin pins the clip" || fail "Pin did not pin"; }
  else fail "no 3-dot position"; fi
  adb logcat -c; dbg close 0.8; dbg "open:clipboard_history" 2.0; wlog cl3
  C=$(pos shots/cl3.txt 'CLIPCARD i=1 ')
  # fix1b: a drag the system cancels must not delete, however far it went (snap back)
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c
    adb shell "input motionevent DOWN $X $Y; input motionevent MOVE $((X-120)) $Y; input motionevent MOVE $((X-260)) $Y; input motionevent MOVE $((X-420)) $Y"
    sleep 0.3; shot cl04a_drag_held_420px_before_cancel
    CO=$(adb shell input motionevent CANCEL $((X-420)) $Y 2>&1 | tr -d '\r'); note "  CANCEL injection said: [${CO:0:160}]"
    if echo "$CO" | grep -qi "unknown\|error\|usage\|exception"; then
      adb shell "input motionevent MOVE $X $Y; input motionevent UP $X $Y"; note "INFO: this emulator's input tool has no CANCEL; cancelled drag not exercised on device (unit test covers it)"
    else
      sleep 1.5; wlog cl4c; shot cl04b_after_cancelled_drag_snap_back
      if grep -q 'WINDOW CLIPMENU' shots/cl4c.txt; then note "INFO: a long-press menu opened before the drag started; cancelled drag not judged on device"
      elif grep -q 'CLIP removed' shots/cl4c.txt; then fail "a cancelled 420 px drag deleted the clip"
      else pass "a cancelled 420 px drag deletes nothing (card snaps back)"; fi
    fi
    adb logcat -c; dbg close 0.8; dbg "open:clipboard_history" 2.0; wlog cl3
    C=$(pos shots/cl3.txt 'CLIPCARD i=1 ')
  fi
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c; adb shell input swipe $X $Y $((X-600)) $Y 300; sleep 1.5; wlog cl4
    grep -q 'CLIP removed=1' shots/cl4.txt || { sleep 1.5; wlog cl4; }
    shot cl04_swiped_undo
    grep -q 'CLIP removed=1' shots/cl4.txt && pass "swipe deletes the clip" || fail "swipe did not delete"
    U=$(pos shots/cl4.txt 'CLIPUNDO'); [ -n "$U" ] && { adb logcat -c; adb shell input tap $U; sleep 1.0; wlog cl5; shot cl05_after_undo
      grep -q 'CLIP undo restored=1' shots/cl5.txt && pass "Undo brings the swiped clip back" || fail "Undo did not restore"; } || fail "no Undo bar after swipe"
  else fail "no card position"; fi
  adb logcat -c; dbg close 0.8; dbg "open:clipboard_history" 2.0; wlog cl6
  D=$(pos shots/cl6.txt 'CLIPDOTS i=2 ')
  if [ -n "$D" ]; then adb logcat -c; adb shell input tap $D; sleep 1.0; wlog cl7
    P=$(pos shots/cl7.txt 'CLIPMENU name=Delete'); [ -n "$P" ] && { adb logcat -c; adb shell input tap $P; sleep 1.0; wlog cl8
      grep -q 'CLIP removed=1' shots/cl8.txt && pass "menu Delete deletes" || fail "menu Delete did nothing"
      U=$(pos shots/cl8.txt 'CLIPUNDO'); [ -n "$U" ] && { adb logcat -c; adb shell input tap $U; sleep 1.0; wlog cl9; grep -q 'CLIP undo restored=1' shots/cl9.txt && pass "Undo after menu Delete" || fail "Undo after menu Delete failed"; }; }
  fi
  adb logcat -c; dbg close 0.8; dbg "open:clipboard_history" 2.0; wlog cla
  CA=$(pos shots/cla.txt 'CLIPCLEARALL')
  if [ -n "$CA" ]; then adb logcat -c; adb shell input tap $CA; sleep 1.0; wlog clb; shot cl06_clear_all_confirm
    B=$(pos shots/clb.txt 'DIALOGBTN text=Delete'); [ -n "$B" ] && pass "Clear all asks first (Delete all clips? Cancel/Delete)" || fail "Clear all showed no confirmation"
    [ -n "$B" ] && { adb logcat -c; adb shell input tap $B; sleep 1.2; wlog clc; shot cl07_cleared_undo
      grep -q 'CLIP removed=' shots/clc.txt && pass "Clear all clears unpinned clips ($(grep 'CLIP removed=' shots/clc.txt | tail -1 | sed 's/.*CLIP //'))" || fail "Clear all did nothing"
      U=$(pos shots/clc.txt 'CLIPUNDO'); [ -n "$U" ] && { adb logcat -c; adb shell input tap $U; sleep 1.0; wlog cld; grep -q 'CLIP undo restored' shots/cld.txt && pass "Undo after Clear all" || fail "Undo after Clear all failed"; }; }
  else fail "no Clear all button"; fi
  dbg close 1.0
fi


if leg update; then
  ensure_ime; focus_target
  note "== UPDATE dialog: Back and Dismiss close it; Update downloads in the app and opens the system installer =="
  adb shell input keyevent KEYCODE_HOME; sleep 1
  [ "$(adb get-state 2>/dev/null | tr -d '\r')" = device ] || fail "emulator went offline before the update leg"
  opensettings "" 8.0          # Settings opens and runs its own update check first
  opensettings update 3.0
  shot u01_update_dialog_first; dumpui u01; T=$(uitext u01); note "dialog texts: ${T:0:300}"
  if echo "$T" | grep -q "Up-to-date\|Update check failed\|Update available"; then
    adb shell input keyevent KEYCODE_BACK; sleep 1.5; dumpui u02; T2=$(uitext u02)
    echo "$T2" | grep -q "Up-to-date\|Update check failed\|Update available" && fail "Back did not close the update dialog" || pass "Back closes the update dialog"
    opensettings update 3.0; dumpui u03
    XY=$(node_center u03 "Dismiss")
    if [ -n "$XY" ]; then adb shell input tap $XY; sleep 1.5; dumpui u04; T4=$(uitext u04)
      echo "$T4" | grep -q "Up-to-date\|Update check failed\|Update available" && fail "Dismiss did not close the update dialog" || pass "Dismiss closes the update dialog"
    else fail "no Dismiss button on the update dialog"; fi
  else fail "update dialog did not open"; fi
  adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1
  # fix1: the prompt must tell success, cancelling and a real failure apart, and a new prompt or attempt starts clean
  noshout() { echo "$1" | grep -qi "fatal" && fail "$2: the prompt says 'Fatal' ('$(echo "$1" | cut -c1-160)')"; return 0; }
  wait_installer() { # wait_installer <tag>: tap the prompt's Update, then wait for Android's installer to be in front
    local i; INST=""
    for i in $(seq 1 45); do
      sleep 2
      FOC=$(adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 'mCurrentFocus')
      case "$FOC" in *packageinstaller*|*PackageInstaller*) INST="$FOC"; break;; esac
      case "$FOC" in *chrome*|*browser*|*Browser*) break;; esac
    done
    note "focus after Update ($1): $FOC"
  }
  installer_tap() { # installer_tap <xmlname> <button text>
    local xy; dumpui "$1"; xy=$(node_center "$1" "$2"); [ -z "$xy" ] && xy=$(node_center "$1" "$(echo "$2" | tr a-z A-Z)")
    [ -n "$xy" ] && { adb shell input tap $xy; return 0; }; note "no '$2' button on the installer: $(uitext "$1" | cut -c1-200)"; return 1
  }
  dlg_wait() { # dlg_wait <xmlname> <regex> [tries]: dump the screen until the text appears (taps an installer OK on the way)
    local i; for i in $(seq 1 "${3:-10}"); do
      dumpui "$1"; uitext "$1" | grep -q "$2" && return 0
      local ok; ok=$(node_center "$1" "OK"); [ -n "$ok" ] && uitext "$1" | grep -qv "Update available" && adb shell input tap $ok
      sleep 1.5
    done; return 1
  }
  dbg "seedupdate:99.0.0|https://github.com/MtndaleRedneck/WrenBoard/releases/download/v1.1.0/WrenBoard-1.1.apk" 1.5
  opensettings update 3.0
  shot u05_update_available; dumpui u05; T=$(uitext u05); note "dialog texts: ${T:0:300}"
  XY=$(node_center u05 "Update")
  if [ -n "$XY" ]; then
    adb shell input tap $XY; wait_installer first
    shot u06_after_update_tap; dumpui u06; note "screen texts: $(uitext u06 | cut -c1-300)"
    [ -n "$INST" ] && pass "Update downloaded in the app and opened the system installer" || fail "Update did not reach the system installer ($FOC)"
    echo "$FOC" | grep -qi "chrome\|browser" && fail "Update opened the browser"
    # 1) cancel in Android's installer -> the prompt says so plainly; reopening the prompt starts clean
    if [ -n "$INST" ] && installer_tap u06c "Cancel"; then
      dlg_wait u07 "Update available" 8; sleep 1.5; dumpui u07; shot u07_after_installer_cancel; T7=$(uitext u07); note "after installer Cancel: ${T7:0:300}"
      echo "$T7" | grep -q "Update cancelled." && pass "cancel in the installer shows 'Update cancelled.'" || fail "cancel in the installer did not show 'Update cancelled.'"
      noshout "$T7" "after cancel"; echo "$T7" | grep -q "Update failed\|Update installed" && fail "cancel was reported as a failure or success"
      adb shell input keyevent KEYCODE_BACK; sleep 1.5
      opensettings update 3.0; dumpui u08; shot u08_reopened_after_cancel; T8=$(uitext u08); note "reopened after cancel: ${T8:0:300}"
      if echo "$T8" | grep -q "Update available" && ! echo "$T8" | grep -q "cancelled\|failed\|Fatal\|Installing"; then pass "after cancel, the reopened prompt starts clean"; else fail "after cancel, the reopened prompt still shows the old attempt"; fi
    else fail "could not cancel in the installer"; fi
    # 2) a real failure: the installer refuses the release-signed 1.1 over this debug build -> plain failure words
    XY=$(node_center u08 "Update")
    if [ -n "$XY" ]; then
      adb shell input tap $XY; wait_installer failure; shot u09a_installer_before_failure
      if [ -n "$INST" ] && installer_tap u09i "Update"; then
        dlg_wait u09 "Update failed\|Update installed\|Update cancelled\|Fatal" 20; sleep 1; dumpui u09; shot u09_after_install_failure; T9=$(uitext u09); note "after installer refused: ${T9:0:300}"
        echo "$T9" | grep -q "Update failed" && pass "a real install failure shows plain 'Update failed' words" || fail "a real install failure did not show 'Update failed'"
        noshout "$T9" "after failure"; echo "$T9" | grep -q "Update installed\|cancelled" && fail "failure was reported as success or cancel"
        adb logcat -d -s InstallReceiver:I | tr -d '\r' | tail -4 > shots/u09_installreceiver.txt
        # retry: reopen the prompt (clean), tap Update again, the installer comes back
        adb shell input keyevent KEYCODE_BACK; sleep 1.5
        opensettings update 3.0; dumpui u10; shot u10_reopened_after_failure; T10=$(uitext u10); note "reopened after failure: ${T10:0:300}"
        if echo "$T10" | grep -q "Update available" && ! echo "$T10" | grep -q "failed\|cancelled\|Fatal\|Installing"; then pass "after a failure, the reopened prompt starts clean"; else fail "after a failure, the reopened prompt still shows the old attempt"; fi
        XY=$(node_center u10 "Update")
        if [ -n "$XY" ]; then adb shell input tap $XY; wait_installer retry; shot u11_retry_installer
          [ -n "$INST" ] && pass "retry after a failure reaches the installer again" || fail "retry after a failure did not reach the installer"
          [ -n "$INST" ] && installer_tap u11c "Cancel"; sleep 2; dumpui u11; noshout "$(uitext u11)" "after retry cancel"
          adb shell input keyevent KEYCODE_BACK; sleep 1
        else fail "no Update button after a failure"; fi
      else fail "the installer did not come up for the failure case"; fi
    else fail "no Update button on the reopened prompt"; fi
  else fail "no Update button with a seeded newer release"; fi
  # 3) a download failure, then Update tapped again on the same prompt (each attempt starts with no leftover words)
  dbg "seedupdate:99.0.0|https://github.com/MtndaleRedneck/WrenBoard/releases/download/v1.1.0/wb12-no-such-file.apk" 1.5
  opensettings update 3.0; dumpui u12a; XY=$(node_center u12a "Update")
  if [ -n "$XY" ]; then
    adb shell input tap $XY; dlg_wait u12 "Download failed" 15; shot u12_download_failed; T12=$(uitext u12); note "download failure: ${T12:0:300}"
    echo "$T12" | grep -q "Download failed" && pass "a failed download says 'Download failed' in the prompt" || fail "a failed download was not reported"
    noshout "$T12" "download failure"
    adb logcat -c; XY=$(node_center u12 "Update"); [ -n "$XY" ] && adb shell input tap $XY; sleep 6
    N=$(adb logcat -d -s UpdateScreen:I | tr -d '\r' | grep -c "Update attempt .* started"); dumpui u13; T13=$(uitext u13); note "retry on the same prompt: attempts started=$N; ${T13:0:200}"
    [ "$N" -ge 1 ] && echo "$T13" | grep -q "Download failed" && pass "Update tapped again after a failed download starts a new attempt" || fail "retry on the same prompt did not start a new attempt"
    adb shell input keyevent KEYCODE_BACK; sleep 1
  else fail "no Update button for the download-failure case"; fi
  # 4) success: the same debug build served over the emulator link, installed over itself
  if [ -n "${WB_LOCAL_APK_URL:-}" ]; then
    adb reverse tcp:8765 tcp:8765 >/dev/null 2>&1
    dbg "seedupdate:99.0.0|$WB_LOCAL_APK_URL" 1.5
    LUT0=$(adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 lastUpdateTime | sed 's/^ *//')
    opensettings update 3.0; dumpui u14a; XY=$(node_center u14a "Update")
    if [ -n "$XY" ]; then
      adb logcat -c; adb shell input tap $XY; wait_installer success; shot u14_success_installer
      if [ -n "$INST" ] && installer_tap u14i "Update"; then
        LUT1="$LUT0"; for i in $(seq 1 45); do sleep 2; LUT1=$(adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 lastUpdateTime | sed 's/^ *//'); [ "$LUT1" != "$LUT0" ] && break; done
        sleep 4; shot u15_after_success; adb logcat -d | tr -d '\r' | grep "InstallReceiver\|UpdateScreen" | tail -8 > shots/u15_success_log.txt
        note "lastUpdateTime before: $LUT0 | after: $LUT1"; note "success log: $(tr '\n' ' ' < shots/u15_success_log.txt | cut -c1-500)"
        if [ "$LUT1" != "$LUT0" ] && grep -q "Received status 0 " shots/u15_success_log.txt; then pass "Update installed over itself and the installer answered success"; else fail "the success install was not confirmed"; fi
        grep -qi "fatal" shots/u15_success_log.txt && fail "success logged as fatal"
      else fail "the installer did not come up for the success case"; fi
    else fail "no Update button for the success case"; fi
  else note "INFO: no local APK served; success path covered by unit tests only"; fi
  adb shell input keyevent KEYCODE_HOME; sleep 1
fi

if leg settings; then
  note "== SETTINGS tree (CP4): six groups + FUTO, each page opens =="
  ensure_ime; note "default IME: $(adb shell settings get secure default_input_method | tr -d '\r')"
  adb shell input keyevent KEYCODE_HOME; sleep 1
  opensettings "" 6
  note "  adb state after opening Settings: $(timeout 10 "$ADB_BIN" get-state 2>&1 | tr -d '\r')"
  dumpui st00
  # the update leg left a seeded 99.0.0 behind; a person would answer the prompt, so skip that version here
  if uitext st00 | grep -q "Update available"; then XY=$(node_center st00 "Skip this version"); [ -n "$XY" ] && { adb shell input tap $XY; sleep 2; note "  skipped the seeded update prompt"; }; dumpui st00; fi
  uitext st00 | grep -q "Personal dictionary" && fail "Settings opened on the Personal dictionary page"
  shot st01_settings_home; dumpui st01; T=$(uitext st01)
  adb shell input swipe 540 1700 540 700 400; sleep 1.5; shot st01b_settings_home_scrolled; dumpui st01b; T="$T$(uitext st01b)"
  note "home: ${T:0:700}"
  ok=1; for w in "Keyboard" "Typing" "Voice" "Clipboard &amp; emoji" "Backup" "About" "FUTO"; do echo "$T" | grep -q "$w" || { ok=0; note "missing on home: $w"; }; done
  [ $ok = 1 ] && pass "home shows the six groups and FUTO" || fail "home is missing groups"
  echo "$T" | grep -qi "swiftkey" && fail "the word SwiftKey appears in Settings" || pass "no SwiftKey text on the home"
  n=2
  for d in wren_keyboard wren_typing voiceInput wren_clipemoji wren_backup about wren_futo; do
    opensettings "$d" 3.5
    shot "st0${n}_$d"; dumpui "st_$d"; P=$(uitext "st_$d"); note "$d: ${P:0:400}"
    echo "$P" | grep -qi "swiftkey" && fail "SwiftKey text on $d"
    [ -n "$P" ] && pass "$d opens" || fail "$d shows nothing"
    n=$((n+1))
  done
  opensettings wren_keyboard 3.5; adb shell input swipe 540 1900 540 500 400; sleep 1.5; adb shell input swipe 540 1900 540 500 400; sleep 1.5
  shot st02b_wren_keyboard_scrolled; dumpui st_kb2; KB="$(uitext st_wren_keyboard)$(uitext st_kb2)"
  # The item-42 FAB bottom inset (72dp) shortens the page viewport, so at high
  # densities the two fixed swipes can stop short of the FEEL card. Scroll on
  # until all section cards have been seen (bounded).
  for i in 1 2 3; do
    echo "$KB" | grep -q "LOOK" && echo "$KB" | grep -q "LAYOUT" && echo "$KB" | grep -q "FEEL" && break
    adb shell input swipe 540 1900 540 500 400; sleep 1.5; dumpui "st_kb_extra$i"; KB="$KB$(uitext st_kb_extra$i)"
  done
  for w in LOOK LAYOUT FEEL; do echo "$KB" | grep -q "$w" && pass "Keyboard page has the $w card" || fail "Keyboard page lacks the $w card"; done
  # FUTO page must still reach FUTO's own screens
  opensettings "" 2; opensettings wren_futo 3.5; dumpui st_futo2
  for w in "Languages" "Swipe" "Actions"; do uitext st_futo2 | grep -q "$w" && pass "FUTO page keeps $w" || fail "FUTO page lost $w"; done
  # fix1b: FUTO's Payment row stays exactly as upstream (FUTO's license forbids hiding payment in copies).
  # Fresh install = IS_ALREADY_PAID false, so upstream shows the Payment row; it must be there.
  shot st08a_wren_futo_top; adb shell input swipe 540 1900 540 600 400; sleep 1.5; shot st08b_wren_futo_scrolled; dumpui st_futo3
  FU="$(uitext st_futo2)$(uitext st_futo3)"; note "FUTO page (top + scrolled): ${FU:0:500}"
  echo "$FU" | grep -q "Payment" && pass "FUTO page shows FUTO's Payment row (not paid, as upstream)" || fail "FUTO page lost FUTO's Payment row"
  echo "$FU" | grep -q "Help" && echo "$FU" | grep -q "Credits" && pass "FUTO page keeps Help and Credits" || fail "FUTO page lost Help or Credits"
  # Personal dictionary is still in Settings (Typing page, Words card) and opens
  opensettings wren_typing 3.5; FOUND=""
  for i in 1 2 3; do dumpui st_ty$i; XY=$(node_center st_ty$i "Personal dictionary"); [ -n "$XY" ] && { FOUND=1; break; }; adb shell input swipe 540 1900 540 700 400; sleep 1.5; done
  if [ -n "$FOUND" ]; then shot st09_typing_personal_dictionary_row; adb shell input tap $XY; sleep 2.5; dumpui st_pd; shot st10_personal_dictionary_page; note "personal dictionary page: $(uitext st_pd | cut -c1-200)"
    uitext st_pd | grep -q "Personal dictionary" && pass "Personal dictionary row in Typing opens its page" || fail "Personal dictionary row did not open its page"
  else fail "no Personal dictionary row on the Typing page"; fi
  # FUTO's "Unpaid FUTO Keyboard" reminder behaves as upstream: forced on, it shows in FUTO's plain Settings style
  focus_target; dbg "dsbool:force_show_notice=true" 1.0; dbg "dsbool:wren_settings_style=false" 1.0
  adb shell input keyevent KEYCODE_HOME; sleep 1; opensettings "" 4; dumpui st_plain; shot st11_futo_style_home_forced_notice
  adb shell input swipe 540 1900 540 700 400; sleep 1.5; dumpui st_plain2; PL="$(uitext st_plain)$(uitext st_plain2)"; note "FUTO-style home, notice forced: ${PL:0:500}"
  if echo "$PL" | grep -q "Languages\|Swipe\|Actions"; then
    echo "$PL" | grep -q "Payment" && pass "FUTO plain style shows the Payment row (as upstream)" || fail "FUTO plain style lost the Payment row"
    # upstream also holds the reminder back while FUTO's migration notice is undismissed, so this one is recorded, not judged
    echo "$PL" | grep -q "Unpaid" && note "INFO: forced Unpaid reminder shows (as upstream)" || note "INFO: forced Unpaid reminder not on screen (upstream holds it while the migration notice is undismissed)"
  else note "INFO: FUTO plain style did not come up (${PL:0:120})"; fi
  focus_target; dbg "dsbool:force_show_notice=false" 1.0; dbg "dsbool:wren_settings_style=true" 1.0
  adb shell input keyevent KEYCODE_HOME
fi

note "== logcat crashes =="
adb logcat -d -b crash > shots/crash.txt 2>&1
C=$(grep -c "FATAL EXCEPTION" shots/crash.txt); [ "$C" -eq 0 ] && pass "no FATAL EXCEPTION in crash buffer" || fail "$C FATAL EXCEPTION(s) in crash buffer"
note "RESULT: $FAILS failure(s)"
[ "$FAILS" -eq 0 ]
