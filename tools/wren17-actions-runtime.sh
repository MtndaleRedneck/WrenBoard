#!/usr/bin/env bash
set -euo pipefail
APK=$1
PKG=com.wrenboard.keyboard
OUT=actions17
mkdir -p "$OUT"
export_screens() {
 echo 'W17_FAILURE_DIAGNOSTICS_BEGIN'
 adb logcat -d -b crash -v brief | tail -180 || true
 adb logcat -d -s AndroidRuntime:E WBDBG:I | tail -180 || true
 adb shell dumpsys input_method | grep -E 'mCurMethodId|mInputShown|mShowRequested|mCurFocusedWindow' || true
 echo 'W17_FAILURE_DIAGNOSTICS_END'

python3 - "$OUT" <<'PY'
from pathlib import Path
from PIL import Image
import sys,io,base64
for p in sorted(Path(sys.argv[1]).glob('*.png')):
 im=Image.open(p); im.thumbnail((2160,2316) if p.stem.startswith("transition_") else (540,1158)); b=io.BytesIO();im.convert('RGB').save(b,format='JPEG',quality=75)
 print('W17_IMAGE_BEGIN '+p.stem)
 text=base64.b64encode(b.getvalue()).decode()
 for i in range(0,len(text),4096):print('W17_DATA '+text[i:i+4096])
 print('W17_IMAGE_END '+p.stem)
for p in sorted(Path(sys.argv[1]).glob('review-transition-*.mp4')):
 assert p.stat().st_size < 1500000, 'review video exceeded bounded log export size'
 print('W17_VIDEO_BEGIN '+p.stem)
 text=base64.b64encode(p.read_bytes()).decode()
 for i in range(0,len(text),4096):print('W17_VIDEO_DATA '+text[i:i+4096])
 print('W17_VIDEO_END '+p.stem)
PY
}
trap export_screens EXIT
adb shell wm size 1080x2316
adb shell wm density 420
adb install -r -t "$APK"
adb install -r tools/ime-target/out/imetarget.apk
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
IME=""
for attempt in $(seq 1 15); do
 IME=$(adb shell ime list -a -s | tr -d '\r' | grep "^$PKG/" | head -1 || true)
 [ -n "$IME" ] && break
 sleep 2
