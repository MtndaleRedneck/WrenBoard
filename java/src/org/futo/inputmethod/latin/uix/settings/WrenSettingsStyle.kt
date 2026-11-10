package org.futo.inputmethod.latin.uix.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.KeyboardColorScheme
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.extendedDarkColorScheme
import org.futo.inputmethod.latin.uix.theme.UixThemeAuto
import org.futo.inputmethod.latin.uix.theme.UixThemeWrapper

/** WrenBoard 1.2 Settings look (render 10_settings_main_rev). false = FUTO's look, following the keyboard theme. */
val WrenSettingsStyle = SettingsKey(booleanPreferencesKey("wren_settings_style"), true)

object WrenSettingsColors {
    val Orange = Color(0xFFFF8714)
    val OrangeTop = Color(0xFFFB9E2E)
    val OrangeBottom = Color(0xFFF97C02)
    val PageTop = Color(0xFF141414)
    val PageBottom = Color(0xFF141414)
    val CardTop = Color(0xFF2E2E36)
    val CardBottom = Color(0xFF26262D)
    val Title = Color(0xFFF5F5F7)
    val Sub = Color(0xFF9696A0)
    val Chevron = Color(0xFF5C5C66)
    val Divider = Color(0x219696A0)
    val ChipDarkTop = Color(0xFF34343D)
    val ChipDarkBottom = Color(0xFF232328)
}

private val wrenSettingsScheme: KeyboardColorScheme by lazy {
    val c = WrenSettingsColors
    val s = extendedDarkColorScheme(
        primary = c.Orange, onPrimary = c.PageTop,
        primaryContainer = c.OrangeBottom, onPrimaryContainer = c.PageTop,
        secondary = c.Title, onSecondary = c.PageTop,
        secondaryContainer = c.ChipDarkTop, onSecondaryContainer = c.Orange,
        tertiary = c.OrangeTop, onTertiary = c.PageTop,
        tertiaryContainer = c.ChipDarkTop, onTertiaryContainer = c.Orange,
        error = Color(0xFFF2A7A9), onError = Color(0xFF783032),
        errorContainer = Color(0xFF801A20), onErrorContainer = Color(0xFFF2C2C5),
        outline = c.Chevron, outlineVariant = Color(0xFF3A3A42),
        surface = c.PageTop, onSurface = c.Title, onSurfaceVariant = c.Sub,
        surfaceContainerHighest = c.CardTop,
        keyboardSurface = Color(0xFF3C3C3C), keyboardContainer = Color(0xFF232323),
        keyboardContainerVariant = Color(0xFF1C1C1C), onKeyboardContainer = c.Orange,
        keyboardPress = Color(0xFF565656),
        primaryTransparent = c.Orange.copy(alpha = 0.3f),
        onSurfaceTransparent = c.Title.copy(alpha = 0.1f),
    )
    s.copy(base = s.base.copy(
        surfaceVariant = c.CardBottom,
        surfaceContainerLowest = c.PageBottom,
        surfaceContainerLow = c.PageTop,
        surfaceContainer = c.CardBottom,
        surfaceContainerHigh = c.CardTop,
        surfaceBright = c.CardTop,
        surfaceDim = c.PageBottom,
        inverseSurface = c.Title,
        inverseOnSurface = c.PageTop,
        inversePrimary = c.OrangeBottom,
        surfaceTint = c.Orange,
    ))
}

/** Settings use the WrenBoard palette (black and orange) unless the user turns the style off. */
@Composable
fun WrenSettingsTheme(content: @Composable () -> Unit) {
    if (useDataStoreValue(WrenSettingsStyle)) {
        UixThemeWrapper(colorScheme = wrenSettingsScheme) {
            CompositionLocalProvider(LocalWrenStyle provides true, content = content)
        }
    } else {
        UixThemeAuto(content)
    }
}


object WrenFlat {
    val Page = Color(0xFF141414)
    val Bar = Color(0xFF212121)
    val Title = Color(0xFFFFFFFF)
    val Sub = Color(0xFFA6A6A6)
    val Icon = Color(0xFFEDEDED)
    val Card = Color(0xFF2A2A31)
    val CardSub = Color(0xFFA6A6A6)
    val CardChevron = Color(0xFFB4B4BC)
    val Orange = Color(0xFFFF8710)
    val ButtonTop = Color(0xFFD88A2B)
    val ButtonBottom = Color(0xFFC4771E)
    val ButtonText = Color(0xFF1A1A1A)
}

/** True wherever the WrenBoard settings look is on (the whole Settings activity). */
val LocalWrenStyle = staticCompositionLocalOf { false }

/**
 * Height the floating try-keyboard button covers at the bottom of Settings (56 dp button + 16 dp margin), or 0 dp
 * while it is hidden. Settings home lets its list run under the button (mock-ups 01/02) and ends with this much
 * extra content padding so its last card can still be scrolled clear of the button.
 */
val LocalSettingsFabInset = staticCompositionLocalOf { 0.dp }

/** True on WrenBoard's own list pages: rows drop FUTO's 48 dp icon gutter and trailing arrows (mock-up rows). */
val LocalWrenFlatRows = staticCompositionLocalOf { false }

val wrenPageBrush: Brush get() = Brush.verticalGradient(listOf(WrenFlat.Page, WrenFlat.Page))

