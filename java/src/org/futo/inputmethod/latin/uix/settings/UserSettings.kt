package org.futo.inputmethod.latin.uix.settings

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.SettingsKey

data class UserSetting(
    @StringRes val name: Int,
    @StringRes val subtitle: Int? = null,
    @StringRes val searchTags: Int? = null,
    val searchTagList: List<Int>? = searchTags?.let { listOf(it) } ?: null,
    val visibilityCheck: (@Composable () -> Boolean)? = null,
    val appearInSearchIfVisibilityCheckFailed: Boolean = true,
    val appearsInSearch: Boolean = true,
    val component: @Composable () -> Unit,
)

data class UserSettingsMenu(
    @StringRes val title: Int,
    @StringRes val searchTags: Int? = null,
    val visibilityCheck: (@Composable () -> Boolean)? = null,
    val navPath: String,
    val registerNavPath: Boolean,
    val settings: List<UserSetting>
)


fun userSettingNavigationItem(
    @StringRes title: Int,
    style: NavigationItemStyle,
    navigateTo: String? = null,
    navigate: ((NavHostController) -> Unit)? = null,
    @DrawableRes icon: Int? = null,
    @StringRes subtitle: Int? = null
): UserSetting = UserSetting(
    name = title,
    subtitle = subtitle,
    component = {
        val navController = LocalNavController.current
        NavigationItem(
            title = stringResource(title),
            style = style,
            icon = icon?.let { painterResource(it) },
            subtitle = subtitle?.let { stringResource(it) },
            navigate = {
                if(navigateTo != null) navController!!.navigate(navigateTo)
                navigate?.invoke(navController!!)
            }
        )
    }
)

fun userSettingToggleSharedPrefs(
    @StringRes title: Int,
    key: String,
    default: @Composable () -> Boolean,
    @StringRes subtitle: Int? = null,
    @StringRes disabledSubtitle: Int? = null,
    disabled: @Composable () -> Boolean = {false},
    icon: (@Composable () -> Unit)? = null,
    submenu: String? = null,
): UserSetting = UserSetting(
    name = title,
    subtitle = subtitle,
    component = {
        val def = default.invoke()
        val navController = LocalNavController.current
        SettingToggleSharedPrefs(
            title = stringResource(title),
            key = key,
            default = def,
            subtitle = subtitle?.let { stringResource(it) },
            disabledSubtitle = disabledSubtitle?.let { stringResource(it) },
            disabled = disabled(),
            icon = icon,
            onSubmenuNavigate = submenu?.let {{
                navController!!.navigate(it)
            }},
        )
    }
)

fun userSettingToggleDataStore(
    @StringRes title: Int,
    setting: SettingsKey<Boolean>,
    @StringRes subtitle: Int? = null,
    @StringRes disabledSubtitle: Int? = null,
    disabled: @Composable () -> Boolean = {false},
    icon: (@Composable () -> Unit)? = null
): UserSetting = UserSetting(
    name = title,
    subtitle = subtitle,
    component = {
        SettingToggleDataStore(
            title = stringResource(title),
            setting = setting,
            subtitle = subtitle?.let { stringResource(it) },
            disabledSubtitle = disabledSubtitle?.let { stringResource(it) },
            disabled = disabled(),
            icon = icon
        )
    }
)

fun userSettingDecorationOnly(
    decoration: @Composable () -> Unit
): UserSetting = UserSetting(
    name = 0,
    component = decoration,
    appearsInSearch = false
)

@Composable
fun UserSettingsMenu.render(showBack: Boolean = true, showTitle: Boolean = true) {
    val navController = LocalNavController.current

    if(showTitle) {
        ScreenTitle(stringResource(title), showBack = showBack, navController)
    }
    settings.forEach {
        if(it.visibilityCheck?.invoke() != false) {
            it.component()
        }
    }
}

@Composable
fun UserSettingsMenuScreen(menu: UserSettingsMenu) {
    // WrenBoard 1.6 (item 11): in the WrenBoard look every menu page is the mock-up's flat list on #141414:
    // a fixed top bar, orange section labels for WrenBoard's grouped pages, flat rows. FUTO's look is unchanged.
    val wren = useDataStoreValue(WrenSettingsStyle)
    if (!wren) {
        ScrollableList {
            menu.render()
            BottomSpacer()
        }
        return
    }
    val sections = org.futo.inputmethod.latin.uix.settings.pages.WrenMenuSections[menu.navPath]
    val nav = LocalNavController.current
    Column(Modifier.fillMaxSize().background(wrenPageBrush)) {
        WrenTopBar(stringResource(menu.title), onBack = { nav?.navigateUp() },
            titleIcon = if (menu.navPath == "wren_futo") org.futo.inputmethod.latin.R.drawable.wren_ic_futo_mark else null)
        Box(Modifier.weight(1f)) {
            ScrollableList {
                CompositionLocalProvider(LocalWrenFlatRows provides true) {
                    if (sections == null) {
                        menu.render(showTitle = false)
                    } else {
                        sections.forEach { (label, items) ->
                            val shown = items.filter { it.visibilityCheck?.invoke() != false }
                            if (shown.isNotEmpty()) {
                                if (label != 0) WrenFlatSectionLabel(stringResource(label))
                                shown.forEach { it.component() }
                            }
                        }
                    }
                }
                BottomSpacer()
            }
        }
    }
}
