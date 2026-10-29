package org.futo.inputmethod.keyboard.internal;

import android.test.suitebuilder.annotation.SmallTest;

import org.futo.inputmethod.latin.common.Constants;

/**
 * WrenBoard "Caps lock: tap cycle" mode at the KeyboardState level (the Settings choice sets
 * KeyboardState.setShiftCycleMode before each press). The FUTO-style mode is the default of the mock
 * and is covered by the existing single/multi touch tests.
 */
@SmallTest
public class KeyboardStateShiftCycleTests extends KeyboardStateTestsBase {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mSwitcher.setShiftCycleMode(true);
    }

    public void testTapCycleLowerShiftLockLower() {
        // 1st press: one-shot shift
        pressAndReleaseKey(CODE_SHIFT, ALPHABET_MANUAL_SHIFTED, ALPHABET_MANUAL_SHIFTED);
        // 2nd press, no timer involved: caps lock
        pressAndReleaseKey(CODE_SHIFT, ALPHABET_SHIFT_LOCKED, ALPHABET_SHIFT_LOCKED);
        // letter keys leave the lock alone
        pressAndReleaseKey('a', ALPHABET_SHIFT_LOCKED, ALPHABET_SHIFT_LOCKED);
        // 3rd press: back to lowercase
        pressAndReleaseKey(CODE_SHIFT, ALPHABET_UNSHIFTED, ALPHABET_UNSHIFTED);
        // and the cycle repeats
        pressAndReleaseKey(CODE_SHIFT, ALPHABET_MANUAL_SHIFTED, ALPHABET_MANUAL_SHIFTED);
        pressAndReleaseKey(CODE_SHIFT, ALPHABET_SHIFT_LOCKED, ALPHABET_SHIFT_LOCKED);
    }

    public void testOneShotRevertsAfterOneLetter() {
        pressAndReleaseKey(CODE_SHIFT, ALPHABET_MANUAL_SHIFTED, ALPHABET_MANUAL_SHIFTED);
        pressAndReleaseKey('a', ALPHABET_MANUAL_SHIFTED, ALPHABET_UNSHIFTED);
    }

    public void testChordFromOneShotStillWorks() {
        // Hold shift, tap a letter with the other finger, release shift: back to lowercase.
        pressKey(CODE_SHIFT, ALPHABET_MANUAL_SHIFTED);
        chordingPressAndReleaseKey('A', ALPHABET_MANUAL_SHIFTED, ALPHABET_MANUAL_SHIFTED);
        releaseKey(CODE_SHIFT, ALPHABET_UNSHIFTED);
    }

    public void testCapsLockEmitAfterPressLockDoesNotUnlock() {
        // Mode flipped mid-hold: a press already locked, then a CODE_CAPSLOCK emit arrives.
        pressAndReleaseKey(CODE_SHIFT, ALPHABET_MANUAL_SHIFTED, ALPHABET_MANUAL_SHIFTED);
        pressKey(CODE_SHIFT, ALPHABET_SHIFT_LOCKED);
        mSwitcher.onPressKey(Constants.CODE_CAPSLOCK, true /* isSinglePointer */);
        mSwitcher.onCodeInput(Constants.CODE_CAPSLOCK);
        mSwitcher.onReleaseKey(Constants.CODE_CAPSLOCK, NOT_SLIDING);
        assertEquals("guard keeps the lock", ALPHABET_SHIFT_LOCKED, mSwitcher.getLayoutId());
    }

    public void testAutoCapitalisedShiftBehavesLikeUpstreamMode() {
        // The cycle branch only fires from the manual one-shot page, so the auto-capitalised page
        // must give the same result in both modes. Compare against a second switcher in FUTO mode.
        final int[] cycle = autoCapPress(true);
        final int[] futo = autoCapPress(false);
        assertEquals("after press", futo[0], cycle[0]);
        assertEquals("after release", futo[1], cycle[1]);
    }

    private int[] autoCapPress(final boolean cycleMode) {
        final MockKeyboardSwitcher sw = new MockKeyboardSwitcher();
        sw.setShiftCycleMode(cycleMode);
        sw.setAutoCapsMode(CAP_MODE_WORDS);
        sw.loadKeyboard();
        sw.onPressKey(CODE_SHIFT, true);
        final int p = sw.getLayoutId();
        sw.onCodeInput(CODE_SHIFT);
        sw.onReleaseKey(CODE_SHIFT, NOT_SLIDING);
        return new int[] { p, sw.getLayoutId() };
    }
}
