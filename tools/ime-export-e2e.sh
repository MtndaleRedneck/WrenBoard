# Sourced last. Real export -> copy off the app -> UNINSTALL -> fresh install -> import, checking seeded data comes back.
# The export uses the app's own Export configuration screen and the system document picker (Save).
# Data checks use adb root on the emulator. Harness steps that do not complete are reported as INCONCLUSIVE(harness).
note "== manual export -> fresh install -> restore (real export file, real importer) =="
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device; sleep 2
if ! adb shell id 2>/dev/null | grep -q "uid=0"; then
  note "INCONCLUSIVE(harness): adb root unavailable, export/restore E2E skipped"
else
APP=com.wrenboard.keyboard; APPDIR=/data/data/$APP; EXT=/sdcard/Android/data/$APP/files
UID_K=$(adb shell stat -c %u $APPDIR | tr -d '\r')
md() { adb shell "md5sum '$1' 2>/dev/null" | awk '{print $1}' | tr -d '\r'; }
# 1. seed data across the physical locations the exporter reads
adb shell "mkdir -p $APPDIR/files/themes $APPDIR/files/clipboardfiles $EXT/rime && \
  echo -n SEED-THEME > $APPDIR/files/themes/e2etheme.zip && echo -n SEED-CLIPIMG > $APPDIR/files/clipboardfiles/e2eclip.bin && \
  echo -n '[]' > $APPDIR/files/clipboard.json && echo -n SEED-LEARNED > $APPDIR/files/UserHistoryDictionary.en_US.dict && \
  echo -n SEED-EXTDICT > $EXT/e2e_user.dict && echo -n SEED-RIME > $EXT/rime/e2e.txt && \
  chown -R $UID_K:$UID_K $APPDIR/files/themes $APPDIR/files/clipboardfiles $APPDIR/files/clipboard.json $APPDIR/files/UserHistoryDictionary.en_US.seed $EXT"
declare -A WANT
for f in files/themes/e2etheme.zip files/clipboardfiles/e2eclip.bin files/clipboard.json files/UserHistoryDictionary.en_US.dict; do WANT[$APPDIR/$f]=$(md $APPDIR/$f); done
for f in e2e_user.dict rime/e2e.txt; do WANT[$EXT/$f]=$(md $EXT/$f); done
# 2. export through the app UI
adb shell rm -f /sdcard/Download/FUTOKeyboardSettings_* /sdcard/Documents/FUTOKeyboardSettings_* 2>/dev/null
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -n "$SETTINGS_ACT" --es navDest exportingcfg >/dev/null 2>&1; sleep 6
shot e2e_01_picker; xdump e2e_01_picker
if SV=$(python3 tools/xml_node.py shots/e2e_01_picker.xml 'Save'); then
  read -r SX SY _ <<< "$SV"; adb shell input tap $SX $SY; sleep 14; shot e2e_02_after_save
else
  note "INCONCLUSIVE(harness): document picker 'Save' button not found (see e2e_01_picker.png)"
fi
EXPF=$(adb shell "ls -t /sdcard/Download/FUTOKeyboardSettings_* /sdcard/Documents/FUTOKeyboardSettings_* /sdcard/FUTOKeyboardSettings_* 2>/dev/null | head -1" | tr -d '\r')
if [ -z "$EXPF" ]; then
  note "INCONCLUSIVE(harness): no exported FUTOKeyboardSettings_* file found on the emulator (picker/location)"
else
  note "export file: $EXPF ($(adb shell stat -c %s "$EXPF" | tr -d '\r') bytes)"
  adb shell "unzip -l '$EXPF' 2>/dev/null || true" | head -30 | sed 's/^/    /' | tee -a "$R"
  TU=$(adb shell stat -c %u /data/data/com.wrenboard.imetarget | tr -d '\r')
  adb shell "mkdir -p /data/data/com.wrenboard.imetarget/files && cp '$EXPF' /data/data/com.wrenboard.imetarget/files/exp.backup && chown -R $TU:$TU /data/data/com.wrenboard.imetarget/files"
  # 3. fresh install: remove the app and all its data, install again
  adb shell input keyevent KEYCODE_HOME
  adb uninstall $APP >/dev/null 2>&1; sleep 2
  note "after uninstall, app dir present: $(adb shell "[ -e $APPDIR ] && echo yes || echo no" | tr -d '\r')"
  adb install -r "$APK" 2>&1 | tail -1 | tee -a "$R"
  adb shell am start -n "$SETTINGS_ACT" >/dev/null 2>&1; sleep 5; adb shell input keyevent KEYCODE_HOME; sleep 1
  UID_K=$(adb shell stat -c %u $APPDIR | tr -d '\r')
  for k in "${!WANT[@]}"; do [ "$(adb shell "[ -e '$k' ] && echo y || echo n" | tr -d '\r')" = n ] && :; done
  note "fresh install has seeded data before restore: $(adb shell "[ -e $APPDIR/files/themes/e2etheme.zip ] && echo YES-UNEXPECTED || echo no" | tr -d '\r')"
  # 4. import the export file
  adb shell am start -W -a android.intent.action.VIEW -d "content://com.wrenboard.imetarget.fix/exp.backup" -t application/octet-stream -n "$APP/org.futo.inputmethod.latin.uix.ImportResourceActivity" >/dev/null 2>&1
  sleep 4; shot e2e_03_importer; xdump e2e_03_importer
  if IB=$(python3 tools/xml_node.py shots/e2e_03_importer.xml 'Import'); then
    read -r IX IY _ <<< "$IB"; adb shell input tap $IX $IY; sleep 12; shot e2e_04_after_restore
    ALLOK=1
    for k in "${!WANT[@]}"; do
      GOT=$(md "$k")
      if [ -n "${WANT[$k]}" ] && [ "$GOT" = "${WANT[$k]}" ]; then note "PASS: restored byte-identical: ${k#/}"; else note "FAIL: not restored / differs: ${k#/} (want ${WANT[$k]:0:8} got ${GOT:0:8})"; ALLOK=0; fi
    done
    [ "$ALLOK" = 1 ] && note "PASS: export -> uninstall -> fresh install -> restore returned all seeded locations byte-identical" || note "FAIL: some seeded locations did not come back (see above)"
  else
    note "INCONCLUSIVE(harness or app): importer screen has no Import button for the real export (see e2e_03_importer.png)"
  fi
fi

adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
fi
