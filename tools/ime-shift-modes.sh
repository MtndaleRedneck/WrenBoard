# Sourced from ime-verify.sh (focus=shift). Tests the shift "Caps lock" setting through the REAL Settings screen:
# default mode, both choices selected by UI taps, restart persistence, behavior in both modes.
# Not covered (no multi-touch injection here): shift+letter chord. Reported as a gap, not a pass.
CYCLE_LABEL="Tap shift: lowercase, uppercase, caps lock"
FUTO_LABEL="Double-tap or hold shift (FUTO style)"
SCR=$(adb shell wm size | tr -d '\r' | grep -o '[0-9]*x[0-9]*' | tail -1); SW=${SCR%x*}; SHH=${SCR#*x}
SH=$(jq -r '.keys.shift | "\(.[0]) \(.[1])"' "$GEOM")

open_settings_page() { # opens Long-Press Keys & Spacebar and scrolls until 'Caps lock' is on screen; sets CL (cx cy y1) or empty
  CL=""
  local i t
  for t in 1 2 3; do
    adb shell input keyevent KEYCODE_HOME; sleep 1
    adb shell am start -n "$SETTINGS_ACT" --es navDest longPress >/dev/null 2>&1; sleep 5
    xdump "shift_page_$1_try$t"
    # the deep link can land on another page on a cold start: retry the launch until the right page shows
    if python3 tools/xml_node.py "shots/shift_page_$1_try$t.xml" 'Long-Press Keys' >/dev/null 2>&1; then break; fi
  done
  for i in 0 1 2 3 4 5; do
    xdump "shift_page_$1_$i"
    if CL=$(python3 tools/xml_node.py "shots/shift_page_$1_$i.xml" 'Caps lock'); then
      shot "shift_page_$1"; return 0
    fi
    adb shell input swipe $((SW/2)) $((SHH*7/10)) $((SW/2)) $((SHH*3/10)) 400; sleep 1
  done
  CL=""; shot "shift_page_$1_NOTFOUND"; note "INCONCLUSIVE(harness): 'Caps lock' row not found on the Long-Press Keys & Spacebar page (shift_page_$1_*.png/xml)"; return 1
}
row_value() { # texts near the Caps lock row; prints 'cycle' / 'futo' / 'unknown' from the displayed selected value
  local f="shots/$1.xml"
  if python3 tools/xml_node.py "$f" "$CYCLE_LABEL" >/dev/null 2>&1; then echo cycle
  elif python3 tools/xml_node.py "$f" "$FUTO_LABEL" >/dev/null 2>&1; then echo futo
  else echo unknown; fi
}
set_mode() { # set_mode <cycle|futo> <tag>: open page, open dropdown, tap option, verify row shows it
  local want label cur
  if [ "$1" = cycle ]; then label="$CYCLE_LABEL"; else label="$FUTO_LABEL"; fi
  open_settings_page "$2_before" || return 1
  cur=$(row_value "shift_page_$2_before_$(ls shots | sed -n "s/^shift_page_$2_before_\([0-9]*\)\.xml$/\1/p" | tail -1)")
  note "settings row before change ($2): displayed value = $cur"
  # tap the dropdown BOX (its selected-value text), not the 'Caps lock' heading above it
  local pidx pcur vlabel; pidx=$(ls shots | sed -n "s/^shift_page_$2_before_\([0-9]*\)\.xml$/\1/p" | tail -1)
  if [ "$cur" = futo ]; then vlabel="$FUTO_LABEL"; else vlabel="$CYCLE_LABEL"; fi
  if ! VN=$(python3 tools/xml_node.py "shots/shift_page_$2_before_${pidx}.xml" "$vlabel"); then note "INCONCLUSIVE(harness): selected-value node not found ($2)"; return 1; fi
  read -r CX CY _ <<< "$VN"
  adb shell input tap $CX $CY; sleep 2
  local k
  for k in 1 2 3 4; do
    xdump "shift_dropdown_$2"
    if python3 tools/xml_node.py "shots/shift_dropdown_$2.xml" "$CYCLE_LABEL" >/dev/null 2>&1 && python3 tools/xml_node.py "shots/shift_dropdown_$2.xml" "$FUTO_LABEL" >/dev/null 2>&1; then break; fi
    sleep 1.5
  done
  shot "shift_dropdown_$2"
  # popup open = both labels on screen; the popup item is the LAST node carrying the label (the row itself comes first)
  if ON=$(python3 tools/xml_node.py "shots/shift_dropdown_$2.xml" "$label" last) && python3 tools/xml_node.py "shots/shift_dropdown_$2.xml" "$CYCLE_LABEL" >/dev/null 2>&1 && python3 tools/xml_node.py "shots/shift_dropdown_$2.xml" "$FUTO_LABEL" >/dev/null 2>&1; then
    read -r OX OY _ <<< "$ON"
    adb shell input tap $OX $OY; sleep 2.5
  else
    note "INCONCLUSIVE(harness): dropdown popup with both options not seen for '$label' (shift_dropdown_$2.png/xml)"; return 1
  fi
  shot "shift_after_select_$2"; xdump "shift_after_select_$2"
  local now
  # after the popup closes the row shows the selected label once; if the popup is still open the check is invalid
  if python3 tools/xml_node.py "shots/shift_after_select_$2.xml" "$CYCLE_LABEL" >/dev/null 2>&1 && python3 tools/xml_node.py "shots/shift_after_select_$2.xml" "$FUTO_LABEL" >/dev/null 2>&1; then note "INCONCLUSIVE(harness): popup still open after the tap ($2)"; return 1; fi
  now=$(row_value "shift_after_select_$2")
  if [ "$now" = "$1" ]; then note "PASS(UI): Caps lock row shows '$label' after selecting it ($2)"; else note "FAIL(UI): Caps lock row shows '$now' after selecting '$1' ($2)"; fi
}
reopen_check() { # reopen_check <expect> <tag>: reopen settings page and read the persisted value
  open_settings_page "$2" || return 1
  local idx now; idx=$(ls shots | sed -n "s/^shift_page_$2_\([0-9]*\)\.xml$/\1/p" | tail -1)
  now=$(row_value "shift_page_${2}_${idx}")
  if [ "$now" = "$1" ]; then note "PASS(persist): after app restart the Caps lock row shows the $1 choice ($2)"; else note "FAIL(persist): after app restart the row shows '$now', wanted '$1' ($2)"; fi
}
restart_app() { # force-stop = app restart; the platform flips the default IME away when the IME package is stopped (seen for upstream too), so record it and re-select ours
  adb shell am force-stop "$PKG"; sleep 2
  note "after force-stop default IME = $(adb shell settings get secure default_input_method | tr -d '\r')"
  adb shell ime enable "$IME" >/dev/null 2>&1; adb shell ime set "$IME" >/dev/null 2>&1; sleep 1
  note "after re-select default IME = $(adb shell settings get secure default_input_method | tr -d '\r')"
}
back_to_target() {
  adb shell input keyevent KEYCODE_BACK; sleep 0.8; adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -n "$TPKG/.TestInput" >/dev/null 2>&1; sleep 2
  local B; B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
  precond "$1" || return 1
  clear_field; sleep 0.5
}
# --- evdev touch device for a measured quick double-tap (same method as the caps double-tap leg) ---
TDEV=""
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device; note "adb root attempted for the sendevent probe: uid=$(adb shell id -u | tr -d '\r')"
for dev in $(adb shell getevent -lp 2>/dev/null | tr -d '\r' | awk '/^add device/{d=$4} /ABS_MT_POSITION_X/{print d}' | sort -u); do
  RX=$(adb shell getevent -lp "$dev" 2>/dev/null | tr -d '\r' | sed -n 's/.*ABS_MT_POSITION_X .*max \([0-9]*\).*/\1/p' | head -1)
  RY=$(adb shell getevent -lp "$dev" 2>/dev/null | tr -d '\r' | sed -n 's/.*ABS_MT_POSITION_Y .*max \([0-9]*\).*/\1/p' | head -1)
  [ -z "$RX" ] && continue
  HXY=$(jq -r '.keys.h | "\(.[0]) \(.[1])"' "$GEOM"); hx=${HXY% *}; hy=${HXY#* }
  X=$(( hx * RX / SW )); Y=$(( hy * RY / SHH ))
  clear_field
  adb shell "sendevent $dev 3 57 7; sendevent $dev 3 53 $X; sendevent $dev 3 54 $Y; sendevent $dev 1 330 1; sendevent $dev 0 0 0; sendevent $dev 3 57 4294967295; sendevent $dev 1 330 0; sendevent $dev 0 0 0" >/dev/null 2>&1
  sleep 0.6; P=$(edit_text)
  note "touch-device probe $dev: field after sendevent tap on h = '$P'"
  if echo "$P" | grep -qi "h"; then TDEV=$dev; TRX=$RX; TRY=$RY; break; fi
done
clear_field
quick_double_tap() { # prints measured down-to-down gap in ms, or fails
  local SX SY GAP
  SX=$(( ${SH% *} * TRX / SW )); SY=$(( ${SH#* } * TRY / SHH ))
  tapev() { echo "sendevent $TDEV 3 57 9; sendevent $TDEV 3 53 $SX; sendevent $TDEV 3 54 $SY; sendevent $TDEV 1 330 1; sendevent $TDEV 0 0 0; sendevent $TDEV 3 57 4294967295; sendevent $TDEV 1 330 0; sendevent $TDEV 0 0 0"; }
  adb shell "printf '%s\n' '$(tapev)' 'sleep 0.12' '$(tapev)' > /sdcard/dt.sh"
  adb shell "nohup getevent -lt $TDEV > /sdcard/ge.txt 2>&1 &"; sleep 0.5
  adb shell "sh /sdcard/dt.sh"; sleep 0.5; adb shell "pkill getevent" >/dev/null 2>&1
  adb shell cat /sdcard/ge.txt | tr -d '\r' | grep "BTN_TOUCH *DOWN" | sed -n 's/^\[ *\([0-9.]*\)\].*/\1/p' | awk 'NR==1{a=$1}NR==2{printf "%d", ($1-a)*1000}'
}
slow_two_taps() { tapk shift; sleep 1.0; tapk shift; }

# ================= CYCLE mode (default) =================
note "== SHIFT SETTING: default state, real Settings screen =="
open_settings_page default && { idx=$(ls shots | sed -n 's/^shift_page_default_\([0-9]*\)\.xml$/\1/p' | tail -1); D=$(row_value "shift_page_default_$idx"); note "default (fresh install, never touched) Caps lock row shows: $D"; [ "$D" = cycle ] && note "PASS(default): default choice is the tap cycle" || note "FAIL(default): default choice is '$D', wanted cycle"; }
back_to_target cyc1 || true

midword_obs() { # observation only: shift tapped while a word is just typed; frames before, right after the tap, after a second tap, after the next letter
  type_letters h i; sleep 0.6; shot "shift_${1}_midword_before"; note "OBSERVE midword before shift [$1]: field='$(edit_text)'"
  tapk shift; sleep 0.8; shot "shift_${1}_midword_aftershift"; note "OBSERVE midword right after one shift tap [$1]: field='$(edit_text)'"
  tapk shift; sleep 0.8; shot "shift_${1}_midword_after2"; note "OBSERVE midword after a second shift tap [$1]: field='$(edit_text)'"
  type_letters x; sleep 0.5; shot "shift_${1}_midword"
  note "OBSERVE midword [$1]: typed 'hi', shift, shift, 'x' -> '$(edit_text)'"
  clear_field
}
cycle_tests() { # cycle_tests <tag>
  note "== CYCLE mode behavior ($1) =="
  clear_field; tapk shift; shot "shift_${1}_c1_oneshot"; type_letters w o; sleep 0.5
  assert_field "cycle: one tap = one-shot, reverts after one letter [$1]" "Wo"
  clear_field; slow_two_taps; shot "shift_${1}_c2_locked"
  type_letters w o r l d; tapk space; type_letters h i; sleep 0.5; shot "shift_${1}_c2_typed"
  assert_field "cycle: slow second press (1 s gap) locks, lock persists across a space [$1]" "WORLD HI"
  tapk shift; type_letters x; sleep 0.5; shot "shift_${1}_c3_unlocked"
  assert_field "cycle: third press returns to lowercase [$1]" "WORLD HIx"
  clear_field
  # observation leg for isolation (not an assertion): shift tapped while a word is still being composed
  midword_obs "$1"
  clear_field
  if [ -n "$TDEV" ]; then
    G=$(quick_double_tap); note "cycle: quick two-tap measured down-to-down gap ${G:-unmeasured} ms"; sleep 0.5
    type_letters a b; sleep 0.5; assert_field "cycle: quick two-tap also locks (no timer needed) [$1]" "AB"
    tapk shift; clear_field
  else note "INCONCLUSIVE(harness): no evdev touch device for the measured quick two-tap [$1]"; fi
  clear_field
  ( adb shell "input swipe $SH $SH 1600" ) & HP=$!
  sleep 1.45; shot "shift_${1}_c4_hold_during"; wait $HP; sleep 0.6
  type_letters w o r l d; sleep 0.5; shot "shift_${1}_c4_hold_typed"
  assert_field "cycle: a 1600 ms hold does NOT lock caps (acts as one tap: 'World') [$1]" "World"
  clear_field
}
cycle_tests default

# ================= FUTO mode via the real dropdown =================
set_mode futo futo1; back_to_target futo1 || true
futo_tests() {
  note "== FUTO-style mode behavior ($1) =="
  clear_field; tapk shift; type_letters w o; sleep 0.5
  assert_field "futo: one tap = one-shot ('Wo') [$1]" "Wo"
  clear_field; slow_two_taps; type_letters a b c; sleep 0.5; shot "shift_${1}_f2_typed"
  assert_field "futo: slow second press does NOT lock (no tap cycle): 'abc' [$1]" "abc"
  clear_field
  if [ -n "$TDEV" ]; then
    G=$(quick_double_tap); note "futo: quick double-tap measured gap ${G:-unmeasured} ms"; sleep 0.5
    type_letters a b; sleep 0.5; shot "shift_${1}_f3_typed"
    assert_field "futo: quick double-tap locks [$1]" "AB"
    tapk shift; type_letters c; sleep 0.5
    assert_field "futo: tap while locked unlocks [$1]" "ABc"
  else note "INCONCLUSIVE(harness): no evdev touch device for the futo quick double-tap [$1]"; fi
  clear_field
  ( adb shell "input swipe $SH $SH 1600" ) & HP=$!
  sleep 1.45; shot "shift_${1}_f4_hold_during"; wait $HP; sleep 0.6
  type_letters w o r l d; sleep 0.5; shot "shift_${1}_f4_hold_typed"
  assert_field "futo: a 1600 ms hold locks caps ('WORLD') [$1]" "WORLD"
  tapk shift; clear_field
  midword_obs "$1"
}
futo_tests futo1

# ================= back to cycle through the UI, restart, persistence =================
set_mode cycle cyc2
restart_app
reopen_check cycle cyc2_restart
back_to_target cyc2 || true
cycle_tests after-restart

# ================= futo again, restart, persistence =================
set_mode futo futo2
restart_app
reopen_check futo futo2_restart
back_to_target futo2 || true
futo_tests after-restart

# ================= engaged state while the setting changes =================
note "== mode change with shift engaged (observation + no-stuck check) =="
set_mode cycle eng1; back_to_target eng1 || true
clear_field; tapk shift; shot "shift_eng_oneshot_before_switch"
set_mode futo eng2   # leaves the editor, so the engaged one-shot may reset when the keyboard hides
back_to_target eng2 || true
type_letters a b c; sleep 0.5; shot "shift_eng_after_switch_typed"
E=$(edit_text); note "after switching cycle->futo with one-shot engaged, typed abc -> '$E'"
case "$E" in ABC) note "FAIL: shift stuck as caps lock after the mode change ('ABC')";; Abc|abc) note "PASS: no stuck caps lock after the mode change ('$E')";; *) note "INCONCLUSIVE: unexpected text after mode change ('$E')";; esac
note "NOT TESTED: shift+letter chord (no multi-touch injection in this harness) and the auto-capitalised shift state; both unchanged by the diff except through the shared press path."

