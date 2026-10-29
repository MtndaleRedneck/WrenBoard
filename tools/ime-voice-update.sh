# Sourced LAST by ime-verify.sh. (1) real update-failure dialog frame, (2) VoiceInputSwitchActivity confirm: no write before
# confirmation, Cancel keeps settings unchanged, Switch persists. Datastore is read as root (emulator only).
note "== update-failure dialog and voice-backend confirmation =="
VAPP=com.wrenboard.keyboard; VDS=/data/data/$VAPP/files/datastore/settings.preferences_pb
VSW="$VAPP/org.futo.inputmethod.latin.uix.settings.VoiceInputSwitchActivity"
VTARGET=com.google.android.tts
adb root >/dev/null 2>&1; sleep 4; adb wait-for-device; sleep 2
if ! adb shell id 2>/dev/null | grep -q "uid=0"; then
  note "INCONCLUSIVE(harness): adb root unavailable for voice/update steps"
else
  vtexts() { sed 's/>/>\n/g' "shots/$1.xml" | sed -n 's/.*text="\([^"]\+\)".*/\1/p' | tr '\n' '|'; }
  vmd() { adb shell "md5sum $VDS 2>/dev/null" | awk '{print $1}' | tr -d '\r'; }
  # (1) update failure dialog: the 404 earlier set LAST_UPDATE_CHECK_FAILED; open the real dialog
  note "datastore has last_update_check_failed key: $(adb shell "grep -c last_update_check_failed $VDS" | tr -d '\r')"
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -n "$SETTINGS_ACT" --es navDest update >/dev/null 2>&1; sleep 5
  shot vu_01_update_dialog; xdump vu_01_update_dialog
  T=$(vtexts vu_01_update_dialog); note "update dialog texts: $T"
  if echo "$T" | grep -q "Update check failed"; then note "PASS: update dialog says 'Update check failed' (not 'Up-to-date') after the 404 check (frame vu_01_update_dialog.png)"
  elif echo "$T" | grep -q "Up-to-date"; then
    if [ "$(adb shell "grep -c last_update_check_failed $VDS" | tr -d '\r')" = 0 ]; then note "INCONCLUSIVE: no failed check was recorded (flag absent), so 'Up-to-date' is not a defect here (frame vu_01_update_dialog.png)"
    else note "FAIL: dialog says Up-to-date although a failed check was recorded (frame vu_01_update_dialog.png)"; fi
  else note "INCONCLUSIVE(harness): update dialog not reached (frame vu_01_update_dialog.png)"; fi
  adb shell input keyevent KEYCODE_BACK; sleep 1
  # (2) voice backend confirmation
  VSKIP=""
  if ! adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -q "package:$VTARGET$"; then
    note "INCONCLUSIVE(harness): $VTARGET is not installed on this emulator; voice confirmation steps skipped"; VSKIP=1
  fi
  if [ -z "$VSKIP" ]; then
  B0=$(vmd); note "datastore md5 before: ${B0:0:8}; target package already in datastore: $(adb shell "grep -c $VTARGET $VDS" | tr -d '\r')"
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -n "$VSW" --es targetPackage $VTARGET --es mode switch >/dev/null 2>&1; sleep 4
  shot vu_02_switch_dialog; xdump vu_02_switch_dialog
  T=$(vtexts vu_02_switch_dialog); note "switch dialog texts: $T"
  if echo "$T" | grep -q "Switch voice input engine to" && echo "$T" | grep -q "$VTARGET"; then note "PASS: mode=switch shows the confirmation naming the target label and package"; else note "FAIL-or-INCONCLUSIVE: confirmation text not found (frame vu_02_switch_dialog.png)"; fi
  sleep 1; B1=$(vmd)
  [ "$B0" = "$B1" ] && note "PASS: no settings change before confirmation (datastore md5 unchanged)" || note "FAIL: datastore changed before the user confirmed"
  if CN=$(python3 tools/xml_node.py shots/vu_02_switch_dialog.xml 'Cancel'); then
    read -r CX CY _ <<< "$CN"; adb shell input tap $CX $CY; sleep 2
    B2=$(vmd)
    [ "$B0" = "$B2" ] && note "PASS: Cancel leaves the datastore unchanged" || note "FAIL: datastore changed after Cancel"
    note "target package in datastore after Cancel: $(adb shell "grep -c $VTARGET $VDS" | tr -d '\r') (0 expected)"
  else note "INCONCLUSIVE(harness): Cancel button not found"; fi
  adb shell am start -n "$VSW" --es targetPackage $VTARGET --es mode switch >/dev/null 2>&1; sleep 4
  xdump vu_03_switch_again
  if SN=$(python3 tools/xml_node.py shots/vu_03_switch_again.xml 'Switch'); then
    read -r SX SY _ <<< "$SN"; adb shell input tap $SX $SY; sleep 3
    note "target package in datastore after Switch: $(adb shell "grep -c $VTARGET $VDS" | tr -d '\r') (1 expected: the confirmed switch persisted)"
  else note "INCONCLUSIVE(harness): Switch button not found"; fi
  fi
  note "(opaque installed IME package used to test the confirmation gate only; this is not proof the engine works)"
  note "FATAL EXCEPTION count after voice/update steps: $(adb logcat -d 2>/dev/null | grep -c 'FATAL EXCEPTION')"
fi
adb unroot >/dev/null 2>&1; sleep 3; adb wait-for-device
