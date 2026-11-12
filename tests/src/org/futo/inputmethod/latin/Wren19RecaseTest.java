/*
 * WrenBoard 1.9: shift re-cases the word just typed (they, They, THEY) and its
 * suggestions follow, in a fresh field before any cursor move too. These tests assert
 * the actual field text after each tap, through the real input logic and EditText.
 */
package org.futo.inputmethod.latin;

import androidx.test.filters.LargeTest;

import org.futo.inputmethod.latin.common.Constants;

@LargeTest
public class Wren19RecaseTest extends InputTestsBase {

    public void testShiftRecaseCyclesWordJustTyped() {
        android.util.Log.i("WBDBG", "TESTSTEP recase1: type they");
        type("they");
        android.util.Log.i("WBDBG", "TESTSTEP recase1: shift 1");
        type(Constants.CODE_SHIFT);
        assertEquals("first shift tap capitalizes", "They", mEditText.getText().toString());
        android.util.Log.i("WBDBG", "TESTSTEP recase1: shift 2");
        type(Constants.CODE_SHIFT);
        assertEquals("second shift tap upper-cases", "THEY", mEditText.getText().toString());
        android.util.Log.i("WBDBG", "TESTSTEP recase1: shift 3");
        type(Constants.CODE_SHIFT);
        assertEquals("third shift tap restores lowercase", "they", mEditText.getText().toString());
    }

    public void testShiftRecaseWorksInFreshFieldBeforeAnyCursorMove() {
        // The field is fresh from setUp: the cursor has never moved, so this exercises the
        // typing-recase bypass of the stale-selection gate.
        android.util.Log.i("WBDBG", "TESTSTEP recase2: type they");
        type("they");
        android.util.Log.i("WBDBG", "TESTSTEP recase2: shift");
        type(Constants.CODE_SHIFT);
        assertEquals("recase applies before the first cursor move", "They",
                mEditText.getText().toString());
    }

    public void testShiftWithNoWordKeepsFieldUntouched() {
        assertEquals("", mEditText.getText().toString());
        android.util.Log.i("WBDBG", "TESTSTEP recase3: shift on empty field");
        type(Constants.CODE_SHIFT);
        assertEquals("no word to re-case leaves the field untouched", "",
                mEditText.getText().toString());
    }
}
