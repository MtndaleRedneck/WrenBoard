#!/usr/bin/env bash
# Layout screenshots of the PRODUCTION Voice Input composable at controlled levels, from the debug-only host activity.
# Proof tier: compiled production composable, controlled magnitude. NOT a microphone or end-to-end device test.
set -u
BAD=0
APK="$1"; PKG=com.wrenboard.keyboard; ACT="$PKG/org.futo.inputmethod.latin.debugpreview.VoicePreviewActivity"
mkdir -p shots; R=shots/results.txt; : > "$R"
note() { echo "$*" | tee -a "$R"; }
note "== voice preview (debug host, production InnerRecognize) =="
adb install -r "$APK" 2>&1 | tail -1 | tee -a "$R"
note "device density: $(adb shell wm density | tr -d '\r') size: $(adb shell wm size | tr -d '\r')"
for lv in 0 0.5 1.0; do
  for st in NOT_TALKED_YET TALKING; do
    adb shell am force-stop $PKG; adb logcat -c; sleep 1
    adb shell am start -W -n "$ACT" --ef level $lv --es state $st >/dev/null 2>&1; sleep 4
    f="shots/vp_${st}_${lv}.png"; adb exec-out screencap -p > "$f"
    adb logcat -d -s AndroidRuntime:E ActivityManager:E > "shots/vp_${st}_${lv}.log" 2>&1
    case "$(adb shell dumpsys window | tr -d '\r' | grep mCurrentFocus | head -1)" in *VoicePreviewActivity*) ;; *) note "HOST-NOT-FOCUSED for $st $lv (frame invalid)"; BAD=1;; esac
    note "shot $f ($(stat -c %s "$f") bytes); focus: $(adb shell dumpsys window | tr -d '\r' | grep mCurrentFocus | head -1 | tr -s ' ')"
  done
done
F=$(cat shots/vp_*.log | grep -c "FATAL EXCEPTION"); note "FATAL count across launches: $F"
[ "$F" -eq 0 ] && [ "$BAD" -eq 0 ] && note "RESULT: all 6 launches focused the host, no FATAL (frames still need inspection)" || { note "RESULT: FAIL (host crashed or lost focus)"; exit 1; }
