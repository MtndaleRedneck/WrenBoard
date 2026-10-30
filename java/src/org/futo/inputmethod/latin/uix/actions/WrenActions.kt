package org.futo.inputmethod.latin.uix.actions

import android.content.Intent
import android.util.Log
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
