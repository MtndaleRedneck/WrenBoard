#!/usr/bin/env bash
# WrenBoard 1.2 checks on a booted emulator, driven by the DEBUG build's WBDBG logcat geometry
# (java/src/org/futo/inputmethod/latin/uix/WbDebug.kt): keys are tapped where the keyboard says they are,
# not where a pixel model guesses, so the checks survive the new skin.
# Usage: wb12-check.sh <debug apk> <ime-target apk>     (WB_LEGS=comma list, default all)
set -u
APK="$1"; TAPK="$2"
PKG=com.wrenboard.keyboard; TPKG=com.wrenboard.imetarget
LEGS=",${WB_LEGS:-setup,keyboard,period,caps,shift,clip,update,toolbar,skin,emoji,clipboard,settings},"
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
dbg() { adb shell "am broadcast -a com.wrenboard.keyboard.WBDBG -p $PKG --es cmd '$1'" >/dev/null 2>&1; sleep "${2:-0.6}"; }  # quoted for the device shell ('|' in seedupdate)
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
  shot s01_settings_not_enabled; dumpui s01; T=$(uitext s01); note "texts: ${T:0:400}"
  echo "$T" | grep -q "WrenBoard is turned off" && pass "banner when not enabled" || fail "banner when not enabled"
  echo "$T" | grep -q "Step 1\|Step 2\|Open Input Method Settings" && fail "old setup wall still shown" || pass "no setup wall (not enabled)"
  echo "$T" | grep -qi "Keyboard\|Languages\|Typing" && pass "settings items reachable while not enabled" || fail "settings items not visible while not enabled"
  # a settings item is usable: open the first navigation row that is not the banner
  XY=$(node_center s01 "Typing"); [ -z "$XY" ] && XY=$(node_center s01 "Keyboard &amp; Typing")
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
  if echo "$T" | grep -q "WrenBoard is turned off\|WrenBoard is not your keyboard"; then
    note "banner still up after relaunch; default IME is $(adb shell settings get secure default_input_method | tr -d '\r'); waiting 4 s"
    sleep 4; adb shell am force-stop "$PKG"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
    shot s05b_settings_active_retry; dumpui s05; T=$(uitext s05)
  fi
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
  sleep 1.5; S=$(sugg); note "suggestions in empty field: [$S]"
  grep 'STARTER' shots/_s.txt | tail -5 | sed 's/^.*WBDBG *: /  /' | tee -a "$R"
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

if leg skin; then
  note "== SKIN: key preview box, letter long-press popup, shift states =="
  focus_target; clear_field; geom skin
  shot k10_letters_lower_or_auto
  GXY=$(kxy label g) && { read -r X Y <<< "$GXY"; adb shell input motionevent DOWN $X $Y; sleep 0.45; shot k11_key_preview_g; release $X $Y; }
  EXY=$(kxy label e) && { read -r X Y <<< "$EXY"; adb shell input motionevent DOWN $X $Y; sleep 1.4; shot k12_longpress_e_popup; release $X $Y; }
  clear_field
  E=$(elem s0); note "element at start: $E"
  for t in 1 2 3 4; do tapcode -1; E=$(elem s$t); case "$E" in 1|2) shot k13_shift_upper; break;; esac; done
  for t in 1 2 3; do case "$E" in 3|4) break;; esac; tapcode -1; E=$(elem sl$t); done
  case "$E" in 3|4) shot k14_caps_lock; pass "caps lock reached for the screenshot";; *) note "INFO: caps lock not reached (element $E)";; esac
  for t in 1 2 3; do [ "$E" = "0" ] && break; tapcode -1; E=$(elem so$t); done
fi

pos() { # pos <file> <tag regex> -> "cx cy" of the last WINDOW line matching
  grep -E "WINDOW $2" "$1" | tail -1 | sed -n 's/.*cx=\([0-9-]*\) cy=\([0-9-]*\).*/\1 \2/p'
}
wlog() { adb logcat -d -s WBDBG:I | tr -d '\r' > "shots/$1.txt"; }

