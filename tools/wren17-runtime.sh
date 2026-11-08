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
# Inspect temporal navigation with ordinary Android animations enabled, not just settled frames.
# Each 4-second clip starts on home, opens a real row and returns with system Back.
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
for TITLE in Themes Languages Typing "Layout & keys" FUTO "Rich input"; do
 openpage home
 python3 - "$OUT/home.xml" "$TITLE" > "$OUT/transition-coordinates" <<'PYTRANSCOORD'
import sys,re,xml.etree.ElementTree as E
for n in E.parse(sys.argv[1]).iter('node'):
 if n.get('text')==sys.argv[2]:
  a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')));print((a+c)//2,(b+d)//2);break
PYTRANSCOORD
 read -r TX TY < "$OUT/transition-coordinates"
 adb shell screenrecord --time-limit 4 /sdcard/w17-transition.mp4 > "$OUT/recording-${TITLE// /_}.log" 2>&1 &
 RECORD_PID=$!
 sleep 0.5
 adb shell input tap "$TX" "$TY"
 sleep 0.8
 adb shell input keyevent KEYCODE_BACK
 wait "$RECORD_PID"
 adb pull /sdcard/w17-transition.mp4 "$OUT/transition-${TITLE// /_}.mp4" >/dev/null
 ffmpeg -y -loglevel error -i "$OUT/transition-${TITLE// /_}.mp4" -vf 'scale=540:-2' -c:v libx264 -preset fast -crf 27 -an -movflags +faststart "$OUT/review-transition-${TITLE// /_}.mp4"
 # Export a contact sheet at8frames/sec so overlapping or stale pages can be inspected.
 ffmpeg -y -loglevel error -i "$OUT/transition-${TITLE// /_}.mp4" -vf 'fps=8,scale=270:-1,tile=8x4' -frames:v 1 "$OUT/transition_${TITLE// /_}.png"
done

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
# The Compose canvas label is absent from this emulator's accessibility dump.
# Measured on the inspected1080x2316 runtime frame: first More tile, centre150,1600.
shot nested_more_before
NX=150; NY=1600
adb shell input tap "$NX" "$NY"; sleep 2; shot nested_clipboard
adb shell input keyevent KEYCODE_BACK; sleep 2; shot nested_back_more
adb logcat -d -s WBDBG:I > "$OUT/nested-back.txt"
cat "$OUT/nested-back.txt"
echo 'Nested Back awaits visual verification of nested_more_before/nested_clipboard/nested_back_more'
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
python3 - "$OUT/cursor-report.txt" <<'PYCURSOR' || REGRESSION_FAILED=1
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
python3 - "$OUT/delete-report.txt" <<'PYDELETE' || REGRESSION_FAILED=1
import re,sys
s=open(sys.argv[1]).read();f=re.findall(r'FIELD=(.*)',s)[-1]
assert len(f)<11,('backspace swipe did not delete',f)
print('PASS backspace swipe deletes text',repr(f))
PYDELETE
clear_fixture
# Actual multi-segment swipe word. The path comes from current keyboard geometry.
python3 - "$OUT/gesture-geometry.txt" > "$OUT/swipe-cat.sh" <<'PYPATH'
import re,sys
keys={}
for l in open(sys.argv[1]):
 if 'KEY ' not in l:continue
 d=dict(re.findall(r'(\w+)=([^ ]+)',l.strip()))
 if 'label' in d:keys[d['label'].lower()]=(int(d['cx']),int(d['cy']))
p=[keys[c] for c in 'cat']
print('input motionevent DOWN %d %d'%p[0]);print('sleep 0.08')
for a,b in zip(p,p[1:]):
 for k in range(1,5):
  x=round(a[0]+(b[0]-a[0])*k/4);y=round(a[1]+(b[1]-a[1])*k/4)
  print('input motionevent MOVE %d %d'%(x,y))
print('sleep 0.08');print('input motionevent UP %d %d'%p[-1])
PYPATH
adb push "$OUT/swipe-cat.sh" /data/local/tmp/w17-swipe.sh >/dev/null
adb shell sh /data/local/tmp/w17-swipe.sh; sleep 3
report_fixture > "$OUT/swipe-report.txt"; shot swipe_cat
python3 - "$OUT/swipe-report.txt" <<'PYSWIPE' || REGRESSION_FAILED=1
import re,sys
s=open(sys.argv[1]).read();f=re.findall(r'FIELD=(.*)',s)[-1]
assert f.strip().lower()=='cat',('controlled cat swipe expected cat',f)
print('PASS controlled swipe word cat',repr(f))
PYSWIPE

