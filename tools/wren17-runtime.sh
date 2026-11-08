#!/usr/bin/env bash
# Isolated fixture device only. No personal accounts or chat data.
set -euo pipefail
APK=$1
PKG=com.wrenboard.keyboard
OUT=runtime17
mkdir -p "$OUT"
export_screens() {
python3 - "$OUT" <<'PY'
from pathlib import Path
from PIL import Image
import sys,io,base64
for p in sorted(Path(sys.argv[1]).glob('*.png')):
 im=Image.open(p); im.thumbnail((540,1158)); b=io.BytesIO();im.convert('RGB').save(b,format='JPEG',quality=75)
 print('W17_IMAGE_BEGIN '+p.stem)
 text=base64.b64encode(b.getvalue()).decode()
 for i in range(0,len(text),4096):print('W17_DATA '+text[i:i+4096])
 print('W17_IMAGE_END '+p.stem)
PY
}
finish_evidence() {
 for report in "$OUT"/*report.txt "$OUT"/instrumentation.txt; do
  [ ! -f "$report" ] || { echo "REPORT $report"; cat "$report"; }
 done
 export_screens
}
trap finish_evidence EXIT
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
dump() { adb shell uiautomator dump /sdcard/w17.xml >/dev/null; adb exec-out cat /sdcard/w17.xml > "$OUT/$1.xml"; }
openpage() { adb shell input keyevent KEYCODE_HOME; adb shell am start -n "$PKG/org.futo.inputmethod.latin.uix.settings.SettingsActivity" -f 0x34000000 --es navDest "$1" >/dev/null; sleep 3; shot "$1"; dump "$1"; }
for page in home themes wren_typing wren_emoji wren_rich_input wren_layout wren_sound wren_backup wren_advanced wren_futo; do
 openpage "$page"
 echo "PAGE $page"
 python3 - "$OUT/$page.xml" <<'PY'
import sys,xml.etree.ElementTree as E
r=E.parse(sys.argv[1]).getroot()
print(' | '.join(n.get('text','') for n in r.iter('node') if n.get('text')))
PY
done
# Real navigation into Themes, then Back, rather than deep-link-only evidence.
openpage home
python3 - "$OUT/home.xml" > "$OUT/theme-coordinates" <<'PY'
import sys,re,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('text')=='Themes':
  a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));print((a+c)//2,(b+d)//2);break
PY
read -r X Y < "$OUT/theme-coordinates"
adb shell input tap "$X" "$Y"; sleep 2; shot themes_from_home; dump themes_from_home
adb shell input keyevent KEYCODE_BACK; sleep 2; shot themes_back_home; dump themes_back_home
# Keyboard and nested action panels in a synthetic blank editor.
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null; sleep 3
dump target
python3 - "$OUT/target.xml" > "$OUT/field-coordinates" <<'PY'
import sys,re,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('class')=='android.widget.EditText':
  a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));print((a+c)//2,(b+d)//2);break
PY
read -r X Y < "$OUT/field-coordinates"
adb shell input tap "$X" "$Y"; sleep 3; shot keyboard_empty
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd geom >/dev/null
sleep 1; adb logcat -d -s WBDBG:I > "$OUT/geometry.txt"
python3 tools/wb12_kv.py "$OUT/geometry.txt" xy KEY label . > "$OUT/period-coordinates"
read -r PX PY < "$OUT/period-coordinates"
adb shell input motionevent DOWN "$PX" "$PY"; sleep 0.8; shot period_hold
adb shell input motionevent UP "$PX" "$PY"; sleep 1
for action in more emoji clipboard_history gif; do
 adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd "open:$action" >/dev/null
 sleep 2; shot "panel_$action"
 adb shell input keyevent KEYCODE_BACK; sleep 1; shot "back_$action"
done

# Exercise a real More-grid tap, not the debug panel shortcut, to test its back stack.
adb logcat -c
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:more >/dev/null
sleep 2; adb logcat -d -s WBDBG:I > "$OUT/more-positions.txt"
dump more_nested
python3 - "$OUT/more_nested.xml" > "$OUT/nested-coordinates" <<'PYNEST'
import re,sys,xml.etree.ElementTree as E
nodes=[n for n in E.parse(sys.argv[1]).iter('node') if n.get('text')=='Clipboard' or n.get('content-desc')=='Clipboard']
assert nodes,'Clipboard tile absent from More UI'
a,b,c,d=map(int,re.findall(r'\d+',nodes[-1].get('bounds')))
print((a+c)//2,(b+d)//2)
PYNEST
read -r NX NY < "$OUT/nested-coordinates"
adb shell input tap "$NX" "$NY"; sleep 2; shot nested_clipboard
adb shell input keyevent KEYCODE_BACK; sleep 2; shot nested_back_more
adb logcat -d -s WBDBG:I > "$OUT/nested-back.txt"
cat "$OUT/nested-back.txt"
python3 - "$OUT/nested-back.txt" <<'PYBACK'
import re,sys
p=re.findall(r'WINDOW open (\S+)',open(sys.argv[1]).read())
assert p[-3:]==['more','clipboard_history','more'],('nested Back did not return to More',p)
print('PASS nested clipboard Back returns to More')
PYBACK
adb shell input keyevent KEYCODE_BACK; sleep 1; shot nested_back_keyboard

# Space cursor and backspace gesture regressions on synthetic text.
clear_fixture() { adb shell am broadcast -a com.wrenboard.imetarget.CLEAR -p com.wrenboard.imetarget >/dev/null; sleep 1; }
report_fixture() { adb shell am broadcast -a com.wrenboard.imetarget.REPORT -p com.wrenboard.imetarget >/dev/null; sleep 0.5; adb logcat -d -s IMETARGET:I | tr -d '\r'; }
clear_fixture
adb shell input text 'hello%sworld'; sleep 1
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd geom >/dev/null
sleep 1; adb logcat -d -s WBDBG:I > "$OUT/gesture-geometry.txt"
read -r SX SY < <(python3 tools/wb12_kv.py "$OUT/gesture-geometry.txt" xy KEY code 32)
adb shell input swipe "$SX" "$SY" "$((SX-180))" "$SY" 300
report_fixture > "$OUT/cursor-report.txt"; shot cursor_swipe
python3 - "$OUT/cursor-report.txt" <<'PYCURSOR'
import re,sys
s=open(sys.argv[1]).read();fields=re.findall(r'FIELD=(.*)',s);sel=re.findall(r'SELECTION=(\d+):(\d+)',s)
assert fields[-1]=='hello world',(fields[-1], 'cursor swipe must leave text unchanged')
assert sel and int(sel[-1][0])<11,('cursor did not move left',sel)
print('PASS space-bar swipe moves cursor without changing text',sel[-1])
PYCURSOR
clear_fixture
adb shell input text 'hello%sworld'; sleep 1
read -r DX DY < <(python3 tools/wb12_kv.py "$OUT/gesture-geometry.txt" xy KEY code -5)
adb shell input swipe "$DX" "$DY" "$((DX-350))" "$DY" 350
report_fixture > "$OUT/delete-report.txt"; shot backspace_swipe
python3 - "$OUT/delete-report.txt" <<'PYDELETE'
import re,sys
s=open(sys.argv[1]).read();f=re.findall(r'FIELD=(.*)',s)[-1]
assert len(f)<11,('backspace swipe did not delete',f)
print('PASS backspace swipe deletes text',repr(f))
PYDELETE
clear_fixture
# Actual multi-segment swipe word. The path comes from current keyboard geometry.
python3 - "$OUT/gesture-geometry.txt" > "$OUT/swipe-hello.sh" <<'PYPATH'
import re,sys
keys={}
for l in open(sys.argv[1]):
 if 'KEY ' not in l:continue
 d=dict(re.findall(r'(\w+)=([^ ]+)',l.strip()))
 if 'label' in d:keys[d['label'].lower()]=(int(d['cx']),int(d['cy']))
p=[keys[c] for c in 'helo']
print('input motionevent DOWN %d %d'%p[0])
for a,b in zip(p,p[1:]):
 for k in range(1,5):
  x=round(a[0]+(b[0]-a[0])*k/4);y=round(a[1]+(b[1]-a[1])*k/4)
  print('input motionevent MOVE %d %d'%(x,y))
print('input motionevent UP %d %d'%p[-1])
PYPATH
adb push "$OUT/swipe-hello.sh" /data/local/tmp/w17-swipe.sh >/dev/null
adb shell sh /data/local/tmp/w17-swipe.sh; sleep 3
report_fixture > "$OUT/swipe-report.txt"; shot swipe_hello
python3 - "$OUT/swipe-report.txt" <<'PYSWIPE'
import re,sys
s=open(sys.argv[1]).read();f=re.findall(r'FIELD=(.*)',s)[-1]
assert f.strip().lower()=='hello',('hello swipe expected hello',f)
print('PASS swipe word hello',repr(f))
PYSWIPE

# Instrumentation uses the installed runner reported by PackageManager, not a guessed package ID.
TEST_APK=w17-tests.apk
test -n "$TEST_APK"
adb install -r -t "$TEST_APK"
RUNNER=$(adb shell pm list instrumentation | tr -d '\r' | grep "target=$PKG)" | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p' | head -1)
test -n "$RUNNER"
adb shell am instrument -w -r -e class org.futo.inputmethod.keyboard.internal.KeyboardStateShiftCycleTests,org.futo.inputmethod.latin.ShiftModeTests "$RUNNER" | tee "$OUT/instrumentation.txt"
grep -Eq 'OK \([0-9]+ tests?\)' "$OUT/instrumentation.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=Process crashed' "$OUT/instrumentation.txt"
