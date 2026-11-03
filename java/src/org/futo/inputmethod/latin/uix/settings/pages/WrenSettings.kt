package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import org.futo.inputmethod.latin.uix.gif.WrenStickersEnabled
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import org.futo.inputmethod.latin.uix.settings.WrenCard
import org.futo.inputmethod.latin.uix.settings.WrenNavRow
import org.futo.inputmethod.latin.uix.settings.WrenSectionLabel
import org.futo.inputmethod.latin.uix.settings.WrenSettingsStyle
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore

/* Original setting objects keep their keys and behavior. Wren menus redistribute the rows;
 * the payment screen and notices remain accessible from Home. Candidate pending pixel review. */

private val keyPressNames = setOf(
    R.string.popup_on_keypress,
    R.string.vibrate_on_keypress,
    R.string.typing_settings_vibration_strength,
    R.string.sound_on_keypress,
    R.string.typing_settings_keypress_sound_volume,
)

private val wrenAppearanceNav = listOf(
    userSettingNavigationItem(
        title = R.string.theme_settings_title,
        style = NavigationItemStyle.Misc,
        navigateTo = "themes",
        icon = R.drawable.themes
    ),
    userSettingNavigationItem(
        title = R.string.wren_set_toolbar_buttons,
        style = NavigationItemStyle.Misc,
        navigateTo = "actions",
        icon = R.drawable.smile
    ),
)
private val wrenAppearanceToggles get() = KeyboardSettingsMenu.settings.filter {
    it.name == R.string.keyboard_settings_show_suggestion_row ||
            it.name == R.string.keyboard_settings_period_key
}

val WrenAppearanceMenu = UserSettingsMenu(
    title = R.string.wren_set_appearance,
    navPath = "wren_appearance", registerNavPath = true,
    settings = wrenAppearanceNav + wrenAppearanceToggles
)

private val wrenLayoutNames = setOf(
    R.string.size_settings_title,
    R.string.keyboard_settings_show_number_row,
    R.string.keyboard_settings_show_arrow_row,
    R.string.keyboard_settings_hide_when_hardware_keyboard_is_connected,
)
private val wrenLayoutRows get() = listOf(
    userSettingNavigationItem(
        title = org.futo.inputmethod.engine.IMESettingsMenu.title,
        style = NavigationItemStyle.Misc,
        navigateTo = org.futo.inputmethod.engine.IMESettingsMenu.navPath
    ).copy(visibilityCheck = org.futo.inputmethod.engine.IMESettingsMenu.visibilityCheck)
) + KeyboardSettingsMenu.settings.filter { it.name in wrenLayoutNames } +
        LongPressMenu.settings.filter { it.name == R.string.morekey_settings_layout }

val WrenLayoutMenu = UserSettingsMenu(
    title = R.string.wren_set_layout,
    navPath = "wren_layout", registerNavPath = true,
    settings = wrenLayoutRows
)

private val wrenTypingNames = setOf(
    R.string.typing_settings_suggest_emojis,
    R.string.auto_cap,
    R.string.use_double_space_period,
    R.string.typing_settings_auto_space_mode,
    R.string.typing_settings_delete_pasted_text_on_backspace,
    R.string.typing_settings_revert_correction_on_backspace,
)
private val wrenPredictionMovedNames = setOf(
    R.string.prediction_settings_transformer,
    R.string.prediction_settings_transformer_models,
    R.string.prediction_settings_transformer_advanced_params,
    R.string.edit_personal_dictionary,
    R.string.prediction_settings_word_blacklist,
)
private val wrenTypingRows get() =
    PredictiveTextMenu.settings.filter { it.name !in wrenPredictionMovedNames && it.name != 0 } +
        TypingSettingsMenu.settings.filter { it.name in wrenTypingNames } +
        LongPressMenu.settings.filter { it.name == R.string.keyboard_settings_shift_lock }

val WrenTypingMenu = UserSettingsMenu(
    title = R.string.wren_set_typing,
    navPath = "wren_typing", registerNavPath = true,
    settings = wrenTypingRows
)

private val wrenGestureNames = setOf(
    R.string.morekey_settings_backspace_hold_delete_words,
    R.string.morekey_settings_backspace_swipe_to_delete,
    R.string.morekey_settings_spacebar_swipe_shortcut,
    R.string.morekey_settings_spacebar_hold_shortcut,
    R.string.morekey_settings_show_hints,
    R.string.morekey_settings_duration,
)
private val wrenSwipeRows get() = SwipeMenu.settings.filter { it.name != R.string.swipe_settings_configure_shortcuts } +
        LongPressMenu.settings.filter { it.name in wrenGestureNames }

