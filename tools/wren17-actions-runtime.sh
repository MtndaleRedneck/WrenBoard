#!/usr/bin/env bash
set -euo pipefail
APK=$1
PKG=com.wrenboard.keyboard
OUT=actions17
mkdir -p "$OUT"
export_screens() {
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
openpage() { adb shell input keyevent KEYCODE_HOME; adb shell am start -n "$PKG/org.futo.inputmethod.latin.uix.settings.SettingsActivity" -f 0x34000000 --es navDest "$1" >/dev/null; sleep 3; dump "$1"; shot "$1"; }

# Focused new action design check only. No swipe/old baseline/GIF/signing.
openpage themes
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
 python3 - "$OUT/$1.xml" "$2" <<'PYCOORD'
import sys,re,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('text','')==sys.argv[2]:
  a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));print((a+c)//2,(b+d)//2);break
else:raise SystemExit('Missing text '+sys.argv[2])
PYCOORD
}
taptext() { dump current; read -r X Y < <(coords current "$1"); adb shell input tap "$X" "$Y"; sleep 1; }
taptext Reorder
shot editor_initial
dump editor_initial
# Reorder the first visible action onto the fixed Hidden destination, using a true long-press drag.
read -r SX SY < <(coords editor_initial Emojis)
# Hidden count depends on the registry; derive its actual label instead of assuming12.
python3 - "$OUT/editor_initial.xml" > "$OUT/hidden-coords" <<'PYH'
import sys,re,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('text','').startswith('Hidden ('):
  a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));print((a+c)//2,(b+d)//2);break
else:raise SystemExit('Missing Hidden tile')
PYH
read -r HX HY < "$OUT/hidden-coords"
adb shell input motionevent DOWN "$SX" "$SY"; sleep 0.8
for I in $(seq 1 12); do
 X=$((SX+(HX-SX)*I/12));Y=$((SY+(HY-SY)*I/12));adb shell input motionevent MOVE "$X" "$Y";sleep 0.07
done
adb shell input motionevent UP "$HX" "$HY";sleep 1
shot after_drag_hidden
taptext Done
shot after_done
# Close editor and reopen: Done persists; unsaved draft must not overwrite it on disposal.
adb shell input keyevent KEYCODE_BACK;sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null;sleep 1
taptext Reorder
shot editor_reopened
# Open the Hidden tile using derived bounds, independent of its count.
dump reopened
python3 - "$OUT/reopened.xml" > "$OUT/open-hidden" <<'PYOPEN'
import sys,re,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('text','').startswith('Hidden ('):
  a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));print((a+c)//2,(b+d)//2);break
PYOPEN
read -r HX HY < "$OUT/open-hidden";adb shell input tap "$HX" "$HY";sleep 1
shot hidden_open
taptext Emojis
shot restored_emoji_draft
taptext Done
shot restored_saved
echo 'FOCUSED ACTIONS CAPTURE COMPLETE: inspect pixels and hierarchy before any drag/persistence verdict'
adb logcat -d -s AndroidRuntime:E | tail -60
