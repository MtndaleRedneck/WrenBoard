package org.futo.inputmethod.latin.uix

/** A typed word remains the Space commit unless the engine explicitly enables autocorrection. */
internal fun <T> spaceCommitSuggestion(autocorrection: T?, typed: T?, predictions: List<T>): T? =
    autocorrection ?: typed ?: predictions.firstOrNull()
