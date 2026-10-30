# Sourced by ime-verify.sh under WB_FOCUS=upgrade, right after the Phase A gate ran
# against the PUBLISHED 1.0 APK (the workflow passes the published APK as $1 and the
# branch build as $3 = UPGRADE_APK). Proves the real upgrade path end to end:
# published 1.0 + typed learned data -> adb install -r branch APK -> learned words,
# dictionary files, app files, and default-IME ownership survive.
# learn_word/predict_slots below are VERBATIM copies from tools/ime-learn-restore.sh
# (edit there first, then here) so this leg reuses the proven learn/predict driving.
note "== upgrade: published 1.0 + typed learned data -> branch APK (adb install -r) =="
if [ -z "${UPGRADE_APK:-}" ] || [ ! -f "$UPGRADE_APK" ]; then note "FAIL: UPGRADE_APK (branch build) not provided to the upgrade leg"; exit 1; fi
ULDIR=/data/data/$PKG/files/UserHistoryDictionary.en_US.dict
VC_PRE=$(adb shell dumpsys package $PKG | grep -E 'versionCode|versionName' | head -2 | tr -d '\r' | tr '\n' ' ')
note "pre-upgrade installed: $VC_PRE"
echo "$VC_PRE" | grep -q "versionCode=1 " && note "PASS: pre-upgrade install is versionCode 1 (published 1.0 baseline)" || note "FAIL: pre-upgrade install is not versionCode 1: $VC_PRE"
learn_word() { local i; for i in 1 2 3; do type_letters "$@" && tapk space; done; }
# predict <label> <prefix letters...>: type prefix then tap each suggestion slot; print the first resulting field text containing the target
LPHASE=p0
predict_slots() { # predict_slots <target> <prefix letters...>; prints "slot:field" on hit, "BLOCKED" if focus/typing could not be verified
  PR=""; local target="$1"; shift; local slot got hit="" want; want=$(printf '%s' "$@")
  for slot in 333 717 1104; do
    precond predict || { PR=BLOCKED; return; }
    clear_field; type_letters "$@"; sleep 1.2
    got=$(edit_text)
    if [ "$got" != "$want" ]; then note "PREDICT[$LPHASE/$target/$slot]: field is '$got' after typing, expected exactly '$want' - INCONCLUSIVE(harness)"; shot "learn_${LPHASE}_${target}_${slot}_BADFIELD"; PR=BLOCKED; return; fi
    shot "learn_${LPHASE}_${target}_${slot}_typed"
    adb shell input tap $slot 1183; sleep 0.8
    got=$(edit_text)
    shot "learn_${LPHASE}_${target}_${slot}"
    if echo "$got" | grep -qi "$target"; then hit="$slot:$got"; break; fi
  done
  PR="$hit"
}
learn_word z o r b l a x
sleep 8   # more than the 5 s delayed flush
LPHASE=pre; predict_slots zorblax z o r b l; H=$PR
if [ "$H" = BLOCKED ]; then note "INCONCLUSIVE(harness): pre-export prediction check blocked (focus/typing not verified)"; elif [ -n "$H" ]; then note "PASS: typed learned word 'zorblax' is offered as a prediction from 'zorbl' (slot:field = '$H')"; else note "FAIL-or-INCONCLUSIVE: no suggestion slot produced 'zorblax' after typing it 3 times (see shots learn_pred_zorblax_*; check frames to tell app from harness)"; fi
adb_ready root; sleep 2
if adb shell id 2>/dev/null | grep -q "uid=0"; then
  note "learned dict dir after typing: $(adb shell ls -l $LDIR | tr -d '\r' | tr '\n' ';')"
  # export through the real Export screen
  adb shell rm -f /sdcard/Download/FUTOKeyboardSettings_* /sdcard/Documents/FUTOKeyboardSettings_* 2>/dev/null
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -n "$SETTINGS_ACT" --es navDest exportingcfg >/dev/null 2>&1; sleep 6
  shot learn_01_picker; xdump learn_01_picker
  if SV=$(python3 tools/xml_node.py shots/learn_01_picker.xml 'SAVE' || python3 tools/xml_node.py shots/learn_01_picker.xml 'Save'); then
    read -r SX SY _ <<< "$SV"; adb shell input tap $SX $SY; sleep 14; shot learn_02_after_save
  else note "INCONCLUSIVE(harness): picker SAVE not found for learned-words export"; fi
  LEXP=$(adb shell "ls -t /sdcard/Download/FUTOKeyboardSettings_* /sdcard/Documents/FUTOKeyboardSettings_* /sdcard/FUTOKeyboardSettings_* 2>/dev/null | head -1" | tr -d '\r')
  if [ -z "$LEXP" ]; then note "INCONCLUSIVE(harness): no export file produced"; else
    note "export: $LEXP ($(adb shell stat -c %s "$LEXP" | tr -d '\r') bytes); learned entries inside: $(adb shell "unzip -l '$LEXP' 2>/dev/null | grep -c userdict/" | tr -d '\r')"
    adb shell "unzip -l '$LEXP' 2>/dev/null | grep userdict/" | sed 's/^/    /' | tee -a "$R"
    LCNT=$(adb shell "unzip -l '$LEXP' 2>/dev/null | grep -c userdict/" | tr -d '\r')
    if [ "${LCNT:-0}" -gt 0 ]; then note "PASS: export contains $LCNT userdict/ entries (typed learned data was exported)"; else note "FAIL: export contains NO userdict/ entries although words were typed (or unzip unavailable on the emulator: check)"; fi
    TU=$(adb shell stat -c %u /data/data/com.wrenboard.imetarget | tr -d '\r')
    adb shell "mkdir -p /data/data/com.wrenboard.imetarget/files && cp '$LEXP' /data/data/com.wrenboard.imetarget/files/exp.backup && chown -R $TU:$TU /data/data/com.wrenboard.imetarget/files"
    adb shell "restorecon -R /data/data/com.wrenboard.imetarget/files 2>/dev/null; ls -lZ /data/data/com.wrenboard.imetarget/files/exp.backup; md5sum '$LEXP' /data/data/com.wrenboard.imetarget/files/exp.backup; run-as com.wrenboard.imetarget sh -c 'wc -c < files/exp.backup' 2>&1" | tr -d '\r' | sed 's/^/    copy-check: /' | tee -a "$R"
    # change the learned words on the RUNNING instance: learn another word
    adb_ready unroot
    precond learn2; clear_field
    learn_word q u e n v i l
    sleep 8
    LPHASE=prerestore; predict_slots quenvil q u e n v; H2=$PR
    note "before restore, 'quenvil' prediction: '${H2:-none}' (BLOCKED = not verified)"
    # restore onto the running instance
    adb_ready root; sleep 2
    adb shell am start -W -a android.intent.action.VIEW -d "content://com.wrenboard.imetarget.fix/exp.backup" -t application/octet-stream -n "$LAPP/org.futo.inputmethod.latin.uix.ImportResourceActivity" >/dev/null 2>&1
    sleep 4; shot learn_03_importer; xdump learn_03_importer
    if IB=$(python3 tools/xml_node.py shots/learn_03_importer.xml 'Import'); then
      read -r IX IY _ <<< "$IB"; adb shell input tap $IX $IY; sleep 12; shot learn_04_after_restore
      sleep 8   # > 5 s: a late delayed flush of old memory would have fired by now
      adb_ready unroot
      LPHASE=postrestore; predict_slots zorblax z o r b l; H3=$PR
      if [ "$H3" = BLOCKED ]; then note "INCONCLUSIVE(harness): post-restore zorblax check blocked (focus/typing not verified)"; elif [ -n "$H3" ]; then note "PASS: after restore on the running instance 'zorblax' predicts again ('$H3')"; else note "FAIL-or-INCONCLUSIVE: 'zorblax' not offered after restore (frames learn_pred_zorblax_*; compare with the pre-export frame)"; fi
      LPHASE=postrestoreq; predict_slots quenvil q u e n v; H4=$PR
      if [ "$H4" = BLOCKED ]; then note "INCONCLUSIVE(harness): post-restore quenvil check blocked - no replace-restore claim"; elif [ -z "$H4" ]; then note "PASS: 'quenvil' (learned AFTER the export) is gone after restore, as a replace-restore implies"; else note "OBSERVATION: 'quenvil' still predicts after restore ('$H4'); restore is a full replace, so this needs explanation"; fi
      # keep typing and wait more sync cycles, then check zorblax persists
      precond learn3; clear_field; type_letters a b c; tapk space; type_letters d e f; tapk space; sleep 8
      LPHASE=postflush; predict_slots zorblax z o r b l; H5=$PR
      if [ "$H5" = BLOCKED ]; then note "INCONCLUSIVE(harness): post-flush zorblax check blocked"; elif [ -n "$H5" ]; then note "PASS: restored word still predicts after further typing and another >5 s flush cycle"; else note "FAIL-or-INCONCLUSIVE: restored word lost after more typing/flush (frames learn_pred_zorblax_*)"; fi
      note "FATAL EXCEPTION count after learn/restore: $(adb logcat -d 2>/dev/null | grep -c 'FATAL EXCEPTION')"
    else note "INCONCLUSIVE(harness or app): no Import button for the learned-words export (learn_03_importer.png)"; fi
  fi
