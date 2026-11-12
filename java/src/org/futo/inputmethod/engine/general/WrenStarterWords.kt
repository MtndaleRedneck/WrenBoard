package org.futo.inputmethod.engine.general

/**
 * WrenBoard 1.2 (Ezzy, Oct 30): starter words in an empty field come from the person's own typing history.
 * GeneralIME asks FUTO's own prediction path with the empty field (beginning-of-sentence context); of what comes
 * back, only words from FUTO's user history dictionary (words they have typed at the start of a field or sentence)
 * are shown, exactly as predicted (no padding, no extra cut). Only when history gives none do I / The / You stand in.
 * The toggle is StarterWordsSetting (Settings, Typing, predictions; default on): off leaves an empty field's strip
 * empty. Pure Kotlin, unit tested.
 */
object WrenStarterWords {
    val FALLBACK = listOf("I", "The", "You")

    fun choose(history: List<String>, enabled: Boolean): List<String> = when {
        !enabled -> emptyList()
        history.isNotEmpty() -> history
        else -> FALLBACK
    }
}

/** WrenBoard 1.10: "goingblank" -> "going blank". Pure, unit tested; the dictionary check is passed in. */
object WrenWordSplit {
    fun best(typed: String, isWord: (String) -> Boolean): String? {
        if (typed.length < 4 || typed.length > 24 || !typed.all { it.isLetter() }) return null
        val lower = typed.lowercase()
        if (isWord(lower)) return null
        var best: String? = null
        var bestScore = 0
        for (i in 1 until lower.length) {
            val a = lower.substring(0, i); val b = lower.substring(i)
            val okA = a.length >= 2 || a == "a" || a == "i"
            val okB = b.length >= 2 || b == "a" || b == "i"
            if (!okA || !okB || !isWord(a) || !isWord(b)) continue
            val score = minOf(a.length, b.length)
            if (score > bestScore) { bestScore = score; best = "$a $b" }
        }
        return best
    }
}
