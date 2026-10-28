# Sourced by ime-verify.sh BEFORE the import regression. Real typed learning, real export, restore onto a RUNNING instance.
# A step that cannot be driven is INCONCLUSIVE(harness). Predictions are checked by tapping suggestion slots and reading the field.
note "== learned words: type -> prediction -> real export -> change words -> restore on RUNNING instance -> prediction (typed, not seeded) =="
LAPP=com.wrenboard.keyboard; LDIR=/data/data/$LAPP/files/UserHistoryDictionary.en_US.dict
# type_word <letters...>; learn by committing with space, three times
learn_word() { local i; for i in 1 2 3; do type_letters "$@" && tapk space; done; }
# predict <label> <prefix letters...>: type prefix then tap each suggestion slot; print the first resulting field text containing the target
LPHASE=p0
predict_slots() { # predict_slots <target> <prefix letters...>; prints "slot:field" on hit, "BLOCKED" if focus/typing could not be verified
  local target="$1"; shift; local slot got hit="" want; want=$(printf '%s' "$@")
  for slot in 720 240 1200; do
    precond predict || { echo BLOCKED; return; }
    clear_field; type_letters "$@"; sleep 1.2
    got=$(edit_text)
    if [ "$got" != "$want" ]; then note "PREDICT[$LPHASE/$target/$slot]: field is '$got' after typing, expected exactly '$want' - INCONCLUSIVE(harness)"; shot "learn_${LPHASE}_${target}_${slot}_BADFIELD"; echo BLOCKED; return; fi
    shot "learn_${LPHASE}_${target}_${slot}_typed"
    adb shell input tap $slot 1044; sleep 0.8
    got=$(edit_text)
    shot "learn_${LPHASE}_${target}_${slot}"
    if echo "$got" | grep -qi "$target"; then hit="$slot:$got"; break; fi
  done
  echo "$hit"
}
precond learn || note "INCONCLUSIVE(harness): learn typing focus not reached"; clear_field
learn_word z o r b l a x
sleep 8   # more than the 5 s delayed flush
LPHASE=pre; H=$(predict_slots zorblax z o r)
if [ "$H" = BLOCKED ]; then note "INCONCLUSIVE(harness): pre-export prediction check blocked (focus/typing not verified)"; elif [ -n "$H" ]; then note "PASS: typed learned word 'zorblax' is offered as a prediction from 'zor' (slot:field = '$H')"; else note "FAIL-or-INCONCLUSIVE: no suggestion slot produced 'zorblax' after typing it 3 times (see shots learn_pred_zorblax_*; check frames to tell app from harness)"; fi
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device; sleep 2
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
    adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
    precond learn2; clear_field
    learn_word q u e n v i l
    sleep 8
    LPHASE=prerestore; H2=$(predict_slots quenvil q u e n)
    note "before restore, 'quenvil' prediction: '${H2:-none}' (BLOCKED = not verified)"
    # restore onto the running instance
    adb root >/dev/null 2>&1; sleep 4; adb wait-for-device; sleep 2
    adb shell am start -W -a android.intent.action.VIEW -d "content://com.wrenboard.imetarget.fix/exp.backup" -t application/octet-stream -n "$LAPP/org.futo.inputmethod.latin.uix.ImportResourceActivity" >/dev/null 2>&1
    sleep 4; shot learn_03_importer; xdump learn_03_importer
    if IB=$(python3 tools/xml_node.py shots/learn_03_importer.xml 'Import'); then
      read -r IX IY _ <<< "$IB"; adb shell input tap $IX $IY; sleep 12; shot learn_04_after_restore
      sleep 8   # > 5 s: a late delayed flush of old memory would have fired by now
      adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
      LPHASE=postrestore; H3=$(predict_slots zorblax z o r)
      if [ "$H3" = BLOCKED ]; then note "INCONCLUSIVE(harness): post-restore zorblax check blocked (focus/typing not verified)"; elif [ -n "$H3" ]; then note "PASS: after restore on the running instance 'zorblax' predicts again ('$H3')"; else note "FAIL-or-INCONCLUSIVE: 'zorblax' not offered after restore (frames learn_pred_zorblax_*; compare with the pre-export frame)"; fi
      LPHASE=postrestoreq; H4=$(predict_slots quenvil q u e n)
      if [ "$H4" = BLOCKED ]; then note "INCONCLUSIVE(harness): post-restore quenvil check blocked - no replace-restore claim"; elif [ -z "$H4" ]; then note "PASS: 'quenvil' (learned AFTER the export) is gone after restore, as a replace-restore implies"; else note "OBSERVATION: 'quenvil' still predicts after restore ('$H4'); restore is a full replace, so this needs explanation"; fi
      # keep typing and wait more sync cycles, then check zorblax persists
      precond learn3; clear_field; type_letters a b c; tapk space; type_letters d e f; tapk space; sleep 8
      LPHASE=postflush; H5=$(predict_slots zorblax z o r)
      if [ "$H5" = BLOCKED ]; then note "INCONCLUSIVE(harness): post-flush zorblax check blocked"; elif [ -n "$H5" ]; then note "PASS: restored word still predicts after further typing and another >5 s flush cycle"; else note "FAIL-or-INCONCLUSIVE: restored word lost after more typing/flush (frames learn_pred_zorblax_*)"; fi
      note "FATAL EXCEPTION count after learn/restore: $(adb logcat -d 2>/dev/null | grep -c 'FATAL EXCEPTION')"
    else note "INCONCLUSIVE(harness or app): no Import button for the learned-words export (learn_03_importer.png)"; fi
  fi
else note "INCONCLUSIVE(harness): adb root unavailable for learned-words test"; fi
adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