# Real installed-IME taps, with sentence capitalization disabled in the synthetic editor.
clear_fixture
adb shell am broadcast -a com.wrenboard.imetarget.CAPSENT -p com.wrenboard.imetarget --ez on false >/dev/null
sleep 1
refresh_keys() { adb logcat -c; adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd geom >/dev/null; sleep 0.4; adb logcat -d -s WBDBG:I > "$OUT/caps-geometry.txt"; }
tap_code() { refresh_keys; read -r AX AY < <(python3 tools/wb12_kv.py "$OUT/caps-geometry.txt" xy KEY code "$1"); adb shell input tap "$AX" "$AY"; sleep 0.4; }
tap_letter() { refresh_keys; read -r AX AY < <(python3 tools/wb12_kv.py "$OUT/caps-geometry.txt" xy KEY label "$1"); adb shell input tap "$AX" "$AY"; sleep 0.4; }
# Begin lowercase, then one-shot uppercase must revert after exactly one letter.
shot caps_lower
tap_code -1; shot caps_oneshot
tap_letter a; tap_letter b
report_fixture > "$OUT/caps-oneshot-report.txt"
python3 - "$OUT/caps-oneshot-report.txt" <<'PYONESHOT' || REGRESSION_FAILED=1
import re,sys
f=re.findall(r'FIELD=(.*)',open(sys.argv[1]).read())[-1]
assert f=='Ab',('one-shot should produce Ab',f)
print('PASS actual UI one-shot reverts after one letter',repr(f))
PYONESHOT
clear_fixture
tap_code -1; sleep 1; tap_code -1; shot caps_locked
tap_letter a; tap_code 32; tap_letter b
# Symbols and back must preserve lock.
tap_code -3; shot caps_symbols; tap_code -3; shot caps_after_symbols
tap_letter c; tap_code -1; shot caps_unlocked; tap_letter d
report_fixture > "$OUT/caps-cycle-report.txt"
python3 - "$OUT/caps-cycle-report.txt" <<'PYCAPSCYCLE' || REGRESSION_FAILED=1
import re,sys
f=re.findall(r'FIELD=(.*)',open(sys.argv[1]).read())[-1]
assert f=='A BCd',('caps must survive words and symbols, then unlock',f)
print('PASS actual UI caps cycle across words and symbols',repr(f))
PYCAPSCYCLE
clear_fixture
# Search the actual emoji catalog for newer glyphs, with system/compat diagnostics.
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:emoji >/dev/null
sleep 1
adb shell input tap 350 950
for LETTER in s h a k i n g; do tap_letter "$LETTER"; done
sleep 2; shot emoji_shaking_search
adb logcat -d -s WBGLYPH:D > "$OUT/glyph-report.txt"
adb shell input keyevent KEYCODE_BACK; sleep 1

# Only synthetic GIF query terms; the existing test app key is injected at build time.
adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd open:gif >/dev/null
sleep 8
shot gif_keyed_recents
adb shell input tap 350 950
for LETTER in c a t; do tap_letter "$LETTER"; done
tap_code 10
sleep 8
shot gif_keyed_cat_results
dump gif_keyed_cat_results
adb logcat -d -s WBDBG:I > "$OUT/gif-positions.txt"
python3 - "$OUT/gif_keyed_cat_results.xml" <<'PYGIFUI'
import sys,xml.etree.ElementTree as E
r=E.parse(sys.argv[1])
print('GIF UI',[(n.get('text'),n.get('content-desc')) for n in r.iter('node') if n.get('text') or n.get('content-desc')])
PYGIFUI
python3 - "$OUT/gif-positions.txt" > "$OUT/gif-first-cell" <<'PYGIFCELL'
import re,sys
m=re.findall(r'GIFCELL i=0 cx=(\d+) cy=(\d+) w=(\d+) h=(\d+)',open(sys.argv[1]).read())
assert m,'no GIF tile geometry; not a handoff pass'
x,y,w,h=map(int,m[-1]);print(x,y)
PYGIFCELL
read -r GX GY < "$OUT/gif-first-cell"
adb shell input tap "$GX" "$GY"; sleep 10
adb logcat -d -s IMETARGET:I > "$OUT/gif-handoff-report.txt"
python3 - "$OUT/gif-handoff-report.txt" <<'PYGIFCHECK' || REGRESSION_FAILED=1
import re,sys
s=open(sys.argv[1]).read();m=re.findall(r'CONTENT= mime=image/gif bytes=(\d+) sig=GIF',s)
assert m and int(m[-1])>100,'actual image/gif handoff did not drain complete media'
print('PASS actual GIF-only editor received GIF signature and bytes',m[-1])
PYGIFCHECK
shot gif_after_handoff
adb shell input keyevent KEYCODE_BACK; sleep 1

# Instrumentation uses the installed runner reported by PackageManager, not a guessed package ID.
TEST_APK=w17-tests.apk
test -n "$TEST_APK"
adb install -r -t "$TEST_APK"
RUNNER=$(adb shell pm list instrumentation | tr -d '\r' | grep "target=$PKG)" | sed -n 's/^instrumentation:\([^ ]*\).*/\1/p' | head -1)
test -n "$RUNNER"
adb shell am instrument -w -r -e class org.futo.inputmethod.keyboard.internal.KeyboardStateShiftCycleTests "$RUNNER" | tee "$OUT/instrumentation.txt"
grep -Eq 'OK \([0-9]+ tests?\)' "$OUT/instrumentation.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=Process crashed' "$OUT/instrumentation.txt"
test "${REGRESSION_FAILED:-0}" = 0