val WrenSwipeMenu = UserSettingsMenu(
    title = R.string.wren_set_swipe,
    navPath = "wren_swipe", registerNavPath = true,
    settings = wrenSwipeRows
)

private val wrenDictRows get() = listOf(
    userSettingNavigationItem(
        title = R.string.edit_personal_dictionary,
        style = NavigationItemStyle.Misc,
        navigateTo = "pdict",
        icon = R.drawable.book
    ),
    userSettingNavigationItem(
        title = R.string.prediction_settings_word_blacklist,
        style = NavigationItemStyle.Misc,
        navigateTo = "blacklist",
        icon = R.drawable.file_text
    ),
) + VoiceInputMenu.settings.filter { it.name == R.string.voice_input_settings_use_personal_dict }

val WrenDictionaryMenu = UserSettingsMenu(
    title = R.string.wren_set_dict,
    navPath = "wren_dict", registerNavPath = true,
    settings = wrenDictRows
)

private val wrenSoundRows get() = TypingSettingsMenu.settings.filter { it.name in keyPressNames } +
        VoiceInputMenu.settings.filter { it.name == R.string.voice_input_settings_indication_sounds }

val WrenSoundMenu = UserSettingsMenu(
    title = R.string.wren_set_soundfeel,
    navPath = "wren_soundfeel", registerNavPath = true,
    settings = wrenSoundRows
)

private val wrenVoiceMovedNames = setOf(
    R.string.voice_input_settings_indication_sounds,
    R.string.voice_input_settings_use_personal_dict,
)
private val wrenVoiceRows get() = VoiceInputMenu.settings.filter { it.name !in wrenVoiceMovedNames }

val WrenVoiceMenu = UserSettingsMenu(
    title = R.string.wren_set_voice,
    navPath = "wren_voice", registerNavPath = true,
    settings = wrenVoiceRows
)

private val wrenTransformerNames = setOf(
    R.string.prediction_settings_transformer,
    R.string.prediction_settings_transformer_models,
    R.string.prediction_settings_transformer_advanced_params,
)
private val wrenAdvancedRows get() = PredictiveTextMenu.settings.filter { it.name in wrenTransformerNames || it.name == 0 } +
        KeyboardSettingsMenu.settings.filter { it.name == R.string.keyboard_settings_inline_autofill } +
        HomeScreenLite.settings.filter { it.name == R.string.dev_settings_title } +
        listOf(
            userSettingToggleDataStore(
                title = R.string.wren_set_style,
                setting = WrenSettingsStyle
            )
        )

val WrenAdvancedMenu = UserSettingsMenu(
    title = R.string.wren_set_advanced,
    navPath = "wren_advanced", registerNavPath = true,
    settings = wrenAdvancedRows
)

val WrenClipEmojiMenu = UserSettingsMenu(
    title = R.string.wren_set_clip_emoji,
    navPath = "wren_clipemoji", registerNavPath = true,
    settings = listOf(
        userSettingToggleDataStore(
            title = R.string.wren_swiftkey_panels,
            setting = WrenSwiftKeyPanels
        ),
        userSettingNavigationItem(
            title = R.string.wren_clip_settings,
            style = NavigationItemStyle.Misc,
            navigateTo = "actions/clipboard_history",
            icon = R.drawable.wren_ic_content_paste
        ),
        UserSetting(name = R.string.wren_set_clear_emoji_recents) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            NavigationItem(
                title = stringResource(R.string.wren_set_clear_emoji_recents),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = { scope.launch { context.resetRecentEmojis() } }
            )
        },
        UserSetting(name = R.string.wren_set_unlock_all_emojis) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val count = useDataStoreValue(WrenLockedEmojis).toLockedList().size
            NavigationItem(
                title = stringResource(R.string.wren_set_unlock_all_emojis),
                subtitle = stringResource(R.string.wren_set_locked_count, count),
                style = NavigationItemStyle.MiscNoArrow,
                navigate = { scope.launch { context.setSetting(WrenLockedEmojis, "") } }
            )
        },
    )
)

val WrenBackupMenu = UserSettingsMenu(
    title = R.string.wren_set_backup,
    navPath = "wren_backup", registerNavPath = true,
    settings = MiscMenu.settings
)

/**
 * WrenBoard style: the group pages render their rows in labelled cards.
 * The rows are the same objects as in each menu's `settings` list, so search and the plain FUTO style see them unchanged.
 */
