package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import android.provider.UserDictionary
import android.view.inputmethod.EditorInfo
import org.futo.inputmethod.latin.utils.InputTypeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.compat.UserDictionaryCompatUtils
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.settings.SettingsActivity

// WrenBoard 1.2 toolbar actions (approved render 03 / 10c: Clipboard, Emoji, GIF, Voice, Share, Info, Settings, More).
// They are appended to the END of AllActionsMap so no existing action's key code moves.

/** Which emoji-panel tab the next open should show. Set by the GIF button, read and cleared by the emoji panel. */
object EmojiPanelRequest {
    @Volatile var openOnGifTab: Boolean = false
}

val GifAction = Action(
    icon = R.drawable.wren_gif,
    name = R.string.wren_action_gif,
    simplePressImpl = { manager, _ ->
        EmojiPanelRequest.openOnGifTab = true
        manager.activateAction(EmojiAction)
    },
    windowImpl = null,
)

/** Share: hands the selected text (or, with nothing selected, the text in the field) to Android's share sheet. */
val ShareAction = Action(
    icon = R.drawable.wren_share,
    name = R.string.wren_action_share,
    simplePressImpl = { manager, _ ->
        val ime = manager.getLatinIMEForDebug()
        val ic = ime.currentInputConnection
        val selected = ic?.getSelectedText(0)?.toString()
        val text = if (!selected.isNullOrEmpty()) selected else {
            val before = ic?.getTextBeforeCursor(5000, 0)?.toString() ?: ""
            val after = ic?.getTextAfterCursor(5000, 0)?.toString() ?: ""
            before + after
        }
        if (text.isNotBlank()) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(send, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                manager.getContext().startActivity(chooser)
            } catch (e: Exception) {
                Log.w("ShareAction", "share sheet failed: ${e.javaClass.simpleName}")
            }
        }
    },
    windowImpl = null,
)

/** Info: opens WrenBoard's About page (version, update check, licenses). */
val InfoAction = Action(
    icon = R.drawable.wren_info,
    name = R.string.wren_action_info,
    simplePressImpl = { manager, _ ->
        val intent = Intent()
        intent.setClass(manager.getContext(), SettingsActivity::class.java)
        intent.putExtra("navDest", "about")
        intent.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        manager.getContext().startActivity(intent)
    },
    windowImpl = null,
)

/** Save word (1.5 item 12): saves the word being typed, else the last typed word, else the
 * selection, into the personal dictionary. Confirms on the centre suggestion chip for 1.2 s -
 * no toast. (Request provenance lives in the conversation record; this comment is not
 * authorization evidence.) */
