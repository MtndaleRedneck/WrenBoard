package org.futo.inputmethod.latin.inputlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WrenAbandonedCommitLearningTest {
    @Test
    fun typedWordIsLearnedWhenCommitIsAbandoned() {
        // The whole point of the fix: a finalized typed word survives a slow suggestion pass.
        assertEquals("zebra", WrenAbandonedCommitLearning.wordToLearn("zebra"))
    }

    @Test
    fun emptyCompositionLearnsNothing() {
        assertNull(WrenAbandonedCommitLearning.wordToLearn(""))
    }

    @Test
    fun ngramLookbackMatchesCommitChosenWordRule() {
        // Still composing: skip the word being committed (2nd previous word is context).
        assertEquals(2, WrenAbandonedCommitLearning.nthPreviousWord(true))
        // Not composing: 1st previous word is context.
        assertEquals(1, WrenAbandonedCommitLearning.nthPreviousWord(false))
    }
}