else note "INCONCLUSIVE(harness): adb root unavailable for learned-words test"; fi
adb_ready unroot
predict_slots() { # predict_slots <target> <prefix letters...>; prints "slot:field" on hit, "BLOCKED" if focus/typing could not be verified
  PR=""; local target="$1"; shift; local slot got hit="" want; want=$(printf '%s' "$@")
  for slot in 333 717 1104; do
    precond predict || { PR=BLOCKED; return; }
    clear_field; type_letters "$@"; sleep 1.2
    got=$(edit_text)
    if [ "$got" != "$want" ]; then note "PREDICT[$LPHASE/$target/$slot]: field is '$got' after typing, expected exactly '$want' - INCONCLUSIVE(harness)"; shot "learn_${LPHASE}_${target}_${slot}_BADFIELD"; PR=BLOCKED; return; fi
    shot "learn_${LPHASE}_${target}_${slot}_typed"
    adb shell input tap $slot 1183; sleep 0.8
    got=$(edit_text)
    shot "learn_${LPHASE}_${target}_${slot}"
    if echo "$got" | grep -qi "$target"; then hit="$slot:$got"; break; fi
  done
  PR="$hit"
}
LPHASE=preup
precond upseed || note "INCONCLUSIVE(harness): upgrade seed typing focus not reached"; clear_field
learn_word z o r b l a x
sleep 8   # more than the 5 s delayed flush
predict_slots zorblax z o r b l; HU=$PR
if [ "$HU" = BLOCKED ]; then note "INCONCLUSIVE(harness): pre-upgrade prediction check blocked (focus/typing not verified)"; elif [ -n "$HU" ]; then note "PASS: typed learned word 'zorblax' is offered as a prediction from 'zorbl' on published 1.0 (slot:field = '$HU')"; else note "FAIL-or-INCONCLUSIVE: no suggestion slot produced 'zorblax' on published 1.0 after typing it 3 times (see shots learn_preup_zorblax_*; check frames to tell app from harness)"; fi
adb_ready root; sleep 2
if adb shell id 2>/dev/null | grep -q "uid=0"; then
  note "pre-upgrade dict dir: $(adb shell "ls -l $ULDIR" | tr -d '\r' | tr '\n' ';')"
  adb shell "md5sum $ULDIR/* 2>/dev/null" | tr -d '\r' > shots/up_dict_pre.md5; sed 's/^/    pre: /' shots/up_dict_pre.md5 | tee -a "$R"
  adb shell "find /data/data/$PKG/shared_prefs /data/data/$PKG/files -type f 2>/dev/null | sort" | tr -d '\r' > shots/up_files_pre.txt
  note "pre-upgrade app file count: $(wc -l < shots/up_files_pre.txt | tr -d ' ')"
