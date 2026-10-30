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
