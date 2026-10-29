# Sourced by ime-verify.sh. The toolbar action editor legs (edit/save/reload and the
# lowered-density drag reorder), moved verbatim so focus=editor can run them alone.
# No logic change.
note "== settings: toolbar action editor - edit, save, reload (navDest=actionEdit) =="
open_editor() { # open_editor <label>: open editor, assert Edit Actions title + Pinned/Favorite headers from the XML
  adb shell input keyevent KEYCODE_HOME; sleep 1
  adb shell am start -n "$SETTINGS_ACT" --es navDest actionEdit >/dev/null 2>&1; sleep 5
  shot "$1"; xdump "$1"
  python3 tools/xml_node.py "shots/$1.xml" "Edit Actions" >/dev/null && python3 tools/xml_node.py "shots/$1.xml" "Pinned Action(s)" >/dev/null && python3 tools/xml_node.py "shots/$1.xml" "Favorite Actions" >/dev/null
}
if open_editor 16_editor_before; then
  note "PASS: editor opened (Edit Actions, Pinned Action(s), Favorite Actions present)"
  read -r VX VY VTOP <<< "$(python3 tools/xml_node.py shots/16_editor_before.xml 'Voice Input')"
  read -r _ _ PTOP <<< "$(python3 tools/xml_node.py shots/16_editor_before.xml 'Pinned Action(s)')"
  read -r _ _ FTOP <<< "$(python3 tools/xml_node.py shots/16_editor_before.xml 'Favorite Actions')"
  if [ "${VTOP:-0}" -gt "${FTOP:-99999}" ]; then note "PASS: precondition: Voice Input starts under Favorite Actions"; else note "FAIL(precondition): Voice Input not under Favorite Actions (voice y=$VTOP fav y=$FTOP)"; fi
  if AN=$(python3 tools/xml_node.py shots/16_editor_before.xml 'Add next'); then
    read -r AX AY _ <<< "$AN"
    adb shell input tap $AX $AY; sleep 1.5
    shot 16b_editor_after_addnext; xdump 16b_editor_after_addnext
    read -r _ _ VTOP2 <<< "$(python3 tools/xml_node.py shots/16b_editor_after_addnext.xml 'Voice Input')"
    read -r _ _ PTOP2 <<< "$(python3 tools/xml_node.py shots/16b_editor_after_addnext.xml 'Pinned Action(s)')"
    read -r _ _ FTOP2 <<< "$(python3 tools/xml_node.py shots/16b_editor_after_addnext.xml 'Favorite Actions')"
    if [ -n "${VTOP2:-}" ] && [ "$VTOP2" -gt "$PTOP2" ] && [ "$VTOP2" -lt "$FTOP2" ]; then note "PASS: Add next moved Voice Input under Pinned Action(s) (voice y=$VTOP2, pinned y=$PTOP2, favorite y=$FTOP2)"; else note "FAIL: Add next did not move Voice Input into Pinned (voice=$VTOP2 pinned=$PTOP2 fav=$FTOP2)"; fi
    adb shell input keyevent KEYCODE_BACK; sleep 2
    if open_editor 16c_editor_reopened; then
      read -r _ _ VTOP3 <<< "$(python3 tools/xml_node.py shots/16c_editor_reopened.xml 'Voice Input')"
      read -r _ _ PTOP3 <<< "$(python3 tools/xml_node.py shots/16c_editor_reopened.xml 'Pinned Action(s)')"
      read -r _ _ FTOP3 <<< "$(python3 tools/xml_node.py shots/16c_editor_reopened.xml 'Favorite Actions')"
      if [ -n "${VTOP3:-}" ] && [ "$VTOP3" -gt "$PTOP3" ] && [ "$VTOP3" -lt "$FTOP3" ]; then note "PASS: edit persisted after leaving and reopening the editor"; else note "FAIL: edit did not persist (voice=$VTOP3 pinned=$PTOP3 fav=$FTOP3)"; fi
    else note "FAIL: editor did not reopen"; fi
  else note "FAIL: Add next node missing in editor XML (see 16_editor_before.png)"; fi