else
  note "INCONCLUSIVE(harness): adb root unavailable for the pre-upgrade file snapshot"
fi
note "== adb install -r branch APK over published 1.0 =="
OUT=$(adb install -r "$UPGRADE_APK" 2>&1); echo "$OUT" | sed 's/^/    install: /' | tee -a "$R"
if echo "$OUT" | grep -q Success; then note "PASS: branch APK installed over 1.0 (same signer; -r keeps app data)"; else note "FAIL: upgrade install failed: $OUT"; exit 1; fi
VC_POST=$(adb shell dumpsys package $PKG | grep -E 'versionCode|versionName' | head -2 | tr -d '\r' | tr '\n' ' ')
note "post-upgrade installed: $VC_POST"
if echo "$VC_POST" | grep -q "versionCode=2 " && echo "$VC_POST" | grep -q "versionName=1.1.0"; then note "PASS: post-upgrade version is 2 / 1.1.0"; else note "FAIL: unexpected post-upgrade version: $VC_POST"; fi
CUR=$(adb shell settings get secure default_input_method)
check "default IME still WrenBoard after upgrade" "$PKG" "$CUR"
if adb shell id 2>/dev/null | grep -q "uid=0"; then
  note "post-upgrade dict dir: $(adb shell "ls -l $ULDIR" | tr -d '\r' | tr '\n' ';')"
  adb shell "md5sum $ULDIR/* 2>/dev/null" | tr -d '\r' > shots/up_dict_post.md5; sed 's/^/    post: /' shots/up_dict_post.md5 | tee -a "$R"
  adb shell "find /data/data/$PKG/shared_prefs /data/data/$PKG/files -type f 2>/dev/null | sort" | tr -d '\r' > shots/up_files_post.txt
  MISSING=$(comm -23 shots/up_files_pre.txt shots/up_files_post.txt | tr '\n' ' ')
  if [ -z "$MISSING" ]; then note "PASS: every pre-upgrade app file still present after upgrade ($(wc -l < shots/up_files_pre.txt | tr -d ' ') files)"; else note "FAIL: app files missing after upgrade: $MISSING"; fi
  HB=$(adb shell "ls $ULDIR/*.header $ULDIR/*.body 2>/dev/null" | tr -d '\r' | tr '\n' ' ')
  if [ -n "$HB" ]; then note "PASS: learned dictionary header/body present post-upgrade ($HB)"; else note "FAIL: learned dictionary header/body missing post-upgrade"; fi
