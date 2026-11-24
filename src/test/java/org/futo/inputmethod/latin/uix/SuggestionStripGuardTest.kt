package org.futo.inputmethod.latin.uix

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WrenBoard 1.10 (review): targeted guard tests for the suggestion-strip blankGuard decision.
 * The raw/display wiring itself (expandable gate reads raw, strip draw sites read the display
 * copy) is pinned by tools/wren110-harness-lint.sh on the release-runtime branch; these tests
 * pin the decision logic both phases route through.
 */
class SuggestionStripGuardTest {
    @Test fun emptyFieldAllOnFills() {
        assertTrue(SuggestionStripGuard.settingsAllowFill(starterWordsOn = true, fieldTakesSuggestions = true, suggestionsEnabled = true))
        assertTrue(SuggestionStripGuard.cursorAllowsFill(null))
        assertTrue(SuggestionStripGuard.cursorAllowsFill(""))
    }

    @Test fun afterLetterSuppresses() {
        assertFalse(SuggestionStripGuard.cursorAllowsFill("a"))
        assertFalse(SuggestionStripGuard.cursorAllowsFill("3"))
    }

    @Test fun punctuationOnlyStillFills() {
        assertTrue(SuggestionStripGuard.cursorAllowsFill("."))
        assertTrue(SuggestionStripGuard.cursorAllowsFill(" "))
    }

    @Test fun starterWordsToggleOffSuppresses() {
        assertFalse(SuggestionStripGuard.settingsAllowFill(starterWordsOn = false, fieldTakesSuggestions = true, suggestionsEnabled = true))
    }

    @Test fun fieldWithoutSuggestionsSuppresses() {
        assertFalse(SuggestionStripGuard.settingsAllowFill(starterWordsOn = true, fieldTakesSuggestions = false, suggestionsEnabled = true))
    }

    @Test fun suggestionsDisabledSuppresses() {
        assertFalse(SuggestionStripGuard.settingsAllowFill(starterWordsOn = true, fieldTakesSuggestions = true, suggestionsEnabled = false))
    }
}