val SaveWordAction = Action(
    icon = R.drawable.wren_ic_bookmark_add,
    name = R.string.wren_action_save_word,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        // Eligibility BEFORE any text retrieval, failing closed on a missing/stale editor.
        // Mirrors the app's trusted no-learning classification (InputAttributes.mNoLearning):
        // explicit IME_FLAG_NO_PERSONALIZED_LEARNING, every password input type, code fields.
        val ime = manager.getLatinIMEForDebug()
        val editorGeneration = ime.actionEditorGeneration
        val requestGeneration = WrenSaveWordNotice.beginRequest()
        val ei = ime.currentInputEditorInfo
        val noLearningFlag = ei != null &&
                (ei.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val codeField = ei != null && ei.packageName != null && (
                (ei.packageName.startsWith("com.replit") &&
                        (ei.inputType and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) == 0) ||
                (ei.packageName.startsWith("com.termux") && ei.inputType == 0) ||
                (ei.packageName.startsWith("sh.haven.app") && ei.inputType == 0) ||
                (ei.packageName.startsWith("com.android.virtualization.terminal") &&
                        (ei.inputType and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0))
        // AOSP keeps visible-password separate from isPasswordInputType; block it too.
        val blockedField = ei == null || noLearningFlag ||
                InputTypeUtils.isPasswordInputType(ei.inputType) ||
                InputTypeUtils.isVisiblePasswordInputType(ei.inputType) || codeField
        if (blockedField) {
            WrenSaveWordNotice.show(context.getString(R.string.wren_word_save_blocked_field))
        } else {
        val ic = manager.getLatinIMEForDebug().currentInputConnection
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '-'
        fun String?.cleanWord(): String? =
            this?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        // A multi-word selection is ambiguous: ask for one word instead of silently
        // falling back to the last typed word.
        val selectedRaw = ic?.getSelectedText(0)?.toString()?.trim()
        val multiWord = selectedRaw != null && selectedRaw.any(Char::isWhitespace)
        val selected = if (multiWord) null else selectedRaw.cleanWord()
        // The word under the cursor: before-cursor tail joined to the after-cursor head,
        // so a mid-word save gets the whole word. (ExtractedText.partialStartOffset/EndOffset
        // are change-range fields of a partial extract, not the composing span - not usable here.)
        val beforeTail = ic?.getTextBeforeCursor(200, 0)?.toString()?.takeLastWhile(::isWordChar)
        val afterHead = ic?.getTextAfterCursor(200, 0)?.toString()?.takeWhile(::isWordChar)
        val atCursor = (beforeTail.orEmpty() + afterHead.orEmpty()).cleanWord()
        val lastTyped = ic?.getTextBeforeCursor(200, 0)?.toString()
            ?.trimEnd()?.takeLastWhile(::isWordChar).cleanWord()
        // Explicit selection first, then the word at the cursor, then the word before the cursor.
        val word = selected ?: atCursor ?: lastTyped
        when {
            multiWord -> WrenSaveWordNotice.show(context.getString(R.string.wren_word_select_one))
            word == null -> WrenSaveWordNotice.show(context.getString(R.string.wren_word_type_first))
            else -> {
                // Everything the chip needs is snapshotted here on the UI thread; the provider
                // IPCs (addWord + re-queries) run off the UI thread and touch no editor state.
                // The framework addWord call is void: it returns no success result. Verify the
                // write before claiming success, so a failed save never shows a false "Saved" chip.
                val appContext = context.applicationContext
                manager.getLifecycleScope().launch {
                    if (ime.actionEditorGeneration != editorGeneration ||
                        !WrenSaveWordNotice.isCurrentRequest(requestGeneration)) return@launch
                    var preExisting = false
                    val persisted = withContext(Dispatchers.IO) { try {
                        preExisting = alreadyInUserDictionary(appContext, word)
                        preExisting || run {
                            UserDictionaryCompatUtils.addWord(appContext, word, 250, null, null)
                            alreadyInUserDictionary(appContext, word)
                        }
                    } catch (_: Exception) { false } }
                    // Never apply a delayed chip to a new/closed editor or a newer save request.
                    if (ime.actionEditorGeneration != editorGeneration ||
                        !WrenSaveWordNotice.isCurrentRequest(requestGeneration)) return@launch
                    WrenSaveWordNotice.show(
                        if (persisted) appContext.getString(
                            if (preExisting) R.string.wren_word_already_saved
                            else R.string.wren_word_saved_chip, word)
                        else appContext.getString(R.string.wren_word_save_failed))
                }
            }
        }
        }
    },
    windowImpl = null,
)

private fun alreadyInUserDictionary(context: Context, word: String): Boolean = try {
    context.contentResolver.query(
        UserDictionary.Words.CONTENT_URI, arrayOf(UserDictionary.Words.WORD),
        "${UserDictionary.Words.WORD}=?", arrayOf(word), null
    )?.use { it.moveToFirst() } == true
} catch (_: Exception) { false }

/** Transient centre-chip text (save-word confirmation). New instance per show so StateFlow re-emits. */
object WrenSaveWordNotice {
    data class Notice(val text: String, val at: Long = android.os.SystemClock.uptimeMillis())
    private var requestGeneration = 0L
    fun beginRequest(): Long = ++requestGeneration
    fun isCurrentRequest(value: Long): Boolean = value == requestGeneration
    val notice = MutableStateFlow<Notice?>(null)
    fun show(text: String) { notice.value = Notice(text) }
    fun clear() { notice.value = null }
}
