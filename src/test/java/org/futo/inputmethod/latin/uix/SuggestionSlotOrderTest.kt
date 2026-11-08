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
}
