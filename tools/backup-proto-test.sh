#!/usr/bin/env bash
# Mechanics only: Android Auto Backup include rules + bmgr LOCAL transport on an emulator. NOT Google cloud, NOT WrenBoard.
set -u
APK="$1"; P=com.wrenboard.bkproto; D=/data/data/$P/files; mkdir -p out; R=out/results.txt; : > "$R"
note() { echo "$*" | tee -a "$R"; }
# adb root/unroot restarts adbd; the emulator can report "device offline" for a while afterwards. Wait until a shell command really works (up to ~60 s).
adb_ready() { local i; [ -n "$1" ] && adb "$1" >/dev/null 2>&1; sleep 3; adb wait-for-device 2>/dev/null; for i in $(seq 1 30); do adb shell true >/dev/null 2>&1 && return 0; sleep 2; done; return 1; }
adb_ready root
note "api: $(adb shell getprop ro.build.version.sdk | tr -d '\r') image: $(adb shell getprop ro.product.model | tr -d '\r')"
note "transports: $(adb shell bmgr list transports | tr -d '\r' | tr '\n' ' ')"
adb shell bmgr enable true 2>&1 | tee -a "$R"
LT=$(adb shell bmgr list transports | tr -d '\r' | grep -o 'com.android.localtransport[^ ]*' | head -1)
[ -n "$LT" ] || { note "BLOCKED: no local transport on this image"; exit 1; }
adb shell bmgr transport "$LT" 2>&1 | tee -a "$R"
adb shell settings put secure backup_auto_restore 1 >/dev/null 2>&1
adb uninstall $P >/dev/null 2>&1; adb install -r "$APK" 2>&1 | tail -1 | tee -a "$R"
adb shell am start -n $P/.Main >/dev/null 2>&1; sleep 3
UID_A=$(adb shell stat -c %u /data/data/$P | tr -d '\r')
seed() { # seed <snapshot-KB>
  adb shell "mkdir -p $D/snapshot && head -c $(( $1 * 1024 )) /dev/urandom > $D/snapshot/snap.bin && echo -n TEMP-CLIPBOARD > $D/clip_history.json && echo -n OTHER > $D/other.txt && chown -R $UID_A:$UID_A $D"
  SNAPMD=$(adb shell md5sum $D/snapshot/snap.bin | cut -d' ' -f1 | tr -d '\r'); }
run_case() { # run_case <label> <KB>
  note "=== case $1: snapshot ${2} KB ==="; seed "$2"
  adb logcat -c
  OUT=$(adb shell bmgr backupnow $P 2>&1 | tr -d '\r'); note "backupnow: $(echo "$OUT" | tr '\n' ' ' | cut -c1-300)"
  adb logcat -d 2>/dev/null | tr -d '\r' | grep -iE "FullBackup|BackupManager|quota|LocalTransport|bkproto|PFTBT|BackupTransport" | cut -c1-260 > "out/log_$1.txt"
  note "full filtered backup log for this case: out/log_$1.txt ($(wc -l < out/log_$1.txt) lines); quota/size lines: $(grep -iE 'quota|exceed|too large|rejected' out/log_$1.txt | head -3 | tr '\n' '|' | cut -c1-300)"
  TOKEN=$(adb shell bmgr list sets 2>/dev/null | tr -d '\r' | awk '{print $1}' | head -1); note "restore set token: $TOKEN"
  adb uninstall $P >/dev/null 2>&1; note "after uninstall dir present: $(adb shell "[ -e /data/data/$P ] && echo yes || echo no" | tr -d '\r')"
  adb install -r "$APK" 2>&1 | tail -1 | tee -a "$R"; sleep 3
  note "after reinstall (auto-restore setting on): snap present=$(adb shell "[ -e $D/snapshot/snap.bin ] && echo yes || echo no" | tr -d '\r')"
  if [ "$(adb shell "[ -e $D/snapshot/snap.bin ] && echo yes || echo no" | tr -d '\r')" = no ] && [ -n "$TOKEN" ]; then
    note "explicit: bmgr restore $TOKEN $P -> $(adb shell bmgr restore $TOKEN $P 2>&1 | tr -d '\r' | tr '\n' ' ' | cut -c1-240)"; sleep 4
  fi
  GOT=$(adb shell md5sum $D/snapshot/snap.bin 2>/dev/null | cut -d' ' -f1 | tr -d '\r')
  [ -n "$GOT" ] && [ "$GOT" = "$SNAPMD" ] && note "RESULT $1: snapshot restored byte-identical" || note "RESULT $1: snapshot NOT restored (want ${SNAPMD:0:8} got '${GOT:0:8}')"
  note "RESULT $1: excluded clip_history.json present after restore=$(adb shell "[ -e $D/clip_history.json ] && echo YES-UNEXPECTED || echo no" | tr -d '\r'); other.txt present=$(adb shell "[ -e $D/other.txt ] && echo YES-UNEXPECTED || echo no" | tr -d '\r')"
  adb shell am start -n $P/.Main >/dev/null 2>&1; sleep 2
}
# order varied on purpose: the first run showed mid (12 MB) not captured after a small case; repeat mid first and again later to separate size from order
run_case mid_first 12288
run_case small 512
run_case mid_again 12288
run_case under_quota 24000
run_case mid_after_under 12288
run_case over_quota 27000
note "== done =="
