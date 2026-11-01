package org.futo.inputmethod.latin.uix.theme.presets

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.extendedDarkColorScheme
import org.futo.inputmethod.latin.uix.theme.AdvancedThemeOptions
import org.futo.inputmethod.latin.uix.theme.KeyBackground
import org.futo.inputmethod.latin.uix.theme.KeyedBitmap
import org.futo.inputmethod.latin.uix.theme.KeyedBitmaps
import org.futo.inputmethod.latin.uix.theme.KeyQualifier
import org.futo.inputmethod.latin.uix.theme.RowColSelection
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.futo.inputmethod.latin.uix.theme.selector.ThemePreview
import org.futo.inputmethod.v2keyboard.KeyVisualStyle

// WrenBoard default theme: dark charcoal board, gradient keys, orange accent.
// Colors measured from the user's reference design.

private val AccentOrange = Color(0xFFFF8A14)
private val HintGrey = 0xFFECECEC.toInt()

private val darkScheme = extendedDarkColorScheme(
    primary=AccentOrange,
    onPrimary=Color(0xFF141414),
    primaryContainer=AccentOrange,
    onPrimaryContainer=Color(0xFF141414),
    secondary=Color(0xFFF5F5F5),
    onSecondary=Color(0xFF3D3D3D),
    secondaryContainer=Color(0xFF3D3D3D),
    onSecondaryContainer=Color(0xFFCBD1D6),
    tertiary=Color(0xFFFFB35C),
    onTertiary=Color(0xFF2A1A05),
    tertiaryContainer=Color(0xFF804A00),
    onTertiaryContainer=Color(0xFFFFD9B0),
    error=Color(0xFFF2A7A9),
    onError=Color(0xFF783032),
    errorContainer=Color(0xFF801A20),
    onErrorContainer=Color(0xFFF2C2C5),
    outline=Color(0xFFB2B2B2),
    outlineVariant=Color(0xFF545859),
    surface=Color(0xFF4C4C4C),
    onSurface=Color(0xFFF2F2F2),
    onSurfaceVariant=Color(0xFFC7C7C7),
    surfaceContainerHighest=Color(0xFF4D4D4D),
    shadow=Color(0xFF000000).copy(alpha = 0.7f),
    keyboardSurface=Color(0xFF3C3C3C),
    // WrenBoard 1.3 (SwiftKey list item 6): Pumpkin board gradient, from Tab's measured SwiftKey pixels.
    keyboardBackgroundGradient=Brush.verticalGradient(listOf(Color(0xFF4C4C4C), Color(0xFF2A2A2A))),
    keyboardSurfaceDim=Color(0xFF3C3C3C),
    keyboardContainer=Color(0xFF232323),
    keyboardContainerVariant=Color(0xFF1C1C1C),
    onKeyboardContainer=AccentOrange,
    keyboardPress=Color(0xFF565656),
    keyboardFade0=Color(0xFF3C3C3C),
    keyboardFade1=Color(0xFF3C3C3C),
    primaryTransparent=AccentOrange.copy(alpha = 0.3f),
    onSurfaceTransparent=Color(0xFFF2F2F2).copy(alpha = 0.1f),
    hintColor=Color(0xFFB0B0B0),
)

/** Lighten [c] toward white by [f] (0..1). */
private fun lighten(c: Int, f: Float): Int {
    val r = android.graphics.Color.red(c) + ((255 - android.graphics.Color.red(c)) * f).toInt()
    val g = android.graphics.Color.green(c) + ((255 - android.graphics.Color.green(c)) * f).toInt()
    val b = android.graphics.Color.blue(c) + ((255 - android.graphics.Color.blue(c)) * f).toInt()
    return android.graphics.Color.rgb(r, g, b)
}

/** Gradient key box with SwiftKey's thin light line along the top edge (WrenBoard 1.3, teardown item 1).
 *  The line shade is interim, pending Tab's measured SwiftKey pixels. */
private class TopLineGradientDrawable(top: Int, bottom: Int, private val radiusPx: Float, dm: android.util.DisplayMetrics) :
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)) {
    // SwiftKey list items 2+3: Tab's pixel measurements are off a ~420dpi phone; scale with density.
    private val px420 = dm.density / 2.625f
    private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6E6E6E.toInt()
        strokeWidth = (1.0f * px420).coerceAtLeast(1f)
    }
    override fun draw(canvas: android.graphics.Canvas) {
        super.draw(canvas)
        // center lands 3 SwiftKey px from the key's top edge (1px border + 2px gap + half the 1px line)
        val y = bounds.top + 3.0f * px420
        canvas.drawLine(bounds.left + radiusPx, y, bounds.right - radiusPx, y, linePaint)
    }
}

