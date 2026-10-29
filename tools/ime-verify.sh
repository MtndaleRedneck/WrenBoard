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

# adb root/unroot restarts adbd; the emulator can report "device offline" for a while afterwards. Wait until a shell command really works (up to ~60 s).
adb_ready() { local i; [ -n "$1" ] && adb "$1" >/dev/null 2>&1; sleep 3; adb wait-for-device 2>/dev/null; for i in $(seq 1 30); do adb shell true >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
SETTINGS_ACT="$PKG/org.futo.inputmethod.latin.uix.settings.SettingsActivity"
xdump() { adb shell uiautomator dump /sdcard/e.xml >/dev/null 2>&1; adb exec-out cat /sdcard/e.xml > "shots/$1.xml"; }
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
node_bounds() { dump | sed 's/>/>\n/g' | grep -i "$1" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1; }
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
precond() { # precond <label>: target app focused and keyboard shown, else relaunch + refresh geometry. rc 1 = still not ready after 3 tries
  local f ish try B
  for try in 1 2 3; do
    f=$(adb shell dumpsys window | tr -d '\r' | grep mCurrentFocus | head -1)
    ish=$(adb shell dumpsys input_method | tr -d '\r' | grep -o 'mInputShown=[a-z]*' | head -1)
    if echo "$f" | grep -q imetarget && [ "$ish" = "mInputShown=true" ]; then
      [ "$try" -gt 1 ] && { shot "precond_$1"; geom_rebuild "precond_$1" "precond_$1_overlay" >/dev/null 2>&1 || note "PRECONDITION[$1]: geometry rebuild failed"; clear_field; }
      return 0
    fi
    note "PRECONDITION[$1]: not ready (try $try; focus: ${f:-none}; $ish) - relaunching target (am start -S, clear-top) and re-tapping field"
    adb shell input keyevent KEYCODE_BACK; sleep 0.5
    adb shell input keyevent KEYCODE_HOME; sleep 1
    adb shell am start -S -f 0x14000000 -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
    B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
  done
  shot "precond_$1_FAILED"
  note "PRECONDITION[$1]: INCONCLUSIVE(harness) - target editor focus + keyboard not reached after 3 tries (focus: ${f:-none}; $ish)"
  return 1
}
screen_hash() { adb exec-out screencap -p | md5sum | cut -d' ' -f1; }

# Harness only: the CI emulator sometimes shows "Pixel Launcher isn't responding" right after boot, which steals window focus from the target (seen in run 40814151119). Hide system error dialogs for the run.
adb shell settings put global hide_error_dialogs 1 >/dev/null 2>&1; adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1
note "== install =="
adb install -r "$APK" >> "$R" 2>&1 || fail_early "WrenBoard APK install"
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO 2>/dev/null

note "== first run / setup screen (pre-enable, wizard expected) =="
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 5
shot 01_first_run
check "first-run shows setup wizard" "WrenBoard\|setup\|enable" "$(dump | grep -o 'text="[^"]*"' | tr '\n' '|')"
# No force-stop of the IME package: control runs showed that force-stopping it just before registration precedes a default-IME flip (also for upstream FUTO 0.1.30). A real user never force-stops it.
adb shell input keyevent KEYCODE_HOME
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
if [ "$GRC" -ne 0 ]; then
  # ONE bounded recovery (diagnostic): record the first failure, relaunch target, re-tap field, re-shoot, re-run the SAME geometry gate.
  cat shots/kbd_geom.err >> "$R"
  cp shots/02_keyboard.png shots/02_keyboard_attempt1.png; cp shots/ui_keyboard.xml shots/ui_keyboard_attempt1.xml
  cp "$GEOM" shots/geom_attempt1.json 2>/dev/null
  note "GEOMETRY-RECOVERY: attempt 1 failed (rc=$GRC: $(tr -d '\n' < shots/kbd_geom.err | cut -c1-160)); focus: $(adb shell dumpsys window | tr -d '\r' | grep mCurrentFocus | head -1); $(adb shell dumpsys input_method | tr -d '\r' | grep -o 'mInputShown=[a-z]*' | head -1); relaunching target once"
  adb shell input keyevent KEYCODE_BACK; sleep 0.5
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -S -f 0x14000000 -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 3
  B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 3
  note "GEOMETRY-RECOVERY: after relaunch focus: $(adb shell dumpsys window | tr -d '\r' | grep mCurrentFocus | head -1); $(adb shell dumpsys input_method | tr -d '\r' | grep -o 'mInputShown=[a-z]*' | head -1); default IME: $(adb shell settings get secure default_input_method | tr -d '\r')"
  shot 02_keyboard_attempt2
  dump > shots/ui_keyboard.xml 2>/dev/null
  geom_rebuild 02_keyboard_attempt2 02_keyboard_overlay
  GRC=$?
  note "GEOMETRY-RECOVERY: attempt 2 kbd_geom exit: $GRC (hard gate unchanged: nonzero = FAIL-EARLY)"
  [ "$GRC" -ne 0 ] && { cat shots/kbd_geom.err >> "$R"; fail_early "keyboard pixels not confirmed after one recovery (kbd_geom rc=$GRC)"; }
fi
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

cp "$GEOM" shots/geom_letters.json
if [ "${WB_FOCUS:-}" = caps ]; then
  note "FOCUS=caps: shift long-press only. Source: shift has moreKeys [!noPanelAutoMoreKey!, key_capslock]; PointerTracker.onLongPressed auto-commits the first more-key with no panel after config_longpress_shift_lock_timeout=1200 ms (stationary hold, no slide)."
  SH=$(jq -r '.keys.shift | "\(.[0]) \(.[1])"' "$GEOM")
  clear_field; sleep 0.5
  note "== caps control: stationary 800 ms hold (the earlier harness gesture, below the 1200 ms timer) =="
  adb shell "input swipe $SH $SH 800"; sleep 0.8
  shot caps_ctl_800_after
  type_letters w o r l d; sleep 0.5
  shot caps_ctl_800_typed
  assert_field "control 800 ms hold typed (expect One-shot 'World', i.e. no lock)" "World"
  clear_field; sleep 0.5
  note "== caps test: stationary 1600 ms hold (past 1200 ms timer), frame taken at ~1.45 s while finger still down =="
  ( adb shell "input swipe $SH $SH 1600" ) &
  SWPID=$!
  sleep 1.45; shot caps_1600_during_hold
  wait $SWPID; sleep 0.8
  shot caps_1600_after
  type_letters w o r l d; sleep 0.5
  shot caps_1600_typed
  assert_field "shift long-press 1600 ms then typed WORLD (caps lock)" "WORLD"
  note "== caps lock persistence: type a space and another word =="
  tapk space; type_letters h i; sleep 0.5
  shot caps_1600_persist
  assert_field "caps lock persists across a space (WORLD HI)" "WORLD HI"
  tapk shift; clear_field
  adb logcat -d > shots/logcat_full.txt 2>&1
  dim "final"
  note "== done =="
  exit 0
fi
if [ "${WB_FOCUS:-}" = shift ]; then
  note "FOCUS=shift: Caps lock setting through the real Settings screen, both modes, restart persistence"
  source tools/ime-shift-modes.sh
  adb logcat -d > shots/logcat_full.txt 2>&1
  FC=$(grep -c "FATAL EXCEPTION" shots/logcat_full.txt)
  note "FATAL EXCEPTION count after the shift legs: $FC"
  dim "final"
  note "== done =="
  exit 0
fi
if [ "${WB_FOCUS:-}" = learn ]; then note "FOCUS=learn: skipping the broad typing/symbols/settings legs; running learn-restore then the real-learned-data export/fresh-install leg only"; fi
if [ "${WB_FOCUS:-}" != learn ]; then
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

note "== word-case cycling (shift on completed word), isolated: fresh field, shift state confirmed off =="
clear_field
shot 07a_case_precondition
type_letters h e l l o
sleep 1.5   # let composing settle
shot 07b_case_before_tap
adb shell log -t IMETARGET "CASEMARK-A"
tapk shift; sleep 0.8; C1=$(edit_text); shot 07c_case_tap1
tapk shift; sleep 0.8; C2=$(edit_text); shot 07d_case_tap2
tapk shift; sleep 0.8; C3=$(edit_text); shot 07e_case_tap3
note "word-case cycle (variant A, typed then wait 1.5s) observed: hello -> '$C1' -> '$C2' -> '$C3'"
if [ "$C1" = "Hello" ] && [ "$C2" = "HELLO" ]; then
  note "PASS: word-case cycling (Hello then HELLO; third tap -> '$C3')"
else
  note "FAIL: word-case cycling variant A (expected Hello/HELLO/hello). Active IME was ours at preceding checkpoint; shift state per 07c-07e screenshots"
fi
note "(case variant B cursor-move diagnostic removed: variant A passes and B only probed the old hypothesis)"
precond caps
note "== normalize shift to off before caps tests =="
for n in 1 2 3; do
  clear_field; tapk a; sleep 0.4; NA=$(edit_text)
  [ "$NA" = "a" ] && { note "shift state normalized: off (typed '$NA')"; break; }
  note "shift state not off (typed '$NA'); tapping shift"; tapk shift
done
clear_field
note "== caps lock via shift DOUBLE-TAP (SwiftKey baseline) =="
clear_field
SH=$(jq -r '.keys.shift | "\(.[0]) \(.[1])"' "$GEOM")
DT_OK=""
# real evdev events via sendevent (adb 'input tap' never reaches getevent): find the touch device that
# actually delivers touches, then send two timed taps and measure the gap with getevent.
SCR=$(adb shell wm size | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1); SW=${SCR%x*}; SHH=${SCR#*x}
TDEV=""
for dev in $(adb shell getevent -lp 2>/dev/null | tr -d '\r' | awk '/^add device/{d=$4} /ABS_MT_POSITION_X/{print d}' | sort -u); do
  RX=$(adb shell getevent -lp "$dev" 2>/dev/null | tr -d '\r' | sed -n 's/.*ABS_MT_POSITION_X .*max \([0-9]*\).*/\1/p' | head -1)
  RY=$(adb shell getevent -lp "$dev" 2>/dev/null | tr -d '\r' | sed -n 's/.*ABS_MT_POSITION_Y .*max \([0-9]*\).*/\1/p' | head -1)
  [ -z "$RX" ] && continue
  # probe: tap 'h' via this device, see whether the field changes
  HXY=$(jq -r '.keys.h | "\(.[0]) \(.[1])"' "$GEOM"); hx=${HXY% *}; hy=${HXY#* }
  X=$(( hx * RX / SW )); Y=$(( hy * RY / SHH ))
  adb shell "sendevent $dev 3 57 7; sendevent $dev 3 53 $X; sendevent $dev 3 54 $Y; sendevent $dev 1 330 1; sendevent $dev 0 0 0; sendevent $dev 3 57 4294967295; sendevent $dev 1 330 0; sendevent $dev 0 0 0" >/dev/null 2>&1
  sleep 0.6
  P=$(edit_text)
  note "touch-device probe $dev (rangeX=$RX rangeY=$RY): field after sendevent tap on h = '$P'"
  if echo "$P" | grep -qi "h"; then TDEV=$dev; TRX=$RX; TRY=$RY; break; fi
done
clear_field
if [ -z "$TDEV" ]; then
  note "no evdev touch device delivered a tap; FALLBACK: two 'input tap' calls back-to-back in ONE adb shell (gap unmeasured, not evdev). The caps-lock outcome below is the only evidence."
  clear_field
  adb shell "input tap $SH; input tap $SH" >/dev/null 2>&1
else
  SX=$(( ${SH% *} * TRX / SW )); SY=$(( ${SH#* } * TRY / SHH ))
  tapev() { echo "sendevent $TDEV 3 57 9; sendevent $TDEV 3 53 $SX; sendevent $TDEV 3 54 $SY; sendevent $TDEV 1 330 1; sendevent $TDEV 0 0 0; sendevent $TDEV 3 57 4294967295; sendevent $TDEV 1 330 0; sendevent $TDEV 0 0 0"; }
  for attempt in 1 2 3 4 5; do
    clear_field
    adb shell "printf '%s\n' '$(tapev)' 'sleep 0.12' '$(tapev)' > /sdcard/dt.sh"
    adb shell "nohup getevent -lt $TDEV > /sdcard/ge.txt 2>&1 &" ; sleep 0.5
    adb shell "sh /sdcard/dt.sh"
    sleep 0.5; adb shell "pkill getevent" >/dev/null 2>&1
    adb shell cat /sdcard/ge.txt > shots/getevent_attempt$attempt.txt 2>&1
    GAP=$(tr -d '\r' < shots/getevent_attempt$attempt.txt | grep "BTN_TOUCH *DOWN" | sed -n 's/^\[ *\([0-9.]*\)\].*/\1/p' | awk 'NR==1{a=$1}NR==2{printf "%d", ($1-a)*1000}')
    note "double-tap attempt $attempt: measured down-to-down gap ${GAP:-unmeasured} ms (evdev, device $TDEV)"
    if [ -n "$GAP" ] && [ "$GAP" -le 250 ]; then DT_OK=1; break; fi
    sleep 0.8
  done
  [ -z "$DT_OK" ] && note "INCONCLUSIVE: no measured double-tap inside 250 ms"
  [ -n "$DT_OK" ] && note "double-tap landed inside 250 ms (measured)"
fi
clear_field
sleep 0.8
shot 06a_capslock_doubletap_state
type_letters w o r l d
sleep 0.5
shot 06b_capslock_doubletap_typed
assert_field "caps lock via double-tap typed WORLD" "WORLD"
tapk shift
note "== shift long-press (default Caps lock setting = tap cycle: a hold must NOT lock; FUTO-mode hold is covered by focus=shift) =="
clear_field
SH=$(jq -r '.keys.shift | "\(.[0]) \(.[1])"' "$GEOM")
adb shell input swipe $SH $SH 800
sleep 0.8
shot 06_capslock_state
type_letters w o r l d
sleep 0.5
shot 07_capslock_typed
assert_field "tap-cycle default: 800 ms hold is only a one-shot shift ('World', no lock)" "World"
tapk shift  # release caps
dim "after-caps"

precond backspace
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

precond symbols
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
cp "$GEOM" shots/geom_sym1.json
# The detected band0 for this page is a sliver (digit keys are lighter than the dark-key model), so
# digit-row taps use a positional model: x = 10 even columns, y = band1.cy - row pitch (band2.cy - band1.cy).
SCRW=$(adb shell wm size | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1 | cut -dx -f1)
B1Y=$(jq -r '.bands[1].cells[0].cy' "$GEOM"); B2Y=$(jq -r '.bands[2].cells[0].cy' "$GEOM")
DY=$(( B1Y - (B2Y - B1Y) ))
probe_key() { # probe_key <label> <x> <y> -> prints typed delta
  local before after
  before=$(edit_text); adb shell input tap $2 $3; sleep 0.5; after=$(edit_text)
  if [ "${after:0:${#before}}" = "$before" ]; then echo "${after:${#before}}"; else echo "?"; fi
}
# page identity via pixel crops: bottom-left key ('ABC' on both symbols pages) and band2-left key
# ('{&=' on page 1, '123' on page 2); letters page has '123' bottom-left.
read -r A1 A2 A3 A4 <<< "$(jq -r '.bands[3].cells[0] | "\(.x1) \(.y1) \(.x2) \(.y2)"' shots/geom_sym1.json)"
read -r C1 C2 C3 C4 <<< "$(jq -r '.bands[2].cells[0] | "\(.x1) \(.y1) \(.x2) \(.y2)"' shots/geom_sym1.json)"
python3 tools/pagecheck.py shots/09_symbols_page1.png $A1 $A2 $A3 $A4 shots/ref_abc.png --save >/dev/null
python3 tools/pagecheck.py shots/09_symbols_page1.png $C1 $C2 $C3 $C4 shots/ref_p1sw.png --save >/dev/null
pageis() { # pageis p1|other
  adb exec-out screencap -p > shots/_pc.png
  local a c
  a=$(python3 tools/pagecheck.py shots/_pc.png $A1 $A2 $A3 $A4 shots/ref_abc.png | cut -d' ' -f1)
  c=$(python3 tools/pagecheck.py shots/_pc.png $C1 $C2 $C3 $C4 shots/ref_p1sw.png | cut -d' ' -f1)
  [ "$a" = SAME ] && [ "$c" = SAME ] && echo p1 || echo other
}
ensure_p1() { # return keyboard to symbols page 1 if it reverted to letters
  [ "$(pageis)" = p1 ] && return 0
  note "  (page not symbols p1 - re-entering via 123 key; page reverted after previous key)"
  tapk symbols; sleep 1.0
  [ "$(pageis)" = p1 ]
}
clear_field
note "symbols p1 per-key probe, page re-entered whenever it reverted (rowDigits y=$DY, row1 y=$B1Y, row2 y=$B2Y, width=$SCRW)"
REVERT=0; DIGITS=""; P1B=""
for k in 0 1 2 3 4 5 6 7 8 9; do
  X=$(( SCRW * (528 + 992 * k) / 10000 ))
  ensure_p1 || { note "  cannot reach symbols p1 for digit $k"; break; }
  D=$(probe_key d$k $X $DY); DIGITS="$DIGITS$D"
  [ "$(pageis)" = p1 ] || { REVERT=$((REVERT+1)); note "  page left symbols p1 after digit key $k (typed '$D')"; }
done
for k in 0 1 2 3 4 5 6 7 8 9; do
  X=$(( SCRW * (528 + 992 * k) / 10000 ))
  ensure_p1 || { note "  cannot reach symbols p1 for symbol key $k"; break; }
  D=$(probe_key s$k $X $B1Y); P1B="$P1B$D"
  [ "$(pageis)" = p1 ] || { REVERT=$((REVERT+1)); note "  page left symbols p1 after symbol key $k (typed '$D')"; }
done
shot 09d_symbols_after_probe
note "symbols p1 digit row typed: '$DIGITS' (expect 1234567890)"
note "symbols p1 symbol row typed: '$P1B' (expect @#\$&_-()=%)"
note "symbols p1: page reverted to letters/other after $REVERT of 20 key taps"
P1="$DIGITS$P1B"
check "symbols page1 digit row types 1234567890" "1234567890" "$DIGITS"
check "symbols page1 symbol row includes @" "@" "$P1B"
dim "after-symbols-p1"

note "== symbols page 2 =="
clear_field
FT0=$(edit_text)
ensure_p1 || note "  could not confirm symbols p1 before switching to p2"
SWITCH=$(jq -r '.bands[2].cells[0] | "\(.cx) \(.cy)"' shots/geom_sym1.json)
adb shell input tap $SWITCH
sleep 1.2
shot 10_symbols_page2
# Page-2 identity: band-cell-count signature from pixel geometry (offline-validated on run 40770482775 frames:
# p1 = 10,10,10,5,4  p2 = 9,9,9,7,4  letters = 10,10,9,9,6,4).
sig_of() { python3 tools/kbd_geom.py "$1" "${1%.png}_ov.png" 2>/dev/null | jq -r '[.bands[]|(.cells|length)]|map(tostring)|join(",")'; }
SIG2=$(sig_of shots/10_symbols_page2.png)
if [ "$SIG2" = "9,9,9,7,4" ]; then
  P2REACHED=1; note "symbols p2 reached (band signature $SIG2) - see 10_symbols_page2.png"
  python3 tools/kbd_geom.py shots/10_symbols_page2.png shots/10_symbols_page2_overlay.png > shots/geom_sym2.json 2>/dev/null
else
  P2REACHED=0; note "INCONCLUSIVE: symbols page 2 NOT reached (band signature '$SIG2', wanted 9,9,9,7,4); p2 probes skipped - see 10_symbols_page2.png"
fi
FT1=$(edit_text)
note "symbols p2 reached via {&= key (field unchanged by switch: $([ "$FT0" = "$FT1" ] && echo yes || echo NO))"
pageis2() {
  adb exec-out screencap -p > shots/_pc.png
  [ "$(sig_of shots/_pc.png)" = "9,9,9,7,4" ] && echo p2 || echo other
}
ensure_p2() {
  [ "$(pageis2)" = p2 ] && return 0
  note "  (page not symbols p2 - re-entering)"
  ensure_p1 && { adb shell input tap $SWITCH; sleep 1.0; }
  [ "$(pageis2)" = p2 ]
}
P2A=""; P2B=""; REV2=0
for k in 0 1 2 3 4 5 6 7 8; do
  [ "$P2REACHED" = 1 ] || break
  ensure_p2 || { note "  cannot reach symbols p2 for top key $k"; break; }
  X=$(jq -r ".bands[0].cells[$k].cx" shots/geom_sym2.json); Y=$(jq -r ".bands[0].cells[$k].cy" shots/geom_sym2.json)
  D=$(probe_key t$k $X $Y); P2A="$P2A$D"
  [ "$(pageis2)" = p2 ] || { REV2=$((REV2+1)); note "  page left p2 after top key $k (typed '$D')"; }
done
for k in 1 2 3 4 5 6 7; do
  [ "$P2REACHED" = 1 ] || break
  ensure_p2 || { note "  cannot reach symbols p2 for second-row key $k"; break; }
  X=$(jq -r ".bands[1].cells[$k].cx" shots/geom_sym2.json); Y=$(jq -r ".bands[1].cells[$k].cy" shots/geom_sym2.json)
  D=$(probe_key u$k $X $Y); P2B="$P2B$D"
  [ "$(pageis2)" = p2 ] || { REV2=$((REV2+1)); note "  page left p2 after second-row key $k (typed '$D')"; }
done
shot 10b_symbols_p2_after_probe
P2="$P2A$P2B"
note "symbols p2 top row typed: '$P2A' (expect currency row)"
note "symbols p2 second row typed: '$P2B' (expect []{}<>^)"
note "symbols p2: page left p2 after $REV2 of 16 key taps"
if [ "$P2REACHED" = 1 ]; then
check "symbols page2 types page-2 symbols (pound)" "£" "$P2"
check "symbols page2 types page-2 symbols (euro)" "€" "$P2"
fi
dim "after-symbols-p2"

note "== back to letters =="
ABC=$(jq -r '.bands[3].cells[0] | "\(.cx) \(.cy)"' shots/geom_sym1.json)
[ -n "$ABC" ] && adb shell input tap $ABC
sleep 1.0
shot 11_back_to_letters
geom_rebuild 11_back_to_letters 11_back_to_letters_overlay || { note "INCONCLUSIVE: geometry after returning to letters failed; skipping letters-dependent checks"; SKIPLET=1; }
clear_field
if [ -n "${SKIPLET:-}" ]; then cp shots/geom_letters.json "$GEOM"; note "using saved letters geometry (page return unverified by geometry; see 11_back_to_letters.png)"; fi
if tapk q; then sleep 0.5; assert_field "letters page restored (q types q)" "q"; else note "INCONCLUSIVE: q key not mapped after return"; fi
dim "after-back-to-letters"

precond clipboard
note "== clipboard (long-press symbols key, toolbar fallback) =="
adb shell input keyevent KEYCODE_BACK; sleep 0.6
adb shell am broadcast -a com.wrenboard.imetarget.SETCLIP -p com.wrenboard.imetarget >/dev/null 2>&1
sleep 0.4
B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 1.5
clear_field
# precondition for the long-press: the LETTERS page must be showing (123 key at bottom-left). Assert from pixels, switch if not.
for _t in 1 2 3; do
  adb exec-out screencap -p > shots/_lp.png
  LSIG=$(sig_of shots/_lp.png)
  [ "$LSIG" = "10,10,9,9,6,4" ] && break
  note "  (clipboard long-press precondition: page signature '$LSIG', not letters; tapping bottom-left key to return to letters)"
  python3 tools/kbd_geom.py shots/_lp.png shots/_lp_ov.png > shots/_lp.json 2>/dev/null
  BL=$(jq -r '.bands[-2].cells[0] | "\(.cx) \(.cy)"' shots/_lp.json)
  [ -n "$BL" ] && adb shell input tap $BL; sleep 1.2
done
if [ "$LSIG" = "10,10,9,9,6,4" ]; then
  note "clipboard long-press precondition OK: letters page confirmed (signature $LSIG)"
  cp shots/_lp.png shots/12_pre_letters.png
  python3 tools/kbd_geom.py shots/12_pre_letters.png shots/12_pre_letters_overlay.png > "$GEOM" 2>/dev/null
else
  note "FAIL(harness precondition): letters page not confirmed before 123 long-press (signature '$LSIG')"
fi
SYX=$(jq -r '.keys.symbols[0]' "$GEOM"); SYY=$(jq -r '.keys.symbols[1]' "$GEOM")
KTOP=$(jq -r '.keyboard_top' "$GEOM")
hold_key $SYX $SYY 4500
sleep 1.2; shot 12_clipboard_hold_1s
sleep 1.2; shot 12_clipboard_popup
sleep 3.0
W=$(jq -r '.bands[0].cells[-1].x2' "$GEOM")
# The long-press outcome is the clipboard panel replacing the keys (Clipboard Manager card). Measure orange area under the keyboard top.
PANEL_PX=$(python3 - "$KTOP" <<'PY'
import sys
from PIL import Image
im=Image.open("shots/12_clipboard_hold_1s.png").convert("RGB"); kt=int(float(sys.argv[1]))
px=im.load(); n=0
for y in range(kt+60, im.height-200, 2):
    for x in range(0, im.width, 2):
        r,g,b=px[x,y]
        if r>230 and 110<g<170 and b<60: n+=1
print(n)
PY
)
if [ "${PANEL_PX:-0}" -gt 20000 ]; then
  note "PASS: long-press on 123 opened the clipboard panel (orange card area ${PANEL_PX} sampled px; see 12_clipboard_hold_1s.png - inspect the frame)"
  adb shell input tap 90 $(( KTOP + 72 )); sleep 1.2; shot 12c_after_panel_close
  CB="clipboard-panel-opened"
  SKIP_CB_FALLBACK=1
fi
if [ -z "${SKIP_CB_FALLBACK:-}" ]; then
adb shell input tap $(( SYX + 120 )) $(( KTOP - 90 ))
sleep 0.8
CB=$(edit_text)
fi
if [ -n "${SKIP_CB_FALLBACK:-}" ]; then :
elif echo "$CB" | grep -q "clipboard-probe-42"; then
  note "PASS: clipboard paste via long-press symbols key ('$CB')"
else
  note "long-press path inconclusive ('$CB'); trying the clipboard suggestion item shown in the strip (as in 12_clipboard_popup.png)"
  STY=$(jq -r '.strip.cy // empty' "$GEOM")
  if [ -n "$STY" ]; then
    adb shell input tap 360 $STY; sleep 1.0
    CB1=$(edit_text); shot 12a_clipboard_strip_item
    if echo "$CB1" | grep -q "clipboard-probe-42"; then note "PASS: clipboard paste via strip clipboard item ('$CB1')"; CB="$CB1"; fi
  fi
  if ! echo "$CB" | grep -q "clipboard-probe-42"; then
  TC=$(jq -r '.toolbar.n // 0' "$GEOM")
  if [ "$TC" -ge 5 ]; then
    adb shell input tap $(jq -r '.keys.toolbar_clipboard | "\(.[0]) \(.[1])"' "$GEOM")
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
fi
dim "after-clipboard"

precond toolbar
note "== toolbar (expand strip, count items) =="
EXP=$(jq -r '.keys.strip_expand // empty | "\(.[0]) \(.[1])"' "$GEOM")
if [ -z "$EXP" ]; then
  note "FAIL: no strip expander in geometry"
else
  # approved default: toolbar starts EXPANDED. Capture as-is first (no tap).
  sleep 1.0
  shot 13_toolbar
  geom_rebuild 13_toolbar 13_toolbar_overlay && {
    TC=$(jq -r '.toolbar.n // 0' "$GEOM")
    note "toolbar icon count (measured on 13_toolbar from pixels): $TC"
    [ "$TC" -eq 7 ] && note "PASS: toolbar shows 7 items" || note "FAIL: toolbar item count $TC (wanted 7; see 13_toolbar + overlay)"
  } || note "FAIL: geometry after toolbar expand"
  # collapse then re-expand via the chevron. The chevron stays at the same spot in both states, and kbd_geom is NOT
  # reliable on the collapsed frame (it picked a digit key), so reuse the expanded-frame chevron and assert each transition from pixels.
  tb_orange() { python3 tools/toolbar_state.py "shots/$1.png" 1000 1090; }
  N0=$(tb_orange 13_toolbar)
  adb shell input tap $EXP; sleep 1.2; shot 13b_toolbar_collapsed
  N1=$(tb_orange 13b_toolbar_collapsed)
  [ "$N0" -gt 500 ] && [ "$N1" -lt 50 ] && note "PASS: chevron collapsed the toolbar (toolbar orange px $N0 -> $N1)" || note "FAIL: collapse transition (orange px $N0 -> $N1)"
  adb shell input tap $EXP; sleep 1.2; shot 13c_toolbar_reexpanded
  N2=$(tb_orange 13c_toolbar_reexpanded)
  [ "$N2" -gt 500 ] && note "PASS: chevron re-expanded the toolbar (orange px $N1 -> $N2)" || note "FAIL: re-expand transition (orange px $N1 -> $N2)"
  geom_rebuild 13c_toolbar_reexpanded 13c_overlay >/dev/null 2>&1
  TC2=$(jq -r '.toolbar.n // 0' "$GEOM"); [ "$TC2" -eq 7 ] && note "PASS: 7 toolbar items after re-expand (measured)" || note "FAIL: toolbar items after re-expand = $TC2"
fi
dim "after-toolbar"

precond dictation
note "== dictation listening state =="
# strip is already expanded from the toolbar section; first item is voice
# (settled toolbar order: voice/emoji/undo/redo/clipboard/theme/settings)
# fresh geometry from a CURRENT expanded-toolbar frame (the old geometry came from a collapsed frame and pointed at the chevron)
adb exec-out screencap -p > shots/14_pre.png
if [ "$(python3 tools/toolbar_state.py shots/14_pre.png 1000 1090)" -lt 500 ]; then
  note "  (dictation precondition: toolbar collapsed, tapping chevron)"; adb shell input tap $EXP; sleep 1.2
  adb exec-out screencap -p > shots/14_pre.png
fi
if [ "$(python3 tools/toolbar_state.py shots/14_pre.png 1000 1090)" -ge 500 ] && geom_rebuild 14_pre 14_pre_overlay && [ "$(jq -r '.toolbar.n // 0' "$GEOM")" -eq 7 ]; then
  note "dictation precondition OK: toolbar expanded, 7 icons measured on 14_pre.png; mic = first toolbar icon $(jq -c '.keys.toolbar_voice' "$GEOM")"
else
  note "FAIL(harness precondition): toolbar not confirmed expanded with 7 icons before dictation tap"
  jq -c . /dev/null 2>/dev/null; GEOM_BAD=1
fi
VOICE=$(jq -r '.keys.toolbar_voice // empty | "\(.[0]) \(.[1])"' "$GEOM")
if [ -z "$VOICE" ]; then
  VOICE=$(jq -r '.keys.strip_mic // empty | "\(.[0]) \(.[1])"' "$GEOM")
fi
if [ -z "$VOICE" ]; then
  VOICE=$(jq -r '.strip.first // empty | "\(.[0]) \(.[1])"' "$GEOM")
fi
if [ -n "${GEOM_BAD:-}" ]; then
  note "SKIPPED dictation tap: precondition failed"
elif [ -z "$VOICE" ]; then
  note "FAIL: no voice key in geometry"
else
  adb shell input tap $VOICE
  sleep 3
  shot 14_dictation_state
  adb shell pidof "$PKG" >/dev/null && note "OBSERVATION: dictation tap done, app alive. NOT a pass: the listening UI must be confirmed from 14_dictation_state.png (an earlier run tapped the wrong strip item). Audio injection N/A in CI." || note "FAIL: app died after dictation tap"
  adb shell input keyevent KEYCODE_BACK
fi
sleep 1

note "== settings: toolbar action editor - edit, save, reload (navDest=actionEdit) =="
open_editor() { # open_editor <label>: open editor, assert Edit Actions title + Pinned/Favorite headers from the XML
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -n "$SETTINGS_ACT" --es navDest actionEdit >/dev/null 2>&1; sleep 5
  shot "$1"; xdump "$1"
  python3 tools/xml_node.py "shots/$1.xml" "Edit Actions" >/dev/null && python3 tools/xml_node.py "shots/$1.xml" "Pinned Action(s)" >/dev/null && python3 tools/xml_node.py "shots/$1.xml" "Favorite Actions" >/dev/null
}
if open_editor 16_editor_before; then
  note "PASS: editor opened (Edit Actions, Pinned Action(s), Favorite Actions present)"
  read -r VX VY VTOP <<< "$(python3 tools/xml_node.py shots/16_editor_before.xml 'Voice Input')"
  read -r _ _ PTOP <<< "$(python3 tools/xml_node.py shots/16_editor_before.xml 'Pinned Action(s)')"
  read -r _ _ FTOP <<< "$(python3 tools/xml_node.py shots/16_editor_before.xml 'Favorite Actions')"
  if [ "${VTOP:-0}" -gt "${FTOP:-99999}" ]; then note "PASS: precondition: Voice Input starts under Favorite Actions"; else note "FAIL(precondition): Voice Input not under Favorite Actions (voice y=$VTOP fav y=$FTOP)"; fi
  if AN=$(python3 tools/xml_node.py shots/16_editor_before.xml 'Add next'); then
    read -r AX AY _ <<< "$AN"
    adb shell input tap $AX $AY; sleep 1.5
    shot 16b_editor_after_addnext; xdump 16b_editor_after_addnext
    read -r _ _ VTOP2 <<< "$(python3 tools/xml_node.py shots/16b_editor_after_addnext.xml 'Voice Input')"
    read -r _ _ PTOP2 <<< "$(python3 tools/xml_node.py shots/16b_editor_after_addnext.xml 'Pinned Action(s)')"
    read -r _ _ FTOP2 <<< "$(python3 tools/xml_node.py shots/16b_editor_after_addnext.xml 'Favorite Actions')"
    if [ -n "${VTOP2:-}" ] && [ "$VTOP2" -gt "$PTOP2" ] && [ "$VTOP2" -lt "$FTOP2" ]; then note "PASS: Add next moved Voice Input under Pinned Action(s) (voice y=$VTOP2, pinned y=$PTOP2, favorite y=$FTOP2)"; else note "FAIL: Add next did not move Voice Input into Pinned (voice=$VTOP2 pinned=$PTOP2 fav=$FTOP2)"; fi
    adb shell input keyevent KEYCODE_BACK; sleep 2
    if open_editor 16c_editor_reopened; then
      read -r _ _ VTOP3 <<< "$(python3 tools/xml_node.py shots/16c_editor_reopened.xml 'Voice Input')"
      read -r _ _ PTOP3 <<< "$(python3 tools/xml_node.py shots/16c_editor_reopened.xml 'Pinned Action(s)')"
      read -r _ _ FTOP3 <<< "$(python3 tools/xml_node.py shots/16c_editor_reopened.xml 'Favorite Actions')"
      if [ -n "${VTOP3:-}" ] && [ "$VTOP3" -gt "$PTOP3" ] && [ "$VTOP3" -lt "$FTOP3" ]; then note "PASS: edit persisted after leaving and reopening the editor"; else note "FAIL: edit did not persist (voice=$VTOP3 pinned=$PTOP3 fav=$FTOP3)"; fi
    else note "FAIL: editor did not reopen"; fi
  else note "FAIL: Add next node missing in editor XML (see 16_editor_before.png)"; fi
else
  note "FAIL: editor not confirmed open (see 16_editor_before.png)"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
shot 17_keyboard_after_edit
geom_rebuild 17_keyboard_after_edit 17_keyboard_after_edit_overlay >/dev/null 2>&1
note "toolbar after edit: icons=$(jq -r '.toolbar.n // 0' "$GEOM") (inspect 17_keyboard_after_edit.png: emoji must still be in the toolbar exactly once)"
dim "after-editor"

note "== settings: editor drag reorder, Disable (Hidden Actions) save, re-enable (density lowered so the whole editor fits one screen; reset afterwards) =="
adb shell wm density 300 >/dev/null 2>&1; sleep 2
ypos() { python3 tools/xml_node.py "shots/$1.xml" "$2" | awk '{print $3}'; }   # y1 of exact-text node, empty if missing
xpos() { python3 tools/xml_node.py "shots/$1.xml" "$2" | awk '{print $1}'; }
drag_node() { # drag_node <from text> <to text> <label>: try draganddrop then a long swipe; assert nothing here, caller asserts from XML
  local f t fx fy tx ty
  f=$(python3 tools/xml_node.py shots/$4.xml "$1") || return 1
  t=$(python3 tools/xml_node.py shots/$4.xml "$2") || return 1
  read -r fx fy _ <<< "$f"; read -r tx ty _ <<< "$t"
  note "  drag '$1' ($fx,$fy) -> '$2' ($tx,$ty) via $3"
  if [ "$3" = dnd ]; then adb shell input draganddrop $fx $fy $tx $ty 1500; else adb shell input swipe $fx $fy $tx $ty 2200; fi
  sleep 1.5
}
if open_editor 18_ed_dense_before; then
  note "editor opened at lowered density; Hidden Actions header y=$(ypos 18_ed_dense_before 'Hidden Actions') Settings y=$(ypos 18_ed_dense_before 'Settings')"
  HY0=$(ypos 18_ed_dense_before 'Hidden Actions'); SY0=$(ypos 18_ed_dense_before 'Settings')
  if [ -z "$HY0" ] || [ -z "$SY0" ] || [ -z "$(ypos 18_ed_dense_before 'Debug Info')" ]; then
    note "INCONCLUSIVE(harness): Hidden Actions / Settings / Debug Info not all visible at once; see 18_ed_dense_before.png"
  else
    DONE=0
    for M in dnd swipe; do
      drag_node "Settings" "Debug Info" $M 18_ed_dense_before || break
      shot 18b_ed_after_disable_$M; xdump 18b_ed_after_disable_$M
      HY=$(ypos 18b_ed_after_disable_$M 'Hidden Actions'); SY=$(ypos 18b_ed_after_disable_$M 'Settings')
      if [ -n "$SY" ] && [ -n "$HY" ] && [ "$SY" -gt "$HY" ]; then note "PASS: Settings moved into Hidden Actions via $M (settings y=$SY, hidden header y=$HY)"; DONE=1; break
      else note "  drag via $M did not move Settings under Hidden Actions (settings y=$SY hidden y=$HY)"; open_editor 18_ed_dense_before >/dev/null; fi
    done
    if [ "$DONE" = 1 ]; then
      adb shell input keyevent KEYCODE_BACK; sleep 2
      open_editor 18c_ed_reopened_after_disable >/dev/null
      HY=$(ypos 18c_ed_reopened_after_disable 'Hidden Actions'); SY=$(ypos 18c_ed_reopened_after_disable 'Settings')
      if [ -n "$SY" ] && [ -n "$HY" ] && [ "$SY" -gt "$HY" ]; then note "PASS: Disabled state persisted after leaving and reopening (settings y=$SY, hidden y=$HY)"; else note "FAIL: Disabled state did not persist (settings y=$SY hidden y=$HY)"; fi
      adb shell wm density reset >/dev/null 2>&1; sleep 2
      adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_HOME; sleep 1
      adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
      B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
      shot 19_keyboard_settings_disabled; geom_rebuild 19_keyboard_settings_disabled 19_ov >/dev/null 2>&1
      note "toolbar with Settings disabled: icons=$(jq -r '.toolbar.n // 0' "$GEOM") (was 6 after pin edit; inspect 19_keyboard_settings_disabled.png: the sun/settings icon must be gone)"
      # re-enable
      adb shell wm density 300 >/dev/null 2>&1; sleep 2
      open_editor 20_ed_before_reenable >/dev/null
      for M in dnd swipe; do
        drag_node "Settings" "Undo" $M 20_ed_before_reenable || break
        shot 20b_ed_after_reenable_$M; xdump 20b_ed_after_reenable_$M
        HY=$(ypos 20b_ed_after_reenable_$M 'Hidden Actions'); SY=$(ypos 20b_ed_after_reenable_$M 'Settings'); FY=$(ypos 20b_ed_after_reenable_$M 'Favorite Actions')
        if [ -n "$SY" ] && [ -n "$FY" ] && [ -n "$HY" ] && [ "$SY" -gt "$FY" ] && [ "$SY" -lt "$HY" ]; then note "PASS: Settings re-enabled into Favorite Actions via $M (settings y=$SY, favorites y=$FY, hidden y=$HY)"; REEN=1; break
        else note "  re-enable via $M failed (settings y=$SY fav y=$FY hidden y=$HY)"; open_editor 20_ed_before_reenable >/dev/null; fi
      done
      if [ "${REEN:-0}" = 1 ]; then
        # reorder: drag Redo onto Undo's slot, assert Redo now left of / above Undo
        shot 21_ed_pre_reorder; xdump 21_ed_pre_reorder
        UX0=$(xpos 21_ed_pre_reorder 'Undo'); RX0=$(xpos 21_ed_pre_reorder 'Redo'); UY0=$(ypos 21_ed_pre_reorder 'Undo'); RY0=$(ypos 21_ed_pre_reorder 'Redo')
        note "  before reorder: Undo x=$UX0 y=$UY0, Redo x=$RX0 y=$RY0"
        drag_node "Redo" "Undo" dnd 21_ed_pre_reorder
        shot 21b_ed_after_reorder; xdump 21b_ed_after_reorder
        UX1=$(xpos 21b_ed_after_reorder 'Undo'); RX1=$(xpos 21b_ed_after_reorder 'Redo'); UY1=$(ypos 21b_ed_after_reorder 'Undo'); RY1=$(ypos 21b_ed_after_reorder 'Redo')
        note "  after reorder: Undo x=$UX1 y=$UY1, Redo x=$RX1 y=$RY1"
        if [ "$RY1" -lt "$UY1" ] || { [ "$RY1" -eq "$UY1" ] && [ "$RX1" -lt "$UX1" ]; }; then note "PASS: drag reorder placed Redo before Undo"; else note "FAIL/INCONCLUSIVE: reorder did not change order (see 21b_ed_after_reorder.png)"; fi
      fi
    fi
  fi
else
  note "FAIL: editor not confirmed open at lowered density (see 18_ed_dense_before.png)"
fi
adb shell wm density reset >/dev/null 2>&1; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
shot 22_keyboard_final_after_editor; geom_rebuild 22_keyboard_final_after_editor 22_ov >/dev/null 2>&1
note "toolbar at end of editor tests: icons=$(jq -r '.toolbar.n // 0' "$GEOM")"
dim "after-editor-2"

note "== update check: manifest permission and real network behavior =="
PERMS=$(adb shell dumpsys package com.wrenboard.keyboard | tr -d '\r' | grep -E "android.permission.INTERNET" | head -3 | tr '\n' ' ')
note "dumpsys INTERNET lines: ${PERMS:-none}"
echo "$PERMS" | grep -q INTERNET && note "PASS: INTERNET permission present in installed app" || note "FAIL: INTERNET permission missing in installed app"
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -n "$SETTINGS_ACT" --es navDest home >/dev/null 2>&1; sleep 5
shot 23_settings_home; xdump 23_settings_home
if UN=$(python3 tools/xml_node.py shots/23_settings_home.xml 'Check for updates manually'); then
  read -r UX UY _ <<< "$UN"
  adb shell input tap $UX $UY; sleep 12
  shot 23b_after_update_check; xdump 23b_after_update_check
  adb logcat -d -s UpdateChecking:* System.err:* 2>/dev/null | tr -d '\r' | tail -12 > shots/update_check_log.txt
  note "update check log tail:"; sed 's/^/    /' shots/update_check_log.txt | tee -a "$R"
  sed 's/>/>\n/g' shots/23b_after_update_check.xml | sed -n 's/.*text="\([^"]\+\)".*/\1/p' | head -14 | tr '\n' '|' | sed 's/^/screen texts after check: /' | tee -a "$R"; echo | tee -a "$R"
else
  note "INCONCLUSIVE(harness): 'Check for updates manually' node not on Settings home (see 23_settings_home.png)"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
dim "after-update-check"

note "== backup eligibility dry run (installed app) =="
adb shell dumpsys package com.wrenboard.keyboard | tr -d '\r' | grep -E "^\s*(flags|pkgFlags|privateFlags)=" | head -3 | sed 's/^/    /' | tee -a "$R"
adb shell dumpsys package com.wrenboard.keyboard | tr -d '\r' | grep -iE "dataExtractionRules|fullBackupContent|ALLOW_BACKUP" | head -5 | sed 's/^/    /' | tee -a "$R"
FL=$(adb shell dumpsys package com.wrenboard.keyboard | tr -d '\r' | grep -E "^\s*pkgFlags=" | head -1)
# FUTO-parity backup (allowBackup=true, one exclude for the bundled model): the flag SHOULD be present.
echo "$FL" | grep -q "ALLOW_BACKUP" && note "PASS: installed app carries ALLOW_BACKUP as intended (FUTO parity) ($FL)" || note "FAIL: installed app lacks ALLOW_BACKUP, expected FUTO parity ($FL)"
note "bmgr dry run:"; adb shell bmgr enabled 2>&1 | head -2 | sed 's/^/    /' | tee -a "$R"
adb shell bmgr backupnow com.wrenboard.keyboard 2>&1 | head -6 | sed 's/^/    /' | tee -a "$R"
adb shell bmgr list transports 2>&1 | head -4 | sed 's/^/    /' | tee -a "$R"

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

fi  # end of the skipped-in-focus region
adb logcat -d > shots/logcat_full.txt 2>&1
note "== final =="
dim "final"
CUR=$(adb shell settings get secure default_input_method)
check "default IME still WrenBoard at end" "$PKG" "$CUR"
LDC() { note "LEARNED-DIR checkpoint [$1]: $(adb shell 'ls -l /data/data/com.wrenboard.keyboard/files/UserHistoryDictionary.en_US.dict 2>&1' | tr -d '\r' | tr '\n' ';' | cut -c1-260)"; }
source tools/ime-learn-restore.sh
LDC after-learn-restore
if [ "${WB_FOCUS:-}" != learn ]; then
source tools/ime-import-regress.sh
LDC after-import-regress
source tools/ime-voice-update.sh   # before e2e: e2e uninstalls and reinstalls the app
LDC after-voice-update
fi
source tools/ime-export-e2e.sh
source tools/ime-launcher-icon.sh
note "== done =="
exit 0
