package org.futo.inputmethod.latin.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranspositionCorrectionTest {
    // A small stand-in for the main dictionary with rough frequencies.
    private val dict = mapOf(
        "the" to 255, "brown" to 160, "jumps" to 120, "this" to 240, "test" to 170, "stet" to 20,
        "quick" to 150, "fox" to 130, "form" to 160, "from" to 230, "trial" to 120, "silent" to 110,
        "listen" to 140, "united" to 120, "untied" to 60, "was" to 240, "saw" to 170, "on" to 250, "no" to 245,
        "angel" to 110, "angle" to 115, "calm" to 100, "clam" to 60, "dog" to 140
    )
    private val check = object : TranspositionCorrection.WordCheck {
        override fun isKnown(word: String) = dict.containsKey(word.lowercase())
        override fun frequency(word: String) = dict[word.lowercase()] ?: -1
    }

    @Test fun fixesTheFourReportedTypos() {
        assertEquals("brown", TranspositionCorrection.pick("brwon", emptyList(), check))
        assertEquals("jumps", TranspositionCorrection.pick("jumsp", emptyList(), check))
        assertEquals("this", TranspositionCorrection.pick("thsi", emptyList(), check))
        // "tset" swaps to both "test" and "stet"; the far more common word wins
        assertEquals("test", TranspositionCorrection.pick("tset", emptyList(), check))
    }

    @Test fun prefersTheEnginesRankedCandidate() {
        assertEquals("stet", TranspositionCorrection.pick("tset", listOf("stet", "test"), check))
        assertEquals("brown", TranspositionCorrection.pick("brwon", listOf("bro", "brown"), check))
    }

    @Test fun keepsSentenceCase() {
        assertEquals("Brown", TranspositionCorrection.pick("Brwon", emptyList(), check))
        assertEquals("This", TranspositionCorrection.pick("Thsi", listOf("This"), check))
    }

    @Test fun neverChangesRealWords() {
        for (w in listOf("form", "from", "trial", "silent", "listen", "united", "untied", "was", "saw",
                "angel", "angle", "calm", "clam", "the", "dog")) {
            assertNull(w, TranspositionCorrection.pick(w, listOf("from", "form", "untied", "united"), check))
        }
    }

    @Test fun ignoresShortWordsNumbersAndSymbols() {
        assertNull(TranspositionCorrection.pick("ot", emptyList(), check))
        assertNull(TranspositionCorrection.pick("th1s", emptyList(), check))
        assertNull(TranspositionCorrection.pick("bro-wn", emptyList(), check))
    }

    @Test fun noCorrectionWhenNothingRealIsOneSwapAway() {
        assertNull(TranspositionCorrection.pick("qzxv", emptyList(), check))
        assertNull(TranspositionCorrection.pick("brwn", listOf("brown"), check)) // missing letter, not a swap
    }

    @Test fun transpositionShape() {
        assertTrue(TranspositionCorrection.isAdjacentTransposition("brwon", "brown"))
        assertTrue(TranspositionCorrection.isAdjacentTransposition("Thsi", "this"))
        assertFalse(TranspositionCorrection.isAdjacentTransposition("brown", "brown"))
        assertFalse(TranspositionCorrection.isAdjacentTransposition("abcd", "badc")) // two swaps
        assertFalse(TranspositionCorrection.isAdjacentTransposition("abc", "cba")) // not neighbours
        assertFalse(TranspositionCorrection.isAdjacentTransposition("aab", "aab"))
    }
}
