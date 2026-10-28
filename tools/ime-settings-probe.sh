#!/usr/bin/env bash
# Exploratory capture of the WrenBoard settings screens (toolbar/action editor path).
# Registers the IME with `ime enable/set` (diagnostic, setup is tested elsewhere), launches the app
# launcher activity, then walks settings by node text, saving screenshot + uiautomator XML per screen.
set -u
APK="$1"; PKG=com.wrenboard.keyboard
D=shots/settings; mkdir -p "$D"; R="$D/results.txt"; : > "$R"
note() { echo "$1" | tee -a "$R"; }
shot() { adb exec-out screencap -p > "$D/$1.png"; adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb exec-out cat /sdcard/u.xml > "$D/$1.xml" 2>/dev/null; note "shot $1"; }
adb install -r "$APK" >> "$R" 2>&1 || { note "install failed"; exit 1; }
IME=""
for i in $(seq 1 30); do IME=$(adb shell ime list -a -s 2>/dev/null | tr -d '\r' | grep "^$PKG/" | head -1); [ -n "$IME" ] && break; sleep 2; done
note "IME=$IME"
adb shell ime enable "$IME" >> "$R" 2>&1; adb shell ime set "$IME" >> "$R" 2>&1
dismiss_anr() {
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  if adb exec-out cat /sdcard/u.xml | grep -q "isn't responding"; then
    line=$(adb exec-out cat /sdcard/u.xml | sed 's/>/>\n/g' | sed -n 's/.*text="Wait".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' | head -1)
    note "ANR dialog present, tapping Wait: $line"
    [ -n "$line" ] && { set -- $line; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2; }
  fi
}
sleep 20; dismiss_anr; dismiss_anr
adb shell input keyevent KEYCODE_HOME; sleep 2
adb shell am start -n "$PKG/org.futo.inputmethod.latin.uix.settings.SettingsActivity" >> "$R" 2>&1; sleep 6
dismiss_anr
shot s0_home
texts() { sed 's/>/>\n/g' "$D/$1.xml" | sed -n 's/.*text="\([^"]\+\)".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1|\2 \3 \4 \5/p'; }
note "TOP ACTIVITY:"; adb shell dumpsys activity activities | grep -m1 topResumedActivity | tee -a "$R"
note "TEXTS[s0_home]:"; texts s0_home | tee -a "$R"
# exploratory: tap each text node of s0 (max 8 below y=250), shot, back
i=1
texts s0_home | awk -F'|' 'NR>0' | head -12 | while IFS='|' read -r t b; do
  set -- $b; cy=$(( ($2+$4)/2 )); [ "$cy" -lt 250 ] && continue
  adb shell input tap $(( ($1+$3)/2 )) $cy; sleep 2
  nm="s${i}_$(echo "$t" | tr -c 'A-Za-z0-9' _ | cut -c1-24)"
  shot "$nm"; note "TAPPED '$t' -> $nm; TEXTS:"; texts "$nm" | tee -a "$R"
  adb shell input keyevent KEYCODE_BACK; sleep 2
  i=$((i+1)); [ $i -gt 8 ] && break
done
note "== done =="
