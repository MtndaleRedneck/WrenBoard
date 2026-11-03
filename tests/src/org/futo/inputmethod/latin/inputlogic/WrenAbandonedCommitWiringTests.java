/*
 * Executed wiring tests for the starter-words learning fix: when an auto-correct
 * commit is abandoned because suggestions missed their time budget, the typed word
 * must still be learned exactly once, with the same order and gates as a normal
 * commit. These drive the real InputLogic with the real InputConnection and the real
 * dictionaries; a recording facilitator counts and orders the learning calls, and the
 * known synthetic fixture word is verified as actually stored in the user history
 * dictionary (PASS/FAIL only - no word content leaves the test process).
 */
package org.futo.inputmethod.latin.inputlogic;

import org.futo.inputmethod.latin.NgramContext;
import org.futo.inputmethod.latin.settings.Settings;

import java.util.List;

public class WrenAbandonedCommitWiringTests extends WrenWiringTestBase {

    private static final long HISTORY_WRITE_TIMEOUT_MS = 5000;

    /**
     * Timeout path: the exact call the abandon branch makes learns the typed word
     * exactly once, in commitChosenWord's order (onWordCommitted before the history
     * write), and the word that lands in history is the known typed word.
     */
    public void testAbandonedCommitLearnsTypedWordExactlyOnceThroughRealPath() {
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

    /** N-gram, start of field: the abandoned learn carries no real previous word. */
    public void testAbandonedCommitNgramContextAtStartOfField() {
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();

        final List<WrenWiringTestBase.LearnRecord> learns = mRecorder.learnsFor("zebra");
        assertEquals(1, learns.size());
        final String ctx = learns.get(0).context.extractPrevWordsContext();
        assertFalse("context must not contain the word being committed: " + ctx,
                ctx.contains("zebra"));
        assertTrue("start-of-field context is empty or sentence start, got: '" + ctx + "'",
                ctx.isEmpty() || ctx.contains(NgramContext.BEGINNING_OF_SENTENCE_TAG));
    }

    /** N-gram, prior word: the abandoned learn carries the real previous word. */
    public void testAbandonedCommitNgramContextWithPriorWord() {
        type("hello ");
        runMessages();
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();

        final List<WrenWiringTestBase.LearnRecord> learns = mRecorder.learnsFor("zebra");
        assertEquals(1, learns.size());
        final String ctx = learns.get(0).context.extractPrevWordsContext();
        assertTrue("context contains the actual previous word 'hello', got: '" + ctx + "'",
                ctx.contains("hello"));
    }

    /** Privacy gate: personalization off blocks the history write on the real path. */
    public void testPersonalizationOffBlocksAbandonedLearn() {
        setBooleanPreference(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, false, true);
        runMessages();
        assertFalse("precondition: personalization is off",
                mSettings.getCurrent().isPersonalizationEnabled());
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();
        assertTrue("no user-history write when personalization is off",
                mRecorder.learnsFor("zebra").isEmpty());
        setBooleanPreference(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, true, true);
        runMessages();
    }

    /** Slow InputConnection gate: learning is skipped on the real path. */
    public void testSlowInputConnectionBlocksAbandonedLearn() throws Exception {
        forceSlowInputConnection();
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();
        assertTrue("no user-history write on a slow input connection",
                mRecorder.learnsFor("zebra").isEmpty());
        resetSlowInputConnection();
    }

    /**
     * Multi-locale: after switching locales the abandoned learn still records and
     * stores, exercising the confidence-based group selection on a fresh locale.
     */
    public void testAbandonedCommitLearnsAfterLocaleSwitch() {
        mRecorder.clearRecords();
        type("zebra");
        callAbandonedLearn();
        assertEquals(1, mRecorder.learnsFor("zebra").size());
        assertTrue("en word stored", waitForHistoryWord("zebra", HISTORY_WRITE_TIMEOUT_MS));

        changeLanguage("fr_FR");
        mRecorder.clearRecords();
        type("bonjour");
        callAbandonedLearn();
        assertEquals("abandoned learn still happens after the locale switch",
                1, mRecorder.learnsFor("bonjour").size());
        assertTrue("fr word stored in the current-locale history",
                waitForHistoryWord("bonjour", HISTORY_WRITE_TIMEOUT_MS));
    }
}