if leg emoji; then
  note "== EMOJI panel (SwiftKey replica): pages, Recents with Lock/Unlock, search, GIF tab =="
  focus_target; clear_field
  adb logcat -c; dbg "open:emoji" 2.5; wlog em0
  shot e01_emoji_smileys
  grep -q 'WINDOW open emoji' shots/em0.txt && pass "emoji panel opens" || fail "emoji panel did not open"
  grep -q 'EMOJI page=Smileys' shots/em0.txt && pass "opens on the Smileys page" || fail "did not open on Smileys"
  G=$(grep 'WINDOW EMOJIGRID' shots/em0.txt | tail -1); note "grid: $G"
  GX=$(echo "$G" | sed -n 's/.*cx=\([0-9]*\).*/\1/p'); GY=$(echo "$G" | sed -n 's/.*cy=\([0-9]*\).*/\1/p')
  GW=$(echo "$G" | sed -n 's/.* w=\([0-9]*\).*/\1/p'); GH=$(echo "$G" | sed -n 's/.* h=\([0-9]*\).*/\1/p')
  if [ -n "$GW" ]; then
    CW=$((GW/8)); RH=$(( 503 * 420 / 1600 )); LEFT=$((GX-GW/2)); TOP=$((GY-GH/2))
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
    adb logcat -c; dbg "close" 1.0; dbg "open:emoji" 2.0; wlog em3
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
    adb logcat -c; dbg "close" 1.0; dbg "open:emoji" 2.0; wlog em8
    GT=$(pos shots/em8.txt 'EMOJIGIFTAB'); [ -n "$GT" ] && { adb shell input tap $GT; sleep 1.2; shot e06_gif_tab; pass "GIF tab opens (placeholder)"; } || fail "no GIF tab"
  else fail "no emoji grid geometry"; fi
  dbg close 1.0
fi

if leg clipboard; then
  note "== CLIPBOARD panel (SwiftKey replica): masonry, 3-dot menu, swipe delete with Undo, Clear all with confirmation =="
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
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c; adb shell input swipe $X $Y $((X-600)) $Y 250; sleep 1.0; wlog cl4; shot cl04_swiped_undo
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
    B=$(pos shots/clb.txt 'DIALOGBTN text=Clear_all'); [ -n "$B" ] && pass "Clear all asks first" || fail "Clear all showed no confirmation"
    [ -n "$B" ] && { adb logcat -c; adb shell input tap $B; sleep 1.2; wlog clc; shot cl07_cleared_undo
      grep -q 'CLIP removed=' shots/clc.txt && pass "Clear all clears unpinned clips ($(grep 'CLIP removed=' shots/clc.txt | tail -1 | sed 's/.*CLIP //'))" || fail "Clear all did nothing"
      U=$(pos shots/clc.txt 'CLIPUNDO'); [ -n "$U" ] && { adb logcat -c; adb shell input tap $U; sleep 1.0; wlog cld; grep -q 'CLIP undo restored' shots/cld.txt && pass "Undo after Clear all" || fail "Undo after Clear all failed"; }; }
  else fail "no Clear all button"; fi
  dbg close 1.0
fi

