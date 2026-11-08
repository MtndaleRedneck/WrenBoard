package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.EmojiTracker.resetRecentEmojis
import org.futo.inputmethod.latin.uix.actions.WrenLockedEmojis
import org.futo.inputmethod.latin.uix.actions.clipboard.WrenSwiftKeyPanels
import org.futo.inputmethod.latin.uix.actions.toLockedList
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.settings.NavigationItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.WrenHomeRow
import org.futo.inputmethod.latin.uix.settings.WrenSettingsStyle
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore



/** The listed settings of [menu], in the order given. A missing name fails loudly at first use. */
private fun UserSettingsMenu.named(vararg ids: Int): List<UserSetting> =
    ids.map { id -> settings.first { it.name == id } }

// Typing
private val typingTyping get() = PredictiveTextMenu.named(R.string.auto_correction) +
    TypingSettingsMenu.named(R.string.auto_cap, R.string.typing_settings_auto_space_mode, R.string.use_double_space_period,
        R.string.typing_settings_revert_correction_on_backspace) +
    LongPressMenu.named(R.string.keyboard_settings_shift_lock)
private val typingPredictions get() = PredictiveTextMenu.named(R.string.use_personalized_dicts, R.string.wren_starter_words) +
    KeyboardSettingsMenu.named(R.string.keyboard_settings_inline_autofill) +
    PredictiveTextMenu.named(R.string.edit_personal_dictionary, R.string.prediction_settings_word_blacklist)
private val typingSwipe get() = SwipeMenu.named(R.string.swipe_settings_enable_swipe_typing, R.string.swipe_settings_sensitive_swipe,
    R.string.swipe_settings_suggest_mode, R.string.swipe_settings_kasroz)
private val typingSpaceBackspace get() = LongPressMenu.named(R.string.morekey_settings_spacebar_swipe_shortcut,
    R.string.morekey_settings_spacebar_hold_shortcut, R.string.morekey_settings_backspace_hold_delete_words,
    R.string.morekey_settings_backspace_swipe_to_delete)

val WrenTypingMenu = UserSettingsMenu(
    title = R.string.wren_home_typing,
    navPath = "wren_typing", registerNavPath = true,
    settings = typingTyping + typingPredictions + typingSwipe + typingSpaceBackspace
)

// Emoji
private val emojiClearRecents = UserSetting(name = R.string.wren_set_clear_emoji_recents) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    NavigationItem(
        title = stringResource(R.string.wren_set_clear_emoji_recents),
        style = NavigationItemStyle.MiscNoArrow,
        navigate = { scope.launch { context.resetRecentEmojis() } }
    )
}
private val emojiUnlockAll = UserSetting(name = R.string.wren_set_unlock_all_emojis) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val count = useDataStoreValue(WrenLockedEmojis).toLockedList().size
    NavigationItem(
        title = stringResource(R.string.wren_set_unlock_all_emojis),
        subtitle = stringResource(R.string.wren_set_locked_count, count),
        disabled = count == 0,
        style = NavigationItemStyle.MiscNoArrow,
        navigate = { if (count > 0) scope.launch { context.setSetting(WrenLockedEmojis, "") } }
    )
}
private val emojiItems get() = TypingSettingsMenu.named(R.string.typing_settings_suggest_emojis) +
    listOf(emojiClearRecents, emojiUnlockAll)

val WrenEmojiMenu = UserSettingsMenu(
    title = R.string.wren_home_emoji,
    navPath = "wren_emoji", registerNavPath = true,
    settings = emojiItems
)

// Voice typing (FUTO's voice page; "Suppress symbols" lives under Advanced, as in the mock-up)
private val voiceEngine get() = VoiceInputMenu.named(R.string.voice_input_settings_backend_system)
private val voiceRest get() = VoiceInputMenu.settings.filter {
    it.name != R.string.voice_input_settings_backend_system && it.name != R.string.voice_input_settings_suppress_symbols
}
val WrenVoiceMenu = UserSettingsMenu(
    title = R.string.wren_home_voice,
    navPath = "wren_voice", registerNavPath = true,
    settings = voiceEngine + voiceRest
)

// Toolbar
private val toolbarButtons get() = ActionsScreen.named(R.string.action_editor_title) + listOf(ActionsScreen.settings.last())
val WrenRichInputMenu = UserSettingsMenu(
    title = R.string.wren17_rich_input, navPath = "wren_rich_input", registerNavPath = true,
    settings = listOf(
        userSettingNavigationItem(R.string.wren_home_voice, NavigationItemStyle.Misc, navigateTo = "wren_voice"),
        userSettingNavigationItem(R.string.wren_home_clipboard, NavigationItemStyle.Misc, navigateTo = "actions/clipboard_history"),
        userSettingNavigationItem(R.string.wren_home_toolbar, NavigationItemStyle.Misc, navigateTo = "wren_toolbar")
    )
)

val WrenToolbarMenu = UserSettingsMenu(
    title = R.string.wren_home_toolbar,
    navPath = "wren_toolbar", registerNavPath = true,
    settings = toolbarButtons
)

