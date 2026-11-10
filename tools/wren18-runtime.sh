#!/usr/bin/env bash
set -euxo pipefail
APK=$1
TESTS=$2
PKG=com.wrenboard.keyboard
OUT=runtime18
mkdir -p "$OUT"
cleanup() {
 adb logcat -d -s AndroidRuntime:E WBDBG:I WB18_NAV:I > "$OUT/logcat.txt" || true
 adb shell dumpsys input_method > "$OUT/input-method.txt" || true
 adb pull "/sdcard/Android/data/$PKG/files/wren18-nav" "$OUT/" || true
}
trap cleanup EXIT
adb shell wm size 1080x2316
adb shell wm density 420
adb install -r -t "$APK"
adb install -r -t "$TESTS"
adb install -r tools/ime-target/out/imetarget.apk
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
IME=""
for attempt in $(seq 1 20); do
 IME=$(adb shell ime list -a -s | tr -d '\r' | grep "^$PKG/" | head -1 || true)
 [ -n "$IME" ] && break
 sleep 2
done
adb shell ime list -a -s
adb shell pm list packages | grep wrenboard
test -n "$IME"
adb shell ime enable "$IME"
adb shell ime set "$IME"
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
sleep 3
TESTPKG=$(adb shell pm list instrumentation | tr -d '\r' | grep "target=$PKG" | sed -E 's/^instrumentation:([^ ]+).*/\1/' | head -1)
test -n "$TESTPKG"
adb shell am instrument -w -r -e class org.futo.inputmethod.latin.uix.settings.Wren18NavigationTest "$TESTPKG" | tee "$OUT/navigation.txt"
grep -q 'OK (1 test)' "$OUT/navigation.txt"
adb shell am start -W -n com.wrenboard.imetarget/.TestInput >/dev/null
sleep 2
adb shell input tap 400 500
sleep 3
adb exec-out screencap -p > "$OUT/keyboard.png"
adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd geom >/dev/null
adb shell screenrecord --time-limit 5 /sdcard/wren18-more.mp4 > "$OUT/screenrecord.txt" 2>&1 &
RECORDER=$!
sleep 1
adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd open:more >/dev/null
sleep 1
adb exec-out screencap -p > "$OUT/actions.png"
adb shell input keyevent KEYCODE_BACK
sleep 1
adb exec-out screencap -p > "$OUT/back_keyboard.png"
wait "$RECORDER"
adb pull /sdcard/wren18-more.mp4 "$OUT/more-open-close.mp4"
ffmpeg -hide_banner -loglevel error -i "$OUT/more-open-close.mp4" -vf fps=20 "$OUT/more_%03d.png"
# Independent density samples expose the real sizing formula. They do not calibrate the owner's phone.
for DPI in 450 480; do
 adb shell wm density "$DPI"
 sleep 2
 adb shell am start -W -n com.wrenboard.imetarget/.TestInput >/dev/null
 adb shell input tap 400 500
 sleep 2
 adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd geom >/dev/null
 adb exec-out screencap -p > "$OUT/keyboard_${DPI}dpi.png"
done
