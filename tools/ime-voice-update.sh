# Sourced LAST by ime-verify.sh. (1) real update-failure dialog frame, (2) VoiceInputSwitchActivity confirm: no write before
# confirmation, Cancel keeps settings unchanged, Switch persists. Datastore is read as root (emulator only).
note "== update-failure dialog and voice-backend confirmation =="
VAPP=com.wrenboard.keyboard; VDS=/data/data/$VAPP/files/datastore/settings.preferences_pb
VSW="$VAPP/org.futo.inputmethod.latin.uix.settings.VoiceInputSwitchActivity"
VTARGET=com.google.android.tts
adb_ready root; sleep 2
if ! adb shell id 2>/dev/null | grep -q "uid=0"; then
  note "INCONCLUSIVE(harness): adb root unavailable for voice/update steps"
else
  vtexts() { sed 's/>/>\n/g' "shots/$1.xml" | sed -n 's/.*text="\([^"]\+\)".*/\1/p' | tr '\n' '|'; }
  vmd() { adb shell "md5sum $VDS 2>/dev/null" | awk '{print $1}' | tr -d '\r'; }
  # (1) update dialog vs the recorded check outcome. The datastore keeps a boolean
  # last_update_check_failed; the key string is present with EITHER value, so read the value:
  # androidx preferences_pb encodes a bool entry as the key bytes then 0x12 0x02 0x08 <0x00|0x01>.
  adb shell "cat $VDS" > shots/vds.pb 2>/dev/null
  VFLAG=$(python3 - <<'PYFLAG'
data = open("shots/vds.pb","rb").read()
k = b"last_update_check_failed"
i = data.find(k)
v = -1
if i >= 0 and data[i+len(k):i+len(k)+3] == b"\x12\x02\x08":
    v = data[i+len(k)+3]
print(v)
PYFLAG
)
  note "last_update_check_failed value: $VFLAG (1 = last check failed, 0 = last check succeeded, -1 = unreadable)"
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -n "$SETTINGS_ACT" --es navDest update >/dev/null 2>&1; sleep 5
  shot vu_01_update_dialog; xdump vu_01_update_dialog
  T=$(vtexts vu_01_update_dialog); note "update dialog texts: $T"
  case "$VFLAG" in
    1) echo "$T" | grep -q "Update check failed"          && note "PASS: dialog says 'Update check failed' and the recorded flag agrees (frame vu_01_update_dialog.png)"          || note "FAIL: a failed check is recorded but the dialog does not say so: $T (frame vu_01_update_dialog.png)";;
    0) echo "$T" | grep -q "Update check failed"          && note "FAIL: dialog says 'Update check failed' but the last recorded check succeeded (frame vu_01_update_dialog.png)"          || note "PASS: dialog matches the recorded successful check: $T (frame vu_01_update_dialog.png)";;
    *) note "INCONCLUSIVE(harness): last_update_check_failed value unreadable (dialog texts: $T)";;
  esac
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
adb_ready unroot
