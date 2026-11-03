package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import android.provider.UserDictionary
import android.view.inputmethod.ExtractedTextRequest
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Save word (1.5 item 12, Tab's pinned candidate for owner review - owner asked only for the
 * button, 9:50 AM): saves the word being typed, else the last typed word, else the selection,
 * into the personal dictionary. Confirms on the centre suggestion chip for 1.2 s - no toast. */
val SaveWordAction = Action(
    icon = R.drawable.wren_ic_bookmark_add,
    name = R.string.wren_action_save_word,
    simplePressImpl = { manager, _ ->
        val context = manager.getContext()
        val ic = manager.getLatinIMEForDebug().currentInputConnection
        fun String?.cleanWord(): String? =
            this?.trim()?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
        val composing = ic?.getExtractedText(ExtractedTextRequest(), 0)?.let { et ->
            if (et.partialStartOffset >= 0 && et.partialEndOffset > et.partialStartOffset)
                et.text?.subSequence(et.partialStartOffset, et.partialEndOffset)?.toString() else null
        }.cleanWord()
        val lastTyped = ic?.getTextBeforeCursor(200, 0)?.toString()
            ?.trimEnd()?.takeLastWhile { it.isLetterOrDigit() || it == '\'' || it == '-' }.cleanWord()
        val selected = ic?.getSelectedText(0)?.toString().cleanWord()
        val word = composing ?: lastTyped ?: selected
        when {
            word == null -> WrenSaveWordNotice.show(context.getString(R.string.wren_word_type_first))
            alreadyInUserDictionary(context, word) ->
                WrenSaveWordNotice.show(context.getString(R.string.wren_word_already_saved))
            else -> {
                UserDictionaryCompatUtils.addWord(context, word, 250, null, null)
                WrenSaveWordNotice.show(context.getString(R.string.wren_word_saved_chip, word))
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
    val notice = MutableStateFlow<Notice?>(null)
    fun show(text: String) { notice.value = Notice(text) }
    fun clear() { notice.value = null }
}
