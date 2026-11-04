#!/usr/bin/env bash
# signed-gif-proof.sh <signed-wrenboard.apk> <imetarget.apk>
# Test-only runtime proof for the ALREADY-ACCEPTED SIGNED candidate binary.
# Installs the given signed APK and the local ime-target fixture on the local
# emulator and exercises the visible GIF search -> insert flow with NO debug
# hooks: WBDBG and wbPos markers are stripped from this binary, so navigation is
# uiautomator-dump only (harness dump/focus/field primitives). Assertions are
# fixture-side only: IMETARGET CONTENT= mime/bytes/signature on insert and host
# text unchanged. The fixture is local (no real contact, no external message).
set -uo pipefail
APK="$1"; FIXTURE="$2"
PKG=com.wrenboard.keyboard; TPKG=com.wrenboard.imetarget
SHOTS=tools/ime-target/proof-shots; mkdir -p "$SHOTS"
note() { echo "==[proof] $*"; }
shot() { adb exec-out screencap -p > "$SHOTS/$1.png" 2>/dev/null || true; }
dumpui() { adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb exec-out cat /sdcard/u.xml > "$SHOTS/$1.xml" 2>/dev/null; }
center_of() { # center_of <xmlname> <attr> <value> -> "x y" of first node with attr="value"
  sed 's/>/>\n/g' "$SHOTS/$1.xml" | grep "$2=\"$3\"" | head -1 \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ' \
    | awk '{print int(($1+$3)/2), int(($2+$4)/2)}'
}
tap_node() { # tap_node <xmlname> <attr> <value> ; returns 1 if no such node
  local c; c=$(center_of "$1" "$2" "$3"); [ -n "$c" ] || return 1
  note "tap $2=$3 at $c"; adb shell input tap $c
}
field_now() { adb shell am broadcast -a com.wrenboard.imetarget.REPORT -p "$TPKG" >/dev/null 2>&1; sleep 0.5; adb logcat -d -s IMETARGET:I 2>/dev/null | tr -d '\r' | grep 'FIELD=' | tail -1 | sed -n 's/.*IMETARGET: FIELD=//p'; }
fail() { note "RESULT: 1 ($1)"; dumpui final_fail; shot final_fail; exit 1; }

note "installing signed candidate + local fixture"
adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
# Fixed test geometry: same override the composition harness used (1080x2316@420),
# so the measured control coordinates below are deterministic.
adb shell wm size 1080x2316; adb shell wm density 420; sleep 2
adb install -r "$APK" >/dev/null || fail "signed APK install failed"
adb install -r "$FIXTURE" >/dev/null || fail "fixture install failed"

note "enabling WrenBoard as the active IME"
IME=""
for i in $(seq 1 30); do IME=$(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1); [ -n "$IME" ] && break; sleep 2; done
[ -n "$IME" ] || fail "WrenBoard IME id not found"
for i in 1 2 3 4 5; do adb shell ime enable "$IME" >/dev/null 2>&1; sleep 1; adb shell ime list -s | tr -d '\r' | grep -qx "$IME" && break; done
for i in 1 2 3 4 5; do adb shell ime set "$IME" >/dev/null 2>&1; sleep 1; adb shell settings get secure default_input_method | tr -d '\r' | grep -qx "$IME" && break; done
adb shell settings get secure default_input_method | tr -d '\r' | grep -qx "$IME" || fail "IME not set"
note "ime id: $IME"
adb logcat -c

note "focusing fixture and typing host text (baseline)"
adb shell am start -S -n "$TPKG/.TestInput" >/dev/null 2>&1; sleep 3
dumpui 01_target
B=$(sed 's/>/>\n/g' "$SHOTS/01_target.xml" | grep 'class="android.widget.EditText"' | head -1 | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}')
[ -n "$B" ] || fail "fixture EditText not found"
adb shell input tap $B; sleep 3
shot 02_keyboard_up
adb shell input text hostok; sleep 1
F=$(field_now); note "field after host typing: [$F]"
echo "$F" | grep -q 'hostok' || fail "host text baseline not in fixture field"