else
  note "FAIL: editor not confirmed open (see 16_editor_before.png)"
fi
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
shot 17_keyboard_after_edit
geom_rebuild 17_keyboard_after_edit 17_keyboard_after_edit_overlay >/dev/null 2>&1
note "toolbar after edit: icons=$(jq -r '.toolbar.n // 0' "$GEOM") (inspect 17_keyboard_after_edit.png: emoji must still be in the toolbar exactly once)"
dim "after-editor"

note "== settings: editor drag reorder, Disable (Hidden Actions) save, re-enable (density lowered so the whole editor fits one screen; reset afterwards) =="
adb shell wm density 300 >/dev/null 2>&1; sleep 2
ypos() { python3 tools/xml_node.py "shots/$1.xml" "$2" | awk '{print $3}'; }   # y1 of exact-text node, empty if missing
xpos() { python3 tools/xml_node.py "shots/$1.xml" "$2" | awk '{print $1}'; }
drag_node() { # drag_node <from text> <to text> <label>: try draganddrop then a long swipe; assert nothing here, caller asserts from XML
  local f t fx fy tx ty
  f=$(python3 tools/xml_node.py shots/$4.xml "$1") || return 1
  t=$(python3 tools/xml_node.py shots/$4.xml "$2") || return 1
  read -r fx fy _ <<< "$f"; read -r tx ty _ <<< "$t"
  note "  drag '$1' ($fx,$fy) -> '$2' ($tx,$ty) via $3"
  if [ "$3" = dnd ]; then adb shell input draganddrop $fx $fy $tx $ty 1500; else adb shell input swipe $fx $fy $tx $ty 2200; fi
  sleep 1.5
}
if open_editor 18_ed_dense_before; then
  note "editor opened at lowered density; Hidden Actions header y=$(ypos 18_ed_dense_before 'Hidden Actions') Settings y=$(ypos 18_ed_dense_before 'Settings')"
  HY0=$(ypos 18_ed_dense_before 'Hidden Actions'); SY0=$(ypos 18_ed_dense_before 'Settings')
  if [ -z "$HY0" ] || [ -z "$SY0" ] || [ -z "$(ypos 18_ed_dense_before 'Debug Info')" ]; then
    note "INCONCLUSIVE(harness): Hidden Actions / Settings / Debug Info not all visible at once; see 18_ed_dense_before.png"
  else
    DONE=0
    for M in dnd swipe; do
      drag_node "Settings" "Debug Info" $M 18_ed_dense_before || break
      shot 18b_ed_after_disable_$M; xdump 18b_ed_after_disable_$M
      HY=$(ypos 18b_ed_after_disable_$M 'Hidden Actions'); SY=$(ypos 18b_ed_after_disable_$M 'Settings')
      if [ -n "$SY" ] && [ -n "$HY" ] && [ "$SY" -gt "$HY" ]; then note "PASS: Settings moved into Hidden Actions via $M (settings y=$SY, hidden header y=$HY)"; DONE=1; break
      else note "  drag via $M did not move Settings under Hidden Actions (settings y=$SY hidden y=$HY)"; open_editor 18_ed_dense_before >/dev/null; fi
    done
    if [ "$DONE" = 1 ]; then
      adb shell input keyevent KEYCODE_BACK; sleep 2
      open_editor 18c_ed_reopened_after_disable >/dev/null
      HY=$(ypos 18c_ed_reopened_after_disable 'Hidden Actions'); SY=$(ypos 18c_ed_reopened_after_disable 'Settings')
      if [ -n "$SY" ] && [ -n "$HY" ] && [ "$SY" -gt "$HY" ]; then note "PASS: Disabled state persisted after leaving and reopening (settings y=$SY, hidden y=$HY)"; else note "FAIL: Disabled state did not persist (settings y=$SY hidden y=$HY)"; fi
      adb shell wm density reset >/dev/null 2>&1; sleep 2
      adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_HOME; sleep 1
      adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
      B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
      shot 19_keyboard_settings_disabled; geom_rebuild 19_keyboard_settings_disabled 19_ov >/dev/null 2>&1
      note "toolbar with Settings disabled: icons=$(jq -r '.toolbar.n // 0' "$GEOM") (was 6 after pin edit; inspect 19_keyboard_settings_disabled.png: the sun/settings icon must be gone)"
      # re-enable
      adb shell wm density 300 >/dev/null 2>&1; sleep 2
      open_editor 20_ed_before_reenable >/dev/null
      for M in dnd swipe; do
        drag_node "Settings" "Undo" $M 20_ed_before_reenable || break
        shot 20b_ed_after_reenable_$M; xdump 20b_ed_after_reenable_$M
        HY=$(ypos 20b_ed_after_reenable_$M 'Hidden Actions'); SY=$(ypos 20b_ed_after_reenable_$M 'Settings'); FY=$(ypos 20b_ed_after_reenable_$M 'Favorite Actions')
        if [ -n "$SY" ] && [ -n "$FY" ] && [ -n "$HY" ] && [ "$SY" -gt "$FY" ] && [ "$SY" -lt "$HY" ]; then note "PASS: Settings re-enabled into Favorite Actions via $M (settings y=$SY, favorites y=$FY, hidden y=$HY)"; REEN=1; break
        else note "  re-enable via $M failed (settings y=$SY fav y=$FY hidden y=$HY)"; open_editor 20_ed_before_reenable >/dev/null; fi
      done
      if [ "${REEN:-0}" = 1 ]; then
        # reorder: drag Redo onto Undo's slot, assert Redo now left of / above Undo
        shot 21_ed_pre_reorder; xdump 21_ed_pre_reorder
        UX0=$(xpos 21_ed_pre_reorder 'Undo'); RX0=$(xpos 21_ed_pre_reorder 'Redo'); UY0=$(ypos 21_ed_pre_reorder 'Undo'); RY0=$(ypos 21_ed_pre_reorder 'Redo')
        note "  before reorder: Undo x=$UX0 y=$UY0, Redo x=$RX0 y=$RY0"
        drag_node "Redo" "Undo" dnd 21_ed_pre_reorder
        shot 21b_ed_after_reorder; xdump 21b_ed_after_reorder
        UX1=$(xpos 21b_ed_after_reorder 'Undo'); RX1=$(xpos 21b_ed_after_reorder 'Redo'); UY1=$(ypos 21b_ed_after_reorder 'Undo'); RY1=$(ypos 21b_ed_after_reorder 'Redo')
        note "  after reorder: Undo x=$UX1 y=$UY1, Redo x=$RX1 y=$RY1"
        if [ "$RY1" -lt "$UY1" ] || { [ "$RY1" -eq "$UY1" ] && [ "$RX1" -lt "$UX1" ]; }; then note "PASS: drag reorder placed Redo before Undo"; else note "FAIL/INCONCLUSIVE: reorder did not change order (see 21b_ed_after_reorder.png)"; fi
      fi
    fi
  fi
else
  note "FAIL: editor not confirmed open at lowered density (see 18_ed_dense_before.png)"
fi
adb shell wm density reset >/dev/null 2>&1; sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 1; adb shell input keyevent KEYCODE_HOME; sleep 1
adb shell am start -n com.wrenboard.imetarget/.TestInput >/dev/null 2>&1; sleep 2
B=$(edit_bounds); [ -n "$B" ] && tap_bounds "$B"; sleep 2
shot 22_keyboard_final_after_editor; geom_rebuild 22_keyboard_final_after_editor 22_ov >/dev/null 2>&1
note "toolbar at end of editor tests: icons=$(jq -r '.toolbar.n // 0' "$GEOM")"
dim "after-editor-2"
