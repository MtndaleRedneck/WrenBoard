#!/usr/bin/env bash
# WrenBoard 1.2 checks on a booted emulator, driven by the DEBUG build's WBDBG logcat geometry
# (java/src/org/futo/inputmethod/latin/uix/WbDebug.kt): keys are tapped where the keyboard says they are,
# not where a pixel model guesses, so the checks survive the new skin.
# Usage: wb12-check.sh <debug apk> <ime-target apk>     (WB_LEGS=comma list, default all)
set -u
APK="$1"; TAPK="$2"
PKG=com.wrenboard.keyboard; TPKG=com.wrenboard.imetarget
LEGS=",${WB_LEGS:-setup,keyboard,period,leftedge,caps,shift,clip,update,toolbar,textedit,skin,symbols,states,emoji,clipboard,saveword,settings,panelexit},"
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
# Same field tap without restarting the activity: keeps a broadcast-set inputType (e.g.
# password) while renegotiating the input connection.
tap_field() {
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
  BTRY=0
  while echo "$T" | grep -q "WrenBoard is turned off\|WrenBoard is not your keyboard" && [ $BTRY -lt 3 ]; do
    BTRY=$((BTRY+1))
    note "banner still up after relaunch (try $BTRY); default IME is $(adb shell settings get secure default_input_method | tr -d '\r'); re-ensuring and waiting"
    sleep 4; adb shell am force-stop "$PKG"; ensure_ime || note "  could not restore WrenBoard after retry force-stop"; adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 6
    shot s05b_settings_active_retry; dumpui s05; T=$(uitext s05)
  done
  echo "$T" | grep -q "WrenBoard is turned off\|WrenBoard is not your keyboard" && fail "banner still shown when active (default IME: $(adb shell settings get secure default_input_method | tr -d '\r'))" || pass "no banner when active"
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

if [ "${WB_LOAD:-0}" != 0 ] && [ -n "${WB_LOAD:-}" ]; then
  # controlled CPU load from here on (self-terminating; WB_LOAD = spinner count). Run-wide 6
  # spinners starved setup itself (E5: default IME fell back to stock, 19 fails) - 2-4 spinners
  # approximate a busy CI runner while keeping the app functional. Experiment runs only; the
  # gate never uses load. Starts after the display setup so the emulator can boot/install first.
  adb shell "for i in \$(seq 1 ${WB_LOAD}); do timeout 1500 sh -c 'while :; do :; done' >/dev/null 2>&1 & done"
  note "LOAD: ${WB_LOAD} CPU spinners on the emulator for 1500 s"
fi

if leg keyboard; then
  note "== KEYBOARD: starter words, 123 hint =="
  adb logcat -c; focus_target; sleep 2; S=$(sugg); note "suggestions in empty field: [$S]"
  grep 'STARTER' shots/_s.txt | tail -5 | sed 's/^.*WBDBG *: /  /' | tee -a "$R"
  # Content-free assertion: suggestion logging is counts-only (no typed/learned text in
  # diagnostics). The fallback set is exactly 3 words; word identity is covered by unit tests.
  [ "$S" = "n=3" ] && pass "fresh empty field, toggle on: 3 fallback starter words ($S)" || fail "fresh empty field shows '$S' (wanted n=3 fallback)"
  shot k01a_starter_words_fallback

  # --- Acceptance: fresh-token privacy negatives with a live positive control. Each gated
  # commit (personalization off, autocorrect off, password field) must queue ZERO history
  # writes; learnSkip/learnEnter counts are noted as executed-gate identity evidence. The
  # positive control after the windows proves the marker path and write queue are live and
  # drained in order; the starter strip staying at the 3-word fallback is the production
  # retrieval negative (a leaked write would surface there, exactly like zebra does). ---
  wqcount() { adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c "STARTER writeQueued"; }
  wait_wq() { local want=$1 i; for i in $(seq 1 25); do [ "$(wqcount)" -ge "$want" ] && return 0; sleep 1; done; return 1; }
  entercount() { adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c "STARTER learnEnter"; }
  skipcount() { adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c "STARTER learnSkip id=[0-9]* reason=$1"; }
  privnote() { adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -oE 'STARTER (learnEnter|learnSkip|writeQueued|drop)( id=[0-9]+)?( reason=[a-zA-Z]+)?' | sed 's/ id=[0-9]*//' | sort | uniq -c | sed 's/^ *//' | tr '\n' ' '; }
  # Dropped submissions (drop reason=noHistoryDict hid for 3 revisions because nothing counted
  # them): control/learning legs must drop ZERO; negatives must skip before ever reaching a drop.
  dropcount() { adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c "STARTER drop id="; }
  # Bounded wait for the fixture to apply a persisted mode (it recreates itself so the IME
  # renegotiates with fresh attributes; live setImeOptions never renegotiated - rev29 neg4).
  wait_mode() { local want=$1 i; for i in $(seq 1 12); do [ "$(adb logcat -d -s IMETARGET:I 2>/dev/null | tr -d '\r' | grep -oE 'MODE=[A-Z]+ APPLIED' | tail -1)" = "MODE=$want APPLIED" ] && return 0; sleep 1; done; return 1; }
  # Bounded wait for the app's OWN keyboard dump to report the expected layout, then promote
  # that dump to the shared tap geometry. Anchors post-addlang legs to app-visible layout state:
  # kills both pre-reload suggestion reads (rev29 fr absent-before '') and stale-geometry taps
  # (rev29 shift leg typed 'a' at azerty coordinates after the board had reloaded to qwerty).
  wait_layout() { local want=$1 i; for i in $(seq 1 10); do geom wait_layout keep >/dev/null 2>&1; [ "$(python3 tools/wb12_kv.py shots/geom_wait_layout.txt kbd layout 2>/dev/null)" = "$want" ] && { cp shots/geom_wait_layout.txt shots/geom.txt; return 0; }; sleep 1; done; return 1; }
  # Bounded marker-settle: poll until any terminal learn marker appears (max 8s) so a delayed
  # submission can't be erased by the next window's log clear. writeQueued is a SUBMISSION
  # marker; stored completion is proven by the post-restoration production readback below.
  settle() { local i; for i in $(seq 1 8); do privnote | grep -q 'learnSkip\|writeQueued' && return 0; sleep 1; done; return 1; }
  # verify the field is actually empty before an empty-field strip read; note the observed value
  verify_empty() { local f; f=$(field | sed 's/[[:space:]]*$//'); note "field before empty-strip read: '$f'"; [ -z "$f" ]; }
  prefnote() { dbg "prefget:pref_key_use_personalized_dicts" 0.5; dbg "prefget:pref_key_auto_correction" 0.5; adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -oE 'PREF [^ ]+=[^ ]+' | tr '\n' ' '; }

  # negative 1: personalization off (autocorrect ON -> expected skip reason personalizationOff)
  dbg "prefbool:pref_key_use_personalized_dicts=false" 1.0; adb shell input keyevent 4; sleep 0.6; focus_target
  adb logcat -c
  for i in 1 2 3; do clear_field; type_word wqzxkv; tapcode 32; sleep 0.8; done
  clear_field
  settle || note "neg1: marker-settle hit its 8s bound (markers may be incomplete)"
  WQ=$(wqcount); PN=$(privnote); SK=$(skipcount personalizationOff); EN=$(entercount)
  note "neg1 personalization-off (3 commits): queued=$WQ enter=$EN skip[personalizationOff]=$SK markers: $PN"
  [ "$EN" -ge 3 ] && [ "$SK" -ge 3 ] && [ "$WQ" -eq 0 ] && pass "privacy neg1: all 3 commits entered and were skipped reason=personalizationOff, zero writes queued in-window" || fail "privacy neg1: enter=$EN skip=$SK queued=$WQ (wanted >=3,>=3,0) [$PN]"
  # restore normal gates BEFORE retrieval: reading while gated off would hide a leak
  dbg "prefbool:pref_key_use_personalized_dicts=true" 1.0; adb shell input keyevent 4; sleep 0.6; focus_target; sleep 2
  verify_empty || fail "neg1: field not empty at retrieval read"
  SN=$(sugg)
  note "neg1 preconditions at restore: $(prefnote)"
  [ "$SN" = "n=3" ] && pass "neg1 post-restoration readback: strip is 3-word fallback, token not retrievable ($SN)" || fail "neg1 post-restoration readback: strip '$SN' (wanted n=3)"
  shot k01g_neg1_strip_fallback_pixels

  # negative 2: autocorrect-DISABLED FIELD (email variation -> mInputTypeNoAutoCorrect=true,
  # suggestions still shown). The learn gate's autocorrectOff branch reads
  # mAutoCorrectionEnabledPerTextFieldSettings, which is field-driven (InputAttributes:
  # email/URI/filter variations, suppressed-suggestion fields); the user
  # pref_key_auto_correction toggle never reaches this gate (rev29 executed: enter=3 skip=0
  # after the prefbool write). The fixture persists the mode and restarts so the IME
  # renegotiates; personalization stays ON.
  focus_target
  adb shell am broadcast -a com.wrenboard.imetarget.NOCORRECT --ez on true -p "$TPKG" >/dev/null 2>&1
  wait_mode NOCORRECT || fail "neg2: fixture did not apply NOCORRECT mode"
  focus_target
  adb logcat -c
  for i in 1 2 3; do clear_field; type_word jkqzxv; tapcode 32; sleep 0.8; done
  clear_field
  # Count-anchored wait: any-marker settle returned on commits 1-2 and raced commit 3's markers
  # (deterministic enter=2/3 on rev30-32). Wait for the third commit's learnEnter, 10s bound.
  for i in $(seq 1 10); do [ "$(entercount)" -ge 3 ] && break; sleep 1; done
  [ "$(entercount)" -ge 3 ] || note "neg2: 10s count-anchored wait expired (markers may be incomplete)"
  # Raw marker evidence (id + uptime only, content-free) preserved for attribution review.
  adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep 'STARTER learn' > shots/neg2_markers.txt
  WQ=$(wqcount); PN=$(privnote); SK=$(skipcount autocorrectOff); EN=$(entercount)
  note "neg2 autocorrect-off (3 commits): queued=$WQ enter=$EN skip[autocorrectOff]=$SK markers: $PN"
  [ "$EN" -ge 3 ] && [ "$SK" -ge 3 ] && [ "$WQ" -eq 0 ] && pass "privacy neg2: all 3 commits entered and were skipped reason=autocorrectOff, zero writes queued in-window" || fail "privacy neg2: enter=$EN skip=$SK queued=$WQ (wanted >=3,>=3,0) [$PN]"
  adb shell am broadcast -a com.wrenboard.imetarget.NOCORRECT --ez on false -p "$TPKG" >/dev/null 2>&1
  wait_mode NORMAL || fail "neg2: fixture did not restore NORMAL mode"
  focus_target; sleep 2
  verify_empty || fail "neg2: field not empty at retrieval read"
  SN=$(sugg)
  [ "$SN" = "n=3" ] && pass "neg2 post-restoration readback: strip is 3-word fallback, token not retrievable ($SN)" || fail "neg2 post-restoration readback: strip '$SN' (wanted n=3)"
  shot k01h_neg2_strip_fallback_pixels

  # negative 3: password field, personalization+autocorrect ON -> expected skip reason noLearning
  focus_target
  adb shell am broadcast -a com.wrenboard.imetarget.PASSWORD --ez on true -p "$TPKG" >/dev/null 2>&1; sleep 0.8
  adb shell input keyevent 4; sleep 0.8; tap_field
  adb logcat -c; PREF3=$(prefnote)
  for i in 1 2 3; do clear_field; type_word vkzjqw; tapcode 32; sleep 0.8; done
  clear_field
  settle || note "neg3: marker-settle hit its 8s bound (markers may be incomplete)"
  WQ=$(wqcount); PN=$(privnote); SK=$(skipcount noLearning); EN=$(entercount)
  note "neg3 password-field (3 commits, $PREF3): queued=$WQ enter=$EN skip[noLearning]=$SK markers: $PN (password fields may stop upstream of the marker path or at the earlier autocorrectOff gate; reason not asserted here - explicit-flag branch isolation is neg4)"
  [ "$WQ" -eq 0 ] && pass "privacy neg3: zero writes queued across 3 password-field commits (markers noted, not required)" || fail "privacy neg3: queued=$WQ (wanted 0) [$PN]"
  adb shell am broadcast -a com.wrenboard.imetarget.PASSWORD --ez on false -p "$TPKG" >/dev/null 2>&1; sleep 0.8
  adb shell input keyevent 4; sleep 0.8; focus_target; sleep 2
  verify_empty || fail "neg3: field not empty at retrieval read"
  SN=$(sugg)
  [ "$SN" = "n=3" ] && pass "neg3 post-restoration readback: strip is 3-word fallback, token not retrievable ($SN)" || fail "neg3 post-restoration readback: strip '$SN' (wanted n=3)"
  shot k01e_privacy_negatives_clean_strip

  # negative 4: explicit IME_FLAG_NO_PERSONALIZED_LEARNING, otherwise normal field, gates ON
  # -> expected skip reason noLearning via the flag (fresh EditorInfo, distinct from neg3's password path)
  focus_target
  adb shell am broadcast -a com.wrenboard.imetarget.NOLEARN --ez on true -p "$TPKG" >/dev/null 2>&1
  wait_mode NOLEARN || fail "neg4: fixture did not apply NOLEARN mode"
  adb shell input keyevent 4; sleep 0.8; tap_field
  adb logcat -c; PREF4=$(prefnote)
  for i in 1 2 3; do clear_field; type_word qjxwvz; tapcode 32; sleep 0.8; done
  clear_field
  settle || note "neg4: marker-settle hit its 8s bound (markers may be incomplete)"
  WQ=$(wqcount); PN=$(privnote); SK=$(skipcount noLearning); EN=$(entercount)
  note "neg4 nolearn-flag (3 commits, $PREF4): queued=$WQ enter=$EN skip[noLearning]=$SK markers: $PN"
  [ "$EN" -ge 3 ] && [ "$SK" -ge 3 ] && [ "$WQ" -eq 0 ] && pass "privacy neg4: all 3 commits entered and were skipped reason=noLearning (explicit flag, normal field)" || fail "privacy neg4: enter=$EN skip=$SK queued=$WQ (wanted >=3,>=3,0) [$PN]"
  adb shell am broadcast -a com.wrenboard.imetarget.NOLEARN --ez on false -p "$TPKG" >/dev/null 2>&1
  wait_mode NORMAL || fail "neg4: fixture did not restore NORMAL mode"
  adb shell input keyevent 4; sleep 0.8; focus_target; sleep 2
  verify_empty || fail "neg4: field not empty at retrieval read"
  SN=$(sugg)
  [ "$SN" = "n=3" ] && pass "neg4 post-restoration readback: strip is 3-word fallback, token not retrievable ($SN)" || fail "neg4 post-restoration readback: strip '$SN' (wanted n=3)"
  shot k01i_neg4_strip_fallback_pixels

  # positive control: 3 gated-on zebra commits must each enqueue; STORED completion is then
  # proven by the production readback (strip surfaces zebra after exactly 3 commits - the
  # same commit count the negatives used, so visibility-at-3 is proven for this build).
  adb logcat -c
  for i in 1 2 3; do clear_field; type_word zebra; tapcode 32; sleep 0.8; done
  clear_field
  if wait_wq 3; then WQOK=1; else WQOK=0; fi
  PN=$(privnote); DR=$(dropcount); note "positive control markers: $PN drops=$DR"
  [ "$WQOK" = 1 ] && [ "$DR" -eq 0 ] && pass "positive control: 3 submissions observed after the negative windows, zero dropped (marker path live) [$PN]" || fail "positive control: submissions=$(wqcount) drops=$DR (wanted >=3,0) [$PN]";
  clear_field; focus_target; sleep 2
  for i in $(seq 1 10); do SC=$(sugg); grep -q 'STARTER history chosen=' shots/_s.txt && break; sleep 2; done
  [ "$SC" = "n=1" ] && pass "positive control stored completion: production readback surfaces the control word ($SC)" || fail "positive control stored completion: strip '$SC' (wanted n=1)"
  clear_field
  XY=$(kxy code -3 2>/dev/null || true); note "123 key at: $XY"
  clear_field
  type_word hello; F=$(field); case "$F" in hello|Hello) pass "typing hello ($F)";; *) fail "typing hello (got '$F')";; esac
  clear_field
  # fix1b: starter words come from the person's own typing history; I/The/You only stand in when there is none
  adb logcat -c   # count learn-queue writes from the zebra commits only
  for i in 1 2 3; do clear_field; type_word zebra; tapcode 32; sleep 0.6; done
  note "field after learning: '$(field)'"
  # Deterministic readiness: wait until the app has LOGGED all three learn writes (wave2-480 read
  # an empty history for >10s on a slow runner; blind retries only measure weather).
  for i in $(seq 1 40); do
    [ "$(adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c "STARTER writeQueued")" -ge 3 ] && break; sleep 1
  done
  WC=$(adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c "STARTER writeQueued")
  note "learn writes seen: $WC"
  [ "$WC" -ge 3 ] && [ "$(dropcount)" -eq 0 ] && pass "3 learn-queue writes recorded since the zebra commits began ($WC, enqueue marker, zero dropped)" || fail "learn-queue writes=$WC drops=$(dropcount) since the zebra commits began (wanted >=3 writes, 0 drops)"
  clear_field; adb logcat -c; focus_target; sleep 2; S2=$(sugg)
  # Content-free readiness: wait until the strip update is logged as sourced from history
  # (the wordless "STARTER history chosen=N from=... history=M" line), not for a word.
  for i in $(seq 1 10); do grep -q 'STARTER history chosen=' shots/_s.txt && break; sleep 2; S2=$(sugg); done
  HIST=$(grep 'STARTER history chosen=' shots/_s.txt | tail -1 | sed 's/^.*WBDBG *: //')
  note "starter strip after typing 'zebra' 3x at the start of a field: [$S2] $HIST"
  grep 'STARTER' shots/_s.txt | tail -3 | sed 's/^.*WBDBG *: /  /' | tee -a "$R"
  shot k01b_starter_words_from_history
  CH=$(echo "$HIST" | sed -n 's/.*chosen=\([0-9][0-9]*\).*/\1/p')
  HN=$(echo "$HIST" | sed -n 's/.*history=\([0-9][0-9]*\).*/\1/p')
  # Strict: a POSITIVE history pick is required (chosen>=1 and history>=1); chosen=0 or a
  # missing sourcing line is a failure, never a pass. Word identity itself is proven by the
  # executed instrumented wiring tests (WrenAbandonedCommitWiringTests); these count and
  # sourcing checks are necessary but not sufficient on their own.
  if [ -n "$CH" ] && [ "$CH" -ge 1 ] && [ -n "$HN" ] && [ "$HN" -ge 1 ]; then
    pass "starter strip positively sourced from typing history ($HIST)"
  else
    fail "starter strip not positively sourced from history (HIST='$HIST')"
  fi
  if [ -n "$CH" ] && [ "$CH" -ge 1 ] && [ "$S2" = "n=$CH" ]; then
    pass "strip count matches the history pick, no fallback padding ($S2)"
  else
    fail "empty field with history shows '$S2' (wanted n=$CH)"
  fi
  # Acceptance: select the learned word on the strip -> the field becomes exactly that word.
  # Strip band sits directly above the keyboard top (KBD y from the geom dump); the single
  # history word occupies the center slot (measured on rev13-J k01b pixels: word cluster
  # x~420-594, strip band ~1165-1283 with keyboard top 1283). Both tap points stay inside
  # the strip band, never on keys; a miss is a deterministic fail with pixels attached.
  clear_field; focus_target; sleep 2
  for i in $(seq 1 10); do S2T=$(sugg); grep -q 'STARTER history chosen=' shots/_s.txt && break; sleep 2; done
  geom ktap >/dev/null 2>&1 || true
  KY=$(awk '/KBD x=/{for(i=1;i<=NF;i++) if($i ~ /^y=/){sub("y=","",$i); print $i}}' shots/geom_ktap.txt | head -1)
  if [ -z "$KY" ]; then
    fail "strip select: no KBD geometry line for the tap position"
  else
    clear_field
    shot k01f_strip_select_zebra
    adb shell input tap 510 $((KY - 76)); sleep 1.2; F=$(field | sed 's/[[:space:]]*$//')
    case "$F" in zebra|Zebra) : ;; *) adb shell input tap 540 $((KY - 38)); sleep 1.2; F=$(field | sed 's/[[:space:]]*$//');; esac
    case "$F" in zebra|Zebra) pass "strip select: tapping the learned word inserts exactly '$F'";; *) fail "strip select: field='$F' after strip taps (wanted the learned word)";; esac
  fi
  clear_field
  dbg "dsbool:wren_starter_words=false" 1.0; adb logcat -c; focus_target; sleep 2; S3=$(sugg); note "starter words with the toggle off: [$S3]"
  shot k01c_starter_words_toggle_off
  [ "$S3" = "n=0" ] && pass "toggle off: no starter words in an empty field" || fail "starter words still shown with the toggle off ($S3)"
  # toggle off only blanks the empty field: typing a word still gets FUTO's normal suggestions
  adb logcat -c; type_word hel; sleep 1; S4=$(sugg)
  # Under emulator load the suggestion broadcast can land after the 1s read; give it a few retries before judging.
  for i in 1 2 3; do [ -n "$S4" ] && [ "$S4" != "n=0" ] && break; sleep 1.5; S4=$(sugg); done
  F4=$(field); note "toggle off, typed '$F4': [$S4]"; shot k01d_typed_word_toggle_off
  [ -n "$S4" ] && [ "$S4" != "n=0" ] && pass "toggle off: typed word still gets normal suggestions ($S4)" || fail "toggle off: typed 'hel' got no normal suggestions ($S4)"
  clear_field
  dbg "dsbool:wren_starter_words=true" 1.0

  # tap_strip_center <geomtag> : tap the single-word strip using THIS run's geometry; echoes nothing.
  tap_strip_center() {
    geom "$1" >/dev/null 2>&1 || true
    local ky; ky=$(awk '/KBD x=/{for(i=1;i<=NF;i++) if($i ~ /^y=/){sub("y=","",$i); print $i}}' "shots/geom_$1.txt" | head -1)
    [ -z "$ky" ] && return 1
    adb shell input tap 510 $((ky - 76)); sleep 1.2
  }
  # --- Acceptance: simultaneous en/fr multi-locale, fresh commit identity via the content-free
  # locale tag on the write marker (DEBUG-only). fr is bundled (res-large/raw/main_fr.dict). ---
  dbg "addlang:fr" 1.5; adb shell input keyevent 4; sleep 0.8; focus_target
  wait_layout azerty || fail "fr leg: keyboard did not reach azerty after addlang:fr"
  adb logcat -c; sleep 1; SFR0=$(sugg)
  # Bounded marker retry: the suggestion broadcast can land after a 1s single read (rev30-32
  # showed '' while pixels visibly rendered the 3-word fallback). An unparseable read after
  # the retries still fails - no fallback substitution.
  for i in 1 2 3 4 5; do [ -n "$SFR0" ] && break; sleep 1.5; SFR0=$(sugg); done
  shot k02a_fr_absent_before
  [ "$SFR0" = "n=3" ] && pass "fr absent-before: fresh fr field shows 3-word fallback ($SFR0)" || fail "fr absent-before: '$SFR0' (wanted n=3)"
  # actual fr commits: bonjour is in the bundled fr main dict, so fr confidence can rise
  adb logcat -c
  for i in 1 2 3 4; do clear_field; type_word bonjour; tapcode 32; sleep 0.8; done
  for i in $(seq 1 30); do
    [ "$(adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c 'STARTER writeQueued.* locale=fr ')" -ge 1 ] && break; sleep 1
  done
  WFR=$(adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c 'STARTER writeQueued.* locale=fr ')
  WEN=$(adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c 'STARTER writeQueued.* locale=en')
  note "fr commits: writeQueued locale=fr x$WFR, locale=en* x$WEN"
  [ "$WFR" -ge 1 ] && [ "$WEN" -eq 0 ] && pass "fr commit identity: writes tagged locale=fr ($WFR), zero en-tagged writes" || fail "fr commit identity: fr=$WFR en=$WEN"
  # fr retrieval: empty fr field must surface the committed word (count + pixels)
  clear_field; focus_target; sleep 2
  for i in $(seq 1 10); do SFR=$(sugg); grep -q 'STARTER history chosen=' shots/_s.txt && break; sleep 2; done
  shot k02b_fr_after_commits
  [ "$SFR" = "n=1" ] || fail "fr retrieval: strip count '$SFR' (wanted n=1)"
  clear_field
  tap_strip_center kfrtap || fail "fr retrieval: no KBD geometry for strip tap"
  F=$(field | sed 's/[[:space:]]*$//')
  case "$F" in bonjour|Bonjour) pass "fr retrieval word-level: strip tap inserts exactly '$F' (pixels k02b)";; *) fail "fr retrieval word-level: field='$F' after strip tap (wanted the fr word)";; esac
  clear_field
  # positive control en: switch back, one commit must be tagged locale=en*
  dbg "addlang:en_US" 1.5; adb shell input keyevent 4; sleep 0.8; focus_target
  wait_layout qwerty || fail "en leg: keyboard did not reach qwerty after addlang:en_US"
  adb logcat -c; type_word zebra; tapcode 32
  if wait_wq 1; then
    WEN2=$(adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -c 'STARTER writeQueued.* locale=en')
    [ "$WEN2" -ge 1 ] && pass "positive control en: commit tagged locale=en* ($WEN2)" || fail "positive control en: writeQueued without en locale tag"
  else fail "positive control en: no writeQueued within 25s"; fi
  # en isolation: en strip must show only its own history word (zebra, n=1); a bonjour leak would add a second
  clear_field; focus_target; sleep 2
  for i in $(seq 1 10); do SEN=$(sugg); grep -q 'STARTER history chosen=' shots/_s.txt && break; sleep 2; done
  shot k02c_en_after_fr
  [ "$SEN" = "n=1" ] && pass "en readback count: exactly one strip word after the fr session (pixels k02c show WHICH word)" || fail "en readback count: '$SEN' (wanted n=1)"
  clear_field
  tap_strip_center kentap || fail "en isolation: no KBD geometry for strip tap"
  F=$(field | sed 's/[[:space:]]*$//')
  case "$F" in zebra|Zebra) pass "en word-level: the sole strip word inserts as '$F' (with count n=1 and pixels k02c, no fr word is offered)";; *) fail "en word-level: field='$F' after strip tap (wanted the en word zebra)";; esac
  clear_field

  # locale-switch round-trip: switch back to fr WITHOUT re-adding; the fr word must still be
  # retrievable. This proves per-locale isolation across switches, NOT simultaneously loaded
  # dictionary groups (no facilitator state evidence is collected for that claim).
  dbg "addlang:fr" 1.5; adb shell input keyevent 4; sleep 0.8; focus_target; sleep 2
  wait_layout azerty || fail "fr reload: keyboard did not reach azerty after addlang:fr"
  for i in $(seq 1 10); do SFR2=$(sugg); grep -q 'STARTER history chosen=' shots/_s.txt && break; sleep 2; done
  shot k02d_fr_reload_readback
  clear_field
  tap_strip_center kfrtap2 || fail "fr reload: no KBD geometry for strip tap"
  F=$(field | sed 's/[[:space:]]*$//')
  case "$F" in bonjour|Bonjour) pass "locale-switch round-trip: fr word still retrievable after the en session ('$F', pixels k02d)";; *) fail "locale-switch round-trip: fr readback field='$F' (wanted the fr word)";; esac
  clear_field
  dbg "addlang:en_US" 1.5; adb shell input keyevent 4; sleep 0.8; focus_target
  wait_layout qwerty || fail "en leg: keyboard did not reach qwerty after addlang:en_US"
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

  # second Back from the plain letters page hides the keyboard.
  # Force a known letters state first: after the matrix the page can be symbols (elementId 5/6),
  # where Back toggles to letters instead of hiding - that made this check read page state as key count.
  focus_target; sleep 0.4
  adb logcat -c
  EA=$(elem panelexit_prehide)
  if [ "$EA" = "5" ] || [ "$EA" = "6" ]; then tapcode -3 >/dev/null 2>&1 || true; sleep 1.0; note "pre-hide: toggled off symbols (elementId was $EA)"; fi
  adb shell input keyevent 4; sleep 1.4
  adb logcat -d -s WBDBG:I | tr -d '\r' | grep 'BACK phase' | tail -4 | sed 's/^.*WBDBG *: /  /' | tee -a "$R"
  # Visibility is the assertion, not model key count: WbDebug geom dumps the loaded keyboard
  # model (mainKeyboardView.keyboard) with no visibility gate, so a correct hide still dumps keys
  # (48 retained after hide in runs 41385942014/41385942026). Ground truth: window state + pixels.
  NK=$(geom panelexit_hidden keep >/dev/null; grep -c 'KEY ' shots/geom_panelexit_hidden.txt)
  adb shell dumpsys input_method > shots/dumpsys_panelexit_hidden.txt 2>&1
  SHOWN=$(grep -oE 'mInputShown=(true|false)' shots/dumpsys_panelexit_hidden.txt | head -1 | cut -d= -f2)
  shot panelexit_hidden
  note "post-Back visibility: mInputShown=$SHOWN (model dump retains $NK keys - model state, not visibility)"
  [ "$SHOWN" = "false" ] && pass "second Back from letters hides the keyboard (mInputShown=false)" || fail "second Back from letters did not hide the keyboard (mInputShown=$SHOWN)"
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
    # Anchor the hold to the APP-VISIBLE down: under input backlog a host-timed DOWN/1.2s/UP can be
    # delivered as a tap (a1953b045, wave2-480). Wait for the cell's EMOJITOUCH DOWN marker first;
    # a late UP only makes the hold longer, which is still a valid long-press.
    adb shell input motionevent DOWN $((LEFT+CW/2)) $((TOP+RH/2))
    for i in $(seq 1 24); do sleep 0.5; adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' | grep -q "EMOJITOUCH DOWN" && break; done
    sleep 1.5; adb shell input motionevent UP $((LEFT+CW/2)) $((TOP+RH/2)); sleep 1.0
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
    # Swipe evidence (4-pair flip-flop with byte-identical product code; cause INCONCLUSIVE between
    # emulator gesture timing and a product race): instrument coords + card identity + state, keep the
    # first-attempt verdict permanent, and on failure run a separate diagnostic manual-stream attempt
    # with mid-gesture frames. No green-by-retry: a first-attempt FAIL stands in the record.
    note "swipe attempt 1: input swipe ($X,$Y)->($((X-600)),$Y) 300ms; cards=$(grep -c 'CLIPCARD' shots/cl3.txt); target=$(grep 'CLIPCARD i=1 ' shots/cl3.txt | head -1 | cut -c1-140)"
    note "inject t0=$(date +%s.%N)"
    adb logcat -c; adb shell input swipe $X $Y $((X-600)) $Y 300; note "inject t1=$(date +%s.%N)"; sleep 1.5; wlog cl4
    grep -q 'CLIP removed=1' shots/cl4.txt || { sleep 1.5; wlog cl4; }
    shot cl04_swiped_undo
    if grep -q 'CLIP removed=1' shots/cl4.txt; then pass "swipe deletes the clip"; else
      fail "swipe did not delete (first attempt)"
      dbg close 0.8; dbg "open:clipboard_history" 2.5; wlog cl3d
      C2=$(pos shots/cl3d.txt 'CLIPCARD i=1 '); note "swipe diagnostic: cards=$(grep -c 'CLIPCARD' shots/cl3d.txt); target=$(grep 'CLIPCARD i=1 ' shots/cl3d.txt | head -1 | cut -c1-140)"
      if [ -n "$C2" ]; then read -r X2 Y2 <<< "$C2"
        adb logcat -c
        # single device-paced shell (see the slow-drag leg): no per-command process spawn gaps
        adb shell "input motionevent DOWN $X2 $Y2; sleep 0.2; input motionevent MOVE $((X2-150)) $Y2; sleep 0.15; input motionevent MOVE $((X2-350)) $Y2; sleep 0.15; input motionevent MOVE $((X2-650)) $Y2; sleep 0.15; input motionevent UP $((X2-650)) $Y2"
        sleep 1.5; wlog cl4d
        shot cl04g_after_diagnostic_swipe
        note "diagnostic stream CLIP lines: $(grep -o 'CLIP [a-z =0-9]*' shots/cl4d.txt | head -4 | tr '\n' '|')"
        grep -q 'CLIP removed=1' shots/cl4d.txt && pass "diagnostic swipe deletes via manual stream (first-attempt FAIL above stands)" || fail "diagnostic swipe also did not delete (manual stream; see frames cl04d-f)"
        grep -q 'CLIP undo restored' shots/cl4d.txt && note "diagnostic: undo already fired in stream log"
      else fail "diagnostic swipe: no card position on panel reopen"; fi
    fi
    U=$(pos shots/cl4.txt 'CLIPUNDO'); [ -n "$U" ] && { adb logcat -c; adb shell input tap $U; sleep 1.0; wlog cl5; shot cl05_after_undo
      grep -q 'CLIP undo restored=1' shots/cl5.txt && pass "Undo brings the swiped clip back" || fail "Undo did not restore"; } || fail "no Undo bar after swipe"
  else fail "no card position"; fi
  # Retained-interaction legs for the arbitration gesture handler: a stationary hold opens the same
  # card menu as the 3-dot, and a tap pastes the card into the target field.
  adb logcat -c; dbg close 0.8; focus_target; clear_field; dbg "open:clipboard_history" 2.0; wlog cl5g
  C=$(pos shots/cl5g.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c
    adb shell "input motionevent DOWN $X $Y"; sleep 1.0; shot cl05h_hold_mid
    adb shell "input motionevent UP $X $Y"; sleep 1.2; wlog cl5h; shot cl05h_hold_pin_menu
    grep -q 'WINDOW CLIPMENU' shots/cl5h.txt && pass "stationary hold opens the card menu (retained interaction)" || fail "stationary hold did not open the card menu"
  else fail "no card position for the hold leg"; fi
  adb logcat -c; dbg close 0.8; focus_target; clear_field; dbg "open:clipboard_history" 2.0; wlog cl5s
  C=$(pos shots/cl5s.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c; adb shell input tap $X $Y; sleep 1.2; wlog cl5t; shot cl05t_tap_paste
    F=$(field); note "field after card tap: ${F:0:80}"
    [ -n "$F" ] && pass "tap on a card pastes it into the field (retained interaction)" || fail "tap on a card pasted nothing"
  else fail "no card position for the tap leg"; fi
  # 3dp-hold-cancel leg (Tab's exact arbitration spec, 20:45): a >3dp movement in any direction
  # cancels the hold timer - so move 24px (9dp, sub-slop horizontally), hold past the timeout,
  # lift: must PASTE, must NOT open the menu. This leg fails on the pre-3dp-cancel handler.
  adb logcat -c; dbg close 0.8; focus_target; clear_field; dbg "open:clipboard_history" 2.0; wlog cl5l
  C=$(pos shots/cl5l.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    # 6dp HORIZONTAL, density-scaled: strictly between the 3dp hold-cancel and touch slop at the
    # gate densities (holdCancel=7px/slop=21px @420 observed, 9/24 @480). The old fixed 24px VERTICAL move
    # crossed slop at 420dpi and was correctly scroll-arbitrated (no paste) - that vertical case
    # stays covered by the v1-v3 vertical legs; this leg pins the sub-slop hold-cancel case.
    DENS=$(adb shell wm density 2>/dev/null | tr -d '\r' | grep -o '[0-9]*' | tail -1)
    MV=$(( ${DENS:-420} * 6 / 160 ))
    adb logcat -c
    adb shell "input motionevent DOWN $X $Y"; sleep 0.1
    adb shell "input motionevent MOVE $((X+MV)) $Y"; sleep 0.9; shot cl05n_moved_hold
    adb shell "input motionevent UP $((X+MV)) $Y"; sleep 1.2; wlog cl5n
    SLOP=$(grep -o 'slop=[0-9]*' shots/cl5n.txt | head -1 | cut -d= -f2)
    HC=$(grep -o 'holdCancel=[0-9]*' shots/cl5n.txt | head -1 | cut -d= -f2)
    DX=$(grep -o 'accDx=-\?[0-9]*' shots/cl5n.txt | sed 's/accDx=//' | awk '{if($1<0)$1=-$1; if($1>m)m=$1} END{print m+0}')
    note "3dp-cancel move: ${MV}px horizontal at density ${DENS:-?} (trace slop=${SLOP:-?} holdCancel=${HC:-?} max|accDx|=${DX})"
    if [ -z "$SLOP" ] || [ -z "$HC" ]; then
      fail "3dp-cancel precondition: handler trace thresholds missing (slop=${SLOP:-?} holdCancel=${HC:-?}) - leg unverified"
    elif [ "$MV" -le "$HC" ] || [ "$MV" -ge "$SLOP" ]; then
      fail "3dp-cancel precondition: ${MV}px move not strictly between holdCancel=${HC} and slop=${SLOP}"
    elif [ "${DX:-0}" -le "$HC" ]; then
      fail "3dp-cancel precondition: horizontal move not delivered to the handler (max |accDx| ${DX}px <= holdCancel ${HC}px)"
    elif grep -q 'WINDOW CLIPMENU' shots/cl5n.txt; then fail "3dp-cancel: Pin menu opened after a >3dp move (hold timer was not cancelled)"
    else F=$(field); note "field after move-hold-lift: ${F:0:80}"
      [ -n "$F" ] && pass "movement >3dp cancels the hold timer; the lift still pastes" || fail "3dp-cancel: the lift after the cancelled hold pasted nothing"; fi
  else fail "no card position for the 3dp-cancel leg"; fi
  # Near-threshold vertical boundary (retains the old fixed-24px-vertical case density-scaled):
  # 12dp vertical is strictly ABOVE touch slop at the gate densities, so the list must own the
  # gesture as a scroll: no paste, no delete, no menu. Delivery proven by the handler trace.
  adb logcat -c; dbg close 0.8; focus_target; clear_field; dbg "open:clipboard_history" 2.0; wlog cl5p
  C=$(pos shots/cl5p.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    DENS=$(adb shell wm density 2>/dev/null | tr -d '\r' | grep -o '[0-9]*' | tail -1)
    MVV=$(( ${DENS:-420} * 12 / 160 ))
    adb logcat -c
    adb shell "input motionevent DOWN $X $Y"; sleep 0.1
    adb shell "input motionevent MOVE $X $((Y-MVV))"; sleep 0.3
    adb shell "input motionevent UP $X $((Y-MVV))"; sleep 1.2; wlog cl5q; shot cl05q_near_threshold_vertical
    SLOPQ=$(grep -o 'slop=[0-9]*' shots/cl5q.txt | head -1 | cut -d= -f2)
    EVQ=$(grep 'phase=' shots/cl5q.txt | grep -o 'accDy=-\?[0-9]*' | sed 's/accDy=//' | awk '{if($1<0)$1=-$1; if($1>m)m=$1} END{print m+0}')
    note "near-threshold vertical: ${MVV}px at density ${DENS:-?} (trace slop=${SLOPQ:-?} max|accDy|=${EVQ})"
    if [ -z "$SLOPQ" ]; then fail "near-threshold vertical: handler trace thresholds missing - leg unverified"
    elif [ "$MVV" -le "$SLOPQ" ]; then fail "near-threshold vertical precondition: ${MVV}px not above slop=${SLOPQ}px"
    elif [ "${EVQ:-0}" -le "$SLOPQ" ]; then fail "near-threshold vertical: gesture not delivered above slop (max |accDy| ${EVQ}px, slop ${SLOPQ}px)"
    else F=$(field)
      if [ -n "$F" ]; then fail "near-threshold vertical (>slop): pasted into the field ('${F:0:40}')"
      elif grep -q 'CLIP removed=' shots/cl5q.txt; then fail "near-threshold vertical (>slop): deleted a clip"
      elif grep -q 'WINDOW CLIPMENU' shots/cl5q.txt; then fail "near-threshold vertical (>slop): opened the Pin menu"
      else pass "near-threshold vertical ${MVV}px (>slop): scroll-arbitrated, no paste/delete/menu"; fi
    fi
  else fail "no card position for the near-threshold vertical leg"; fi
  # Menu-then-drag leg (Tab's exact arbitration spec, 20:45): once the stationary hold has opened
  # the Pin menu, a later drag in the same gesture must never delete the clip.
  adb logcat -c; dbg close 0.8; dbg "open:clipboard_history" 2.0; wlog cl5k
  C=$(pos shots/cl5k.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c
    adb shell "input motionevent DOWN $X $Y"; sleep 0.8
    adb shell "input motionevent MOVE $((X-300)) $Y"; sleep 0.3
    adb shell "input motionevent UP $((X-300)) $Y"; sleep 1.2; wlog cl5m; shot cl05m_menu_then_drag
    if grep -q 'CLIP removed=' shots/cl5m.txt; then fail "menu-then-drag: the clip was deleted after the Pin menu had opened"
    else pass "a drag after the Pin menu opened never deletes"; fi
    adb shell input keyevent KEYCODE_BACK; sleep 0.8
  else fail "no card position for the menu-then-drag leg"; fi
  # Slow-drag leg (Tab review): a manual stream with dwells must still delete - the drag claims on
  # first slop-crossing motion, dwells after that cannot open the long-press menu.
  adb logcat -c; dbg close 0.8; dbg "open:clipboard_history" 2.0; wlog cl5u
  C=$(pos shots/cl5u.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c
    # ONE adb shell, device-paced: each `input motionevent` as its own command spawns a fresh
    # on-device process, which can stall for seconds apiece on a loaded emulator (480/load=3 run
    # 41605113045: DOWN delivered, then 18 s of silence, the hold timer correctly fired for zero
    # delivered movement). In one shell the dwells are device-timed and in-order; a pipeline stall
    # before the DOWN flushes the stream together, so the app-visible DOWN->first-MOVE gap stays
    # small. The dwell structure is unchanged; the verdict stays strict.
    adb shell "input motionevent DOWN $X $Y; sleep 0.2; input motionevent MOVE $((X-150)) $Y; sleep 0.3; input motionevent MOVE $((X-350)) $Y; sleep 0.3; input motionevent MOVE $((X-650)) $Y; sleep 0.3; input motionevent UP $((X-650)) $Y"
    sleep 1.5; wlog cl5v; shot cl05v_slowdrag_after
    if grep -q 'CLIP removed=1' shots/cl5v.txt; then pass "slow drag with dwells deletes the clip"
    elif grep -q 'WINDOW CLIPMENU' shots/cl5v.txt; then fail "slow drag opened the long-press menu instead of dragging"
    else fail "slow drag did not delete"; fi
    U=$(pos shots/cl5v.txt 'CLIPUNDO'); [ -n "$U" ] && { adb logcat -c; adb shell input tap $U; sleep 1.0; wlog cl5w; grep -q 'CLIP undo restored=1' shots/cl5w.txt && pass "Undo after slow drag" || fail "Undo after slow drag failed"; } || fail "no Undo bar after slow drag"
  else fail "no card position for the slow-drag leg"; fi
  # Vertical-arbitration legs (1.5 B5): a vertical-dominant gesture on a card is the list's
  # scroll - it must never paste, never delete, never open the Pin menu, however the UP arrives
  # (coalesced tail, out-and-back return near the origin). Delivery is evidenced by the
  # handler's own WBDBG EV accDy samples (slopwatch phase); retained interactions (tap paste,
  # hold menu, horizontal swipe delete) are covered by cl04/cl05 above.
  for t in clip_v_06 clip_v_07 clip_v_08 clip_v_09 clip_v_10 clip_v_11 clip_v_12; do dbg "clipset:$t" 0.4; done
  vchk() { # vchk <logfile> <label> [floor] : shared verdicts for the vertical legs; $F/$EV filled by caller
    local L="$1" lbl="$2" floor="${3:-60}"
    if [ "$EV" -lt "$floor" ]; then fail "$lbl: vertical gesture was not delivered to the card handler (max |accDy| ${EV}px, floor ${floor}px)"
    elif [ -n "$F" ]; then fail "$lbl: vertical scroll pasted into the field ('${F:0:40}')"
    elif grep -q 'CLIP removed=' "$L"; then fail "$lbl: vertical scroll deleted a clip"
    elif grep -q 'WINDOW CLIPMENU' "$L"; then fail "$lbl: vertical scroll opened the Pin menu"
    else pass "$lbl: no paste, no delete, no menu"; fi
  }
  vev() { grep 'phase=slopwatch\|phase=hold' "shots/$1.txt" | grep -o 'accDy=-\?[0-9]*' | sed 's/accDy=//' | awk '{if($1<0)$1=-$1; if($1>m)m=$1} END{print m+0}'; }
  # v1: device-paced slow vertical drag on a card
  adb logcat -c; dbg close 0.8; focus_target; clear_field; dbg "open:clipboard_history" 2.0; wlog clv0
  N=$(grep 'CLIP shown=' shots/clv0.txt | tail -1 | sed 's/.*shown=//'); [ "${N:-0}" -ge 12 ] && pass "12 clips seeded for the scroll legs ($N)" || fail "fewer than 12 clips stored (N=${N:-0}): scroll legs underfed"
  C=$(pos shots/clv0.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c
    adb shell "input motionevent DOWN $X $Y; sleep 0.15; input motionevent MOVE $X $((Y-250)); sleep 0.15; input motionevent MOVE $X $((Y-500)); sleep 0.15; input motionevent MOVE $X $((Y-700)); sleep 0.15; input motionevent UP $X $((Y-700))"
    sleep 1.2; wlog clv1; shot cl06v_vertical_scroll
    EV=$(vev clv1); note "  v1 max |accDy| at handler: ${EV}px"; F=$(field); note "  field after vertical drag: '${F:0:40}'"
    vchk shots/clv1.txt "vertical drag on a card"
  else fail "no card position for the vertical-drag leg"; fi
  # v2: fast fling whose tail coalesces into the UP event
  adb logcat -c; dbg close 0.8; clear_field; dbg "open:clipboard_history" 2.0; wlog clv2w
  C=$(pos shots/clv2w.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c; adb shell input swipe $X $Y $X $((Y-700)) 80
    sleep 1.2; wlog clv2; shot cl06w_fast_fling_scroll
    EV=$(vev clv2); F=$(field)
    # Delivery floor for the 80ms fling: the handler's own accumulation crossing touch slop
    # (parsed from its DOWN trace) proves delivery. Observed on rev32 under this runner: 43-52px
    # at slop=21 with correct scroll-arbitration, below the v1/v3 device-paced 60px floor. This
    # proves delivery + no paste/delete/menu only - not the UP-coalescing path shape.
    VSLOP=$(grep -o 'slop=[0-9]*' shots/clv2.txt | head -1 | cut -d= -f2)
    VF=60; [ -n "$VSLOP" ] && VF=$((VSLOP + 1))
    note "  v2 fast fling (80ms requested); max |accDy| at handler: ${EV}px (trace slop=${VSLOP:-?}, floor=${VF})"; note "  field after fling: '${F:0:40}'"
    vchk shots/clv2.txt "fast vertical fling (80ms)" "$VF"
  else fail "no card position for the coalesced-UP leg"; fi
  # v3: out-and-back scroll returning near the origin (net dy ~ 0, peak ~ 400px)
  adb logcat -c; dbg close 0.8; clear_field; dbg "open:clipboard_history" 2.0; wlog clv3w
  C=$(pos shots/clv3w.txt 'CLIPCARD i=1 ')
  if [ -n "$C" ]; then read -r X Y <<< "$C"
    adb logcat -c
    adb shell "input motionevent DOWN $X $Y; sleep 0.15; input motionevent MOVE $X $((Y-200)); sleep 0.15; input motionevent MOVE $X $((Y-400)); sleep 0.15; input motionevent MOVE $X $((Y-200)); sleep 0.15; input motionevent MOVE $X $((Y+10)); sleep 0.1; input motionevent UP $X $((Y+10))"
    sleep 1.2; wlog clv3; shot cl06x_out_return_scroll
    EV=$(vev clv3); note "  v3 max |accDy| at handler: ${EV}px (net ~0 by construction)"; F=$(field); note "  field after out-and-back: '${F:0:40}'"
    vchk shots/clv3.txt "out-and-back scroll returning near origin"
  else fail "no card position for the out-and-back leg"; fi
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


if leg saveword; then
  note "== SAVE WORD privacy boundary (1.5 B6): selection-priority positive; password / NOLEARN-flag / code-field negatives against the actual UserDictionary provider =="
  # Fail-closed provider access: `content` can print an Error/SecurityException payload with
  # exit code 0, so BOTH the exit code and the payload shape are validated. Token checks use an
  # exact --where word='...' filter plus the row shape word=<token>, never a substring grep.
  dict_has() { # <token> <label> -> 0 present, 1 absent, 2 query failed
    local tok="$1" lbl="$2" rows rc
    rows=$(adb shell content query --uri content://user_dictionary/words --where "word='$tok'" 2>&1); rc=$?
    if [ $rc -ne 0 ] || echo "$rows" | head -3 | grep -qi 'error\|exception\|denied'; then
      fail "$lbl: user_dictionary query FAILED: $(echo "$rows" | head -1 | cut -c1-120)"; return 2
    fi
    if echo "$rows" | grep -q "word=$tok,"; then return 0; fi
    # a present row must match the exact row shape; anything else is an empty (no-result) set
    return 1
  }
  dictdel() { adb shell content delete --uri content://user_dictionary/words --where "word='$1'" >/dev/null 2>&1; }
  wait_mode() { local want=$1 i; for i in $(seq 1 12); do [ "$(adb logcat -d -s IMETARGET:I 2>/dev/null | tr -d '\r' | grep -oE 'MODE=[A-Z]+ APPLIED' | tail -1)" = "MODE=$want APPLIED" ] && return 0; sleep 1; done; return 1; }
  SWP="zqxwjpos"; SWT="zqxwjtail"; SW2="zqxwjctb"; SW3="zqxwjctc"; SW4="zqxwjctd"
  for w in $SWP $SWT $SW2 $SW3 $SW4; do dictdel "$w"; done
  for w in $SWP $SWT $SW2 $SW3 $SW4; do
    dict_has "$w" "baseline"; b=$?
    [ $b -eq 0 ] && { dictdel "$w"; dict_has "$w" "baseline"; b=$?; }
    [ $b -eq 0 ] && fail "baseline: canary $w could not be cleared from the user dictionary"
  done
  POS_OK=0
  # positive + priority: two different words; the SELECTED one (first) must be saved, the word
  # before the cursor (last typed, nonempty) must NOT. Proves the selected-over-lastTyped branch
  # with both candidates present; says nothing about composing-vs-selected ordering.
  adb shell am broadcast -a com.wrenboard.imetarget.NOLEARN --ez on false -p "$TPKG" >/dev/null 2>&1; wait_mode NORMAL
  focus_target; clear_field
  # adb input text needs %s for a literal space; a raw space dropped the second canary on
  # rev30-32 and made the priority precondition degenerate. Field content verified below.
  adb shell input text "$SWP%s$SWT"; sleep 0.8
  PRE0=0; F=$(field); if [ "$F" = "$SWP $SWT" ]; then PRE0=1; else fail "positive precondition: field holds '${F:0:40}' (wanted both canaries)"; fi
  adb shell am broadcast -a com.wrenboard.imetarget.SELECT --ei start 0 --ei end ${#SWP} -p "$TPKG" >/dev/null 2>&1; sleep 0.8
  # Chip lives 1.2s: shoot at ~0.4s to capture the branch verdict, again at 1.5s for the field state.
  adb logcat -c; dbg "open:save_word" 0.4; shot sw01_chip; sleep 1.1; shot sw01_selection_saved
  # Raw receiver-side evidence: any "CMD ... failed" exception from the action path lands here.
  adb logcat -d -s WBDBG:I 2>/dev/null | tr -d '\r' > shots/sw01_wbdbg_raw.txt
  # Raw provider payload (canary tokens only) preserved against row-shape false negatives.
  adb shell content query --uri content://user_dictionary/words --where "word='$SWP'" > shots/sw01_provider_raw.txt 2>&1
  if [ "$PRE0" != 1 ]; then
    note "INFO: saveword positive/priority UNQUALIFIED - two-canary precondition failed above"
  else
  dict_has "$SWP" "positive"; rp=$?
  dict_has "$SWT" "positive"; rt=$?
  if [ $rp -eq 0 ] && [ $rt -eq 1 ]; then
    POS_OK=1; pass "save word saves the SELECTED word over the nonempty last-typed word (priority + liveness)"
  elif [ $rt -eq 0 ]; then fail "priority: the LAST-TYPED word was saved over the explicit selection"
  elif [ $rp -eq 1 ]; then fail "positive control: nothing was saved to the user dictionary (action path dead; negatives unqualified)"; fi
  fi
  dictdel "$SWP"; dictdel "$SWT"
  negcheck() { # <token> <label> <precondition 0/1>
    local tok="$1" lbl="$2" pre="$3"
    [ "$pre" = 1 ] || { note "INFO: $lbl negative not executed (honest-execution precondition failed above)"; return 1; }
    dict_has "$tok" "$lbl"; local r=$?
    if [ $r -eq 0 ]; then fail "$lbl: save word WROTE the token to the user dictionary"; return 1
    elif [ $r -eq 2 ]; then return 1
    elif [ "$POS_OK" = 1 ]; then pass "$lbl: save word refuses, no dictionary write"
    else note "INFO: $lbl refused, but UNQUALIFIED - positive liveness failed above"; return 1; fi
  }
  # negative 1: password field (broadcast-set inputType, field tap renegotiates the session)
  PRE1=0
  adb shell am broadcast -a com.wrenboard.imetarget.PASSWORD --ez on true -p "$TPKG" >/dev/null 2>&1; sleep 1.2; tap_field
  clear_field
  adb shell input text "$SW2"; sleep 0.8
  F=$(field); if [ "$F" = "$SW2" ]; then PRE1=1; else fail "password negative precondition: canary not observed in the fixture field ('${F:0:40}')"; fi
  adb logcat -c; dbg "open:save_word" 1.5; shot sw02_password_blocked
  negcheck "$SW2" "password field" $PRE1
  clear_field
  adb shell am broadcast -a com.wrenboard.imetarget.PASSWORD --ez on false -p "$TPKG" >/dev/null 2>&1; sleep 1.0
  # negative 2: IME_FLAG_NO_PERSONALIZED_LEARNING field (persisted+recreated fixture mode: the
  # flag is fresh in the EditorInfo at onStartInput, MODE=NOLEARN APPLIED is the readiness marker)
  PRE2=0
  adb shell am broadcast -a com.wrenboard.imetarget.NOLEARN --ez on true -p "$TPKG" >/dev/null 2>&1
  if wait_mode NOLEARN; then
    focus_target; adb shell input text "$SW3"; sleep 0.8
    F=$(field); if [ "$F" = "$SW3" ]; then PRE2=1; else fail "NOLEARN negative precondition: canary not observed in the fixture field ('${F:0:40}')"; fi
  else fail "NOLEARN mode was not applied by the fixture"; fi
  adb logcat -c; dbg "open:save_word" 1.5; shot sw03_nolearn_blocked
  negcheck "$SW3" "no-learning flag field" $PRE2
  adb shell am broadcast -a com.wrenboard.imetarget.NOLEARN --ez on false -p "$TPKG" >/dev/null 2>&1; wait_mode NORMAL
  # negative 3: code-field package classification (com.replit.wbcodefield fixture variant; a plain
  # text field has no AUTO_CORRECT flag -> the app's own getCodeFieldType says code field). The
  # focused window and the typed canary are verified before invoking; install/start failure fails.
  PRE3=0
  if [ -f tools/ime-target-codefield/out/imetarget-codefield.apk ]; then
    if adb install -r tools/ime-target-codefield/out/imetarget-codefield.apk >> "$R" 2>&1; then
      if adb shell am start -S -n "com.replit.wbcodefield/com.wrenboard.imetarget.TestInput" >> "$R" 2>&1; then
        sleep 3; tap_field
        FOCUS=$(adb shell dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus')
        if echo "$FOCUS" | grep -q 'com.replit.wbcodefield'; then
          adb shell input text "$SW4"; sleep 0.8
          F=$(field); if [ "$F" = "$SW4" ]; then PRE3=1; else fail "code-field negative precondition: canary not observed in the fixture field ('${F:0:40}')"; fi
        else fail "code-field fixture is NOT the focused window: ${FOCUS:0:140}"; fi
      else fail "code-field fixture failed to start"; fi
    else fail "install of the code-field fixture failed"; fi
  else fail "code-field fixture apk missing from the checkout"; fi
  adb logcat -c; dbg "open:save_word" 1.5; shot sw04_codefield_blocked
  negcheck "$SW4" "code field (com.replit.*)" $PRE3
  adb shell am broadcast -a com.wrenboard.imetarget.CLEAR -p com.replit.wbcodefield >/dev/null 2>&1
  for w in $SWP $SWT $SW2 $SW3 $SW4; do dictdel "$w"; done
  focus_target
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
      # mCurrentFocus can lag the actual window on a slow emulator (run 41600879995: the installer
      # dialog was in the uiautomator dump while focus still read the launcher) - trust the dump.
      dumpui _inst_probe; uitext _inst_probe | grep -q "Do you want to update this app?" && { INST="dump:installer-dialog (focus said: $FOC)"; break; }
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

if leg settingspreview; then
  note "== 1.5 settings candidate pixel preview; not full acceptance =="
  ensure_ime
  adb shell input keyevent KEYCODE_HOME; sleep 1
  opensettings "" 5
  shot sp01_home_top
  adb shell input swipe 540 1800 540 600 400; sleep 1.2; shot sp02_home_bottom
  for d in languages wren_appearance wren_layout wren_typing wren_swipe wren_dict wren_soundfeel wren_voice wren_clipemoji wren_backup about wren_advanced payment; do
    opensettings "$d" 3
    shot "sp_${d}_top"; dumpui "sp_$d"
    P=$(uitext "sp_$d"); [ -n "$P" ] && pass "preview $d has visible UI text" || fail "preview $d empty UI"
    adb shell input swipe 540 1800 540 600 400; sleep 1; shot "sp_${d}_bottom"
  done
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
  # scroll-until-visible: a loaded emulator can swallow a single fling (run 41600879995 lost the
  # lower groups); keep scrolling until FUTO is in view, max 4 tries. Final check below stays strict.
  for i in 1 2 3 4; do
    adb shell input swipe 540 1700 540 700 400; sleep 1.5; shot st01b_settings_home_scrolled; dumpui st01b
    uitext st01b | grep -q "FUTO" && break
  done
  T="$T$(uitext st01b)"
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
  shot st08a_wren_futo_top
  # scroll-until-visible: a loaded emulator can swallow a single fling (LazyColumn never moves);
  # keep scrolling until Credits enters the viewport, max 4 tries. Final check below stays strict.
  for i in 1 2 3 4; do
    adb shell input swipe 540 1900 540 600 400; sleep 1.5; shot st08b_wren_futo_scrolled; dumpui st_futo3
    uitext st_futo3 | grep -q "Credits" && { [ $i -gt 1 ] && note "FUTO Credits visible after $i scrolls"; break; }
  done
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
