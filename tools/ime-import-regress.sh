# Sourced by ime-verify.sh. Import/restore regression using the REAL importer (content:// VIEW intent served by the
# test-only FixtureProvider in imetarget). App data is inspected through adb root (emulator only).
# A failure here is stated as app fault only when the importer UI was reached; otherwise it is harness fault.
APP=com.wrenboard.keyboard
APPDIR=/data/data/$APP
IMPORT_ACT="$APP/org.futo.inputmethod.latin.uix.ImportResourceActivity"
note "== import/restore regression (real importer; fixtures served by test-only provider; data checked as root on emulator) =="
adb_ready root; sleep 2
if adb shell id 2>/dev/null | grep -q "uid=0"; then IR_ROOT=1; else IR_ROOT=0; fi
note "adb root available: $IR_ROOT"
if [ "$IR_ROOT" = 1 ]; then
APPUID=$(adb shell stat -c %u $APPDIR | tr -d '\r')
note "physical app data inventory after the typing tests (filesDir, depth 3):"
adb shell "ls -lAR $APPDIR/files 2>/dev/null" | head -80 | sed 's/^/    /' | tee -a "$R"
note "external files inventory:"
adb shell "ls -lAR /sdcard/Android/data/$APP/files 2>/dev/null" | head -30 | sed 's/^/    /' | tee -a "$R"
adb shell "ls -la $APPDIR/shared_prefs $APPDIR/databases 2>/dev/null" | head -20 | sed 's/^/    /' | tee -a "$R"

seed() { # recreate known pre-existing data
  adb shell "content insert --uri content://user_dictionary/words --bind word:s:e2epersonalword --bind frequency:i:77 --bind locale:s:en_US" >/dev/null 2>&1
  adb shell "mkdir -p $APPDIR/files/themes $APPDIR/files/clipboardfiles && echo -n KEEP-THEME > $APPDIR/files/themes/preexisting.zip && echo -n KEEP-CLIP > $APPDIR/files/clipboardfiles/keep.txt && chown -R $APPUID:$APPUID $APPDIR/files/themes $APPDIR/files/clipboardfiles"
}
snap() {
  adb shell "cd $APPDIR/files && find themes clipboardfiles transformer-models -type f 2>/dev/null | sort | xargs md5sum 2>/dev/null; md5sum datastore/*.preferences_pb 2>/dev/null; md5sum ../shared_prefs/*.xml 2>/dev/null" | tr -d '\r'
  adb shell "cd $APPDIR/files && find . -maxdepth 2 -path './UserHistoryDictionary*' -type f 2>/dev/null | sort | xargs md5sum 2>/dev/null" | tr -d '\r'
  adb shell "cd $APPDIR && find .mozc -type f 2>/dev/null | sort | xargs md5sum 2>/dev/null" | tr -d '\r'
  adb shell "cd /sdcard/Android/data/$APP/files 2>/dev/null && find . -type f 2>/dev/null | sort | xargs md5sum 2>/dev/null" | tr -d '\r'
  adb shell "content query --uri content://user_dictionary/words --projection word:frequency:locale 2>&1" | tr -d '\r' | sort
}
exists() { adb shell "[ -e '$1' ] && echo yes || echo no" | tr -d '\r'; }
fatals() { adb logcat -d 2>/dev/null | grep -c "FATAL EXCEPTION" ; }

run_import() { # run_import <label> <fixture> -> sets IMPORT_BTN (1/0), takes shots
  local L=$1 F=$2
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -W -a android.intent.action.VIEW -d "content://com.wrenboard.imetarget.fix/$F" -t application/octet-stream -n "$IMPORT_ACT" > shots/imp_$L.start.txt 2>&1
  sleep 4; shot imp_${L}_a; xdump imp_${L}_a
  IMPORT_BTN=0
  if IB=$(python3 tools/xml_node.py shots/imp_${L}_a.xml 'Import'); then
    IMPORT_BTN=1; read -r IX IY _ <<< "$IB"; adb shell input tap $IX $IY
    sleep 1.5; shot imp_${L}_b; sleep 3
  fi
  sed 's/>/>\n/g' shots/imp_${L}_a.xml | sed -n 's/.*text="\([^"]\+\)".*/\1/p' | head -12 | tr '\n' '|' | sed "s/^/  [$L] importer screen texts: /" | tee -a "$R"; echo | tee -a "$R"
  adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_HOME; sleep 1
}

