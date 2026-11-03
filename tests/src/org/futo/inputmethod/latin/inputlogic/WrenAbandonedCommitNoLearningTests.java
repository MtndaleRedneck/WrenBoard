/*
 * Privacy gate wiring test: in a no-learning field (IME_FLAG_NO_PERSONALIZED_LEARNING)
 * an abandoned auto-correct commit must not write anything to the user history
 * dictionary. The flag sets InputAttributes.mNoLearning WITHOUT disabling
 * auto-correction, so the noLearning gate is the branch exercised - asserted as a
 * precondition on both gate variables. Runs as its own class because the field type
 * is fixed at startInput time.
 */
package org.futo.inputmethod.latin.inputlogic;

import android.view.inputmethod.EditorInfo;

public class WrenAbandonedCommitNoLearningTests extends WrenWiringTestBase {

    @Override
    protected EditorInfo enrichEditorInfo(final EditorInfo ei) {
        ei.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        return ei;
    }

    public void testAbandonedCommitDoesNotLearnInNoLearningField() {
        assertTrue("precondition: this is a no-learning field",
                mSettings.getCurrent().mInputAttributes.mNoLearning);
        assertTrue("precondition: auto-correction stays on, so the noLearning gate "
                + "is the branch under test",
                mSettings.getCurrent().mAutoCorrectionEnabledPerTextFieldSettings);
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();
        assertEquals("no user-history write at all in a no-learning field",
                0, mRecorder.totalLearnCount());
    }
}
