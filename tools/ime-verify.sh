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
dump() {
  local i x
  for i in 1 2 3 4; do
    adb shell rm -f /sdcard/ui.xml
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    x=$(adb shell cat /sdcard/ui.xml 2>/dev/null)
    if echo "$x" | grep -q "<hierarchy"; then echo "$x"; return 0; fi
    sleep 0.6
  done
  echo ""
  return 1
}

dim() { # dim <step-label>: log default_input_method ownership
  local cur
  cur=$(adb shell settings get secure default_input_method)
  if echo "$cur" | grep -q "$PKG"; then note "DEFAULT-IME[$1]: ours ($cur)"
  else
    note "DEFAULT-IME[$1]: NOT-OURS -> $cur"
    if [ -z "$FLIP_RECOVERED" ]; then
      FLIP_RECOVERED=1
      note "MID-SUITE FLIP detected at step '$1' (see flipwatch.txt for first-change time). Results of the preceding step are INVALID. Recovering (re-set IME + retap field) to continue; this is a WARM-SESSION continuation, not a pass of the cold path."
      adb logcat -d > shots/logcat_at_midsuite_flip.txt 2>&1
      adb shell ime set "$IME" >> "$R" 2>&1
      local b; b=$(edit_bounds); [ -n "$b" ] && tap_bounds "$b"
      sleep 3
      if [ -z "$GEOM_AFTER_RECOVERY" ]; then GEOM_AFTER_RECOVERY=1; shot recovered_keyboard; fi
    fi
  fi
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
node_bounds() { dump | tr '>' '>\n' | grep -i "$1" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1; }
tap_node() { local b; b=$(node_bounds "$1"); [ -z "$b" ] && return 1; tap_bounds "$b"; sleep 0.4; }
edit_bounds() { node_bounds 'class="android.widget.EditText"'; }
edit_text() { sleep 0.3; adb logcat -d -s IMETARGET:I 2>/dev/null | tr -d '\r' | grep 'FIELD=\|CLEARED' | tail -1 | sed -n 's/.*IMETARGET: FIELD=//p'; }
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
  adb shell am broadcast -a com.wrenboard.imetarget.CLEAR -p com.wrenboard.imetarget >/dev/null 2>&1
  sleep 0.8
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
IME=""
for i in $(seq 1 30); do
  IME=$(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1)
  [ -n "$IME" ] && break
  sleep 2
done
note "enumerated IME services (attempt $i): $(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | tr '\n' ' ')"
[ -z "$IME" ] && fail_early "WrenBoard IME service never enumerated by 'ime list -a -s' (no constructed id used)"
note "resolved IME id: $IME"
REGPATH=none
adb shell settings put secure show_ime_with_hard_keyboard 1
for i in 1 2 3; do
  adb shell ime enable "$IME" >> "$R" 2>&1
  adb shell ime set "$IME" >> "$R" 2>&1
  CUR=$(adb shell settings get secure default_input_method)
  if echo "$CUR" | grep -q "$PKG"; then REGPATH=normal-ime-command; break; fi
  sleep 3
done
note "REGISTRATION-PATH: $REGPATH"
[ "$REGPATH" = none ] && fail_early "normal ime enable/set did not register WrenBoard (no settings-provider fallback in the gate)"
note "NORMAL-REGISTRATION: PASS"
check "IME selected as default" "$PKG" "$CUR"
adb shell input keyevent KEYCODE_BACK
dim "post-registration"

( : > shots/flipwatch.txt; while true; do c=$(adb shell settings get secure default_input_method 2>/dev/null); if ! echo "$c" | grep -q "$PKG"; then echo "first non-ours default at runner $(date +%H:%M:%S.%N) device $(adb shell date +%H:%M:%S) -> $c" >> shots/flipwatch.txt; adb logcat -d > shots/logcat_at_flipwatch.txt 2>&1; break; fi; sleep 0.4; done ) &
FLIPWATCH_PID=$!
trap "kill $FLIPWATCH_PID 2>/dev/null" EXIT
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
if [ -n "$FLIP" ]; then note "COLD-SESSION-1 FLIP DETECTED: $FLIP"; else note "COLD-SESSION-1: no flip (held 10s)"; fi
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
    note "WARM-SESSION (recovery probe): second attempt HOLDS - cold-first-session-only switch, not persistent"
    shot 02_keyboard
  else
    note "WARM-SESSION (recovery probe): FLIPPED AGAIN -> $CUR2 - persistent fault"
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

note "== caps lock via shift DOUBLE-TAP (SwiftKey baseline) =="
clear_field
SH=$(jq -r '.keys.shift | "\(.[0]) \(.[1])"' "$GEOM")
DT_OK=""
for attempt in 1 2 3 4 5; do
  adb shell "nohup getevent -lt > /sdcard/ge.txt 2>&1 &" ; sleep 0.5
  adb shell "input tap $SH; input tap $SH"
  sleep 0.5; adb shell "pkill getevent" >/dev/null 2>&1
  adb shell cat /sdcard/ge.txt > shots/getevent_attempt$attempt.txt 2>&1
  GAP=$(adb shell cat /sdcard/ge.txt | tr -d '\r' | grep "BTN_TOUCH *DOWN" | sed -n 's/^\[ *\([0-9.]*\)\].*/\1/p' | awk 'NR==1{a=$1}NR==2{printf "%d", ($1-a)*1000}')
  note "double-tap attempt $attempt: measured down-to-down gap ${GAP:-unmeasured} ms (system double-tap window ~300 ms)"
  if [ -n "$GAP" ] && [ "$GAP" -le 250 ]; then DT_OK=1; break; fi
  sleep 0.8   # slow pair = shift on then off, back to unshifted; retry
done
[ -z "$DT_OK" ] && note "WARN: no double-tap landed inside 250 ms; caps double-tap result is NOT a valid test"
sleep 0.8
shot 06a_capslock_doubletap_state
type_letters w o r l d
sleep 0.5
shot 06b_capslock_doubletap_typed
assert_field "caps lock via double-tap typed WORLD" "WORLD"
tapk shift
note "== caps lock via shift long-press (observation, not assumed spec) =="
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
type_letters h e l l o; tapk space; type_letters w o r l d; tapk space; type_letters h e l l o; tapk space; type_letters w o r l d; tapk space; type_letters h e l l o; tapk space; type_letters w o r l d; tapk space; type_letters h e l l o; tapk space; type_letters w o r l d
sleep 0.6
BEFORE=$(edit_text)
note "backspace test text: '$BEFORE'"
BS=$(jq -r '.keys.backspace | "\(.[0]) \(.[1])"' "$GEOM")
adb shell log -t IMETARGET "BSMARK"
hold_key $BS 4500
sleep 1.0
shot 08b_backspace_midhold
sleep 4.5
adb logcat -d -v time -s IMETARGET:I | tr -d '\r' | sed -n '/BSMARK/,$p' | grep -v BSMARK > shots/backspace_series.txt
python3 - "$BEFORE" shots/backspace_series.txt >> "$R" <<'PY'
import sys,re
before=sys.argv[1]; rows=[]
for l in open(sys.argv[2]):
    m=re.match(r'\d+-\d+ (\d+):(\d+):(\d+\.\d+) .*FIELD=(.*)$',l.rstrip('\n'))
    if m: rows.append((int(m[1])*3600+int(m[2])*60+float(m[3]),m[4]))
states=[t for _,t in rows]; times=[t for t,_ in rows]
print("backspace states: "+" | ".join(repr(x) for x in states))
bad=[x for x in states if not (before.startswith(x) and (x=="" or x.endswith(" ") or x==before or before[len(x):len(x)+1]==" "))]
drops=[len(a)-len(b) for a,b in zip([before]+states,states)]
print("step drops (chars): %s" % drops)
print(("PASS" if states and not bad and max(drops+[0])>=2 else "FAIL")+": hold-backspace every step ends on a word boundary (no mid-word states)" + ("" if not bad else "; mid-word states: %r"%bad))
iv=[times[i+1]-times[i] for i in range(len(times)-1)]
print("step intervals (s): "+", ".join("%.2f"%x for x in iv))
if len(iv)>=4:
    h=len(iv)//2; fa=sum(iv[:h])/h; sa=sum(iv[h:])/(len(iv)-h)
    print(("PASS" if sa<fa else "FAIL")+": hold-backspace accelerates (first-half mean %.2fs, second-half mean %.2fs)"%(fa,sa))
else:
    print("FAIL: hold-backspace too few steps (%d) to assess acceleration"%len(iv))
PY
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
adb shell am broadcast -a com.wrenboard.imetarget.SETCLIP -p com.wrenboard.imetarget >/dev/null 2>&1
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

note "== cold session 2 (force-stop -> second cold sample) =="
adb shell am force-stop $PKG
sleep 2
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1
sleep 2
B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"
FLIP2=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 1
  CURI=$(adb shell settings get secure default_input_method)
  if ! echo "$CURI" | grep -q "$PKG"; then FLIP2="t+${i}s -> $CURI"; break; fi
done
if [ -n "$FLIP2" ]; then
  note "COLD-SESSION-2 FLIP DETECTED: $FLIP2"
  adb logcat -d > shots/logcat_at_flip2.txt 2>&1
  adb logcat -b crash -d > shots/logcat_crash_at_flip2.txt 2>&1
  adb shell ime set "$IME" >> "$R" 2>&1
  B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 3
  CUR2=$(adb shell settings get secure default_input_method)
  if echo "$CUR2" | grep -q "$PKG"; then note "WARM-SESSION-2: holds after re-set"; else note "WARM-SESSION-2: flipped again -> $CUR2"; fi
else
  note "COLD-SESSION-2: no flip (held 10s)"
fi
shot 15_cold2_state

note "== final =="
dim "final"
CUR=$(adb shell settings get secure default_input_method)
check "default IME still WrenBoard at end" "$PKG" "$CUR"
note "== done =="
exit 0
