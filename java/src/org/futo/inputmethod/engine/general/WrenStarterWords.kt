package org.futo.inputmethod.engine.general

/**
 * WrenBoard 1.2 (Ezzy, Oct 30): starter words in an empty field come from the person's own typing history.
 * GeneralIME asks FUTO's own prediction path first with an empty field (beginning-of-sentence context), which
 * reads the user history dictionary (words they have typed at the start of a field or sentence). Only when that
 * gives nothing (a fresh install, history off, or nothing learned yet) do the plain fallback words stand in.
 * The on/off toggle is StarterWordsSetting (Settings, Typing, predictions; default on). Pure Kotlin, unit tested.
 */
object WrenStarterWords {
    val FALLBACK = listOf("I", "The", "You")

    /** What the empty-field strip shows: the predicted (history) words when there are any, else the fallback. */
    fun choose(predicted: List<String>, enabled: Boolean): List<String> = when {
        !enabled -> emptyList()
        predicted.any { it.isNotBlank() } -> predicted.filter { it.isNotBlank() }
        else -> FALLBACK
    }
}
