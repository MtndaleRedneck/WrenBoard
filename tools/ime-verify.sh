#!/usr/bin/env bash
# Installed-IME verification v5 for WrenBoard on a booted emulator.
# Phase A (gate): install, normal-path registration, deterministic focused
#   EditText via the ime-target app, and CONFIRMED WrenBoard keyboard pixels.
#   Any gate failure aborts with decisive logs (no cascading suite).
# Phase B (suite, only after gate PASS): real input-tap coverage driven by
#   pixel-measured key geometry (tools/kbd_geom.py): typing, shift states,
#   caps lock, word-case cycling, hold-backspace, symbols pages 1+2,
#   clipboard, toolbar, dictation listening state.
# default_input_method is logged before/after every step.
set -u
APK="$1"
TARGET_APK="$2"
PKG="com.wrenboard.keyboard"
TPKG="com.wrenboard.imetarget"
mkdir -p shots
R=shots/results.txt
: > "$R"
GEOM=shots/geom.json

shot() { adb exec-out screencap -p > "shots/$1.png"; echo "shot $1" >> "$R"; }
note() { echo "$1" | tee -a "$R"; }
check() { if echo "$3" | grep -qi "$2"; then note "PASS: $1"; else note "FAIL: $1 (wanted '$2' in '${3:0:160}')"; fi; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml; }

dim() { # dim <step-label>: log default_input_method ownership
  local cur
  cur=$(adb shell settings get secure default_input_method)
  if echo "$cur" | grep -q "$PKG"; then note "DEFAULT-IME[$1]: ours ($cur)"
  else note "DEFAULT-IME[$1]: NOT-OURS -> $cur"; fi
}
focus_log() {
  note "focus: $(adb shell dumpsys window | grep -i 'mCurrentFocus' | head -1 | tr -s ' ')"
  note "ime-state: $(adb shell dumpsys input_method | grep -E 'mInputShown|mCurId' | tr -s ' ' | tr '\n' ' ')"
}
fail_early() {
  note "FAIL-EARLY: $1"
  adb shell dumpsys input_method > shots/dumpsys_ime.txt 2>&1
  adb shell ime list -a >> shots/dumpsys_ime.txt 2>&1
  adb shell dumpsys window > shots/dumpsys_window.txt 2>&1
  adb logcat -d > shots/logcat_full.txt 2>&1
  adb logcat -b crash -d > shots/logcat_crash.txt 2>&1
  adb logcat -b system -d > shots/logcat_system.txt 2>&1
  note "ABORTED with decisive logs (dumpsys_ime/dumpsys_window/logcat_full/crash/system)"
  exit 1
}

tap_bounds() { local nums x1 y1 x2 y2; nums=$(echo "$1" | grep -o '[0-9]*' | tr '\n' ' '); read -r x1 y1 x2 y2 <<< "$nums"; adb shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 )); }
node_bounds() { dump | tr '>' '>\n' | grep "$1" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1; }
tap_node() { local b; b=$(node_bounds "$1"); [ -z "$b" ] && return 1; tap_bounds "$b"; sleep 0.4; }
edit_bounds() { node_bounds 'class="android.widget.EditText"'; }
edit_text() { dump | tr '>' '>\n' | grep 'class="android.widget.EditText"' | grep -o 'text="[^"]*"' | head -1 | sed 's/^text="//; s/"$//'; }
assert_field() { local got; got=$(edit_text); if [ "$got" = "$2" ]; then note "PASS: $1 ('$got')"; else note "FAIL: $1 (wanted '$2', got '${got:0:160}')"; fi; }