done
adb shell ime list -a -s
test -n "$IME"
adb shell ime enable "$IME"
adb shell ime set "$IME"
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() {
 for ATTEMPT in 1 2 3 4 5; do
  adb shell rm -f /sdcard/w17.xml
  adb shell uiautomator dump /sdcard/w17.xml >/dev/null 2>&1 || true
  adb exec-out cat /sdcard/w17.xml > "$OUT/$1.xml" 2>/dev/null || true
  if python3 -c 'import sys,xml.etree.ElementTree as E;assert E.parse(sys.argv[1]).getroot().tag=="hierarchy"' "$OUT/$1.xml" 2>/dev/null; then return 0; fi
  sleep 2
 done
 echo "No valid UI hierarchy for $1 after5attempts" >&2;return 1
}
openpage() { adb shell am start -W -n "$PKG/org.futo.inputmethod.latin.uix.settings.SettingsActivity" -f 0x34000000 --es navDest "$1" >/dev/null; sleep 5; dump "$1"; shot "$1"; adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" || true; }

# Focused new action design check only. No swipe/old baseline/GIF/signing.
adb shell am start -W -n com.wrenboard.imetarget/.TestInput >/dev/null
sleep 2
adb shell input tap 400 500
sleep 2
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd settings:themes
sleep 5
shot themes
dump themes
adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' || true
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null
sleep 3
adb shell input tap 400 500
sleep 3
shot fixed_emoji_keyboard
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null
sleep 2
shot unified_normal
dump normal
# UI text selects real buttons; no hard-coded app coordinates.
coords() {
 if python3 tools/wren17-screen-coords.py "$OUT/$1.png" "$2" 2>/dev/null; then return; fi
 dump locator
 if python3 - "$OUT/locator.xml" "$2" <<'PYCOORD'
import sys,xml.etree.ElementTree as E,re
root=E.parse(sys.argv[1]).getroot();label=sys.argv[2].lower()
nodes=[n for n in root.iter('node') if n.get('text','').lower()==label]
if not nodes:nodes=[n for n in root.iter('node') if n.get('content-desc','').lower()==label]
assert nodes, 'No actual text or accessible icon for '+label
v=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));print((v[0]+v[2])//2,(v[1]+v[3])//2)
PYCOORD
 then return; fi
 adb logcat -d -s WBDBG:I > "$OUT/locator.log"
 python3 - "$OUT/locator.log" "$2" <<'PYICON'
import sys,re
text=open(sys.argv[1]).read();label=sys.argv[2].replace(' ','_')
items=re.findall(r'UNIFIED_BAR name='+re.escape(label)+r' cx=(\d+) cy=(\d+)',text)
assert items, 'No rendered/debug icon bounds for '+label
print(*items[-1])
PYICON
}
HIDDEN_VIEW=0
taptext() {
 if [ "$HIDDEN_VIEW" = 1 ] && [ "$1" != Back ]; then
  # Never use old toolbar icon bounds to find an off-screen hidden action.
  for I in 1 2 3; do adb shell input swipe 800 1510 800 2050 300;done
  for I in 1 2 3 4 5 6; do
   shot current
   if POS=$(python3 tools/wren17-screen-coords.py "$OUT/current.png" "$1" hidden 2>/dev/null); then
    read -r X Y <<< "$POS";adb shell input tap "$X" "$Y";sleep 1;return
   fi
   adb shell input swipe 800 2040 800 1480 350;sleep 0.5
  done
  echo "Hidden action not found after bounded scroll: $1" >&2;return 1
 fi
 shot current;read -r X Y < <(coords current "$1");adb shell input tap "$X" "$Y";sleep 1
 if [ "$1" = Hidden ]; then HIDDEN_VIEW=1;fi
 if [ "$1" = Back ]; then HIDDEN_VIEW=0;fi
}
# No separate editor: drag directly in the open action box, save only on completed drop.
shot direct_initial
read -r SX SY < <(coords direct_initial Emojis)
read -r HX HY < <(coords direct_initial Hidden)
adb shell input motionevent DOWN "$SX" "$SY"; sleep 0.8
for I in $(seq 1 12); do
 X=$((SX+(HX-SX)*I/12));Y=$((SY+(HY-SY)*I/12));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
done
adb shell input motionevent UP "$HX" "$HY";sleep 2
shot after_drop_hidden
adb shell input keyevent KEYCODE_BACK;sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null;sleep 2
shot direct_reopened
taptext Hidden
shot hidden_open
# Restored item is appended to Hidden; scroll its grid into view.
adb shell input swipe 800 1990 800 1440 500; sleep 1
shot hidden_scrolled
taptext Emojis
shot restored_saved
HIDDEN_VIEW=0
adb shell input keyevent KEYCODE_BACK;sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null;sleep 2
shot restored_reopened
# Verify visible reorder, not only hide: bring the restored end item to the first action.
read -r SX SY < <(coords restored_reopened Emojis)
read -r HX HY < <(coords restored_reopened Clipboard)
adb shell input motionevent DOWN "$SX" "$SY"; sleep 0.8
for I in $(seq 1 12); do
 X=$((SX+(HX-SX)*I/12));Y=$((SY+(HY-SY)*I/12));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
done
adb shell input motionevent UP "$HX" "$HY";sleep 2
shot reordered_visible
adb shell input keyevent KEYCODE_BACK;sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null;sleep 2
shot reordered_reopened
# Cancel a changed in-memory drag by dismissing its window before pointer release.
read -r SX SY < <(coords reordered_reopened Emojis)
read -r HX HY < <(coords reordered_reopened GIF)
adb shell input motionevent DOWN "$SX" "$SY"; sleep 0.8
for I in $(seq 1 12); do
 X=$((SX+(HX-SX)*I/12));Y=$((SY+(HY-SY)*I/12));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
