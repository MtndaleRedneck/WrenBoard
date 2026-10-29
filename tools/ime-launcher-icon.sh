# Sourced after e2e: captures the launcher with the installed WrenBoard icon (adaptive-icon mask pixels from the real launcher).
note "== launcher icon capture (real launcher app drawer) =="
adb shell input keyevent KEYCODE_HOME; sleep 2
ICON_FOUND=0
for try in 1 2 3; do
  adb shell input swipe 720 2200 720 700 300; sleep 2
  adb shell uiautomator dump /sdcard/icon_dump.xml >/dev/null 2>&1; adb pull /sdcard/icon_dump.xml shots/launcher_drawer_$try.xml >/dev/null 2>&1
  shot launcher_drawer_$try
  if python3 tools/xml_node.py shots/launcher_drawer_$try.xml 'WrenBoard' >/dev/null 2>&1; then ICON_FOUND=1; cp shots/launcher_drawer_$try.png shots/launcher_wren_icon.png; note "launcher shows a WrenBoard label (frame launcher_wren_icon.png; icon pixels need inspection)"; break; fi
  adb shell input swipe 720 1800 720 900 300; sleep 1
done
[ "$ICON_FOUND" = 1 ] || note "INCONCLUSIVE(harness): WrenBoard label not found in launcher drawer (see launcher_drawer_*.png)"
adb shell input keyevent KEYCODE_HOME; sleep 1
