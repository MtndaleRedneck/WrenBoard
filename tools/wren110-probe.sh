#!/usr/bin/env bash
# WrenBoard 1.10 diagnostics probes: back-disposition pipeline + updater lifecycle.
# Invoked by the android-emulator-runner as ONE script line. The pinned runner
# (reactivecircus/android-emulator-runner@a421e43) splits multi-line script inputs into
# independent `sh -c <line>` calls (src/script-parser.ts: split on newlines, trim, drop
# comments/blanks; src/main.ts: per-line exec), so shell state and multi-line constructs in a
# workflow YAML block do not survive. All probe state therefore lives in this file.
set -euo pipefail
PKG=com.wrenboard.keyboard

# Full-fidelity captures run on ANY exit (I2 capture-gap correction): an early set -e exit
# (install/staging/IME/instrument) must not lose the captures - the known startup ANR evidence
# lives in the early device state. Trap is armed before the first device action; the original
# exit code is preserved. Unconditional: no tag filters, no grep caps, ANR traces if any exist.
captures() {
  adb logcat -d > probe-logcat-full.txt 2>&1 || true
  adb shell dumpsys dropbox --print > probe-dropbox-full.txt 2>&1 || true
  adb shell ls -la /data/anr/ > probe-anr-list.txt 2>&1 || true
  adb pull /data/anr probe-anr || true
  adb pull "/sdcard/Android/data/$PKG/files" probe-files || true
}
trap 'ec=$?; captures; exit $ec' EXIT

adb root || true
adb wait-for-device
adb shell wm size 1080x2316
adb shell wm density 420
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton || true
adb shell settings put secure navigation_mode 0 || true
adb install -r -t w17-release.apk
adb install -r -t w17-rel-tests.apk
adb install -r tools/ime-target/out/imetarget.apk

# Isolated-emulator test setting (not a real account change): the production ON_RESUME observer
# only consumes pendingApk when canRequestPackageInstalls() is true (UpdateScreen.kt). Without
# this grant the observer path is never reached and the probe would misread a precondition as
# product behavior. Logged before and after.
adb shell appops get "$PKG" REQUEST_INSTALL_PACKAGES || true
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
adb shell appops get "$PKG" REQUEST_INSTALL_PACKAGES

# Stage the same-tree vc-12 update fixture into the app's updates cache dir. The CI release
# fixture carries the CI-only debuggable patch, so run-as works without root; adb root chmod
# fallback for images where run-as refuses. T3b asserts the fixture exists (fail-closed), so
# a staging failure surfaces as a probe failure, not a silent skip.
adb push w17-vc12.apk /data/local/tmp/w17-vc12.apk
if adb shell run-as "$PKG" sh -c 'mkdir -p cache/updates && cp /data/local/tmp/w17-vc12.apk cache/updates/w17-vc12-fixture.apk && ls -la cache/updates/w17-vc12-fixture.apk'; then
  echo "fixture staged via run-as"
else
  echo "run-as staging failed; trying adb root fallback"
  adb root || true
  sleep 2
  adb wait-for-device
  adb shell "mkdir -p /data/data/$PKG/cache/updates && cp /data/local/tmp/w17-vc12.apk /data/data/$PKG/cache/updates/w17-vc12-fixture.apk && chmod 666 /data/data/$PKG/cache/updates/w17-vc12-fixture.apk"
  echo "fixture staged via adb root fallback"
fi

IME=""
for attempt in $(seq 1 20); do
  IME=$(adb shell ime list -a -s | tr -d '\r' | grep "^$PKG/" | head -1 || true)
  [ -n "$IME" ] && break
  sleep 2
done
test -n "$IME"
adb shell ime enable "$IME"
adb shell ime set "$IME"
adb shell am start -W -n com.wrenboard.imetarget/.TestInput >/dev/null
sleep 2
adb shell input tap 400 500
sleep 5
TESTPKG=$(adb shell pm list instrumentation | tr -d '\r' | grep "target=$PKG" | sed -E 's/^instrumentation:([^ ]+).*/\1/' | head -1)
test -n "$TESTPKG"

# am instrument's process exit code is not a reliable verdict; the terminal instrumentation
# result summary is asserted below in addition to the exit code.
set +e
timeout 600 adb shell am instrument -w -r -e class org.futo.inputmethod.latin.Wren110BackDispositionProbeTest,org.futo.inputmethod.latin.Wren110UpdaterProbeTest "$TESTPKG" | tee probe-out.txt
PROBE_EXIT=${PIPESTATUS[0]}
set -e
echo "PROBE_EXIT=$PROBE_EXIT" | tee probe-exit.txt

# Expected: 1 disposition test + 5 updater case tests (T1, T2, T3 tampered, T3b valid, T4).
# Per-case JUnit3 methods keep case counts distinguishable: a case that dies mid-run shortens
# the summary and fails this assertion instead of passing silently. CR/framing from the adb
# stream is normalized by stripping \r; the match stays exact (anchored, exact count).
RESULT_LINE=$(tr -d '\r' < probe-out.txt | grep -E '^OK \(|^FAILURES!!!|^Tests run:' || true)
echo "instrumentation summary: $RESULT_LINE"
RESULT_OK=0
if tr -d '\r' < probe-out.txt | grep -qE '^OK \(6 tests\)[[:space:]]*$'; then RESULT_OK=1; fi
echo "RESULT_OK=$RESULT_OK" | tee -a probe-exit.txt

test "$RESULT_OK" = 1
test "$PROBE_EXIT" = 0
