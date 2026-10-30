package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Test

/** WrenBoard 1.2 fix1b: starter words come from typing history, with I/The/You only when there is none. */
class WrenStarterWordsTest {
    @Test fun historyWordsWinOverFallback() {
        assertEquals(listOf("Zebra", "Hello"), WrenStarterWords.choose(listOf("Zebra", "Hello"), enabled = true))
    }

    @Test fun noHistoryGivesFallback() {
        assertEquals(listOf("I", "The", "You"), WrenStarterWords.choose(emptyList(), enabled = true))
        assertEquals(listOf("I", "The", "You"), WrenStarterWords.choose(listOf("", " "), enabled = true))
    }

    @Test fun toggleOffShowsNothing() {
        assertEquals(emptyList<String>(), WrenStarterWords.choose(listOf("Zebra"), enabled = false))
        assertEquals(emptyList<String>(), WrenStarterWords.choose(emptyList(), enabled = false))
    }
}