fi
precond uppost || note "INCONCLUSIVE(harness): post-upgrade target focus not reached"; clear_field
shot up_06_keyboard_post
geom_rebuild up_06_keyboard_post up_06_keyboard_post_overlay
UGRC=$?
note "post-upgrade kbd_geom exit: $UGRC"
if [ "$UGRC" -ne 0 ]; then
  note "FAIL: keyboard pixels not confirmed after upgrade (kbd_geom rc=$UGRC: $(tr -d '\n' < shots/kbd_geom.err | cut -c1-160))"
else
  note "PASS: keyboard pixels + key mapping confirmed after upgrade (fresh geometry)"
  LPHASE=postup
  predict_slots zorblax z o r b l; HU2=$PR
  if [ "$HU2" = BLOCKED ]; then note "INCONCLUSIVE(harness): post-upgrade prediction check blocked (focus/typing not verified)"; elif [ -n "$HU2" ]; then note "PASS: learned word 'zorblax' STILL offered as a prediction from 'zorbl' after the upgrade to 1.1.0 (slot:field = '$HU2')"; else note "FAIL-or-INCONCLUSIVE: learned word 'zorblax' no longer predicted after upgrade (see shots learn_postup_zorblax_*; check frames to tell app from harness)"; fi
fi
adb logcat -d > shots/logcat_full.txt 2>&1
note "FATAL EXCEPTION count across the upgrade leg: $(grep -c 'FATAL EXCEPTION' shots/logcat_full.txt)"
dim "final"
note "== done =="
exit 0
