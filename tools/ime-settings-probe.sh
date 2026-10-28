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
for dest in home actionEdit keyboardAndTyping; do
  adb shell am start -n "$PKG/org.futo.inputmethod.latin.uix.settings.SettingsActivity" --es navDest "$dest" >> "$R" 2>&1; sleep 5
  nm="n_$dest"; shot "$nm"; note "TEXTS[$nm]:"; texts "$nm" | tee -a "$R"
  note "DESCS[$nm]:"; sed 's/>/>\n/g' "$D/$nm.xml" | sed -n 's/.*content-desc="\([^"]\+\)".*bounds="\(\[[0-9,]*\]\[[0-9,]*\]\)".*/\1 \2/p' | tee -a "$R"
  adb shell input swipe 720 2000 720 700 400; sleep 2; shot "${nm}_scrolled"; note "TEXTS[${nm}_scrolled]:"; texts "${nm}_scrolled" | tee -a "$R"
done
note "== done =="
