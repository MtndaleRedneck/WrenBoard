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
# 3-button nav so the IME back disposition is visible as back-arrow vs down-chevron.
adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton || true
adb shell settings put secure navigation_mode 0 || true
sleep 2
# Keep the unrelated Pixel Launcher out of the fixture and its ANR dialogs out of screenshots.
adb shell am force-stop com.google.android.apps.nexuslauncher || true
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
adb shell am start -W -n com.wrenboard.imetarget/.TestInput >/dev/null
sleep 2
adb shell input tap 400 500
sleep 3
sleep 4
# A slow emulator may focus the editor before the freshly installed IME is ready.
# Retry focus only; missing keyboard geometry remains a failed runtime check.
for attempt in $(seq 1 8); do
 adb shell input tap 400 500
 adb shell input keyevent KEYCODE_WAKEUP
 sleep 3
 adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd geom >/dev/null
 sleep 1
 adb logcat -d -s WBDBG:I > "$OUT/period-geometry.txt"
 if python3 tools/wb12_kv.py "$OUT/period-geometry.txt" xy KEY label . > "$OUT/period-coordinates"; then break; fi
 adb shell am start -W -n com.wrenboard.imetarget/.TestInput >/dev/null
 adb shell ime set "$IME"
done
adb exec-out screencap -p > "$OUT/keyboard.png"
# Runtime Back matrix: use normal Android Back, never the debug close shortcut.
for PANEL in emoji gif clipboard_history text_edit more; do
 adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd "open:$PANEL" >/dev/null
 sleep 2
 adb exec-out screencap -p > "$OUT/panel_${PANEL}_open.png"
 adb logcat -d -s WB18_NAV:I > "$OUT/navlog_${PANEL}_open.txt" || true
 adb shell input keyevent KEYCODE_BACK
 sleep 2
 adb exec-out screencap -p > "$OUT/panel_${PANEL}_back.png"
 adb logcat -d -s WB18_NAV:I > "$OUT/navlog_${PANEL}_back.txt" || true
 python3 tools/wren18-panel-check.py "$OUT/keyboard.png" "$OUT/panel_${PANEL}_open.png" "$OUT/panel_${PANEL}_back.png" "$PANEL" | tee -a "$OUT/panel-back.txt"
done
read -r PX PY < "$OUT/period-coordinates"
adb shell input motionevent DOWN "$PX" "$PY"
sleep 1
adb exec-out screencap -p > "$OUT/period_hold.png"
# Same comma-selected state as the owner's reference, without changing the default.
CELL=$(python3 -c 'print(round(1080 / 10 * 0.914))')
adb shell input motionevent MOVE "$((PX-CELL))" "$PY"
sleep 1
adb exec-out screencap -p > "$OUT/period_comma.png"
adb shell input motionevent UP "$((PX-CELL))" "$PY"
sleep 1
adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd geom >/dev/null
adb shell screenrecord --time-limit 12 /sdcard/wren18-more.mp4 > "$OUT/screenrecord.txt" 2>&1 &
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
adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd dsbool:wren_clipboard_on_123=false >/dev/null
sleep 2
adb exec-out screencap -p > "$OUT/clipboard123_off.png"
# 420dpi geom from the actual candidate: 123 center=(54,2000), 108x145.
adb shell input swipe 54 2000 54 2000 900
sleep 1
adb exec-out screencap -p > "$OUT/clipboard123_off_longpress.png"
# A normal tap must still switch to symbols while clipboard is disabled.
adb shell input tap 54 2000
sleep 1
adb exec-out screencap -p > "$OUT/clipboard123_off_symbols.png"
adb shell input tap 54 2000
sleep 1
adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd dsbool:wren_clipboard_on_123=true >/dev/null
adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd dsbool:wren_clipboard_strip=true >/dev/null
sleep 2
adb exec-out screencap -p > "$OUT/clipboard_strip_on.png"
adb shell input swipe 54 2000 54 2000 900
sleep 1
adb exec-out screencap -p > "$OUT/clipboard123_on_longpress.png"
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell input tap 1030 1300
sleep 1
adb exec-out screencap -p > "$OUT/clipboard_strip_open.png"
adb shell input keyevent KEYCODE_BACK
sleep 1
adb shell am broadcast -a "$PKG.WBDBG" -p "$PKG" --es cmd open:emoji >/dev/null
sleep 3
adb exec-out screencap -p > "$OUT/emoji_approved.png"
adb shell input tap 255 2130
sleep 3
adb exec-out screencap -p > "$OUT/emoji_category.png"
# Swipe the category pager: search row must remain on the next page.
adb shell input swipe 950 1700 150 1700 450
sleep 3
adb exec-out screencap -p > "$OUT/emoji_nextpage.png"
adb shell input keyevent KEYCODE_BACK
sleep 1
# A real synthetic word exercises populated suggestions, not just empty chips.
adb shell input text hel
sleep 3
adb exec-out screencap -p > "$OUT/suggestions_approved.png"
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

TESTPKG=$(adb shell pm list instrumentation | tr -d '\r' | grep "target=$PKG" | sed -E 's/^instrumentation:([^ ]+).*/\1/' | head -1)
test -n "$TESTPKG"
timeout 480 adb shell am instrument -w -r -e class org.futo.inputmethod.latin.uix.settings.Wren18NavigationTest "$TESTPKG" | tee "$OUT/navigation.txt" || true
NAV_STATUS=0
grep -q 'OK (1 test)' "$OUT/navigation.txt" || NAV_STATUS=1
# WrenBoard 1.9: recase must assert actual field text (1/2/3 shift taps, fresh field, no word).
timeout 480 adb shell am instrument -w -r -e class org.futo.inputmethod.latin.Wren19RecaseTest "$TESTPKG" | tee "$OUT/recase.txt" || true
grep -q 'OK (3 tests)' "$OUT/recase.txt" || NAV_STATUS=1

exit "$NAV_STATUS"
