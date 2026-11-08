package org.futo.inputmethod.latin.uix

/** A typed word remains the Space commit unless the engine explicitly enables autocorrection. */
internal fun <T> spaceCommitSuggestion(autocorrection: T?, typed: T?, predictions: List<T>): T? =
    autocorrection ?: typed ?: predictions.firstOrNull()

/** Do not repeat the actual Space commit in the left slot. Equality uses display text, not object identity. */
internal fun <T, K> leftSuggestion(typed: T?, centre: T?, predictions: List<T>, key: (T) -> K): T? {
    val centreKey = centre?.let(key)
    return typed?.takeIf { key(it) != centreKey }
        ?: predictions.firstOrNull { key(it) != centreKey }
}