private fun keyGradient(context: android.content.Context, top: Int, bottom: Int): GradientDrawable {
    val radiusPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        4.0f,
        context.resources.displayMetrics
    )
    return TopLineGradientDrawable(top, bottom, radiusPx, context.resources.displayMetrics).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        // SwiftKey list item 2: 1px #0C0C0C key border lives on the gradient; KeyBackground outline is off.
        setStroke((1.0f * context.resources.displayMetrics.density / 2.625f).toInt().coerceAtLeast(1), 0xFF0C0C0C.toInt())
    }
}

private fun popupBox(context: android.content.Context): GradientDrawable {
    val dm = context.resources.displayMetrics
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6.0f, dm)
        // SwiftKey list item 5: #333333 fill, 3px #BBBBBB border
        setColor(0xFF333333.toInt())
        setStroke((3.0f * dm.density / 2.625f).toInt().coerceAtLeast(2), 0xFFBBBBBB.toInt())
    }
}

private fun wrenKeyBackgrounds(context: android.content.Context) = KeyedBitmaps(
    listOf(
        // Long-press popup (P0-1): one solid box with a light border, items drawn flat inside it, and the item
        // under the finger raised as a key so it is clear what a release will type (SwiftKey reference frame).
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.MoreKeysKeyboardBackground),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                outlineColor = null,
                background = popupBox(context)
            )
        ),
        // Key preview: a raised key-shaped box (not the tall balloon), lighter than the key with a light border.
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.Popup),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = keyGradient(context, 0xFF5E5E5E.toInt(), 0xFF262626.toInt()).apply {
                    setStroke(
                        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.0f, context.resources.displayMetrics).toInt().coerceAtLeast(2),
                        0xFFB4B4B4.toInt()
                    )
                }
            )
        ),
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.VisualStyle(KeyVisualStyle.MoreKey), KeyQualifier.Pressed),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = 0xFFD0D0D0.toInt(),
                outlineWidth = 1.dp,
                background = keyGradient(context, 0xFF626262.toInt(), 0xFF2A2A2A.toInt())
            )
        ),
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.VisualStyle(KeyVisualStyle.MoreKey)),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = ColorDrawable(android.graphics.Color.TRANSPARENT)
            )
        ),
        // Most-specific entries first: matcher returns the first match.
        // Pressed top number row: lightened number gradient
        KeyedBitmap(
            qualifiers = setOf(
                KeyQualifier.RowColSelector(RowColSelection.RowEq(0)),
                KeyQualifier.Pressed
            ),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = keyGradient(context, 0xFF6A6A6A.toInt(), 0xFF383838.toInt())
            )
        ),
        // Pressed base keys: lightened charcoal gradient
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.Pressed),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = keyGradient(context, 0xFF565656.toInt(), 0xFF242424.toInt())
            )
        ),
        // Top number row: slightly lighter gradient
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.RowColSelector(RowColSelection.RowEq(0))),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = keyGradient(context, 0xFF515151.toInt(), 0xFF242424.toInt())
            )
        ),
        // Base letter/functional keys: charcoal vertical gradient with thin dark border
        KeyedBitmap(
            qualifiers = emptySet(),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                // WrenBoard 1.3 (teardown item 1): the old top (#3E3E3E) sat 2 levels off the #3C3C3C board,
            // so the key top vanished. #4C4C4C keeps the charcoal look but reads as a full button box.
            background = keyGradient(context, 0xFF4C4C4C.toInt(), 0xFF060606.toInt())
            )
        ),
    )
)

val WrenBoardTheme = ThemeOption(
    dynamic = false,
    key = "WrenBoard",
    name = R.string.theme_wrenboard,
    available = { true }
) { context ->
    darkScheme.copy(
        base = darkScheme.base.copy(
            onBackground = AccentOrange,
            onSurface = AccentOrange,
            onSurfaceVariant = AccentOrange.copy(alpha = 0.78f)
        ),
        extended = darkScheme.extended.copy(
            advancedThemeOptions = AdvancedThemeOptions(
                keyRoundness = 0.78f,
                // SwiftKey list item 1: letters measured ~35% too big at 1.0; 0.74 matches Tab's SwiftKey spec
                // (Ezzy's phone renders ~0.82; Tab re-measures there in the consolidated review).
                textSizeMultiplier = 0.74f,
                // WrenBoard 1.3 (teardown item 5): no orange transformer marker under suggestions.
                showSuggestionSourceIndicators = false,
                // Symbols sit centred above the letter, as on Ezzy's phone, SwiftKey and the approved render.
                centerHints = true,
                keyBorders = true,
                keyBackgrounds = wrenKeyBackgrounds(context)
            )
        )
    )
}

@Composable
@Preview
private fun PreviewTheme() {
    ThemePreview(WrenBoardTheme)
}
