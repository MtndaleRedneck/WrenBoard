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

note "expanding the action bar (desc: Open Action Bar)"
dumpui 03_toolbar_collapsed
tap_node 03_toolbar_collapsed content-desc "Open Action Bar" || note "action bar already expanded or chevron absent"
sleep 1
dumpui 04_toolbar_expanded
shot 04_toolbar_expanded

note "tapping the GIF action (desc: GIF)"
tap_node 04_toolbar_expanded content-desc "GIF" || fail "GIF action button not found"
sleep 2
dumpui 05_gif_panel
shot 05_gif_panel
sed 's/>/>\n/g' "$SHOTS/05_gif_panel.xml" | grep -q 'text="Search KLIPY GIFs"' || fail "GIF search field placeholder not visible (panel did not open on GIF tab)"

note "focusing GIF search and typing query"
tap_node 05_gif_panel text "Search KLIPY GIFs" || fail "search field node not tappable"
sleep 1
adb shell input text cat; sleep 2

note "waiting for GIF results grid (clickable content-desc nodes not in known-label set)"
CELL=""
for i in $(seq 1 15); do
  dumpui "06_grid_poll_$i"
  CELL=$(sed 's/>/>\n/g' "$SHOTS/06_grid_poll_$i.xml" \
    | grep 'clickable="true"' | grep 'content-desc="[^"]' \
    | grep -v 'content-desc="Open Action Bar"' | grep -v 'content-desc="Close Action Bar"' \
    | grep -v 'content-desc="GIF"' | grep -v 'content-desc="Emoji"' | grep -v 'content-desc="Backspace"' \
    | head -1)
  [ -n "$CELL" ] && break
  sleep 2
done
[ -n "$CELL" ] || fail "no GIF result cells after 30s"
CT=$(echo "$CELL" | sed -n 's/.*content-desc="\([^"]*\)".*/\1/p')
note "first result cell: [$CT]"
shot 06_results
C=$(echo "$CELL" | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | grep -o '[0-9]*' | tr '\n' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}')
note "tapping first result at $C"
adb shell input tap $C; sleep 3
shot 07_after_insert

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
