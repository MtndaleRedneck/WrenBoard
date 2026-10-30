package org.futo.inputmethod.latin.uix

import androidx.datastore.preferences.core.booleanPreferencesKey

// WrenBoard 1.2 settings. Keys are new names, so nothing a 1.1 backup or the updater reads changes meaning.

/** P0-7: show predicted starter words (from the dictionary and the user's own history) in an empty field. */
val StarterWordsSetting = SettingsKey(
    booleanPreferencesKey("wren_starter_words"),
    true
)