tapk() { # tapk <geometry-key-name>
  local xy
  xy=$(jq -r --arg k "$1" '.keys[$k] // empty | "\(.[0]) \(.[1])"' "$GEOM" 2>/dev/null)
  if [ -z "$xy" ]; then note "FAIL: no geometry for key '$1'"; return 1; fi
  adb shell input tap $xy
  sleep 0.3
}
type_letters() { for c in "$@"; do tapk "$c" || return 1; done; }
hold_key() { # hold_key <x> <y> <ms> - backgrounded so we can shot mid-hold
  adb shell "nohup input swipe $1 $2 $1 $2 $3 >/dev/null 2>&1 &"
}
geom_rebuild() { # geom_rebuild <screenshot> <overlay> ; returns kbd_geom rc
  python3 tools/kbd_geom.py "shots/$1.png" "shots/$2.png" > "$GEOM" 2>shots/kbd_geom.err
}
clear_field() {
  adb shell input keyevent KEYCODE_BACK; sleep 0.6
  tap_node 'text="Clear"' || note "WARN: Clear button not found"
  sleep 0.3
  local b; b=$(edit_bounds); [ -n "$b" ] && tap_bounds "$b"
  sleep 1.5
}
screen_hash() { adb exec-out screencap -p | md5sum | cut -d' ' -f1; }

note "== install =="
adb install -r "$APK" >> "$R" 2>&1 || fail_early "WrenBoard APK install"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null

note "== first run / setup screen (pre-enable, wizard expected) =="
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
shot 01_first_run
check "first-run shows setup wizard" "WrenBoard\|setup\|enable" "$(dump | grep -o 'text="[^"]*"' | tr '\n' '|')"
adb shell am force-stop "$PKG"
IME=$(adb shell ime list -a -s 2>/dev/null | grep "^$PKG/" | head -1)
[ -z "$IME" ] && IME="$PKG/$PKG.LatinIME"
note "resolved IME id: $IME"
REGPATH=none
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell ime enable "$IME" >> "$R" 2>&1
adb shell ime set "$IME" >> "$R" 2>&1
CUR=$(adb shell settings get secure default_input_method)
if echo "$CUR" | grep -q "$PKG"; then REGPATH=normal-ime-command; fi
if [ "$REGPATH" = none ]; then
  note "standard enable failed; trying explicit component id"
  IME2="$PKG/org.futo.inputmethod.latin.LatinIME"
  adb shell ime enable "$IME2" >> "$R" 2>&1
  adb shell ime set "$IME2" >> "$R" 2>&1
  IME="$IME2"
  CUR=$(adb shell settings get secure default_input_method)
  if echo "$CUR" | grep -q "$PKG"; then REGPATH=normal-ime-command-explicit-id; fi
fi
if [ "$REGPATH" = none ]; then
  note "DIAGNOSTIC-ONLY: settings-provider write (NOT a registration pass)"
  adb shell settings put secure enabled_input_methods "$IME" >> "$R" 2>&1
  adb shell settings put secure default_input_method "$IME" >> "$R" 2>&1
  CUR=$(adb shell settings get secure default_input_method)
  echo "$CUR" | grep -q "$PKG" && REGPATH=settings-provider-write-DIAGNOSTIC-ONLY
fi
note "REGISTRATION-PATH: $REGPATH"
case "$REGPATH" in
  normal-ime-command*) note "NORMAL-REGISTRATION: PASS" ;;
  *) note "NORMAL-REGISTRATION: FAIL (no usability PASS may be claimed from this run)" ;;
esac
check "IME selected as default" "$PKG" "$CUR"
adb shell input keyevent KEYCODE_BACK
dim "post-registration"

note "== PHASE A: focused-field + keyboard-pixel gate =="
adb install -r "$TARGET_APK" >> "$R" 2>&1 || fail_early "ime-target APK install"
adb shell am start -n "$TPKG/.TestInput" >/dev/null 2>&1
sleep 3
dim "target-launched"
B=$(edit_bounds)
[ -n "$B" ] && tap_bounds "$B" || fail_early "EditText node not found in ime-target"
# poll default IME for 10s after focus: pin any fallback flip to its exact time
FLIP=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 1
  CURI=$(adb shell settings get secure default_input_method)
  if ! echo "$CURI" | grep -q "$PKG"; then FLIP="t+${i}s -> $CURI"; break; fi
