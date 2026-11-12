package org.futo.inputmethod.engine

import org.futo.inputmethod.latin.uix.getSetting
import android.content.Context
import android.graphics.Rect
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import org.futo.inputmethod.engine.general.ActionInputTransactionIME
import org.futo.inputmethod.keyboard.KeyboardSwitcher
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.settings.Settings
import org.futo.inputmethod.latin.uix.FloatingPreEdit

interface InputMethodConnectionProvider {
    fun getCurrentInputConnection(): InputConnection?
    fun getCurrentEditorInfo(): EditorInfo?
    fun getContextForSettings(): Context
}

data class ExpandableSuggestionBarConfiguration(
    val useExpandableUi: Boolean,
    val addExtraSpaceForFirstEntry: Boolean
)

val NonExpandableSuggestionBar = ExpandableSuggestionBarConfiguration(false, false)

class IMEHelper(
    private val latinIME: LatinIME
) : InputMethodConnectionProvider {
    val context: Context
        get() = latinIME.applicationContext

    val lifecycleScope: LifecycleCoroutineScope
        get() = latinIME.lifecycleScope

    public val keyboardSwitcher: KeyboardSwitcher
        get() = latinIME.latinIMELegacy.mKeyboardSwitcher

    public val keyboardShiftMode: Int
        get() = latinIME.latinIMELegacy.mKeyboardSwitcher.keyboardShiftMode

    public val currentKeyboardScriptId: Int
        get() = latinIME.latinIMELegacy.mKeyboardSwitcher.currentKeyboardScriptId

    public val keyboardRect: Rect
        get() = run {
            val kb = latinIME.latinIMELegacy.mKeyboardSwitcher.keyboard
            return Rect(0, 0, kb?.mBaseWidth ?: 1, kb?.mBaseHeight ?: 1)
        }

    public fun updateBoostedCodePoints(codes: Set<Int>?) {
        latinIME.latinIMELegacy.mKeyboardSwitcher?.mainKeyboardView?.mKeyDetector?.let {
            it.updateBoostedCodePoints(codes)
        }
    }

    public fun updateUiInputState(textEmpty: Boolean) {
        latinIME.uixManager.onInputEvent(textEmpty)
    }

    public fun getCodepointCoordinates(codePoints: IntArray): IntArray {
        return latinIME.latinIMELegacy.getCoordinatesForCurrentKeyboard(codePoints)
    }

    public fun triggerAction(actionId: Int, alt: Boolean) {
        latinIME.uixManager.triggerActionInternalFromIme(actionId, alt)
    }

    override fun getCurrentInputConnection(): InputConnection? {
        return latinIME.currentInputConnection
    }

    override fun getCurrentEditorInfo(): EditorInfo? {
        return latinIME.currentInputEditorInfo
    }

    override fun getContextForSettings(): Context = context

    /**
     * WrenBoard 1.10 (Ezzy: "the top bar keeps going blank"): whenever the strip would show nothing at the start of
     * a field or after punctuation/space, show I / The / You instead (only when starter words are on and the field
     * takes suggestions). Never while a word is being typed.
     */
    private fun neverBlank(words: SuggestedWords?): SuggestedWords {
        val empty = SuggestedWords.getEmptyInstance()
        if (words != null && !words.isEmpty) return words
        try {
            if (!context.getSetting(org.futo.inputmethod.latin.uix.StarterWordsSetting)) return empty
            val cur = Settings.getInstance().current
            if (!cur.needsToLookupSuggestions() || !cur.isSuggestionsEnabledPerUserSettings) return empty
            val before = latinIME.currentInputConnection?.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && before[0].isLetterOrDigit()) return empty
            val list = ArrayList<SuggestedWords.SuggestedWordInfo>()
            org.futo.inputmethod.engine.general.WrenStarterWords.FALLBACK.forEach { w ->
                list.add(SuggestedWords.SuggestedWordInfo(w, "", SuggestedWords.SuggestedWordInfo.MAX_SCORE,
                    SuggestedWords.SuggestedWordInfo.KIND_PREDICTION, org.futo.inputmethod.latin.Dictionary.DICTIONARY_HARDCODED,
                    SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX, SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE))
            }
            return SuggestedWords(list, null, null, false, false, false, SuggestedWords.INPUT_STYLE_TYPING, -1)
        } catch (e: Exception) { return empty }
    }

    fun setNeutralSuggestionStrip(cfg: ExpandableSuggestionBarConfiguration = NonExpandableSuggestionBar) {
        latinIME.setSuggestions(
            suggestedWords = neverBlank(null),
            rtlSubtype = Settings.getInstance().current.mIsRTL,
            cfg = cfg
        )
    }

    fun showSuggestionStrip(suggestedWords: SuggestedWords?, cfg: ExpandableSuggestionBarConfiguration = NonExpandableSuggestionBar) {
        latinIME.setSuggestions(
            suggestedWords = neverBlank(suggestedWords),
            rtlSubtype = Settings.getInstance().current.mIsRTL,
            cfg = cfg
        )
    }

    fun endInputTransaction(inputTransactionIME: ActionInputTransactionIME) {
        latinIME.imeManager.endInputTransaction(inputTransactionIME)
    }

    fun updateGestureAvailability(to: Boolean) {
        latinIME.latinIMELegacy.mKeyboardSwitcher?.mainKeyboardView?.setImeAllowsGestureInput(to)
    }

    /**
     * Please call this in `onEvent` before processing the event. It will make sure any pending
     * cursor update gets processed (they are typically delayed 20ms for debouncing purposes)
     *
     * In the future, IMEManager should probably call this automatically, but the current control
     * flow doesn't allow that
     */
    fun requestCursorUpdate() {
        latinIME.imeManager.ensureUpdateSelectionFinished()
    }

    fun isImeActive(ime: IMEInterface) =
        latinIME.imeManager.getActiveIME(Settings.getInstance().current) == ime

    fun setPreedit(preEdit: FloatingPreEdit?) {
        latinIME.uixManager.setPreedit(preEdit)
    }
}