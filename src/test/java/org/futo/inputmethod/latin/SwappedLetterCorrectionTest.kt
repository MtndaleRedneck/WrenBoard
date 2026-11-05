package org.futo.inputmethod.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwappedLetterCorrectionTest {
    private val dict = setOf("the", "receive", "because", "believe", "form", "from", "and", "their")

    @Test fun swapsAreNeighbourPairsOnly() {
        assertEquals(listOf("eth", "the"), SwappedLetterCorrection.adjacentSwaps("teh"))
        assertEquals(emptyList<String>(), SwappedLetterCorrection.adjacentSwaps("aa"))
    }

    @Test fun recognisesSwappedLetterTypos() {
        assertTrue(SwappedLetterCorrection.isAdjacentSwap("recieve", "receive"))
        assertTrue(SwappedLetterCorrection.isAdjacentSwap("teh", "the"))
        assertTrue(SwappedLetterCorrection.isAdjacentSwap("Recieve", "Receive"))
        assertTrue(SwappedLetterCorrection.isAdjacentSwap("becuase", "because"))
    }

    @Test fun rejectsOtherEdits() {
        assertFalse(SwappedLetterCorrection.isAdjacentSwap("recieve", "recieve")) // same word
        assertFalse(SwappedLetterCorrection.isAdjacentSwap("recieve", "relieve")) // substitution
        assertFalse(SwappedLetterCorrection.isAdjacentSwap("teh", "ten"))
        assertFalse(SwappedLetterCorrection.isAdjacentSwap("abcd", "badc")) // two swaps
        assertFalse(SwappedLetterCorrection.isAdjacentSwap("abc", "cba")) // not neighbours
        assertFalse(SwappedLetterCorrection.isAdjacentSwap("the", "thee")) // length
        assertFalse(SwappedLetterCorrection.isAdjacentSwap("a1b", "1ab")) // digits are not letters
    }

    @Test fun picksFirstRankedSwapFromSuggestions() {
        assertEquals(1, SwappedLetterCorrection.findInSuggestions("recieve", listOf("relieve", "receive", "recieved")))
        assertEquals(-1, SwappedLetterCorrection.findInSuggestions("recieve", listOf("relieve", "recipe")))
        assertEquals(-1, SwappedLetterCorrection.findInSuggestions("ot", listOf("to"))) // too short
    }

    @Test fun dictionaryFallbackKeepsCaseAndRefusesAmbiguity() {
        val valid: (String) -> Boolean = { it in dict }
        assertEquals("receive", SwappedLetterCorrection.findInDictionary("recieve", valid))
        assertEquals("Receive", SwappedLetterCorrection.findInDictionary("Recieve", valid))
        assertEquals("the", SwappedLetterCorrection.findInDictionary("teh", valid))
        assertNull(SwappedLetterCorrection.findInDictionary("xyzzy", valid))
        // "fomr" -> only "form" is valid
        assertEquals("form", SwappedLetterCorrection.findInDictionary("fomr", valid))
        // two valid swaps -> ambiguous -> nothing
        assertNull(SwappedLetterCorrection.findInDictionary("bcad") { it == "cbad" || it == "bacd" })
    }
}