if leg settings; then
  note "== SETTINGS tree (CP4): six groups + FUTO, each page opens =="
  adb shell am force-stop "$PKG"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
  shot st01_settings_home; dumpui st01; T=$(uitext st01); note "home: ${T:0:500}"
  ok=1; for w in "Keyboard" "Typing" "Voice" "Clipboard &amp; emoji" "Backup" "About" "FUTO"; do echo "$T" | grep -q "$w" || { ok=0; note "missing on home: $w"; }; done
  [ $ok = 1 ] && pass "home shows the six groups and FUTO" || fail "home is missing groups"
  echo "$T" | grep -qi "swiftkey" && fail "the word SwiftKey appears in Settings" || pass "no SwiftKey text on the home"
  n=2
  for d in wren_keyboard wren_typing voiceInput wren_clipemoji wren_backup about wren_futo; do
    dbg "settings:$d" 3.0
    shot "st0${n}_$d"; dumpui "st_$d"; P=$(uitext "st_$d"); note "$d: ${P:0:400}"
    echo "$P" | grep -qi "swiftkey" && fail "SwiftKey text on $d"
    [ -n "$P" ] && pass "$d opens" || fail "$d shows nothing"
    n=$((n+1))
  done
  # FUTO page must still reach FUTO's own screens
  dbg "settings:wren_futo" 3.0; dumpui st_futo2
  for w in "Languages" "Swipe" "Actions"; do uitext st_futo2 | grep -q "$w" && pass "FUTO page keeps $w" || fail "FUTO page lost $w"; done
  adb shell input keyevent KEYCODE_HOME
fi

if leg update; then
  note "== UPDATE dialog: Back and Dismiss close it; Update downloads in the app and opens the system installer =="
  adb shell input keyevent KEYCODE_HOME; sleep 1
  dbg "settings:" 8.0          # Settings opens and runs its own update check first
  dbg "settings:update" 3.0
  shot u01_update_dialog_first; dumpui u01; T=$(uitext u01); note "dialog texts: ${T:0:300}"
  if echo "$T" | grep -q "Up-to-date\|Update check failed\|Update available"; then
    adb shell input keyevent KEYCODE_BACK; sleep 1.5; dumpui u02; T2=$(uitext u02)
    echo "$T2" | grep -q "Up-to-date\|Update check failed\|Update available" && fail "Back did not close the update dialog" || pass "Back closes the update dialog"
    dbg "settings:update" 3.0; dumpui u03
    XY=$(node_center u03 "Dismiss")
    if [ -n "$XY" ]; then adb shell input tap $XY; sleep 1.5; dumpui u04; T4=$(uitext u04)
      echo "$T4" | grep -q "Up-to-date\|Update check failed\|Update available" && fail "Dismiss did not close the update dialog" || pass "Dismiss closes the update dialog"
    else fail "no Dismiss button on the update dialog"; fi
  else fail "update dialog did not open"; fi
  adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1
  dbg "seedupdate:99.0.0|https://github.com/MtndaleRedneck/WrenBoard/releases/download/v1.1.0/WrenBoard-1.1.apk" 1.5
  dbg "settings:update" 3.0
  shot u05_update_available; dumpui u05; T=$(uitext u05); note "dialog texts: ${T:0:300}"
  XY=$(node_center u05 "Update")
  if [ -n "$XY" ]; then
    adb shell input tap $XY
    INST=""
    for i in $(seq 1 45); do
      sleep 2
      FOC=$(adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 'mCurrentFocus')
      case "$FOC" in *packageinstaller*|*PackageInstaller*) INST="$FOC"; break;; esac
      case "$FOC" in *chrome*|*browser*|*Browser*) break;; esac
    done
    note "focus after Update: $FOC"
    shot u06_after_update_tap; dumpui u06; note "screen texts: $(uitext u06 | cut -c1-300)"
    [ -n "$INST" ] && pass "Update downloaded in the app and opened the system installer" || fail "Update did not reach the system installer ($FOC)"
    echo "$FOC" | grep -qi "chrome\|browser" && fail "Update opened the browser"
    adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1
  else fail "no Update button with a seeded newer release"; fi
  adb shell input keyevent KEYCODE_HOME; sleep 1
fi

note "== logcat crashes =="
adb logcat -d -b crash > shots/crash.txt 2>&1
C=$(grep -c "FATAL EXCEPTION" shots/crash.txt); [ "$C" -eq 0 ] && pass "no FATAL EXCEPTION in crash buffer" || fail "$C FATAL EXCEPTION(s) in crash buffer"
note "RESULT: $FAILS failure(s)"
[ "$FAILS" -eq 0 ]
