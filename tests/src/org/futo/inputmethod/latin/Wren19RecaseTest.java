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
        type("they");
        type(Constants.CODE_SHIFT);
        assertEquals("first shift tap capitalizes", "They", mEditText.getText().toString());
        type(Constants.CODE_SHIFT);
        assertEquals("second shift tap upper-cases", "THEY", mEditText.getText().toString());
        type(Constants.CODE_SHIFT);
        assertEquals("third shift tap restores lowercase", "they", mEditText.getText().toString());
    }

    public void testShiftRecaseWorksInFreshFieldBeforeAnyCursorMove() {
        // The field is fresh from setUp: the cursor has never moved, so this exercises the
        // typing-recase bypass of the stale-selection gate.
        type("they");
        type(Constants.CODE_SHIFT);
        assertEquals("recase applies before the first cursor move", "They",
                mEditText.getText().toString());
    }

    public void testShiftWithNoWordKeepsFieldUntouched() {
        assertEquals("", mEditText.getText().toString());
        type(Constants.CODE_SHIFT);
        assertEquals("no word to re-case leaves the field untouched", "",
                mEditText.getText().toString());
    }
}