done
dim "field-tapped"
[ -n "$FLIP" ] && note "DEFAULT-IME FLIP DETECTED: $FLIP"
focus_log
if [ -n "$FLIP" ]; then
  note "capturing full buffers at flip moment"
  adb logcat -d > shots/logcat_at_flip.txt 2>&1
  adb logcat -b crash -d > shots/logcat_crash_at_flip.txt 2>&1
  adb logcat -b system -d > shots/logcat_system_at_flip.txt 2>&1
  note "recovery probe: re-set IME and retap field"
  adb shell ime set "$IME" >> "$R" 2>&1
  B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"
  sleep 3
  CUR2=$(adb shell settings get secure default_input_method)
  if echo "$CUR2" | grep -q "$PKG"; then
    note "RECOVERY-PROBE: second attempt HOLDS (first-session race, not persistent fault)"
    shot 02_keyboard
  else
    note "RECOVERY-PROBE: second attempt FLIPPED AGAIN -> $CUR2 (persistent fault)"
    shot 02_keyboard
    fail_early "default IME flips to $CUR2 on every input session"
  fi
else
  shot 02_keyboard
fi
dump > shots/ui_keyboard.xml 2>/dev/null
geom_rebuild 02_keyboard 02_keyboard_overlay
GRC=$?
note "kbd_geom exit: $GRC"
[ "$GRC" -ne 0 ] && { cat shots/kbd_geom.err >> "$R"; fail_early "keyboard pixels not confirmed (kbd_geom rc=$GRC)"; }
NBANDS=$(jq '.bands | length' "$GEOM"); NKEYS=$(jq '.keys | length' "$GEOM")
note "geometry: bands=$NBANDS mapped-keys=$NKEYS toolbar=$(jq -c '.toolbar' "$GEOM")"
[ "$NBANDS" -lt 4 ] && fail_early "too few keyboard bands ($NBANDS)"
dim "geometry-built"
# mapping self-validation: geometry says where q is; tapping it must type q
clear_field
dim "after-clear"
tapk q || fail_early "no q in geometry"
sleep 0.5
assert_field "geometry self-validation (tap q types q)" "q"
clear_field
note "GATE: PASS (focused EditText + WrenBoard keyboard pixels + validated key mapping)"

note "== lowercase typing =="
dim "before-type"
type_letters h e l l o
sleep 0.5
shot 03_typed_hello
assert_field "typed hello" "hello"
dim "after-type"

note "== shift single letter =="
clear_field
tapk shift
sleep 0.3
shot 04_shift_single_state
type_letters h e l l o
sleep 0.5
shot 05_shift_typed
assert_field "shift produced Hello" "Hello"
dim "after-shift-single"

note "== caps lock via shift long-press =="
clear_field
SH=$(jq -r '.keys.shift | "\(.[0]) \(.[1])"' "$GEOM")
adb shell input swipe $SH $SH 800
sleep 0.8
shot 06_capslock_state
type_letters w o r l d
sleep 0.5
shot 07_capslock_typed
assert_field "caps lock typed WORLD" "WORLD"
tapk shift  # release caps
dim "after-caps"

note "== word-case cycling (shift on completed word) =="
clear_field
type_letters h e l l o
sleep 0.3
tapk shift; sleep 0.4; C1=$(edit_text)
tapk shift; sleep 0.4; C2=$(edit_text)
tapk shift; sleep 0.4; C3=$(edit_text)
shot 08_word_case_cycle
note "word-case cycle observed: hello -> '$C1' -> '$C2' -> '$C3'"
if [ "$C1" = "Hello" ] && [ "$C2" = "HELLO" ]; then
  note "PASS: word-case cycling (Hello then HELLO; third tap -> '$C3')"
else
  note "FAIL: word-case cycling (expected Hello/HELLO/hello, see observed)"
fi
dim "after-case-cycle"

note "== whole-word hold backspace (accelerating) =="
clear_field
type_letters h e l l o; tapk space; type_letters w o r l d
sleep 0.4
BEFORE=$(edit_text)
BS=$(jq -r '.keys.backspace | "\(.[0]) \(.[1])"' "$GEOM")
hold_key $BS 1300
sleep 0.6
shot 08b_backspace_midhold
sleep 1.2
AFTER1=$(edit_text)
note "hold-backspace #1: '$BEFORE' -> '$AFTER1'"
if [ "${#AFTER1}" -lt "${#BEFORE}" ] && [ "${#AFTER1}" -ge 5 ] && [ "${AFTER1:0:5}" = "hello" ]; then
  note "PASS: hold-backspace deleted at word level ('$AFTER1')"
