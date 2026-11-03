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
// WrenBoard 1.5 (item 10, candidate): SwiftKey's hint symbols are pure #FFFFFF.
private val HintGrey = 0xFFFFFFFF.toInt()

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
private class TopLineGradientDrawable(
    top: Int,
    bottom: Int,
    private val radiusPx: Float,
    dm: android.util.DisplayMetrics,
    private val chrome: Boolean
) : GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)) {
    // SwiftKey list items 2+3: Tab's pixel measurements are off a ~420dpi phone; scale with density.
    private val px420 = dm.density / 2.625f
    // WrenBoard 1.5 item 5c (Tab's cosmetic values, candidate pending owner pixel sign-off): the halo
    // is two crisp 1px rings just outside the key border - outer #393939 (9 levels under the #424242
    // board), inner #2E2E2E (20 under). Replaces the PARITY 1 single blurred #353535 stroke.
    private val haloPx = 1.0f * px420
    private val haloOuterPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = 0xFF393939.toInt()
        strokeWidth = haloPx.coerceAtLeast(1f)
    }
    private val haloInnerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = 0xFF2E2E2E.toInt()
        strokeWidth = haloPx.coerceAtLeast(1f)
    }
    // PARITY 1: 1px side rim 2px inside the border, #616161 at the top fading to ~#0B0B0B.
    // WrenBoard 1.5 (item 10, candidate): Tab measured SwiftKey's rim ~25 levels over the fill
    // vs our ~14 - brighten the top stop.
    private val rimPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = (1.0f * px420).coerceAtLeast(1f)
    }
    private val linePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        // WrenBoard 1.5 (item 10, candidate): SwiftKey's top highlight is a 2px soft line about
        // #52-#58, not our hard 1px #6D6D6D.
        color = 0xFF565656.toInt()
        strokeWidth = (2.0f * px420).coerceAtLeast(1.5f)
    }
    override fun draw(canvas: android.graphics.Canvas) {
        if (!chrome) { super.draw(canvas); drawTopLine(canvas, bounds); return }
        // WrenBoard 1.5 (item 10, candidate): inset 2px -> 1px so the halo hugs the border and the
        // visible key-to-key gap tightens toward SwiftKey's 6px@420.
        val inset = (1.0f * px420).toInt().coerceAtLeast(1)
        val save = canvas.save()
        canvas.clipRect(android.graphics.RectF(bounds))
        canvas.translate(0f, 0f)
        val orig = android.graphics.Rect(bounds)
        bounds = android.graphics.Rect(orig).apply { inset(inset, inset) }
        super.draw(canvas)
        val inner = android.graphics.Rect(bounds)
        bounds = orig
        canvas.restoreToCount(save)
        // two crisp halo rings just outside the border: inner ring 1px out, outer ring 2px out
        val ringInner = android.graphics.RectF(inner).apply { inset(-haloPx, -haloPx) }
        canvas.drawRoundRect(ringInner, radiusPx, radiusPx, haloInnerPaint)
        val ringOuter = android.graphics.RectF(inner).apply { inset(-2.0f * haloPx, -2.0f * haloPx) }
        canvas.drawRoundRect(ringOuter, radiusPx, radiusPx, haloOuterPaint)
        // side rims: vertical gradient #616161 -> #0B0B0B, 2px inside the border
        val rimX_l = inner.left + 2.0f * px420 + rimPaint.strokeWidth / 2f
        val rimX_r = inner.right - 2.0f * px420 - rimPaint.strokeWidth / 2f
        rimPaint.shader = android.graphics.LinearGradient(
            0f, inner.top.toFloat(), 0f, inner.bottom.toFloat(),
            0xFF707070.toInt(), 0xFF0B0B0B.toInt(), android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawLine(rimX_l, inner.top + radiusPx, rimX_l, inner.bottom - 1f, rimPaint)
        canvas.drawLine(rimX_r, inner.top + radiusPx, rimX_r, inner.bottom - 1f, rimPaint)
        drawTopLine(canvas, inner)
        drawBorderNoBottom(canvas, inner)
        drawBottomFade(canvas, inner)
    }

    // WrenBoard 1.5 (item 10, candidate): 1px #0C0C0C on top and sides only (was a full stroke);
    // bottom edge fades into the gap over 3-4px instead.
    private val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0C0C0C.toInt()
        strokeWidth = 1.0f
    }
    private fun drawBorderNoBottom(canvas: android.graphics.Canvas, b: android.graphics.Rect) {
        canvas.drawLine(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top.toFloat(), borderPaint)
        canvas.drawLine(b.left.toFloat(), b.top.toFloat(), b.left.toFloat(), b.bottom.toFloat(), borderPaint)
        canvas.drawLine(b.right.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), borderPaint)
    }
    private val bottomFadePaint = android.graphics.Paint().apply { isDither = true }
    private fun drawBottomFade(canvas: android.graphics.Canvas, b: android.graphics.Rect) {
        val fadeH = 3.5f * px420
        bottomFadePaint.shader = android.graphics.LinearGradient(
            0f, b.bottom - fadeH, 0f, b.bottom.toFloat(),
            intArrayOf(0x000C0C0C, 0x660C0C0C, 0xCC0C0C0C.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(b.left.toFloat(), b.bottom - fadeH, b.right.toFloat(), b.bottom.toFloat(), bottomFadePaint)
    }
    private fun drawTopLine(canvas: android.graphics.Canvas, b: android.graphics.Rect) {
        // center lands 3 SwiftKey px from the key's top edge (1px border + 2px gap + half the 1px line)
        val y = b.top + 3.0f * px420
        canvas.drawLine(b.left + radiusPx, y, b.right - radiusPx, y, linePaint)
    }
}

private fun keyGradient(context: android.content.Context, top: Int, bottom: Int, chrome: Boolean = true): GradientDrawable {
    val radiusPx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        // WrenBoard 1.5 (item 10, candidate): Tab measured SwiftKey corners ~13-14px@420 (~5.3dp)
        // vs our ~10px (4.0dp).
        5.3f,
        context.resources.displayMetrics
    )
    return TopLineGradientDrawable(top, bottom, radiusPx, context.resources.displayMetrics, chrome).apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        // SwiftKey list item 2: 1px #0C0C0C key border lives on the gradient; KeyBackground outline is off.
        // WrenBoard 1.5 (item 10, candidate): SwiftKey has NO hard bottom outline - the border draws on
        // the top and sides only, and the bottom fades into the gap over 3-4px. The stroke here drops to
        // the top+sides lines drawn in TopLineGradientDrawable; the fade lives there too.
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
                // S4: flat dark grey fill (Tab measured the gradient, target flat #333333); light border stays
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4.0f, context.resources.displayMetrics)
                    setColor(0xFF333333.toInt())
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
                // S7: selected cell inset so it doesn't paint over the panel outline (420dpi px in Rect)
                padding = run { val d = context.resources.displayMetrics.density; android.graphics.Rect((5*d/2.625f).toInt(), (13*d/2.625f).toInt(), (5*d/2.625f).toInt(), (13*d/2.625f).toInt()) },
                background = keyGradient(context, 0xFF626262.toInt(), 0xFF2A2A2A.toInt(), chrome = false)
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
                background = keyGradient(context, 0xFF767676.toInt(), 0xFF4A4A4A.toInt())
            )
        ),
        // Pressed base keys: lightened charcoal gradient
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.Pressed),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = keyGradient(context, 0xFF626262.toInt(), 0xFF363636.toInt())
            )
        ),
        // Top number row: slightly lighter gradient
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.RowColSelector(RowColSelection.RowEq(0))),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                // WrenBoard 1.5 (item 10, candidate): SwiftKey's number-row keys are visibly lighter
                // (top #54-#5A); target top about #56, bottom unchanged.
                background = keyGradient(context, 0xFF565656.toInt(), 0xFF242424.toInt())
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
            background = keyGradient(context, 0xFF3D3D3D.toInt(), 0xFF060606.toInt())
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
                // WrenBoard 1.5 (item 10, candidate): Tab's 1.5 measurement has our letters 14% SMALL
                // (x-height 28px vs SwiftKey 32px, tall letters 40 vs 45): 0.74 * 1.14 = 0.84.
                textSizeMultiplier = 0.84f,
                // PARITY 6: hint symbols at SwiftKey's size (Tab measured 1.3-size, target x1.2)
                hintSizeMultiplier = 1.2f,
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