// Layout & keys
private val layoutSize get() = KeyboardSettingsMenu.named(R.string.size_settings_title)
private val layoutRows get() = KeyboardSettingsMenu.named(R.string.keyboard_settings_show_number_row) +
    NumberRowSettingMenu.named(R.string.keyboard_settings_number_row_style, R.string.keyboard_settings_number_row_dont_use_script_digits) +
    KeyboardSettingsMenu.named(R.string.keyboard_settings_show_arrow_row)
private val layoutKeys get() = KeyboardSettingsMenu.named(R.string.keyboard_settings_period_key) +
    LongPressMenu.named(R.string.morekey_settings_show_hints, R.string.morekey_settings_duration, R.string.wren17_accents, R.string.morekey_settings_layout)
val WrenLayoutMenu = UserSettingsMenu(
    title = R.string.wren_home_layout,
    navPath = "wren_layout", registerNavPath = true,
    settings = layoutSize + layoutRows + layoutKeys +
        ActionsScreen.named(R.string.action_settings_quick_option_enable_action_key, R.string.action_settings_quick_option_language_switch_key) +
        TypingSettingsMenu.named(R.string.popup_on_keypress)
)

// Sound & vibration
val WrenSoundMenu = UserSettingsMenu(
    title = R.string.wren_home_sound,
    navPath = "wren_sound", registerNavPath = true,
    settings = TypingSettingsMenu.named(R.string.sound_on_keypress, R.string.typing_settings_keypress_sound_volume,
        R.string.vibrate_on_keypress, R.string.typing_settings_vibration_strength)
)

// Backup & restore (FUTO's export / import rows; the old in-page heading is left to the page title)
val WrenBackupMenu = UserSettingsMenu(
    title = R.string.wren_home_backup,
    navPath = "wren_backup", registerNavPath = true,
    settings = MiscMenu.settings
)

// Advanced
private val advancedTip = userSettingDecorationOnly {
    androidx.compose.material3.Text(
        stringResource(R.string.wren_advanced_tip),
        color = org.futo.inputmethod.latin.uix.settings.WrenFlat.Sub,
        fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp)
    )
}
private val advancedEngine get() = PredictiveTextMenu.named(R.string.prediction_settings_transformer,
        R.string.prediction_settings_transformer_models, R.string.prediction_settings_transformer_advanced_params) +
    listOf(PredictiveTextMenu.settings[3]) + // FUTO's "alpha" tip under the model rows (shown only with the model on)
    PredictiveTextMenu.named(R.string.prefs_show_suggestions, R.string.bigram_prediction, R.string.prediction_settings_smart_keyhit_detection)
private val advancedKeyboard get() = KeyboardSettingsMenu.named(R.string.keyboard_settings_show_suggestion_row) +
    listOf(userSettingToggleDataStore(title = R.string.wren_swiftkey_panels, setting = WrenSwiftKeyPanels)) +
    TypingSettingsMenu.named(R.string.typing_settings_delete_pasted_text_on_backspace) +
    KeyboardSettingsMenu.named(R.string.keyboard_settings_hide_when_hardware_keyboard_is_connected) +
    listOf(SwipeMenu.settings.last()) // FUTO's "show unsupported / old swipe engine" control
private val advancedVoice get() = VoiceInputMenu.named(R.string.voice_input_settings_suppress_symbols)
private val advancedOther get() = HomeScreenLite.named(R.string.dev_settings_title) + futoLook
val WrenAdvancedMenu = UserSettingsMenu(
    title = R.string.wren_home_advanced,
    navPath = "wren_advanced", registerNavPath = true,
    settings = listOf(advancedTip) + advancedEngine + advancedKeyboard + advancedVoice + advancedOther
)