val WrenMenuSections: Map<String, List<Pair<Int, List<UserSetting>>>> by lazy {
    mapOf(
        "wren_appearance" to listOf(R.string.wren_set_appearance to WrenAppearanceMenu.settings),
        "wren_layout" to listOf(R.string.wren_set_layout to WrenLayoutMenu.settings),
        "wren_typing" to listOf(R.string.wren_set_typing to WrenTypingMenu.settings),
        "wren_swipe" to listOf(R.string.wren_set_swipe to WrenSwipeMenu.settings),
        "wren_dict" to listOf(R.string.wren_set_dict to WrenDictionaryMenu.settings),
        "wren_soundfeel" to listOf(R.string.wren_set_soundfeel to WrenSoundMenu.settings),
        "wren_voice" to listOf(R.string.wren_set_voice to WrenVoiceMenu.settings),
        "wren_clipemoji" to listOf(
            R.string.wren_sec_panels to WrenClipEmojiMenu.settings.take(1),
            R.string.wren_sec_clipboard to WrenClipEmojiMenu.settings.drop(1).take(1),
            R.string.wren_sec_emoji to WrenClipEmojiMenu.settings.drop(2)
        ),
        "wren_backup" to listOf(R.string.wren_sec_backup to WrenBackupMenu.settings),
        "wren_advanced" to listOf(R.string.wren_set_advanced to WrenAdvancedMenu.settings),
        "wren_futo" to listOf(R.string.wren_sec_futo to WrenFutoMenu.settings),
    )
}

val WrenFutoMenu = UserSettingsMenu(
    title = R.string.wren_set_futo,
    navPath = "wren_futo", registerNavPath = true,
    // Payment remains reachable from Home on its unchanged screen; notices are preserved.
    settings = HomeScreenLite.settings.filter { it.name == R.string.payment_screen_short_title } + listOf(
        userSettingDecorationOnly {
            val isPaid = useDataStoreValue(IS_ALREADY_PAID)
            if(isPaid || androidx.compose.ui.platform.LocalInspectionMode.current) {
                androidx.compose.material3.Text(
                    stringResource(R.string.payment_paid_version_indicator),
                    style = org.futo.inputmethod.latin.uix.theme.Typography.SmallMl,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        },
    )
)

@Composable
fun WrenHomeList() {
    val nav = LocalNavController.current
    Column {
        Spacer(Modifier.height(6.dp))
        WrenCard {
            WrenNavRow(stringResource(R.string.wren_set_appearance), stringResource(R.string.wren_set_appearance_sub), R.drawable.themes) { nav?.navigate(WrenAppearanceMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_languages), stringResource(R.string.wren_set_languages_sub), R.drawable.globe) { nav?.navigate("languages") }
            WrenNavRow(stringResource(R.string.wren_set_layout), stringResource(R.string.wren_set_layout_sub), R.drawable.maximize) { nav?.navigate(WrenLayoutMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_typing), stringResource(R.string.wren_set_typing_sub), R.drawable.wren_ic_spellcheck) { nav?.navigate(WrenTypingMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_swipe), stringResource(R.string.wren_set_swipe_sub), R.drawable.direction_arrows) { nav?.navigate(WrenSwipeMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_dict), stringResource(R.string.wren_set_dict_sub), R.drawable.book) { nav?.navigate(WrenDictionaryMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_soundfeel), stringResource(R.string.wren_set_soundfeel_sub), R.drawable.wren_ic_volume) { nav?.navigate(WrenSoundMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_voice), stringResource(R.string.wren_set_voice_sub), R.drawable.wren_ic_mic_fill) { nav?.navigate(WrenVoiceMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_clip_emoji), stringResource(R.string.wren_set_clip_emoji_sub), R.drawable.wren_ic_content_paste) { nav?.navigate(WrenClipEmojiMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_backup), stringResource(R.string.wren_set_backup_sub), R.drawable.wren_ic_settings_backup_restore) { nav?.navigate(WrenBackupMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_about), stringResource(R.string.wren_set_about_sub), R.drawable.wren_ic_info_fill) { nav?.navigate(WrenAboutMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_advanced), stringResource(R.string.wren_set_advanced_sub), R.drawable.wren_ic_more_horiz, divider = false) { nav?.navigate(WrenAdvancedMenu.navPath) }
        }
        WrenSectionLabel(stringResource(R.string.wren_set_futo))
        WrenCard {
            WrenNavRow(stringResource(R.string.wren_set_futo), stringResource(R.string.wren_set_futo_sub), R.drawable.wren_ic_more_horiz, dark = true, divider = false) { nav?.navigate("payment") }
        }
        val isPaid = useDataStoreValue(IS_ALREADY_PAID)
        if (isPaid) androidx.compose.material3.Text(
            stringResource(R.string.payment_paid_version_indicator),
            style = org.futo.inputmethod.latin.uix.theme.Typography.SmallMl,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
    }
}
