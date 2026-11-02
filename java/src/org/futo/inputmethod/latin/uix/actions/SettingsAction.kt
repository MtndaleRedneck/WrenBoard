package org.futo.inputmethod.latin.uix.actions

import android.content.Intent
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.settings.SettingsActivity

val SettingsAction = Action(
    // WrenBoard 1.5: swap the "sun" glyph for the WrenBoard gear (same Material gear used in the clipboard panel).
    icon = R.drawable.wren_ic_settings,
    name = R.string.go_to_settings,
    simplePressImpl = { manager, _ ->
        val intent = Intent()
        intent.setClass(manager.getContext(), SettingsActivity::class.java)
        intent.setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
        manager.getContext().startActivity(intent)
    },
    windowImpl = null,
)