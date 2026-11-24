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

# Stage the same-tree vc-12 update fixture into the app's updates cache dir. No nested
# `sh -c`: adb joins host argv with spaces before the device shell, so an inner quoted
# payload would lose its grouping (I2 staging hypothesis). Each run-as call is one simple
# command. adb-root fallback chowns to the app's own uid:gid (from the existing cache dir),
# so a root-created updates dir never stays app-unwritable.
adb push w17-vc12.apk /data/local/tmp/w17-vc12.apk
STAGED=""
if adb shell run-as "$PKG" mkdir -p cache/updates && \
   adb shell run-as "$PKG" cp /data/local/tmp/w17-vc12.apk cache/updates/w17-vc12-fixture.apk; then
  STAGED=run-as
else
  echo "run-as staging failed; trying adb root fallback"
  adb root || true
  sleep 2
  adb wait-for-device
  adb shell "mkdir -p /data/data/$PKG/cache/updates"
  adb shell "cp /data/local/tmp/w17-vc12.apk /data/data/$PKG/cache/updates/w17-vc12-fixture.apk"
  APPOWN=$(adb shell stat -c '%u:%g' "/data/data/$PKG/cache" | tr -d '\r')
  echo "app uid:gid from cache dir: $APPOWN"
  adb shell "chown $APPOWN /data/data/$PKG/cache/updates /data/data/$PKG/cache/updates/w17-vc12-fixture.apk"
  STAGED=root
fi
echo "fixture staged via $STAGED"

# Actual-device verification before ANY T3/T3b interpretation (a stub is not proof). The check
# matches the staging route: run-as route gets a real write test as the app user; the root
# route (run-as refused image) verifies numeric ownership of file AND directory against the
# app's own uid:gid, so a root-created updates dir never reads as app-unwritable.
if [ "$STAGED" = "run-as" ]; then
  adb shell run-as "$PKG" ls -la cache/updates/
  if adb shell run-as "$PKG" touch cache/updates/.wtest && adb shell run-as "$PKG" rm cache/updates/.wtest; then
    echo "updates cache writability as app user: OK (run-as)"
  else
    echo "updates cache writability as app user: FAILED (run-as) - T3b fixture precondition broken; classify any T3 failure as harness, not product"
    exit 1
  fi
else
  adb shell ls -la "/data/data/$PKG/cache/updates/"
  FIXOWN=$(adb shell stat -c '%u:%g' "/data/data/$PKG/cache/updates/w17-vc12-fixture.apk" | tr -d '\r')
  DIROWN=$(adb shell stat -c '%u:%g' "/data/data/$PKG/cache/updates" | tr -d '\r')
  echo "ownership: fixture=$FIXOWN dir=$DIROWN app=$APPOWN"
  if [ "$FIXOWN" = "$APPOWN" ] && [ "$DIROWN" = "$APPOWN" ]; then
    echo "updates cache ownership verified (root route)"
  else
    echo "updates cache ownership MISMATCH (root route) - T3b fixture precondition broken; classify any T3 failure as harness, not product"
    exit 1
  fi
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
# Deterministic race fix (run 45327274226): the emulator has network, so the app's real
# periodic/on-open update check fetches the live 1.9.0 feed a few seconds after the WBDBG seed
# and OVERWRITES last_update_check_result (1.9.0 < fixture 1.10.0 -> up-to-date -> observer
# never registers). A failed check does NOT touch the stored result (UpdateChecking
# saveUpdateResult failure branch), so cutting network around instrumentation makes the seed
# hold deterministically. Re-enabled right after the run.
adb shell svc wifi disable || true
adb shell svc data disable || true
timeout 600 adb shell am instrument -w -r -e class org.futo.inputmethod.latin.Wren110BackDispositionProbeTest,org.futo.inputmethod.latin.Wren110RetentionProbeTest,org.futo.inputmethod.latin.Wren110UpdaterProbeTest "$TESTPKG" | tee probe-out.txt
PROBE_EXIT=${PIPESTATUS[0]}
set -e
adb shell svc wifi enable || true
adb shell svc data enable || true
echo "PROBE_EXIT=$PROBE_EXIT" | tee probe-exit.txt

# Expected: 1 disposition pipeline test + 5 updater case tests (T1, T2, T3 tampered, T3b valid, T4)
# plus 2 JUnit3 ServiceTestCase setUp smoke tests, 8 total. The OK(N) count must match the
# actual runner count or green runs fail the gate (run 45331743846: OK (8 tests) vs gate 6).
# Per-case JUnit3 methods keep case counts distinguishable: a case that dies mid-run shortens
# the summary and fails this assertion instead of passing silently. CR/framing from the adb
# stream is normalized by stripping \r; the match stays exact (anchored, exact count).
RESULT_LINE=$(tr -d '\r' < probe-out.txt | grep -E '^OK \(|^FAILURES!!!|^Tests run:' || true)
echo "instrumentation summary: $RESULT_LINE"
RESULT_OK=0
if tr -d '\r' < probe-out.txt | grep -qE '^OK \(8 tests\)[[:space:]]*$'; then RESULT_OK=1; fi
echo "RESULT_OK=$RESULT_OK" | tee -a probe-exit.txt

test "$RESULT_OK" = 1
test "$PROBE_EXIT" = 0