F0=$(fatals)
# A: legit theme imports
seed; run_import A theme_legit.zip
[ "$IMPORT_BTN" = 1 ] && note "importer reached for legit theme (Import button present)" || note "FAIL(harness or app): legit theme: no Import button on importer screen (see imp_A_a.png)"
[ "$(exists $APPDIR/files/themes/wrenfixture1.zip)" = yes ] && note "PASS: legit theme import wrote themes/wrenfixture1.zip" || note "FAIL: legit theme import did not write themes/wrenfixture1.zip (IMPORT_BTN=$IMPORT_BTN)"
[ "$(exists $APPDIR/files/themes/wrenfixture1.zip.part)" = no ] && note "PASS: no .part file left after legit import" || note "FAIL: .part file left behind"
# B: traversal theme id
seed; run_import B theme_traversal.zip
note "traversal theme: Import button present = $IMPORT_BTN (new build should reject before this)"
[ "$(exists $APPDIR/files/pwned_by_theme_id.zip)" = no ] && note "PASS: traversal theme id wrote nothing outside themes/ (files/pwned_by_theme_id.zip absent)" || note "FAIL: traversal theme id wrote files/pwned_by_theme_id.zip OUTSIDE themes/"
adb shell rm -f $APPDIR/files/pwned_by_theme_id.zip
# C: slash in theme id
seed; run_import C theme_badid_slash.zip
[ "$(exists $APPDIR/files/themes/a/b/c1.zip)" = no ] && [ "$(exists $APPDIR/files/themes/a)" = no ] && note "PASS: slash theme id created no nested path" || note "FAIL: slash theme id created nested path under themes/"
# E/F/G/H: rejected restores must leave existing data untouched
for CASE in "E cfg_badtype_last.cfg themes/shouldnotland.zip" "F cfg_crcbad_last.cfg themes/shouldnotland2.zip" "G cfg_traversal_last.cfg themes/shouldnotland3.zip" "H cfg_traversal_rime.cfg themes/none" "J cfg_headeronly_userdict.cfg restore-dict-stage"; do
  set -- $CASE; L=$1; F=$2; NO=$3
  seed; BEFORE=$(snap); run_import $L $F; AFTER=$(snap)
  note "restore $L ($F): importer Import button present = $IMPORT_BTN"
  if [ "$IMPORT_BTN" != 1 ]; then note "INCONCLUSIVE(harness or early reject): restore $L: no Import button, nothing tapped (see imp_${L}_a.png)"; fi
  [ "$BEFORE" = "$AFTER" ] && note "PASS: restore $L rejected, existing data (themes, clipboardfiles, transformer-models, learned-word dictionaries, .mozc, external files, datastore, shared_prefs, personal dictionary rows) identical to before" || { note "FAIL: restore $L changed existing data"; echo "--- before"; echo "$BEFORE"; echo "--- after"; echo "$AFTER"; } | tee -a "$R"
  [ "$(exists $APPDIR/files/$NO)" = no ] && note "PASS: restore $L did not write $NO" || note "FAIL: restore $L wrote $NO"
done
[ "$(exists $APPDIR/pwned_by_restore.txt)" = no ] && note "PASS: traversal restore wrote nothing at app root" || note "FAIL: traversal restore wrote $APPDIR/pwned_by_restore.txt"
[ "$(exists /sdcard/Android/data/pwned_by_rime.txt)" = no ] && note "PASS: rime traversal wrote nothing outside rime dir" || note "FAIL: rime traversal wrote /sdcard/Android/data/pwned_by_rime.txt"
adb shell rm -f $APPDIR/pwned_by_restore.txt /sdcard/Android/data/pwned_by_rime.txt
# D: valid restore round trip (destructive: replaces themes)
seed; run_import D cfg_valid.cfg
[ "$(exists $APPDIR/files/themes/restored1.zip)" = yes ] && note "PASS: valid backup restored themes/restored1.zip" || note "FAIL: valid backup did not restore themes/restored1.zip (IMPORT_BTN=$IMPORT_BTN)"
[ "$(exists $APPDIR/files/themes/preexisting.zip)" = no ] && note "valid restore replaced pre-existing themes (destructive restore as the screen warns)" || note "valid restore kept preexisting.zip"
# I: low-space clean abort (emulator only): leave less than the 32 MiB floor
AV=$(adb shell df -k /data | tail -1 | awk '{print $4}' | tr -d '\r')
note "free space before fill: ${AV} KiB"
seed; adb shell "echo -n OLDCONTENT > $APPDIR/files/themes/wrenfixture1.zip; chown $APPUID:$APPUID $APPDIR/files/themes/wrenfixture1.zip"
FILL=$(( AV/1024 - 20 ))
if [ "$FILL" -gt 100 ]; then
  adb shell "fallocate -l ${FILL}M /data/local/tmp/fill.bin" 2>&1 | head -2
  AV2=$(adb shell df -k /data | tail -1 | awk '{print $4}' | tr -d '\r'); note "free space after fill: ${AV2} KiB"
  if [ "$AV2" -lt 30000 ]; then
    run_import I theme_legit.zip
    OLD=$(adb shell cat $APPDIR/files/themes/wrenfixture1.zip | tr -d '\r')
    adb shell rm -f /data/local/tmp/fill.bin
    [ "$OLD" = "OLDCONTENT" ] && note "PASS: low-space import aborted and left the existing theme file unchanged" || note "FAIL(or harness): low-space import changed existing file (content: ${OLD:0:30})"
    [ "$(exists $APPDIR/files/themes/wrenfixture1.zip.part)" = no ] && note "PASS: no .part left after low-space abort" || note "FAIL: .part left after low-space abort"
  else
    adb shell rm -f /data/local/tmp/fill.bin
    note "INCONCLUSIVE(harness): could not fill the volume below the floor (fallocate failed)"
  fi
else
  note "INCONCLUSIVE(harness): not enough measurable free space to fill"
fi
adb shell rm -f /data/local/tmp/fill.bin
F1=$(fatals); note "FATAL EXCEPTION count in logcat during import tests: before=$F0 after=$F1"
[ "$F1" = "$F0" ] && note "PASS: no crash during import/restore tests" || note "FAIL: logcat shows FATAL EXCEPTION during import/restore tests (see logcat_import.txt)"
adb logcat -d 2>/dev/null | grep -B2 -A12 "FATAL EXCEPTION" | tail -60 > shots/logcat_import.txt
adb_ready unroot
fi
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
