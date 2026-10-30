package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Test

/** WrenBoard 1.2: starter words are the history predictions as they come; I/The/You only when history has none. */
class WrenStarterWordsTest {
    @Test fun historyIsShownAsPredicted() {
        assertEquals(listOf("Zebra", "Hello", "Thanks", "Ok"), WrenStarterWords.choose(listOf("Zebra", "Hello", "Thanks", "Ok"), enabled = true))
    }

    @Test fun shortHistoryIsNotPadded() {
        assertEquals(listOf("zebra"), WrenStarterWords.choose(listOf("zebra"), enabled = true))
    }

    @Test fun noHistoryGivesFallback() {
        assertEquals(listOf("I", "The", "You"), WrenStarterWords.choose(emptyList(), enabled = true))
    }

    @Test fun toggleOffShowsNothing() {
        assertEquals(emptyList<String>(), WrenStarterWords.choose(listOf("Zebra"), enabled = false))
        assertEquals(emptyList<String>(), WrenStarterWords.choose(emptyList(), enabled = false))
    }
}
