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
screen_hash() { adb exec-out screencap -p | md5sum | cut -d' ' -f1; }

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

cp "$GEOM" shots/geom_letters.json
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
note "== word-case cycling variant B: same, but cursor moved once first (tap at end of field) =="
clear_field
type_letters h e l l o
sleep 1.0
EB=$(edit_bounds)
if [ -n "$EB" ]; then nums=$(echo "$EB" | grep -o '[0-9]*' | tr '\n' ' '); set -- $nums; adb shell input tap $(( $3 - 30 )) $(( ($2+$4)/2 )); sleep 1.2; fi
shot 07f_caseB_before_tap
adb shell log -t IMETARGET "CASEMARK-B"
tapk shift; sleep 0.8; D1=$(edit_text); shot 07g_caseB_tap1
tapk shift; sleep 0.8; D2=$(edit_text); shot 07h_caseB_tap2
tapk shift; sleep 0.8; D3=$(edit_text); shot 07i_caseB_tap3
note "word-case cycle (variant B, cursor moved first) observed: hello -> '$D1' -> '$D2' -> '$D3'"
if [ "$D1" = "Hello" ] && [ "$D2" = "HELLO" ]; then note "PASS: word-case cycling variant B (Hello then HELLO; third tap -> '$D3')"; else note "FAIL: word-case cycling variant B (see 07f-07i)"; fi
adb logcat -d > shots/logcat_after_case.txt 2>&1
dim "after-case-cycle"

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
  note "INCONCLUSIVE: no evdev touch device delivered a tap; double-tap gesture NOT tested"
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
python3 tools/pagecheck.py shots/10_symbols_page2.png $C1 $C2 $C3 $C4 shots/ref_p2sw.png --save >/dev/null
FT1=$(edit_text)
note "symbols p2 reached via {&= key (field unchanged by switch: $([ "$FT0" = "$FT1" ] && echo yes || echo NO))"
pageis2() {
  adb exec-out screencap -p > shots/_pc.png
  local a c
  a=$(python3 tools/pagecheck.py shots/_pc.png $A1 $A2 $A3 $A4 shots/ref_abc.png | cut -d' ' -f1)
  c=$(python3 tools/pagecheck.py shots/_pc.png $C1 $C2 $C3 $C4 shots/ref_p2sw.png | cut -d' ' -f1)
  [ "$a" = SAME ] && [ "$c" = SAME ] && echo p2 || echo other
}
ensure_p2() {
  [ "$(pageis2)" = p2 ] && return 0
  note "  (page not symbols p2 - re-entering)"
  ensure_p1 && { adb shell input tap $SWITCH; sleep 1.0; }
  [ "$(pageis2)" = p2 ]
}
P2A=""; P2B=""; REV2=0
for k in 0 1 2 3 4 5 6 7 8; do
  X=$(( SCRW * (750 + 1082 * k) / 10000 ))
  ensure_p2 || { note "  cannot reach symbols p2 for top key $k"; break; }
  D=$(probe_key t$k $X $DY); P2A="$P2A$D"
  [ "$(pageis2)" = p2 ] || { REV2=$((REV2+1)); note "  page left p2 after top key $k (typed '$D')"; }
done
for k in 0 1 2 3 4 5 6 7 8; do
  X=$(( SCRW * (750 + 1082 * k) / 10000 ))
  ensure_p2 || { note "  cannot reach symbols p2 for second-row key $k"; break; }
  D=$(probe_key u$k $X $B1Y); P2B="$P2B$D"
  [ "$(pageis2)" = p2 ] || { REV2=$((REV2+1)); note "  page left p2 after second-row key $k (typed '$D')"; }
done
shot 10b_symbols_p2_after_probe
P2="$P2A$P2B"
note "symbols p2 top row typed: '$P2A' (expect currency row)"
note "symbols p2 second row typed: '$P2B' (expect tab then []{}<>^)"
note "symbols p2: page left p2 after $REV2 of 18 key taps"
check "symbols page2 types page-2 symbols (pound)" "£" "$P2"
check "symbols page2 types page-2 symbols (euro)" "€" "$P2"
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
  note "long-press path inconclusive ('$CB'); trying the clipboard suggestion item shown in the strip (as in 12_clipboard_popup.png)"
  STY=$(jq -r '.strip.cy // empty' "$GEOM")
  if [ -n "$STY" ]; then
    adb shell input tap 360 $STY; sleep 1.0
    CB1=$(edit_text); shot 12a_clipboard_strip_item
    if echo "$CB1" | grep -q "clipboard-probe-42"; then note "PASS: clipboard paste via strip clipboard item ('$CB1')"; CB="$CB1"; fi
  fi
  if ! echo "$CB" | grep -q "clipboard-probe-42"; then
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
VOICE=$(jq -r '.keys.strip_mic // empty | "\(.[0]) \(.[1])"' "$GEOM")
if [ -z "$VOICE" ]; then
  VOICE=$(jq -r '.strip.first // empty | "\(.[0]) \(.[1])"' "$GEOM")
fi
if [ -z "$VOICE" ]; then
  note "FAIL: no voice key in geometry"
else
  adb shell input tap $VOICE
  sleep 3
  shot 14_dictation_state
  adb shell pidof "$PKG" >/dev/null && note "OBSERVATION: dictation tap done, app alive. NOT a pass: the listening UI must be confirmed from 14_dictation_state.png (an earlier run tapped the wrong strip item). Audio injection N/A in CI." || note "FAIL: app died after dictation tap"
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

adb logcat -d > shots/logcat_full.txt 2>&1
note "== final =="
dim "final"
CUR=$(adb shell settings get secure default_input_method)
check "default IME still WrenBoard at end" "$PKG" "$CUR"
note "== done =="
exit 0
