package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.BuildConfig
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.theme.Typography
import org.futo.inputmethod.updates.openManualUpdateCheck

/** WrenBoard 1.2 About page: version, update check, licenses. Opened by the toolbar Info button and from Settings. */
val WrenAboutMenu = UserSettingsMenu(
    title = R.string.wren_about_title,
    navPath = "about", registerNavPath = true,
    settings = listOf(
        userSettingDecorationOnly {
            Text(
                "WrenBoard ${BuildConfig.VERSION_NAME}",
                style = Typography.Body.RegularMl,
                color = Color(0xFFF2F2F2),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            )
        },
        userSettingNavigationItem(
            title = R.string.settings_check_for_updates_manually,
            style = NavigationItemStyle.MiscNoArrow,
            navigate = { nav -> nav.context.openManualUpdateCheck() }
        ),
        userSettingNavigationItem(
            title = R.string.credits_menu_title,
            style = NavigationItemStyle.MiscNoArrow,
            navigateTo = "credits",
        ),
    )
)