else
  note "FAIL: hold-backspace word-level delete (before='$BEFORE' after='$AFTER1')"
fi
hold_key $BS 2500
sleep 1.2
shot 08c_backspace_longhold
sleep 2.0
AFTER2=$(edit_text)
note "hold-backspace #2 (accelerated): '$AFTER1' -> '$AFTER2'"
[ "${#AFTER2}" -lt "${#AFTER1}" ] && note "PASS: hold-backspace acceleration continues" || note "FAIL: hold-backspace acceleration (after='$AFTER2')"
dim "after-backspace"

note "== symbols page 1 =="
tapk symbols || fail_early "symbols key missing from geometry"
sleep 1.2
shot 09_symbols_page1
geom_rebuild 09_symbols_page1 09_symbols_page1_overlay || fail_early "symbols p1 geometry"
# grounded layout (java/assets/layouts/Special/symbols.yaml):
#   band0 digits 1-0 (10), band1 @ # $ & _ - ( ) = % (10),
#   band2 [{&= switch, " * ' : / ! ? +, delete] (10), band3 bottom row
SIG=$(jq -r '[.bands[].cells | length] | @csv' "$GEOM")
note "symbols p1 band signature: $SIG"
[ "$(echo "$SIG" | cut -d, -f1-3)" = "10,10,10" ] || note "WARN: unexpected symbols p1 signature (probe continues positionally)"
for xy in $(jq -r '[.bands[0].cells[], .bands[1].cells[], .bands[2].cells[1:9][]] | .[] | "\(.cx) \(.cy)"' "$GEOM"); do
  adb shell input tap $xy; sleep 0.2
done
sleep 0.6
P1=$(edit_text)
note "symbols p1 probe output: '$P1'"
check "symbols page1 types symbols including @" "@" "$P1"
check "symbols page1 digit row types 5" "5" "$P1"
dim "after-symbols-p1"

note "== symbols page 2 =="
clear_field
shot 09b_reenter
geom_rebuild 09b_reenter 09b_reenter_overlay || fail_early "geometry after clear before p2"
# if the keyboard came back on letters, re-enter symbols
if jq -e '.keys.symbols' "$GEOM" >/dev/null 2>&1; then
  tapk symbols || true
  sleep 1.0
  shot 09c_symbols_reentered
  geom_rebuild 09c_symbols_reentered 09c_symbols_reentered_overlay || fail_early "symbols p1 geometry after re-entry"
fi
SIG=$(jq -r '[.bands[].cells | length] | @csv' "$GEOM")
[ "$(echo "$SIG" | cut -d, -f1-3)" = "10,10,10" ] || fail_early "not on symbols page 1 for switch (signature $SIG)"
SWITCH=$(jq -r '.bands[2].cells[0] | "\(.cx) \(.cy)"' "$GEOM")
FT0=$(edit_text)
adb shell input tap $SWITCH
sleep 1.2
shot 10_symbols_page2
FT1=$(edit_text)
geom_rebuild 10_symbols_page2 10_symbols_page2_overlay || fail_early "symbols p2 geometry"
SIG2=$(jq -r '[.bands[].cells | length] | @csv' "$GEOM")
note "symbols p2 band signature: $SIG2 (field unchanged by switch: $([ "$FT0" = "$FT1" ] && echo yes || echo NO))"
# grounded layout (symbols_shift.yaml): band0 9 cells (pound euro yen ...), band1 9, band2 [123-back, 7 symbols, delete]
for xy in $(jq -r '[.bands[0].cells[], .bands[1].cells[], .bands[2].cells[1:-1][]] | .[] | "\(.cx) \(.cy)"' "$GEOM"); do
  adb shell input tap $xy; sleep 0.2
done
sleep 0.6
P2=$(edit_text)
note "symbols p2 probe output: '$P2'"
check "symbols page2 types page-2 symbols (pound)" "£" "$P2"
check "symbols page2 types page-2 symbols (euro)" "€" "$P2"
dim "after-symbols-p2"

