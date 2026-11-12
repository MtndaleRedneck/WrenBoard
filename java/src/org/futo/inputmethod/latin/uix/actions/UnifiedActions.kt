package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

// One atomic value owns the visible order and Hidden order. Legacy preferences are read only
// during first migration; a saved unified value is never rebuilt from old categories.
val UnifiedActionsKey = stringPreferencesKey("wren_unified_actions_v1")
// WrenBoard 1.9 (item 2): v2 applies a one-time favorites-first reorder over a saved v1 order.
private val UnifiedActionsKeyV2 = stringPreferencesKey("wren_unified_actions_v2")
private val UnifiedKnownIds get() = AllActionsMap.filterValues { it != MoreActionsAction }.keys.toList()

/** One-time reorder of a saved v1 order to the spec's SwiftKey-style toolbar order (favorites
 * first: clipboard, emoji, GIF, mic, share, info, ...). Hidden entries are untouched. */
fun OrderedActionIds.reorderFavoritesFirstV2(): OrderedActionIds {
    val favoritesOrder = ids(DefaultActionSettings[ActionCategory.Favorites].orEmpty())
    val rest = visible.toMutableList()
    val front = favoritesOrder.filter { rest.remove(it) }
    return OrderedActionIds.normalize(front + rest, hidden, UnifiedKnownIds)
}

private fun ids(actions: List<org.futo.inputmethod.latin.uix.Action>) =
    actions.mapNotNull { ActionToId[it] }

fun migrateUnifiedActionIds(legacy: String): OrderedActionIds {
    val map = legacy.toActionEditorItems().toActionMap().ensureAllCategoriesPresent()
    return OrderedActionIds.migrate(ids(map[ActionCategory.ActionKey].orEmpty()),
        ids(map[ActionCategory.PinnedKey].orEmpty()), ids(map[ActionCategory.Favorites].orEmpty()),
        ids(map[ActionCategory.More].orEmpty()), ids(map[ActionCategory.Disabled].orEmpty()), UnifiedKnownIds)
}

fun OrderedActionIds.encode(): String = visible.joinToString(",") + "|" + hidden.joinToString(",")
fun String.decodeUnified(): OrderedActionIds {
    val parts = split('|', limit = 2)
    return OrderedActionIds.normalize(parts[0].split(',').filter { it.isNotEmpty() },
        parts.getOrElse(1) { "" }.split(',').filter { it.isNotEmpty() }, UnifiedKnownIds)
}

/** No effect until called from the new action consumers. Atomic, idempotent and restart safe. */
fun Context.readUnifiedActions(): OrderedActionIds = runBlocking {
    var result: OrderedActionIds? = null
    dataStore.edit { prefs ->
        result = prefs[UnifiedActionsKeyV2]?.decodeUnified()
            ?: prefs[UnifiedActionsKey]?.decodeUnified()?.reorderFavoritesFirstV2()
            ?: migrateUnifiedActionIds(prefs[ActionsSettings.key] ?: ActionsSettings.default)
        prefs[UnifiedActionsKeyV2] = result!!.encode()
    }
    result!!
}

/** Atomic commit for a completed drop or explicit restore. Never called on move frames or disposal. */
suspend fun Context.saveUnifiedActions(model: OrderedActionIds) {
    val safe = OrderedActionIds.normalize(model.visible, model.hidden, UnifiedKnownIds)
    dataStore.edit { it[UnifiedActionsKeyV2] = safe.encode() }
}

val UnifiedActionsSetting = SettingsKey(UnifiedActionsKeyV2, migrateUnifiedActionIds(ActionsSettings.default).encode())

@Composable
fun useUnifiedActions(): OrderedActionIds {
    val context = LocalContext.current
    // Build the initial draft from actual saved values, even before IME startup.
    // Never block the UI thread while DataStore emits a new value.
    val legacy = useDataStoreValue(ActionsSettings)
    val v1 = useDataStoreValue(SettingsKey(UnifiedActionsKey, ""))
    val initial = androidx.compose.runtime.remember(legacy, v1) {
        v1.takeIf { it.isNotEmpty() }?.decodeUnified()?.reorderFavoritesFirstV2()?.encode()
            ?: migrateUnifiedActionIds(legacy).encode()
    }
    androidx.compose.runtime.LaunchedEffect(context) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { context.readUnifiedActions() }
    }
    return useDataStoreValue(SettingsKey(UnifiedActionsKeyV2, initial)).decodeUnified()
}
