package org.futo.inputmethod.latin.utils

/**
 * WrenBoard (owner-approved item 2): auto-correct adjacent-transposition typos such as
 * "brwon", "jumsp", "thsi" and "tset", which the edit-distance threshold scores as two edits
 * and so never corrects. Real words are never changed: a typed word the main or personal
 * dictionary knows is left alone.
 */
object TranspositionCorrection {
    /** Dictionary questions this needs, kept tiny so it can be faked in JVM tests. */
    interface WordCheck {
        /** True when [word] is a real word (main or personal dictionary; NOT learned history). */
        fun isKnown(word: String): Boolean
        /** Frequency of [word] in the main dictionary, or a value below 0 when unknown. */
        fun frequency(word: String): Int = 0
    }

    const val MIN_LENGTH = 3

    /** True when [candidate] is [typed] with exactly one pair of neighbouring, different letters swapped (case-insensitive). */
    @JvmStatic
    fun isAdjacentTransposition(typed: String, candidate: String): Boolean {
        if (typed.length != candidate.length || typed.length < 2) return false
        val a = typed.lowercase()
        val b = candidate.lowercase()
        if (a == b) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        if (i >= a.length - 1) return false
        if (a[i] == a[i + 1]) return false
        if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
        return a.regionMatches(i + 2, b, i + 2, a.length - i - 2)
    }

    private fun known(check: WordCheck, w: String) = check.isKnown(w) || check.isKnown(w.lowercase())

    private fun matchCase(typed: String, word: String): String = when {
        typed.isNotEmpty() && typed[0].isUpperCase() && word.isNotEmpty() ->
            word.substring(0, 1).uppercase() + word.substring(1)
        else -> word
    }

    /**
     * The correction for [typed], or null to leave it alone.
     * [rankedCandidates] are the engine's suggestions in rank order (already case-adjusted); the
     * first adjacent transposition among them that is a real word wins. When none is there, every
     * single adjacent swap of [typed] is tried and the most frequent real word wins, but only when
     * it is strictly more frequent than any other real swap (no coin flips).
     */
    @JvmStatic
    fun pick(typed: String, rankedCandidates: List<String>, check: WordCheck): String? {
        if (typed.length < MIN_LENGTH) return null
        if (typed.any { !it.isLetter() }) return null
        if (known(check, typed)) return null
        for (c in rankedCandidates) {
            if (isAdjacentTransposition(typed, c) && known(check, c)) return c
        }
        val lower = typed.lowercase()
        var best: String? = null
        var bestFreq = Int.MIN_VALUE
        var tie = false
        for (i in 0 until lower.length - 1) {
            if (lower[i] == lower[i + 1]) continue
            val sw = StringBuilder(lower).apply { setCharAt(i, lower[i + 1]); setCharAt(i + 1, lower[i]) }.toString()
            if (!check.isKnown(sw)) continue
            val f = check.frequency(sw)
            if (best == null || f > bestFreq) { best = sw; bestFreq = f; tie = false }
            else if (f == bestFreq) tie = true
        }
        if (best == null || tie) return null
        return matchCase(typed, best)
    }
}
