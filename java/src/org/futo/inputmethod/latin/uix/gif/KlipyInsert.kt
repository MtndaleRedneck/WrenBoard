package org.futo.inputmethod.latin.uix.gif

import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction

/**
 * WrenBoard 1.5 (item 1): insert a picked GIF straight into the field (Nov 1 agreed flow).
 * No edit prompt, no URL/paste fallback. If the field cannot take a GIF we offer nothing
 * (the stickers setting, off by default, is the only escape hatch - wired when that setting
 * lands). On success the share is registered with KLIPY so recents/trending stay accurate.
 */

val WrenStickersEnabled = SettingsKey(booleanPreferencesKey("wren_stickers_enabled"), false)

object KlipyInsert {
    // ignoreConnectionOverride = true: capability and insertion always target the HOST
    // editor, never the panel's own local search field while its input override is active.
    fun canOffer(manager: KeyboardManagerForAction): Boolean =
        manager.appSupportsImageInsertion("image/gif", true) ||
            manager.appSupportsImageInsertion("video/mp4", true)


    fun pickVariant(gif: KlipyClient.KlipyGif, supports: (String) -> Boolean): Pair<KlipyClient.KlipyVariant, String>? {
        if (supports("video/mp4")) {
            gif.fullMp4()?.takeIf { KlipyGifPipe.isAllowedUrl(it.url) }?.let { return it to "video/mp4" }
        }
        if (supports("image/gif")) {
            gif.fullGif()?.takeIf { KlipyGifPipe.isAllowedUrl(it.url) }?.let { return it to "image/gif" }
        }
        return null
    }

    fun insert(manager: KeyboardManagerForAction, appKey: String, gif: KlipyClient.KlipyGif): Boolean {
        val pick = pickVariant(gif) { manager.appSupportsImageInsertion(it, true) } ?: return false
        val uri = KlipyGifPipe.register(pick.first.url, pick.second)
        val inserted = manager.typeUri(uri, listOf(pick.second), ignoreConnectionOverride = true)
        if (inserted && appKey.isNotEmpty()) {
            manager.getLifecycleScope().launch { KlipyClient.registerShare(appKey, gif.slug) }
        }
        return inserted
    }
}
