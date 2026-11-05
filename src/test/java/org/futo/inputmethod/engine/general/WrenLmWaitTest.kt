package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/** WrenBoard 1.6 review finding 2: a space must not lose the autocorrection to a late LM result. */
class WrenLmWaitTest {
    private val now = { System.currentTimeMillis() }

    @Test fun resultBeforeDeadlineIsUsed() {
        val w = WrenLmWait<String>(1, now() + 2_000, now)
        thread { Thread.sleep(30); w.set("brown") }
        assertEquals(WrenLmWait.Outcome.RESULT to "brown", w.await())
    }

    @Test fun lateLmTimesOutAtNormalDeadline() {
        val w = WrenLmWait<String>(1, now() + 60, now)
        val t0 = now()
        val (outcome, value) = w.await()
        assertEquals(WrenLmWait.Outcome.TIMED_OUT, outcome)
        assertNull(value)
        assertTrue(now() - t0 >= 55)
        assertTrue(WrenLmWait.countsAsLmTimeout(outcome))
    }

    @Test fun aSupersededPassIsCutShortAndThatIsNotAnLmTimeout() {
        // an older pass is waiting its normal 350 ms; a newer word's space arrives 20 ms in
        val w = WrenLmWait<String>(7, now() + 5_000, now)
        thread { Thread.sleep(20); w.shortenTo(now() + 80, superseded = true) }
        val t0 = now()
        val (outcome, value) = w.await()
        val waited = now() - t0
        assertEquals(WrenLmWait.Outcome.CUT_SHORT, outcome)
        assertNull(value)
        assertTrue("waited $waited ms", waited in 90..1_000)
        // its result is no longer wanted: not the LM's fault, must not switch the LM off
        assertFalse(WrenLmWait.countsAsLmTimeout(outcome))
    }

    @Test fun supersededPassStopsWaitingAtOnce() {
        val w = WrenLmWait<String>(3, now() + 5_000, now)
        val deadline = WrenLmWait.commitDeadlineForWaitingPass(passSequence = 3, commitSequence = 4, nowMs = now(), commitLmDeadlineMs = now() + 250)
        w.shortenTo(deadline, superseded = true)
        val t0 = now()
        assertEquals(WrenLmWait.Outcome.CUT_SHORT, w.await().first)
        assertTrue(now() - t0 < 50)
    }

    @Test fun thePassForTheCommittedWordKeepsTheCommitBudget() {
        val t = 10_000L
        assertEquals(t + 250, WrenLmWait.commitDeadlineForWaitingPass(passSequence = 4, commitSequence = 4, nowMs = t, commitLmDeadlineMs = t + 250))
    }

    @Test fun deadlineNeverMovesLater() {
        val w = WrenLmWait<String>(1, now() + 40, now)
        w.shortenTo(now() + 10_000, superseded = true)
        val t0 = now()
        assertEquals(WrenLmWait.Outcome.TIMED_OUT, w.await().first)
        assertTrue(now() - t0 < 1_000)
    }

    @Test fun resultAlreadyInIsNotCutShort() {
        val w = WrenLmWait<String>(1, now() + 5_000, now)
        w.set("the")
        w.shortenTo(0, superseded = true)
        assertEquals(WrenLmWait.Outcome.RESULT to "the", w.await())
    }

    @Test fun passDeadlineIsTheEarlierOfNormalAndCommit() {
        assertEquals(1_350L, WrenLmWait.deadlineFor(1_000, null))
        assertEquals(1_200L, WrenLmWait.deadlineFor(1_000, 1_200))
        assertEquals(1_350L, WrenLmWait.deadlineFor(1_000, 9_000))
    }

    @Test fun commitBudgetsAreOrderedAndFarBelowTheInputTimeout() {
        assertTrue(WrenLmWait.COMMIT_LM_BUDGET_MS < WrenLmWait.NORMAL_LM_WAIT_MS)
        assertTrue(WrenLmWait.COMMIT_LM_BUDGET_MS < WrenLmWait.COMMIT_MAX_WAIT_MS)
        // Android declares an input ANR after 5 s; the commit never blocks anywhere near that
        assertTrue(WrenLmWait.COMMIT_MAX_WAIT_MS <= 2_000)
    }

    @Test fun theCommittedWordsPassEndsAtTheCommitDeadlineAndTheMissCounts() {
        // the pass for the word being committed may wait only until the commit's LM deadline;
        // the dictionary result then decides, and the miss counts toward FUTO's slow-LM fallback
        val w = WrenLmWait<String>(5, now() + 5_000, now)
        w.shortenTo(now() + 60, superseded = false)
        val t0 = now()
        val (outcome, value) = w.await()
        assertEquals(WrenLmWait.Outcome.TIMED_OUT, outcome)
        assertNull(value)
        assertTrue(now() - t0 in 50..1_000)
        assertTrue(WrenLmWait.countsAsLmTimeout(outcome))
    }

    @Test fun theCommittedWordsPassStillTakesAnLmResultInTime() {
        val w = WrenLmWait<String>(5, now() + 5_000, now)
        w.shortenTo(now() + 2_000, superseded = false)
        thread { Thread.sleep(20); w.set("can't") }
        assertEquals(WrenLmWait.Outcome.RESULT to "can't", w.await())
    }
}
