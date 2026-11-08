package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.actions.ActionCategory
import org.futo.inputmethod.latin.uix.actions.ActionsEditor
import org.futo.inputmethod.latin.uix.actions.ActionsSettings
import org.futo.inputmethod.latin.uix.actions.AllActionsMap
import org.futo.inputmethod.latin.uix.actions.EmojiAction
import org.futo.inputmethod.latin.uix.actions.SwitchLanguageAction
import org.futo.inputmethod.latin.uix.actions.ensureWellFormed
import org.futo.inputmethod.latin.uix.actions.toActionEditorItems
import org.futo.inputmethod.latin.uix.actions.toActionMap
import org.futo.inputmethod.latin.uix.actions.updateSettingsWithNewActions
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.ScreenTitle
import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.urlEncode

val ActionsScreen = UserSettingsMenu(
    title = R.string.action_settings_title,
    navPath = "actions", registerNavPath = true,
    settings = listOf(
        userSettingNavigationItem(
            title = R.string.action_editor_title,
            subtitle = R.string.action_editor_subtitle,
            style = NavigationItemStyle.Misc,
            navigateTo = "actionEdit"
        ),

        userSettingDecorationOnly {
            ScreenTitle(stringResource(R.string.action_settings_action_settings))
        }
    ) + AllActionsMap.mapNotNull { v ->
        v.value.settingsMenu?.let {
            userSettingNavigationItem(
                title = v.value.name,
                style = NavigationItemStyle.HomeTertiary,
                navigateTo = "actions/" + v.key.urlEncode(),
                icon = v.value.icon
            )
        }
    }
)

@Preview(showBackground = true)
@Composable
fun ActionEditorScreen(navController: NavHostController = rememberNavController()) {
    Column {
        ScreenTitle(stringResource(R.string.action_editor_title), showBack = true, navController)
        ActionsEditor { }
    }
}