// FUTO: everything FUTO's own (paid version, FUTO's manual update check, help and links, licence, the classic look)
private val futoPaidNotice = userSettingDecorationOnly {
    val isPaid = useDataStoreValue(IS_ALREADY_PAID)
    if(isPaid || androidx.compose.ui.platform.LocalInspectionMode.current) {
        androidx.compose.material3.Text(
            stringResource(R.string.payment_paid_version_indicator),
            style = org.futo.inputmethod.latin.uix.theme.Typography.SmallMl,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
private val futoAtl get() = listOf(HomeScreenLite.settings[0]) // FUTO's ATL launch rows (only on ATL builds)
private val futoPaid get() = HomeScreenLite.named(R.string.payment_screen_short_title) + listOf(futoPaidNotice)
private val futoUpdates get() = HomeScreenLite.named(R.string.settings_check_for_updates_manually)
private val futoHelp get() = HelpMenu.named(R.string.help_menu_website, R.string.help_menu_documentation, R.string.help_menu_discord,
    R.string.help_menu_futo_chat, R.string.help_menu_github, R.string.help_menu_email)
private val futoVersion get() = HelpMenu.named(R.string.help_menu_version_name, R.string.help_menu_version_code) +
    listOf(HelpMenu.settings[2]) // FUTO's version tip (tapping the version code is how developer mode is unlocked)
private val futoLicence get() = HomeScreenLite.named(R.string.credits_menu_title)
private val futoLook get() = listOf(userSettingToggleDataStore(title = R.string.wren_set_style, setting = WrenSettingsStyle))

val WrenFutoMenu = UserSettingsMenu(
    title = R.string.wren_set_futo,
    navPath = "wren_futo", registerNavPath = true,
    settings = futoAtl + futoPaid + futoUpdates + futoHelp + futoVersion + futoLicence
)

/** The grouped pages: section label (0 = none) and its rows, the same objects as each menu's `settings`. */
val WrenMenuSections: Map<String, List<Pair<Int, List<UserSetting>>>> by lazy {
    mapOf(
        "wren_typing" to listOf(
            R.string.wren17_corrections to typingTyping,
            R.string.wren_s_predictions to typingPredictions,
            R.string.wren_s_swipe to typingSwipe,
            R.string.wren_s_space_backspace to typingSpaceBackspace,
        ),
        "wren_emoji" to listOf(0 to WrenEmojiMenu.settings),
        "wren_voice" to listOf(
            R.string.wren_s_voice_engine to voiceEngine,
            0 to voiceRest,
        ),
        "wren_toolbar" to listOf(R.string.wren_s_toolbar_buttons to toolbarButtons),
        "wren_layout" to listOf(
            R.string.wren_s_size to layoutSize,
            R.string.wren_s_rows to layoutRows,
            R.string.wren_s_keys to (WrenLayoutMenu.settings - layoutSize.toSet() - layoutRows.toSet()),
        ),
        "wren_sound" to listOf(0 to WrenSoundMenu.settings),
        "wren_backup" to listOf(0 to MiscMenu.settings.drop(1)),
        "wren_advanced" to listOf(
            0 to listOf(advancedTip),
            R.string.wren_s_predictions_engine to advancedEngine,
            R.string.wren_s_keyboard to advancedKeyboard,
            R.string.wren_s_voice to advancedVoice,
            R.string.wren_s_other to advancedOther,
        ),
        "wren_futo" to listOf(
            0 to futoAtl,
            R.string.wren_s_paid to futoPaid,
            R.string.wren_s_updates to futoUpdates,
            R.string.wren_s_help_links to futoHelp,
            R.string.wren_s_version to futoVersion,
            R.string.wren_s_licence to futoLicence,
        ),
    )
}

/** The home list (mock-up 01): twelve flat rows with outline icons, FUTO last with the FUTO logo mark. */
@Composable
fun WrenHomeList() {
    val nav = LocalNavController.current
    Column {
        Spacer(Modifier.height(8.dp))
        WrenHomeRow(stringResource(R.string.wren_home_languages), R.drawable.wren_set_ic_languages, subtitle = stringResource(R.string.wren17_languages_summary)) { nav?.navigate("languages") }
        WrenHomeRow(stringResource(R.string.wren_home_themes), R.drawable.wren_set_ic_themes, subtitle = stringResource(R.string.wren17_themes_summary)) { nav?.navigate("themes") }
        WrenHomeRow(stringResource(R.string.wren_home_typing), R.drawable.wren_set_ic_typing, subtitle = stringResource(R.string.wren17_typing_summary)) { nav?.navigate(WrenTypingMenu.navPath) }
        WrenHomeRow(stringResource(R.string.wren_home_emoji), R.drawable.wren_set_ic_emoji, subtitle = stringResource(R.string.wren17_emoji_summary)) { nav?.navigate(WrenEmojiMenu.navPath) }
        WrenHomeRow(stringResource(R.string.wren17_rich_input), R.drawable.wren_set_ic_voice, subtitle = stringResource(R.string.wren17_rich_summary)) { nav?.navigate(WrenRichInputMenu.navPath) }
        WrenHomeRow(stringResource(R.string.wren_home_layout), R.drawable.wren_set_ic_layout, subtitle = stringResource(R.string.wren17_layout_summary)) { nav?.navigate(WrenLayoutMenu.navPath) }
        WrenHomeRow(stringResource(R.string.wren_home_sound), R.drawable.wren_set_ic_sound, subtitle = stringResource(R.string.wren17_sound_summary)) { nav?.navigate(WrenSoundMenu.navPath) }
        WrenHomeRow(stringResource(R.string.wren_home_backup), R.drawable.wren_set_ic_backup, subtitle = stringResource(R.string.wren17_backup_summary)) { nav?.navigate(WrenBackupMenu.navPath) }
        WrenHomeRow(stringResource(R.string.wren_home_advanced), R.drawable.wren_set_ic_advanced, subtitle = stringResource(R.string.wren17_advanced_summary)) { nav?.navigate(WrenAdvancedMenu.navPath) }
        WrenHomeRow(stringResource(R.string.wren_set_futo), R.drawable.wren_ic_futo_mark, iconSize = 21.dp) { nav?.navigate(WrenFutoMenu.navPath) }
    }
}