done
shot cancel_before_back
adb shell input keyevent KEYCODE_BACK;sleep 1
adb shell input motionevent UP "$HX" "$HY";sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null;sleep 2
shot cancelled_reopened
# Real hide gestures leave five visible actions, proving fewer-than-six spacing in the real strip.
for LABEL in Emojis Clipboard GIF Share Info Settings Undo Redo; do
 shot few_current
 read -r SX SY < <(coords few_current "$LABEL")
 read -r HX HY < <(coords few_current Hidden)
 adb shell input motionevent DOWN "$SX" "$SY"; sleep 0.8
 for I in $(seq 1 12); do
  X=$((SX+(HX-SX)*I/12));Y=$((SY+(HY-SY)*I/12));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
 done
 adb shell input motionevent UP "$HX" "$HY";sleep 1
done
shot fewer_than_six
adb shell input keyevent KEYCODE_BACK;sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null;sleep 2
shot fewer_than_six_reopened
# Exercise overflow beyond sixteen actions, including returning from Hidden before a multi-cell drag.
taptext Hidden
for LABEL in Emojis Clipboard GIF Share Info Settings Undo Redo 'Select All' 'Keyboard Modes' WordStyles 'Language switch key'; do
 taptext "$LABEL"
done
taptext Back
shot overflow_initial
read -r SX SY < <(coords overflow_initial 'Text Editor')
read -r HX HY < <(coords overflow_initial 'Voice Input')
adb shell input motionevent DOWN "$SX" "$SY";sleep 0.8
for I in $(seq 1 12); do
 X=$((SX+(HX-SX)*I/12));Y=$((SY+(HY-SY)*I/12));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
done
adb shell input motionevent UP "$HX" "$HY";sleep 1
shot overflow_reordered
# Hidden stays last inside the scrolling grid; this setup must bring it into view.
read -r SX SY < <(coords overflow_reordered 'Text Editor')
read -r HX HY < <(coords overflow_reordered Hidden)
adb shell input motionevent DOWN "$SX" "$SY";sleep 0.8
for I in $(seq 1 12); do
 X=$((SX+(HX-SX)*I/12));Y=$((SY+(HY-SY)*I/12));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
done
adb shell input motionevent UP "$HX" "$HY";sleep 1
adb shell input keyevent KEYCODE_BACK;sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null;sleep 2
shot overflow_hidden_reopened
# A real box tile crosses two rows, then crosses up into the actual toolbar.
dragtext() {
 shot vertical_current
 read -r SX SY < <(coords vertical_current "$1")
 read -r HX HY < <(coords vertical_current "$2")
 adb shell input motionevent DOWN "$SX" "$SY";sleep 0.8
 for I in $(seq 1 18); do
  X=$((SX+(HX-SX)*I/18));Y=$((SY+(HY-SY)*I/18));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
 done
 adb shell input motionevent UP "$HX" "$HY";sleep 1
}
dragtext GIF WordStyles
shot box_two_rows_down
dragtext GIF 'Voice Input'
shot box_to_bar
# Restore every remaining hidden action, producing twenty-four visible actions.
taptext Hidden
for LABEL in 'Debug Info' 'Copy to Clipboard' 'Cut to Clipboard' 'Paste from Clipboard' 'Arrow Up' 'Arrow Down' 'Arrow Right' 'Text Editor'; do
 taptext "$LABEL"
done
taptext Back
shot true_overflow_initial
# Hold a real box drag near the grid's bottom edge until its last Hidden target scrolls in.
read -r SX SY < <(coords true_overflow_initial Clipboard)
read -r EX EY < <(coords true_overflow_initial WordStyles)
# The currently rendered bottom tile's lower edge is the scroll zone, not a guessed screen point.
EY=$((EY+28))
adb shell input motionevent DOWN "$SX" "$SY";sleep 0.8
for I in $(seq 1 18); do
 X=$((SX+(EX-SX)*I/18));Y=$((SY+(EY-SY)*I/18));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
done
sleep 3
shot true_overflow_scrolled
read -r HX HY < <(coords true_overflow_scrolled Hidden)
adb shell input motionevent MOVE "$HX" "$HY";sleep 0.3
adb shell input motionevent UP "$HX" "$HY";sleep 1
adb shell input keyevent KEYCODE_BACK;sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null;sleep 2
shot true_overflow_hidden_reopened
echo 'FOCUSED DIRECT ACTIONS CAPTURE COMPLETE: inspect pixels before persistence verdict'
adb logcat -d -s AndroidRuntime:E | tail -60
