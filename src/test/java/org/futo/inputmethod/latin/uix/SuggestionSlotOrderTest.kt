package org.futo.inputmethod.latin.uix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestionSlotOrderTest {
    @Test fun activeAutocorrectionIsTheCommit() {
        assertEquals("the", spaceCommitSuggestion("the", "teh", listOf("there", "they")))
    }
    @Test fun inactiveAutocorrectionKeepsTypedWordEvenWithAlternatives() {
        assertEquals("teh", spaceCommitSuggestion(null, "teh", listOf("the", "there")))
    }
    @Test fun predictionsUseTheBestWhenNoWordIsBeingTyped() {
        assertEquals("hello", spaceCommitSuggestion(null, null, listOf("hello", "hi")))
    }
    @Test fun emptyFrameKeepsEmptyCommit() {
        assertNull(spaceCommitSuggestion<String>(null, null, emptyList()))
    }
    @Test fun leftDoesNotRepeatTypedCommit() {
        assertEquals("the", leftSuggestion("teh", "teh", listOf("teh", "the")) { it })
    }
    @Test fun leftKeepsTypedWhenCentreIsCorrection() {
        assertEquals("teh", leftSuggestion("teh", "the", listOf("the", "there")) { it })
    }
    @Test fun onlyTypedCandidateLeavesLeftEmpty() {
        assertNull(leftSuggestion("teh", "teh", listOf("teh")) { it })
    }
    @Test fun displayTextNotObjectIdentityRemovesDuplicates() {
        data class Candidate(val text: String, val score: Int)
        assertEquals("the", leftSuggestion(Candidate("teh", 1), Candidate("teh", 2),
            listOf(Candidate("teh", 3), Candidate("the", 4))) { it.text }?.text)
    }
}
