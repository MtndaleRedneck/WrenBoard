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
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 5
shot s0_home
texts() { sed 's/>/>\n/g' "$D/$1.xml" | sed -n 's/.*text="\([^"]\+\)".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1|\2 \3 \4 \5/p'; }
for n in s0_home; do note "TEXTS[$n]:"; texts $n | tee -a "$R"; done
step=1
for want in "Typing" "Keyboard" "Toolbar|Actions|Action bar|Favorite" "Toolbar|Actions|Favorite|Pinned" ; do
  last=$(ls $D/*.xml | sort | tail -1 | xargs basename | sed 's/\.xml//')
  line=$(texts $last | grep -i -E "^($want)[^|]*\|" | head -1)
  if [ -z "$line" ]; then note "no node matching /$want/ on $last"; continue; fi
  set -- ${line#*|}; adb shell input tap $(( ($1+$3)/2 )) $(( ($2+$4)/2 )); sleep 2
  shot "s${step}_after_$(echo "${line%%|*}" | tr -c 'A-Za-z0-9' _)"
  note "TEXTS[s${step}]:"; texts "s${step}_after_$(echo "${line%%|*}" | tr -c 'A-Za-z0-9' _)" | tee -a "$R"
  step=$((step+1))
done
note "== done =="
