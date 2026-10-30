package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Test

/** WrenBoard 1.2 fix1b: starter words come from typing history; I/The/You only fill what history does not. */
class WrenStarterWordsTest {
    @Test fun historyWordsComeFirst() {
        assertEquals(listOf("Zebra", "Hello", "Thanks"), WrenStarterWords.choose(listOf("Zebra", "Hello", "Thanks", "Ok"), enabled = true))
    }

    @Test fun shortHistoryIsFilledWithFallbackWithoutDuplicates() {
        assertEquals(listOf("zebra", "I", "The"), WrenStarterWords.choose(listOf("zebra"), enabled = true))
        assertEquals(listOf("the", "I", "You"), WrenStarterWords.choose(listOf("the"), enabled = true))
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
