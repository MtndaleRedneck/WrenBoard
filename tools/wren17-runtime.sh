#!/usr/bin/env bash
# Isolated fixture device only. No personal accounts or chat data.
set -euo pipefail
APK=$1
PKG=com.wrenboard.keyboard
OUT=runtime17
mkdir -p "$OUT"
adb shell wm size 1080x2316
adb shell wm density 420
adb install -r -t "$APK"
adb install -r tools/ime-target/out/imetarget.apk
IME=$(adb shell ime list -a -s | tr -d '\r' | grep "^$PKG/" | head -1)
test -n "$IME"
adb shell ime enable "$IME"
adb shell ime set "$IME"
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
for action in more emoji clipboard_history gif; do
 adb shell am broadcast -a com.wrenboard.keyboard.WBDBG -p "$PKG" --es cmd "open:$action" >/dev/null
 sleep 2; shot "panel_$action"
 adb shell input keyevent KEYCODE_BACK; sleep 1; shot "back_$action"
done
# Stream synthetic screenshot bytes into job logs. No paid artifact or cache storage.
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
