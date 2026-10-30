package org.futo.inputmethod.latin.uix

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.LatinIME
import org.futo.inputmethod.latin.SuggestedWords
import org.futo.inputmethod.latin.uix.actions.AllActionsMap

/**
 * DEBUG BUILDS ONLY (every entry point checks BuildConfig.DEBUG; the receiver is never registered in a release
 * build). Lets the CI emulator harness find keys on screen and open panels without guessing pixels:
 *  - logcat tag WBDBG, lines "KEY code=.. label=.. cx=.. cy=.. w=.. h=.." in SCREEN pixels (key centres)
 *  - "MORE ..." lines for an open long-press panel, "SUGG ..." lines for every suggestion strip update
 *  - adb shell am broadcast -a com.wrenboard.keyboard.WBDBG --es cmd geom|expand|collapse|open:<action>|close
 */
object WbDebug {
    const val TAG = "WBDBG"
    const val ACTION = "com.wrenboard.keyboard.WBDBG"

    fun logKeyboard(view: View?, keyboard: Keyboard?) {
        if (!BuildConfig.DEBUG || view == null || keyboard == null) return
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        Log.i(TAG, "KBD x=${loc[0]} y=${loc[1]} w=${view.width} h=${view.height} layout=${keyboard.mId.mKeyboardLayoutSetName} elementId=${keyboard.mId.mElementId}")
        for (key in keyboard.sortedKeys) {
            val cx = loc[0] + view.paddingLeft + key.x + key.width / 2
            val cy = loc[1] + view.paddingTop + key.y + key.height / 2
            Log.i(TAG, "KEY code=${key.code} label=${key.label.replace(' ', '_')} cx=$cx cy=$cy w=${key.width} h=${key.height}")
        }
        Log.i(TAG, "KEYEND")
    }

    @JvmStatic
    fun logMoreKeys(panel: View, keyboard: Keyboard?, originX: Int, originY: Int, winX: Int, winY: Int, parent: View) {
        if (!BuildConfig.DEBUG || keyboard == null) return
        val loc = IntArray(2)
        parent.getLocationOnScreen(loc)
        for (key in keyboard.sortedKeys) {
            val cx = loc[0] + originX + panel.paddingLeft + key.x + key.width / 2
            val cy = loc[1] + originY + panel.paddingTop + key.y + key.height / 2
            Log.i(TAG, "MORE code=${key.code} label=${key.label.replace(' ', '_')} cx=$cx cy=$cy w=${key.width} h=${key.height}")
        }
        Log.i(TAG, "MOREEND")
    }

    fun logWindow(what: String) {
        if (!BuildConfig.DEBUG) return
        Log.i(TAG, "WINDOW $what")
    }

    fun logSuggestions(words: SuggestedWords?) {
        if (!BuildConfig.DEBUG) return
        val list = words?.mSuggestedWordInfoList?.joinToString("|") { it.mWord } ?: ""
        Log.i(TAG, "SUGG [$list]")
    }

    fun install(ime: LatinIME) {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val cmd = intent?.getStringExtra("cmd") ?: return
                Log.i(TAG, "CMD $cmd")
                try {
                    when {
                        cmd == "geom" -> {
                            val v = ime.latinIMELegacy.mKeyboardSwitcher.mainKeyboardView
                            logKeyboard(v, v?.keyboard)
                        }
                        cmd == "expand" -> ime.uixManager.debugSetActionsExpanded(true)
                        cmd == "collapse" -> ime.uixManager.debugSetActionsExpanded(false)
                        cmd == "close" -> ime.uixManager.closeActionWindow()
                        cmd.startsWith("prefbool:") -> {
                            val kv = cmd.removePrefix("prefbool:").split("=")
                            PreferenceUtils.getDefaultSharedPreferences(ime).edit()
                                .putBoolean(kv[0], kv.getOrNull(1) == "true").apply()
                        }
                        cmd.startsWith("seedupdate:") -> {
                            // seedupdate:<version>|<apk url> : store an update-check result as if the check had found it
                            val parts = cmd.removePrefix("seedupdate:").split("|", limit = 2)
                            val result = org.futo.inputmethod.updates.UpdateResult(
                                org.futo.inputmethod.updates.versionStringToCode(parts[0]), parts.getOrElse(1) { "" }, parts[0]
                            )
                            ime.setSettingBlocking(org.futo.inputmethod.updates.LAST_UPDATE_CHECK_RESULT, kotlinx.serialization.json.Json.encodeToString(org.futo.inputmethod.updates.UpdateResult.serializer(), result))
                            ime.setSettingBlocking(org.futo.inputmethod.updates.LAST_UPDATE_CHECK_FAILED, false)
                            Log.i(TAG, "SEEDED update ${parts[0]}")
                        }
                        cmd.startsWith("clipset:") -> {
                            // clipset:<text> : put text on the system clipboard (the history panel records it)
                            val cm = ime.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("wbdbg", cmd.removePrefix("clipset:").replace('_', ' ')))
                        }
                        cmd.startsWith("settings:") -> {
                            org.futo.inputmethod.latin.uix.settings.SettingsActivity.openToNavDest(ime, cmd.removePrefix("settings:").ifEmpty { null })
                        }
                        cmd.startsWith("open:") -> {
                            val action = AllActionsMap[cmd.removePrefix("open:")]
                            if (action != null) ime.uixManager.debugOpenAction(action)
                            else Log.i(TAG, "CMD unknown action ${cmd.removePrefix("open:")}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "CMD $cmd failed", e)
                }
            }
        }
        ContextCompat.registerReceiver(ime, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
        Log.i(TAG, "receiver installed")
    }
}
