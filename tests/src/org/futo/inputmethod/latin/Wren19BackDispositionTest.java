/*
 * WrenBoard 1.9 (item 6): while a sub-panel is open the IME pushes
 * BACK_DISPOSITION_ADJUST_NOTHING to the OS (nav bar shows a back arrow) and restores
 * BACK_DISPOSITION_DEFAULT when the panel closes. Asserts the pushed value through the
 * real open/close path (UixManager.enterActionWindowView / closeActionWindow ->
 * LatinIME.syncBackDispositionForPanels). The OS-side icon render is covered by the
 * WB18_NAV log and nav-bar pixel check in tools/wren18-runtime.sh.
 */
package org.futo.inputmethod.latin;

import android.inputmethodservice.InputMethodService;

import androidx.test.filters.LargeTest;

import org.futo.inputmethod.latin.uix.Action;
import org.futo.inputmethod.latin.uix.actions.RegistryKt;

@LargeTest
public class Wren19BackDispositionTest extends InputTestsBase {

    public void testBackDispositionAdjustNothingWhilePanelOpen() {
        android.util.Log.i("WBDBG", "TESTSTEP backdisp1: getAllActionsMap");
        final Action emoji = RegistryKt.getAllActionsMap().get("emoji");
        assertNotNull("emoji action exists in AllActionsMap", emoji);
        android.util.Log.i("WBDBG", "TESTSTEP backdisp1: debugOpenAction");
        getService().getUixManager().debugOpenAction(emoji);
        android.util.Log.i("WBDBG", "TESTSTEP backdisp1: assert open");
        assertTrue("panel reports open", getService().getUixManager().hasOpenActionWindow());
        assertEquals("panel open pushes ADJUST_NOTHING to the OS",
                InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING,
                getService().getBackDisposition());
    }

    public void testBackDispositionRestoredToDefaultAfterPanelClose() {
        android.util.Log.i("WBDBG", "TESTSTEP backdisp2: getAllActionsMap");
        final Action emoji = RegistryKt.getAllActionsMap().get("emoji");
        assertNotNull("emoji action exists in AllActionsMap", emoji);
        android.util.Log.i("WBDBG", "TESTSTEP backdisp2: debugOpenAction");
        getService().getUixManager().debugOpenAction(emoji);
        assertEquals("panel open pushes ADJUST_NOTHING to the OS",
                InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING,
                getService().getBackDisposition());
        android.util.Log.i("WBDBG", "TESTSTEP backdisp2: closeActionWindow");
        getService().getUixManager().closeActionWindow(true);
        assertFalse("panel reports closed", getService().getUixManager().hasOpenActionWindow());
        assertEquals("panel close restores DEFAULT",
                InputMethodService.BACK_DISPOSITION_DEFAULT,
                getService().getBackDisposition());
    }
}
