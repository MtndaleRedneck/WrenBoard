package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.futo.inputmethod.latin.uix.dataStore
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import androidx.compose.runtime.Composable

// One atomic value owns the visible order and Hidden order. Legacy preferences are read only
// during first migration; a saved unified value is never rebuilt from old categories.
val UnifiedActionsKey = stringPreferencesKey("wren_unified_actions_v1")
private val UnifiedKnownIds get() = AllActionsMap.filterValues { it != MoreActionsAction }.keys.toList()

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
        result = prefs[UnifiedActionsKey]?.decodeUnified() ?: migrateUnifiedActionIds(
            prefs[ActionsSettings.key] ?: ActionsSettings.default)
        prefs[UnifiedActionsKey] = result!!.encode()
    }
    result!!
}

/** Explicit Done only. Editor drafts stay in memory until this function is called. */
suspend fun Context.saveUnifiedActions(model: OrderedActionIds) {
    val safe = OrderedActionIds.normalize(model.visible, model.hidden, UnifiedKnownIds)
    dataStore.edit { it[UnifiedActionsKey] = safe.encode() }
}

val UnifiedActionsSetting = SettingsKey(UnifiedActionsKey, migrateUnifiedActionIds(ActionsSettings.default).encode())

@Composable
fun useUnifiedActions(): OrderedActionIds = useDataStoreValue(UnifiedActionsSetting).decodeUnified()
