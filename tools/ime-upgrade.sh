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
