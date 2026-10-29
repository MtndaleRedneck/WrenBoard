#!/usr/bin/env bash
# Layout screenshots of the PRODUCTION Voice Input composable at controlled levels, from the debug-only host activity.
# Proof tier: compiled production composable, controlled magnitude. NOT a microphone or end-to-end device test.
set -u
APK="$1"; PKG=com.wrenboard.keyboard; ACT="$PKG/org.futo.inputmethod.latin.debugpreview.VoicePreviewActivity"
mkdir -p shots; R=shots/results.txt; : > "$R"
note() { echo "$*" | tee -a "$R"; }
note "== voice preview (debug host, production InnerRecognize) =="
adb install -r "$APK" 2>&1 | tail -1 | tee -a "$R"
note "device density: $(adb shell wm density | tr -d '\r') size: $(adb shell wm size | tr -d '\r')"
for lv in 0 0.5 1.0; do
  for st in NOT_TALKED_YET TALKING; do
    adb shell am force-stop $PKG; sleep 1
    adb shell am start -W -n "$ACT" --ef level $lv --es state $st >/dev/null 2>&1; sleep 4
    f="shots/vp_${st}_${lv}.png"; adb exec-out screencap -p > "$f"
    note "shot $f ($(stat -c %s "$f") bytes); focus: $(adb shell dumpsys window | tr -d '\r' | grep mCurrentFocus | head -1 | tr -s ' ')"
  done
done
note "FATAL count: $(adb logcat -d 2>/dev/null | grep -c 'FATAL EXCEPTION')"
