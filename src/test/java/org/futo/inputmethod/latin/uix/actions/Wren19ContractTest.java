package org.futo.inputmethod.latin.uix.actions;
import org.junit.Test;
import java.nio.file.*;
import static org.junit.Assert.*;

/** WrenBoard 1.9 wiring contracts: single-slot pin and panel back disposition. */
public class Wren19ContractTest {
 private String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), java.nio.charset.StandardCharsets.UTF_8); }

 @Test public void pinSlotReplacesSingleEntryAndUnpins() throws Exception {
  String source=read("java/src/org/futo/inputmethod/latin/uix/actions/MoreActions.kt");
  // Dropping on the pin slot replaces the single pinned action; dropping the pinned action again unpins.
  assertTrue(source.contains("persistPinned(if(dropped in latestPinnedIds) emptyList() else listOf(dropped))"));
  // Tapping the pinned action unpins it.
  assertTrue(source.contains("clickable { persistPinned(emptyList()) }"));
 }

 @Test public void panelOpenAndCloseSyncBackDisposition() throws Exception {
  String uix=read("java/src/org/futo/inputmethod/latin/uix/UixManager.kt");
  String ime=read("java/src/org/futo/inputmethod/latin/LatinIME.kt");
  // The disposition must be pushed to the OS (setBackDisposition), not just answered from a getter.
  assertTrue(ime.contains("fun syncBackDispositionForPanels()"));
  assertTrue(ime.contains("BACK_DISPOSITION_ADJUST_NOTHING"));
  assertTrue(ime.contains("BACK_DISPOSITION_DEFAULT"));
  assertFalse(ime.contains("override fun getBackDisposition()"));
  assertFalse(ime.contains("override fun onBackPressed()"));
  int open=uix.indexOf("currWindowActionWindow.value = (action.windowImpl!!)(keyboardManagerForAction, persistentStates[action])");
  assertTrue(open>=0 && uix.indexOf("latinIME.syncBackDispositionForPanels()",open)>open);
  int close=uix.indexOf("currWindowActionWindow.value = null");
  assertTrue(close>=0 && uix.indexOf("latinIME.syncBackDispositionForPanels()",close)>close);
 }
}