/** The mock-up's top bar: 56 dp, #212121, back arrow, 20 sp title, optional search. */
@Composable
fun WrenTopBar(title: String, onBack: (() -> Unit)?, onSearch: (() -> Unit)? = null, titleIcon: Int? = null) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(WrenFlat.Bar).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClickLabel = "Navigate back", onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.wren_set_ic_back), contentDescription = "Back", tint = WrenFlat.Icon, modifier = Modifier.size(24.dp))
            }
        } else {
            Spacer(Modifier.width(4.dp))
        }
        if (titleIcon != null) {
            // The FUTO page shows the FUTO logo mark before its title (mock-up 19).
            Spacer(Modifier.width(12.dp))
            Icon(painterResource(titleIcon), contentDescription = null, tint = WrenFlat.Icon, modifier = Modifier.size(24.dp))
        }
        Text(
            title, color = WrenFlat.Title, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        )
        if (onSearch != null) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable(onClick = onSearch), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.wren_set_ic_search), contentDescription = androidx.compose.ui.res.stringResource(R.string.settings_search_menu_title), tint = WrenFlat.Icon, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** Orange section label: 14 sp medium, 22 dp above, 8 dp below, 16 dp in. */
@Composable
fun WrenFlatSectionLabel(text: String) {
    Text(
        text, color = WrenFlat.Orange, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp)
    )
}

/** One home row: 24 dp outline icon at 16 dp, title at 72 dp, 54 dp tall. */
@Composable
fun WrenHomeRow(title: String, icon: Int, iconSize: androidx.compose.ui.unit.Dp = 24.dp, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = WrenFlat.Icon, modifier = Modifier.size(iconSize))
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(title, color = WrenFlat.Title, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp)
            subtitle?.let { Text(it, color = WrenFlat.Sub, fontSize = 13.sp, lineHeight = 18.sp) }
        }
    }
}

/** The card behind Feedback & bugs and Updates: #2A2A31, 16 dp corners, 12 dp margin, 18 dp padding. */
@Composable
fun WrenBottomCard(title: String, titleGap: androidx.compose.ui.unit.Dp = 10.dp, titleAction: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)).background(WrenFlat.Card).padding(18.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = WrenFlat.Title, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp, modifier = Modifier.weight(1f))
            titleAction?.invoke()
        }
        Spacer(Modifier.height(titleGap))
        content()
    }
}

/** The orange pill button from the mock-up (46 dp, vertical gradient, dark bold label). */
@Composable
fun WrenOrangeButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp))
            .background(Brush.verticalGradient(listOf(WrenFlat.ButtonTop, WrenFlat.ButtonBottom)))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = WrenFlat.ButtonText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** Rounded card (1008 x n px at 36 px in), with the faint orange top hairline from the render. */
@Composable
fun WrenCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Box(
        modifier.padding(horizontal = 14.dp).fillMaxWidth().clip(shape)
            .background(Brush.verticalGradient(listOf(WrenSettingsColors.CardTop, WrenSettingsColors.CardBottom)))
            .border(0.6.dp, Color(0x09FFFFFF), shape)
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(
                Brush.horizontalGradient(listOf(Color(0x00FB9E2E), Color(0x8CFB9E2E), Color(0x8CF97C02), Color(0x00F97C02)))
            )
        )
    }
}

@Composable
fun WrenSectionLabel(text: String) {
    Text(
        text, color = WrenSettingsColors.Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
        modifier = Modifier.padding(start = 29.dp, top = 19.dp, bottom = 10.dp)
    )
}

/** One row: orange chip (or dark chip with orange icon), title, grey subtitle, chevron. 180 px tall. */
@Composable
fun WrenNavRow(
    title: String, subtitle: String?, icon: Int, dark: Boolean = false, divider: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.6.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(15.dp))
            val chipShape = RoundedCornerShape(12.dp)
            Box(
                Modifier.size(42.7.dp).clip(chipShape).background(
                    if (dark) Brush.verticalGradient(listOf(WrenSettingsColors.ChipDarkTop, WrenSettingsColors.ChipDarkBottom))
                    else Brush.verticalGradient(listOf(WrenSettingsColors.OrangeTop, WrenSettingsColors.OrangeBottom))
                ).then(if (dark) Modifier.border(0.8.dp, WrenSettingsColors.Orange.copy(alpha = 0.55f), chipShape) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(painterResource(icon), contentDescription = null, tint = if (dark) WrenSettingsColors.Orange else WrenSettingsColors.PageTop, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = WrenSettingsColors.Title, fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 23.sp)
                if (subtitle != null) Text(subtitle, color = WrenSettingsColors.Sub, fontSize = 12.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(painterResource(R.drawable.wren_ic_chevron_right), contentDescription = null, tint = WrenSettingsColors.Chevron, modifier = Modifier.padding(start = 4.dp, end = 10.dp).size(20.dp))
        }
        if (divider) Box(Modifier.padding(start = 72.dp).fillMaxWidth().height(0.8.dp).background(WrenSettingsColors.Divider))
    }
}

/** The mock-up switch: 36 x 14 dp track, 20 dp thumb; orange when on, grey when off. Drawn only; the row toggles. */
@Composable
fun WrenSwitchVisual(checked: Boolean, disabled: Boolean = false) {
    Box(Modifier.padding(start = 16.dp).width(40.dp).height(20.dp).then(if (disabled) Modifier.alpha(0.5f) else Modifier), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width(36.dp).height(14.dp).clip(RoundedCornerShape(7.dp))
                .background(if (checked) WrenFlat.Orange.copy(alpha = 0.5f) else Color(0xFF5A5A5A))
        )
        Box(
            Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .shadow(2.dp, RoundedCornerShape(10.dp))
                .size(20.dp).clip(RoundedCornerShape(10.dp))
                .background(if (checked) WrenFlat.Orange else Color(0xFFBDBDBD))
        )
    }
}
