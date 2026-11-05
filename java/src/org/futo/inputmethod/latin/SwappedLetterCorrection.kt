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

    /**
     * Swapped-letter typos that some web-built dictionaries list as words ("teh", "adn"), so the
     * "typed word is not a known word" test lets them through. SwiftKey corrects these every time.
     * Each maps to its one intended word, which is used only when it is itself a known word.
     * (Emulator, 5b81a99: "teh " stayed "teh" three times out of three.)
     */
    private val KNOWN_TYPOS = mapOf(
        "teh" to "the", "hte" to "the", "adn" to "and", "taht" to "that", "waht" to "what",
        "jsut" to "just", "yuo" to "you", "tihs" to "this", "thsi" to "this", "wnat" to "want",
        "konw" to "know", "liek" to "like", "mroe" to "more", "nto" to "not"
    )

    /**
     * The intended word for a listed swapped-letter typo ([KNOWN_TYPOS]), with the typed
     * capitalisation ("Teh" -> "The"), or null when [typed] is not one of them.
     */
    @JvmStatic
    fun knownTypoTarget(typed: String): String? {
        val target = KNOWN_TYPOS[typed.lowercase(Locale.ROOT)] ?: return null
        if (!isAdjacentSwap(typed, target)) return null
        return if (typed.isNotEmpty() && Character.isUpperCase(typed[0]))
            target.replaceFirstChar { it.titlecase(Locale.ROOT) } else target
    }

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
