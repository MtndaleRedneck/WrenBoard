package org.futo.inputmethod.latin.uix

/**
 * WrenBoard 1.10 (review): the suggestion-strip blankGuard decision, pure and unit tested.
 * The guard fills the strip's DISPLAY copy with synthetic starter words; the raw suggestion value
 * is never altered by these decisions, so non-display consumers (the expandable-container gate)
 * keep their original input. Split into two phases so the one binder IPC (the cursor read) only
 * runs when every cheaper gate has already passed.
 */
object SuggestionStripGuard {
    /** Phase 1, no IPC: settings and field gates for the starter-words display fill. */
    fun settingsAllowFill(starterWordsOn: Boolean, fieldTakesSuggestions: Boolean, suggestionsEnabled: Boolean): Boolean =
        starterWordsOn && fieldTakesSuggestions && suggestionsEnabled

    /** Phase 2, after the cursor read: letters/digits before the cursor suppress starters (mid-word typing). */
    fun cursorAllowsFill(textBeforeCursor: CharSequence?): Boolean =
        textBeforeCursor.isNullOrEmpty() || !textBeforeCursor[0].isLetterOrDigit()
}