note "pixel navigation: IME window is absent from uiautomator dumps on this setup (verified"
note "in run 41839936532: dumps contain only com.wrenboard.imetarget nodes while the keyboard"
note "visibly renders), so controls are tapped by measured coordinates. Coordinates are from"
note "pair-4 run 41793297781 WBDBG markers at the identical 1080x2316@420 config (same product"
note "layout code in release; cleanup touched no panel/keyboard layout), emoji-key position"
note "verified against pair-4 k01 screenshot pixels. Every transition is screenshot-captured"
note "for review; PASS/FAIL comes ONLY from the fixture-side assertions below."
note "tapping emoji key (160,1967) to open the panel"
adb shell input tap 160 1967; sleep 2
shot 05_panel_open
note "tapping GIF tab (390,919)"
adb shell input tap 390 919; sleep 2
shot 06_gif_tab
note "tapping GIF search field (521,1038) to raise the letters keyboard"
adb shell input tap 521 1038; sleep 1.5
shot 07_search_keyboard
note "typing query via on-screen keys c(430,1808) a(106,1650) t(484,1492)"
adb shell input tap 430 1808; sleep 0.3
adb shell input tap 106 1650; sleep 0.3
adb shell input tap 484 1492; sleep 1.0
F1=$(field_now); note "host field after GIF-search typing: [$F1]"
[ "$F1" = "hostok" ] || fail "host text changed while typing in GIF search (hostok -> $F1)"
shot 08_query_typed
note "submitting the search via the round arrow (1012,1105; measured on the 07/08 captures of"
note "run 41842190012 at this exact config - the arrow submits the query and dismisses the"
note "search keyboard; typing alone does not populate the grid in this build)"
adb shell input tap 1012 1105; sleep 1
shot 08b_submitted
# Render pacing below uses screen-change polling ONLY to decide when to capture.
# It PROVES NOTHING: the search-field cursor blink can produce one or more changes
# in any panel state. The grid verdict comes from the actual pixels of 08c/09
# (reviewed after the run) and from the fixture-side assertions, which fail closed
# if no real cell is tapped.
note "post-submit pacing: up to 20s for the search keyboard to dismiss"
PREV=$(adb exec-out screencap -p | md5sum | cut -d' ' -f1)
for i in $(seq 1 10); do
  sleep 2
  NOW=$(adb exec-out screencap -p | md5sum | cut -d' ' -f1)
  [ "$NOW" != "$PREV" ] && break
done
shot 08c_after_dismiss
note "post-dismiss pacing: up to 30s for a render change, then settle"
PREV=$(adb exec-out screencap -p | md5sum | cut -d' ' -f1)
RENDERED=0
for i in $(seq 1 15); do
  sleep 2
  NOW=$(adb exec-out screencap -p | md5sum | cut -d' ' -f1)
  if [ "$NOW" != "$PREV" ]; then RENDERED=1; break; fi
done
[ "$RENDERED" = 1 ] || note "no render change detected within 30s; proceeding to capture anyway (assertions decide)"
sleep 2
shot 09_results
note "tapping first result cell (274,1251; 156px into the grid, inside cell 0 for any cell height)"
adb shell input tap 274 1251; sleep 3
shot 10_after_insert

note "asserting fixture-side commit (mime/bytes/signature) and host text unchanged"
L=$(adb logcat -d -s IMETARGET:I 2>/dev/null | tr -d '\r' | grep 'CONTENT=' | tail -1)
note "content line: $L"
[ -n "$L" ] || fail "no CONTENT= line in IMETARGET log"
echo "$L" | grep -q 'mime=image/gif' || fail "inserted mime is not image/gif"
B2=$(echo "$L" | sed -n 's/.*bytes=\([0-9]*\).*/\1/p'); [ -n "$B2" ] && [ "$B2" -gt 0 ] || fail "inserted bytes not > 0"
echo "$L" | grep -q 'sig=GIF' || fail "inserted bytes lack GIF signature"
F2=$(field_now); note "field after insert: [$F2]"
[ "$F2" = "hostok" ] || fail "host text changed by insert (expected hostok)"
dumpui 08_final
note "RESULT: 0 mime=image/gif bytes=$B2 sig=GIF host=unchanged"
exit 0
