package org.futo.inputmethod.engine.general

/**
 * WrenBoard 1.2 (Ezzy, Oct 30): starter words in an empty field come from the person's own typing history.
 * GeneralIME asks FUTO's own prediction path with the empty field (beginning-of-sentence context); of what comes
 * back, only words from FUTO's user history dictionary (words they have typed at the start of a field or sentence)
 * are kept, and I / The / You fill the rest of the three slots (all three when there is no history yet).
 * The toggle is StarterWordsSetting (Settings, Typing, predictions; default on): off leaves an empty field's strip
 * empty. Pure Kotlin, unit tested.
 */
object WrenStarterWords {
    val FALLBACK = listOf("I", "The", "You")
    const val SLOTS = 3

    fun choose(history: List<String>, enabled: Boolean): List<String> {
        if (!enabled) return emptyList()
        val out = ArrayList<String>()
        for (w in history + FALLBACK) {
            if (out.size >= SLOTS) break
            if (w.isNotBlank() && out.none { it.equals(w, ignoreCase = true) }) out.add(w)
        }
        return out
    }
}
