/*
 * Privacy gate wiring test: in a no-learning field (password variation) an abandoned
 * auto-correct commit must not write anything to the user history dictionary.
 * Runs the same real-path machinery as WrenAbandonedCommitWiringTests with a password
 * EditorInfo, as its own class because the field type is fixed at startInput time.
 */
package org.futo.inputmethod.latin.inputlogic;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

public class WrenAbandonedCommitNoLearningTests extends WrenWiringTestBase {

    @Override
    protected EditorInfo enrichEditorInfo(final EditorInfo ei) {
        ei.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        return ei;
    }

    public void testAbandonedCommitDoesNotLearnInNoLearningField() {
        assertTrue("precondition: this is a no-learning field",
                mSettings.getCurrent().mInputAttributes.mNoLearning);
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();
        assertTrue("no user-history write in a no-learning field",
                mRecorder.learnsFor("zebra").isEmpty());
    }
}
