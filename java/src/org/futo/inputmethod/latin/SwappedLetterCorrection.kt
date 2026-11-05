package org.futo.inputmethod.latin

import java.util.Locale

/**
 * WrenBoard (item 6): swapped-letter typo autocorrect, as SwiftKey does it.
 *
 * When the typed word is not a known word and swapping ONE pair of neighbouring letters turns it
 * into a known word ("recieve" -> "receive", "teh" -> "the", "becuase" -> "because"), that word is
 * the autocorrection, even when the normal score threshold would not have picked it.
 *
 * Pure logic, no Android types, so it is covered by a plain JVM unit test.
 */
object SwappedLetterCorrection {
    /** Shortest typed word this applies to ("teh" is 3 letters). */
    const val MIN_LENGTH = 3

    /** Every distinct word made by swapping one pair of neighbouring, different letters. */
    @JvmStatic
    fun adjacentSwaps(word: String): List<String> {
        if (word.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        val chars = word.toCharArray()
        for (i in 0 until chars.size - 1) {
            val a = chars[i]
            val b = chars[i + 1]
            if (a == b || !Character.isLetter(a) || !Character.isLetter(b)) continue
            chars[i] = b; chars[i + 1] = a
            out.add(String(chars))
            chars[i] = a; chars[i + 1] = b
        }
        return out.toList()
    }

    /** True when [candidate] is [typed] with exactly one neighbouring pair swapped (case-insensitive). */
    @JvmStatic
    fun isAdjacentSwap(typed: String, candidate: String): Boolean {
        if (typed.length != candidate.length || typed.length < 2) return false
        val t = typed.lowercase(Locale.ROOT)
        val c = candidate.lowercase(Locale.ROOT)
        if (t == c) return false
        var i = 0
        while (i < t.length && t[i] == c[i]) i++
        if (i >= t.length - 1) return false
        if (t[i] != c[i + 1] || t[i + 1] != c[i] || t[i] == t[i + 1]) return false
        if (!Character.isLetter(t[i]) || !Character.isLetter(t[i + 1])) return false
        return t.regionMatches(i + 2, c, i + 2, t.length - i - 2)
    }

    /**
     * Index in [suggestions] of the first word that is [typed] with one neighbouring pair swapped,
     * or -1. Suggestions are in rank order, so the first match is the best one.
     */
    @JvmStatic
    fun findInSuggestions(typed: String, suggestions: List<String>): Int {
        if (typed.length < MIN_LENGTH) return -1
        for (i in suggestions.indices) {
            if (isAdjacentSwap(typed, suggestions[i])) return i
        }
        return -1
    }

    /**
     * When the suggestions do not contain the swapped word, ask the dictionaries directly.
     * Returns the swapped word (keeping the typed capitalisation) only when exactly ONE swap is a
     * valid word; two or more valid swaps are ambiguous and nothing is returned.
     */
    @JvmStatic
    fun findInDictionary(typed: String, isValidWord: (String) -> Boolean): String? {
        if (typed.length < MIN_LENGTH) return null
        var found: String? = null
        for (swap in adjacentSwaps(typed)) {
            val lower = swap.lowercase(Locale.ROOT)
            if (isValidWord(swap) || (lower != swap && isValidWord(lower))) {
                if (found != null) return null
                found = swap
            }
        }
        return found
    }
}