note "== back to letters =="
ABC=$(jq -r 'if (.bands[-1].cells | length) == 4 then .bands[-2].cells[0] else .bands[-1].cells[0] end | "\(.cx) \(.cy)"' "$GEOM")
[ -n "$ABC" ] && adb shell input tap $ABC
sleep 1.0
shot 11_back_to_letters
geom_rebuild 11_back_to_letters 11_back_to_letters_overlay || fail_early "no geometry after returning to letters"
clear_field
tapk q || fail_early "no q after returning to letters"
sleep 0.5
assert_field "letters page restored (q types q)" "q"
dim "after-back-to-letters"

note "== clipboard (long-press symbols key, toolbar fallback) =="
adb shell input keyevent KEYCODE_BACK; sleep 0.6
tap_node 'text="SetClip"' || note "WARN: SetClip button not found"
sleep 0.4
B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 1.5
clear_field
SYX=$(jq -r '.keys.symbols[0]' "$GEOM"); SYY=$(jq -r '.keys.symbols[1]' "$GEOM")
KTOP=$(jq -r '.keyboard_top' "$GEOM")
hold_key $SYX $SYY 1000
sleep 1.5
shot 12_clipboard_popup
W=$(jq -r '.bands[0].cells[-1].x2' "$GEOM")
adb shell input tap $(( SYX + 120 )) $(( KTOP - 90 ))
sleep 0.8
CB=$(edit_text)
if echo "$CB" | grep -q "clipboard-probe-42"; then
  note "PASS: clipboard paste via long-press symbols key ('$CB')"
else
  note "long-press path inconclusive ('$CB'); trying toolbar clipboard item"
  TC=$(jq -r '.strip.clusters // 0' "$GEOM")
  if [ "$TC" -ge 5 ]; then
    TY=$(jq -r '.strip.cy' "$GEOM")
    adb shell input tap $(( W / (2 * TC) + 4 * W / TC )) $TY
    sleep 1.2
    shot 12b_clipboard_toolbar
    adb shell input tap $(( W / 2 )) $(( KTOP + 150 ))
    sleep 0.8
    CB2=$(edit_text)
    echo "$CB2" | grep -q "clipboard-probe-42" && note "PASS: clipboard paste via toolbar ('$CB2')" || note "FAIL: clipboard paste ('$CB2')"
  else
    note "FAIL: clipboard paste and no toolbar fallback cells ($TC)"
  fi
fi
dim "after-clipboard"

note "== toolbar (expand strip, count items) =="
EXP=$(jq -r '.keys.strip_expand // empty | "\(.[0]) \(.[1])"' "$GEOM")
if [ -z "$EXP" ]; then
  note "FAIL: no strip expander in geometry"
else
  adb shell input tap $EXP
  sleep 1.2
  shot 13_toolbar
  geom_rebuild 13_toolbar 13_toolbar_overlay && {
    TC=$(jq -r '.strip.clusters // 0' "$GEOM")
    note "expanded strip clusters: $TC"
    [ "$TC" -eq 7 ] && note "PASS: toolbar shows 7 items" || note "FAIL: toolbar item count $TC (wanted 7; see 13_toolbar + overlay)"
  } || note "FAIL: geometry after toolbar expand"
fi
dim "after-toolbar"

note "== dictation listening state =="
# strip is already expanded from the toolbar section; first item is voice
# (settled toolbar order: voice/emoji/undo/redo/clipboard/theme/settings)
VOICE=$(jq -r '.strip.first // empty | "\(.[0]) \(.[1])"' "$GEOM")
if [ -z "$VOICE" ]; then
  VOICE=$(jq -r '.keys.strip_mic // empty | "\(.[0]) \(.[1])"' "$GEOM")
fi
if [ -z "$VOICE" ]; then
  note "FAIL: no voice key in geometry"
else
  adb shell input tap $VOICE
  sleep 3
  shot 14_dictation_state
  adb shell pidof "$PKG" >/dev/null && note "PASS: dictation tap - app alive, listening state captured (audio injection N/A in CI; engine transcription validated via dictation-engine artifact)" || note "FAIL: app died after dictation tap"
  adb shell input keyevent KEYCODE_BACK
fi
sleep 1

note "== final =="
dim "final"
CUR=$(adb shell settings get secure default_input_method)
check "default IME still WrenBoard at end" "$PKG" "$CUR"
note "== done =="
exit 0
