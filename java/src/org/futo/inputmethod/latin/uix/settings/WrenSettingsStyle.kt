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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val PageTop = Color(0xFF1B1B1E)
    val PageBottom = Color(0xFF141417)
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
        UixThemeWrapper(colorScheme = wrenSettingsScheme, content)
    } else {
        UixThemeAuto(content)
    }
}

val wrenPageBrush: Brush get() = Brush.verticalGradient(listOf(WrenSettingsColors.PageTop, WrenSettingsColors.PageTop, WrenSettingsColors.PageBottom))

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
                if (subtitle != null) Text(subtitle, color = WrenSettingsColors.Sub, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(painterResource(R.drawable.wren_ic_chevron_right), contentDescription = null, tint = WrenSettingsColors.Chevron, modifier = Modifier.padding(horizontal = 12.dp).size(23.dp))
        }
        if (divider) Box(Modifier.padding(start = 72.dp).fillMaxWidth().height(0.8.dp).background(WrenSettingsColors.Divider))
    }
}
