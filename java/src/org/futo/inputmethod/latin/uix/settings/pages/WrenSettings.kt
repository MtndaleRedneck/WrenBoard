package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore

/*
 * WrenBoard 1.2 Settings tree (FULL_LIST P3 and Ezzy's 11:44 PM decision): six groups, then one FUTO entry that
 * holds FUTO's whole original Settings home, so nothing FUTO had is cut. The rows below are FUTO's own setting
 * items, regrouped; their keys and behaviour are unchanged, so backups and the 1.1 repairs read them as before.
 */

private val keyPressNames = setOf(
    R.string.popup_on_keypress,
    R.string.vibrate_on_keypress,
    R.string.typing_settings_vibration_strength,
    R.string.sound_on_keypress,
    R.string.typing_settings_keypress_sound_volume,
)

private val wrenKeyboardLook = listOf(
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
private val wrenKeyboardLayout get() = KeyboardSettingsMenu.settings
private val wrenKeyboardFeel get() = TypingSettingsMenu.settings.filter { it.name in keyPressNames }

val WrenKeyboardMenu = UserSettingsMenu(
    title = R.string.wren_set_keyboard,
    navPath = "wren_keyboard", registerNavPath = true,
    settings = wrenKeyboardLook + wrenKeyboardLayout + wrenKeyboardFeel
)

private val wrenTypingShift get() = LongPressMenu.settings.filter { it.name == R.string.keyboard_settings_shift_lock }
private val wrenTypingTyping get() = TypingSettingsMenu.settings.filter { it.name !in keyPressNames }
private val wrenTypingWords = listOf(
            userSettingNavigationItem(
                title = R.string.wren_set_suggestions,
                subtitle = R.string.wren_set_suggestions_sub,
                style = NavigationItemStyle.Misc,
                navigateTo = PredictiveTextMenu.navPath,
                icon = R.drawable.text_prediction
            ),
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
)

val WrenTypingMenu = UserSettingsMenu(
    title = R.string.wren_set_typing,
    navPath = "wren_typing", registerNavPath = true,
    settings = wrenTypingShift + wrenTypingTyping + wrenTypingWords
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

/** FUTO's original Settings home, whole: swipe typing, languages, actions, theme, payment, help, developer, credits. */
/**
 * WrenBoard style: the six group pages render their rows in labelled cards (render 10b: LOOK / LAYOUT / FEEL).
 * The rows are the same objects as in each menu's `settings` list, so search and the plain FUTO style see them unchanged.
 */
val WrenMenuSections: Map<String, List<Pair<Int, List<UserSetting>>>> by lazy {
    mapOf(
        "wren_keyboard" to listOf(
            R.string.wren_sec_look to wrenKeyboardLook,
            R.string.wren_sec_layout to wrenKeyboardLayout,
            R.string.wren_sec_feel to wrenKeyboardFeel,
        ),
        "wren_typing" to listOf(
            R.string.wren_sec_shift to wrenTypingShift,
            R.string.wren_sec_typing to wrenTypingTyping,
            R.string.wren_sec_words to wrenTypingWords,
        ),
        "wren_clipemoji" to listOf(
            R.string.wren_sec_panels to WrenClipEmojiMenu.settings.take(1),
            R.string.wren_sec_clipboard to WrenClipEmojiMenu.settings.drop(1).take(1),
            R.string.wren_sec_emoji to WrenClipEmojiMenu.settings.drop(2),
        ),
        "wren_backup" to listOf(R.string.wren_sec_backup to WrenBackupMenu.settings),
        "wren_futo" to listOf(
            R.string.wren_sec_futo to WrenFutoMenu.settings.dropLast(1),
            R.string.wren_sec_style to WrenFutoMenu.settings.takeLast(1),
        ),
    )
}

val WrenFutoMenu = UserSettingsMenu(
    title = R.string.wren_set_futo,
    navPath = "wren_futo", registerNavPath = true,
    settings = HomeScreenLite.settings + listOf(
        userSettingToggleDataStore(
            title = R.string.wren_set_style,
            setting = WrenSettingsStyle
        )
    )
)

@Composable
fun WrenHomeList() {
    val nav = LocalNavController.current
    Column {
        Spacer(Modifier.height(6.dp))
        WrenCard {
            WrenNavRow(stringResource(R.string.wren_set_keyboard), stringResource(R.string.wren_set_keyboard_sub), R.drawable.wren_ic_keyboard_fill) { nav?.navigate(WrenKeyboardMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_typing), stringResource(R.string.wren_set_typing_sub), R.drawable.wren_ic_spellcheck) { nav?.navigate(WrenTypingMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_voice), stringResource(R.string.wren_set_voice_sub), R.drawable.wren_ic_mic_fill) { nav?.navigate(VoiceInputMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_clip_emoji), stringResource(R.string.wren_set_clip_emoji_sub), R.drawable.wren_ic_content_paste) { nav?.navigate(WrenClipEmojiMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_backup), stringResource(R.string.wren_set_backup_sub), R.drawable.wren_ic_settings_backup_restore) { nav?.navigate(WrenBackupMenu.navPath) }
            WrenNavRow(stringResource(R.string.wren_set_about), stringResource(R.string.wren_set_about_sub), R.drawable.wren_ic_info_fill, divider = false) { nav?.navigate(WrenAboutMenu.navPath) }
        }
        WrenSectionLabel(stringResource(R.string.wren_set_futo))
        WrenCard {
            WrenNavRow(stringResource(R.string.wren_set_futo), stringResource(R.string.wren_set_futo_sub), R.drawable.wren_ic_more_horiz, dark = true, divider = false) { nav?.navigate(WrenFutoMenu.navPath) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
