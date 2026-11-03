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
/** Owner original (WhatsApp Oct 31 21:51:57 EDT, [reference removed]):
 * sticker support only as an option that can be shut off; 21:53:32 EDT ([reference removed]):
 * "post as sticker/gif" shows only when stickers are enabled via setting. Default OFF.
 * The "post as sticker" affordance itself is not implemented in 1.5 (GIFs only). */
val WrenStickersEnabled = SettingsKey(booleanPreferencesKey("wren_stickers_enabled"), false)

object KlipyInsert {
    // ignoreConnectionOverride = true: capability and insertion always target the HOST
    // editor, never the panel's own local search field while its input override is active.
    fun canOffer(manager: KeyboardManagerForAction): Boolean =
        manager.appSupportsImageInsertion("image/gif", true)

    fun insert(manager: KeyboardManagerForAction, appKey: String, gif: KlipyClient.KlipyGif): Boolean {
        val variant = gif.fullGif() ?: return false
        if (!KlipyGifPipe.isAllowedUrl(variant.url)) return false
        if (!canOffer(manager)) return false
        val uri = KlipyGifPipe.register(variant.url)
        val inserted = manager.typeUri(uri, listOf("image/gif"), ignoreConnectionOverride = true)
        if (inserted && appKey.isNotEmpty()) {
            manager.getLifecycleScope().launch { KlipyClient.registerShare(appKey, gif.slug) }
        }
        return inserted
    }
}
