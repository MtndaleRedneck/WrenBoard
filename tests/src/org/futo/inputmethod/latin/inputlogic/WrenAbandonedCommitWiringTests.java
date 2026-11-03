/*
 * Executed wiring tests for the starter-words learning fix: when an auto-correct
 * commit is abandoned because suggestions missed their time budget, the typed word
 * must still be learned exactly once, with the same order and gates as a normal
 * commit. These drive the real InputLogic with the real InputConnection and the real
 * dictionaries; a recording facilitator counts and orders the learning calls, and the
 * known synthetic fixture word is verified as actually stored in the user history
 * dictionary (PASS/FAIL only - no word content leaves the test process).
 *
 * Claim bound: the seam tests prove the branch BODY - the exact learning call the
 * abandon branch makes - exactly once. They do not prove the 450ms timeout routing
 * itself; the on-device rev13-J trace (CI run 41629290203) is that evidence.
 */
package org.futo.inputmethod.latin.inputlogic;

import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.futo.inputmethod.engine.general.GeneralIME;
import org.futo.inputmethod.latin.Dictionary;
import org.futo.inputmethod.latin.NgramContext;
import org.futo.inputmethod.latin.SuggestedWords;
import org.futo.inputmethod.latin.settings.Settings;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class WrenAbandonedCommitWiringTests extends WrenWiringTestBase {

    private static final long HISTORY_WRITE_TIMEOUT_MS = 5000;

    /**
     * Timeout-path branch BODY: the exact call the abandon branch makes learns the
     * typed word exactly once, in commitChosenWord's order (onWordCommitted before
     * the history write), and the stored word is the known typed word.
     */
    public void testAbandonedLearnBodyLearnsTypedWordExactlyOnce() {
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();

        final List<WrenWiringTestBase.LearnRecord> learns = mRecorder.learnsFor("zebra");
        assertEquals("typed word learned exactly once", 1, learns.size());

        final List<String> events = mRecorder.eventsSnapshot();
        final int commitIdx = events.indexOf("commit:zebra");
        final int learnIdx = events.indexOf("learn:zebra");
        assertTrue("onWordCommitted ran before the history write (order: " + events + ")",
                commitIdx >= 0 && learnIdx > commitIdx);

        assertTrue("the word actually stored in user history is the known word 'zebra'",
                waitForHistoryWord("zebra", HISTORY_WRITE_TIMEOUT_MS));
    }

    /** Normal path: a fast separator commit learns the typed word exactly once. */
    public void testNormalSeparatorCommitLearnsExactlyOnce() {
        mRecorder.clearRecords();
        type("zebra ");
        runMessages();

        assertEquals("normal commit learned the typed word exactly once",
                1, mRecorder.learnsFor("zebra").size());
        assertEquals("onWordCommitted fired exactly once",
                1, countOf(mRecorder.eventsSnapshot(), "commit:zebra"));
        assertTrue("the word actually stored in user history is the known word 'zebra'",
                waitForHistoryWord("zebra", HISTORY_WRITE_TIMEOUT_MS));
    }

    /**
     * Actual starter-OUTPUT identity: the production starter-words function shows
     * exactly the fallback set on a fresh history, then exactly the learned word with
     * no padding once the word is in the user history. UNIT-level composition: the
     * learned-word leg feeds a manually constructed input whose source dict is the real
     * user-history dictionary; the wire-level coverage (real empty-field prediction) is
     * testEmptyFieldPredictionRealWireSurfacesLearnedWord.
     */
    public void testStarterOutputIdentityFallbackThenLearnedWord() throws Exception {
        // Fresh history (per setUp's explicit clear): exactly the fallback set, in order.
        assertEquals("fallback starters, exactly and in order",
                Arrays.asList("I", "The", "You"),
                invokeStarterWordsIfNeeded(SuggestedWords.getEmptyInstance()));

        type("zebra ");
        runMessages();
        assertTrue("zebra stored", waitForHistoryWord("zebra", HISTORY_WRITE_TIMEOUT_MS));

        // Back to an empty field, then feed the production starter function the history
        // word the way the empty-field prediction feeds it.
        mEditText.setText("");
        sendUpdateForCursorMoveTo(0);
        assertEquals("starter output is exactly the learned word, no padding",
                Arrays.asList("zebra"),
                invokeStarterWordsIfNeeded(predictionWithHistoryWord("zebra")));
    }

    /** N-gram, start of field: the abandoned learn carries exactly a sentence start. */
    public void testAbandonedCommitNgramContextAtStartOfField() {
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();

        final List<WrenWiringTestBase.LearnRecord> learns = mRecorder.learnsFor("zebra");
        assertEquals(1, learns.size());
        final String ctx = learns.get(0).context.extractPrevWordsContext();
        assertEquals("start-of-field context is exactly the sentence-start marker",
                NgramContext.BEGINNING_OF_SENTENCE.extractPrevWordsContext(), ctx);
        assertFalse("context must not contain the word being committed: " + ctx,
                ctx.contains("zebra"));
    }

    /** N-gram, prior word: the abandoned learn carries exactly the real previous word. */
    public void testAbandonedCommitNgramContextWithPriorWord() {
        type("hello ");
        runMessages();
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();

        final List<WrenWiringTestBase.LearnRecord> learns = mRecorder.learnsFor("zebra");
        assertEquals(1, learns.size());
        final String ctx = learns.get(0).context.extractPrevWordsContext();
        assertEquals("context is exactly the sentence start then the previous word",
                "<S> hello", ctx);
    }

    /** Privacy gate: personalization off blocks every history write on the real path. */
    public void testPersonalizationOffBlocksAbandonedLearn() {
        setBooleanPreference(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, false, true);
        runMessages();
        assertFalse("precondition: personalization is off",
                mSettings.getCurrent().isPersonalizationEnabled());
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();
        assertEquals("no user-history write at all when personalization is off",
                0, mRecorder.totalLearnCount());
        setBooleanPreference(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, true, true);
        runMessages();
    }

    /** Auto-correction gate: with auto-correction off the real path writes nothing. */
    public void testAutoCorrectionOffBlocksAbandonedLearn() {
        setBooleanPreference(Settings.PREF_AUTO_CORRECTION, false, true);
        runMessages();
        assertFalse("precondition: the gate's own per-field variable reads off",
                mSettings.getCurrent().mAutoCorrectionEnabledPerTextFieldSettings);
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();
        assertEquals("no user-history write at all when auto-correction is off",
                0, mRecorder.totalLearnCount());
        setBooleanPreference(Settings.PREF_AUTO_CORRECTION, true, true);
        runMessages();
    }

    /** Slow InputConnection gate: learning is skipped on the real path. */
    public void testSlowInputConnectionBlocksAbandonedLearn() throws Exception {
        forceSlowInputConnection();
        mRecorder.clearRecords();
        type("zebra");
        assertTrue("slow-connection gate engaged just before the call",
                mInputLogic.mConnection.hasSlowInputConnection());
        callAbandonedLearn();
        assertEquals("no user-history write at all on a slow input connection",
                0, mRecorder.totalLearnCount());
        resetSlowInputConnection();
    }


    /**
     * REAL WIRE: a word learned through the real commit path surfaces in the actual
     * empty-field prediction the engine delivers - the full production pipeline
     * (updateSuggestions -> real dictionaries -> showSuggestionStrip(starterWordsIfNeeded)
     * -> inputLogic.setSuggestedWords), not a manually constructed input. The delivered
     * strip is exactly the learned word, still sourced from the real user-history dict.
     * Claim bound: the transformer passes through to legacy here (asserted below), so the
     * transformer leg itself is outside this test's coverage.
     */
    public void testEmptyFieldPredictionRealWireSurfacesLearnedWord() throws Exception {
        // Dynamic absence: the setUp clear is real, so the learned word is absent first.
        assertFalse("history starts empty (clear actually deleted the history file)",
                isInLocaleGroupHistory(Locale.US, "zebra"));
        // Precondition: transformer passes through to legacy in this environment
        // (USE_TRANSFORMER_FINETUNING defaults false; no model files on the runner).
        final Field lmF = getField(GeneralIME.class, "languageModelFacilitator");
        final Object lm = lmF.get(mGeneralIme);
        final Method pass = lm.getClass().getDeclaredMethod("shouldPassThroughToLegacy");
        pass.setAccessible(true);
        assertTrue("transformer passes through to legacy here", (Boolean) pass.invoke(lm));

        mRecorder.clearRecords();
        type("zebra ");
        runMessages();
        assertTrue("zebra stored", waitForHistoryWord("zebra", HISTORY_WRITE_TIMEOUT_MS));

        // Restart input on an empty field, mirroring setUp's input-start sequence.
        mLatinIMELegacy.onFinishInputView(true);
        mLatinIMELegacy.onFinishInput();
        runMessages();
        mEditText.setText("");
        final EditorInfo ei = enrichEditorInfo(new EditorInfo());
        final InputConnection ic = mEditText.onCreateInputConnection(ei);
        getService().onCreateInputMethodInterface().startInput(ic, ei);
        getService().onStartInputView(ei, false);

        // The actual production trigger for a fresh suggestion pass on the empty field.
        mGeneralIme.updateSuggestions(SuggestedWords.INPUT_STYLE_TYPING);
        mGeneralIme.ensureSuggestionsCompleted();

        SuggestedWords delivered = null;
        final long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            final SuggestedWords w = mInputLogic.mSuggestedWords;
            if (w != null && w.size() > 0) {
                delivered = w;
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull("engine delivered empty-field suggestions", delivered);
        assertEquals("delivered strip is exactly the learned word", 1, delivered.size());
        assertEquals("zebra", delivered.getWord(0));
        assertEquals("delivered word is sourced from the real user-history dictionary",
                Dictionary.TYPE_USER_HISTORY,
                delivered.getInfo(0).mSourceDict.mDictType);
    }
    /**
     * Multi-locale with SIMULTANEOUS groups: with several locale groups loaded at
     * once, a zero-confidence write is refused (the refusal branch requires
     * groups > 1 by construction), and the real abandoned-learn path - which runs
     * onWordCommitted first - lands the word in the confidence-selected locale group.
     */
    public void testMultiLocaleSimultaneousGroupsConfidenceSelection() throws Exception {
        // Both locales' history files persist across runs. fr has no live dictionary
        // yet, so delete its file directly (the same removal the clear performs); en_US is
        // held by the recorder, so clear it through the real API and await the deletion.
        final File frFile = new File(getContext().getFilesDir(),
                "UserHistoryDictionary." + Locale.FRANCE + ".dict");
        if (frFile.exists()) assertTrue("delete stale fr history file", frFile.delete());
        mRecorder.clearUserHistoryDictionary(getContext());
        awaitUserHistoryFileAbsent(Locale.US, 10_000);
        awaitUserHistoryFileAbsent(Locale.FRANCE, 10_000);
        resetFacilitatorLocales(Arrays.asList(Locale.US, Locale.FRANCE));
        assertFalse("en_US history empty after clear", isInLocaleGroupHistory(Locale.US, "zebra"));
        assertFalse("fr history empty after clear", isInLocaleGroupHistory(Locale.FRANCE, "zebra"));
        assertTrue("precondition: several locale groups loaded simultaneously",
                mRecorder.getLocales().size() > 1);

        // Zero-confidence precondition: the write attempt happens but is refused
        // (addToUserHistory returns early when the selected group has confidence 0
        // and more than one group exists).
        mRecorder.clearRecords();
        mRecorder.addToUserHistory("bonjour", false /* wasAutoCapitalized */,
                NgramContext.BEGINNING_OF_SENTENCE,
                TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()),
                false /* blockPotentiallyOffensive */);
        assertEquals("the write attempt was made", 1, mRecorder.learnsFor("bonjour").size());
        assertFalse("zero-confidence write is refused by group selection",
                waitForHistoryWord("bonjour", 2000));
        assertFalse("refused write not in the fr group either",
                isInLocaleGroupHistory(Locale.FRANCE, "bonjour"));

        // Real seam path: onWordCommitted first -> the fr group is selected and accepts.
        mRecorder.clearRecords();
        type("bonjour");
        callAbandonedLearn();
        assertEquals(1, mRecorder.learnsFor("bonjour").size());
        final List<String> events = mRecorder.eventsSnapshot();
        final int commitIdx = events.indexOf("commit:bonjour");
        final int learnIdx = events.indexOf("learn:bonjour");
        assertTrue("confidence update precedes the write (order: " + events + ")",
                commitIdx >= 0 && learnIdx > commitIdx);
        assertTrue("word stored after the confidence update",
                waitForHistoryWord("bonjour", HISTORY_WRITE_TIMEOUT_MS));
        assertTrue("stored in the confidence-selected fr group",
                isInLocaleGroupHistory(Locale.FRANCE, "bonjour"));
        assertFalse("not stored in the en group",
                isInLocaleGroupHistory(Locale.US, "bonjour"));
    }
}
