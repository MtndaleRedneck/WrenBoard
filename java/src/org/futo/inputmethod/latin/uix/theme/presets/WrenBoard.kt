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
import org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind
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

// Pixel spec v1.1 item 1: SwiftKey Pumpkin accent, measured ink #FF8710.
private val AccentOrange = Color(0xFFFF8710)
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
    // Pixel spec v1.1 item 22: board gradient #4C4C4C at the top to #2C2C2C at the bottom.
    keyboardBackgroundGradient=Brush.verticalGradient(listOf(Color(0xFF4C4C4C), Color(0xFF2C2C2C))),
    // Pixel spec v1.1 item 23: flat #3C3C3C under the keyboard, in the navigation bar area.
    navigationBarColor=Color(0xFF3C3C3C),
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

/** Original Pumpkin key drawing. Each cell owns its border, side rim and outer shadow. */
private class WrenKeyDrawable(
    private val fillTop: Int,
    private val fillBottom: Int,
    private val outlineColor: Int?,
    private val highlightColor: Int?,
    private val halo: Boolean,
    private val radiusPx: Float,
    dm: android.util.DisplayMetrics
) : android.graphics.drawable.Drawable() {
    private fun dp(v: Float, dm: android.util.DisplayMetrics) = v * dm.density
    private val hair = dp(0.5f, dm).coerceAtLeast(1f)
    private val marginX = dp(1.15f, dm)
    private val marginY = dp(1.35f, dm)
    private val highlightRadius = (radiusPx - hair).coerceAtLeast(0f)
    private val sidePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = hair
    }

    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = 0x40000000 // black at 25%
        strokeWidth = hair
    }
    private val outlinePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = hair
    }
    private val highlightPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = hair
    }
    private val rect = android.graphics.RectF()

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val left = b.left + marginX; val top = b.top + marginY
        val right = b.right - marginX; val bottom = b.bottom - marginY

        if (halo) {
            rect.set(left - hair / 2f, top - hair / 2f, right + hair / 2f, bottom + hair / 2f)
            canvas.drawRoundRect(rect, radiusPx + hair / 2f, radiusPx + hair / 2f, haloPaint)
        }

        fillPaint.shader = android.graphics.LinearGradient(
            0f, top, 0f, bottom, fillTop, fillBottom, android.graphics.Shader.TileMode.CLAMP
        )
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, fillPaint)

        // Draw the brighter side rim, then restore the face in the center. No bottom rim.
        fun brighter(c: Int, gain: Float): Int = android.graphics.Color.argb(
            android.graphics.Color.alpha(c),
            (android.graphics.Color.red(c) * gain).toInt().coerceAtMost(255),
            (android.graphics.Color.green(c) * gain).toInt().coerceAtMost(255),
            (android.graphics.Color.blue(c) * gain).toInt().coerceAtMost(255))
        val gain = if(fillTop == 0xF2555555.toInt()) 1.5f else 1.95f
        sidePaint.shader = android.graphics.LinearGradient(0f, top, 0f, bottom,
            brighter(fillTop, gain), fillBottom, android.graphics.Shader.TileMode.CLAMP)
        val hl = left + hair * 1.5f
        val ht = top + hair * 1.5f
        val hr = right - hair * 1.5f
        val hb = bottom - hair * 1.5f
        if(hr > hl && hb > ht) {
            val restore = canvas.save()
            canvas.clipRect(left, top, right, bottom - radiusPx)
            rect.set(hl, ht, hr, hb)
            canvas.drawRoundRect(rect, highlightRadius, highlightRadius, sidePaint)
            canvas.restoreToCount(restore)
            highlightColor?.let { c ->
                highlightPaint.color = c
                highlightPaint.shader = null
                val topOnly = canvas.save()
                canvas.clipRect(left, top, right, top + radiusPx)
                rect.set(hl, ht, hr, hb)
                canvas.drawRoundRect(rect, highlightRadius, highlightRadius, highlightPaint)
                canvas.restoreToCount(topOnly)
            }
        }

        outlineColor?.let { c ->
            outlinePaint.color = c
            rect.set(left + hair / 2f, top + hair / 2f, right - hair / 2f, bottom - hair / 2f)
            canvas.drawRoundRect(rect, radiusPx - hair / 2f, radiusPx - hair / 2f, outlinePaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha; haloPaint.alpha = alpha * 0x40 / 255
        outlinePaint.alpha = alpha; highlightPaint.alpha = alpha; sidePaint.alpha = alpha
    }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter; outlinePaint.colorFilter = colorFilter; highlightPaint.colorFilter = colorFilter; sidePaint.colorFilter = colorFilter
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

private fun dpPx(context: android.content.Context, v: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

// Approved outer key corner radius.
private const val KeyRadiusDp = 5.0f

/** Approved Pumpkin face: #3D3D3D to #020202 at 95%, white hints. */
fun letterKey(context: android.content.Context): android.graphics.drawable.Drawable = WrenKeyDrawable(
    0xF23D3D3D.toInt(), 0xF2020202.toInt(), 0xF2000000.toInt(), 0xF6777777.toInt(), true,
    dpPx(context, KeyRadiusDp), context.resources.displayMetrics)

/** Lighter number-row face, with the same original key shape. */
private fun numberKey(context: android.content.Context) = WrenKeyDrawable(
    0xF2555555.toInt(), 0xF2212121.toInt(), 0xF2212121.toInt(), 0xF6888888.toInt(), true,
    dpPx(context, KeyRadiusDp), context.resources.displayMetrics)

/** Pressed face: #646464 to #373737 at 96%. */
fun pressedKey(context: android.content.Context, halo: Boolean = true): android.graphics.drawable.Drawable = WrenKeyDrawable(
    0xF5646464.toInt(), 0xF5373737.toInt(), 0xCC000000.toInt(), 0xFF858585.toInt(), halo,
    dpPx(context, KeyRadiusDp), context.resources.displayMetrics)

private fun popupBox(context: android.content.Context): GradientDrawable {
    val dm = context.resources.displayMetrics
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        // Pixel spec v1.1 item 57: #333333 fill, 1 dp #BBBBBB border (2 px on a 2.8x screen), 5.0 dp radius
        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5.0f, dm)
        setColor(0xFF333333.toInt())
        setStroke(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.0f, dm)).coerceAtLeast(2), 0xFFBBBBBB.toInt())
    }
}

private fun popupSelection(context: android.content.Context): android.graphics.drawable.Drawable =
    object : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()
        override fun draw(canvas: android.graphics.Canvas) {
            val dx = dpPx(context, 2f)
            val dy = dpPx(context, 5f)
            rect.set(bounds.left + dx, bounds.top + dy, bounds.right - dx, bounds.bottom - dy)
            if (rect.width() <= 0 || rect.height() <= 0) return
            paint.style = android.graphics.Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom,
                0xFF595959.toInt(), 0xFF393939.toInt(), android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, dpPx(context, 5f), dpPx(context, 5f), paint)
            paint.shader = null
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = dpPx(context, 0.8f).coerceAtLeast(1f)
            paint.color = 0xFF222222.toInt()
            canvas.drawRoundRect(rect, dpPx(context, 5f), dpPx(context, 5f), paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
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
                // Pixel spec v1.1 item 57: same box as the hold bubble (1 dp #BBBBBB, 5.0 dp radius)
                background = popupBox(context)
            )
        ),
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.VisualStyle(KeyVisualStyle.MoreKey), KeyQualifier.Pressed),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                // Every selected accent uses the same pressed face as a held key.
                background = pressedKey(context, halo = false)
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
        // Pressed, every key including the number row (pixel spec v1.1 item 34)
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.Pressed),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = pressedKey(context)
            )
        ),
        // WrenBoard 1.6 (item 10): SwiftKey's number/phone pad has no lighter top row; every pad key is the
        // normal dark key (approved mock-ups 13/14).
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.ElementKind(setOf(KeyboardLayoutKind.NumberBasic, KeyboardLayoutKind.Phone))),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = letterKey(context)
            )
        ),
        // Top number row: slightly lighter gradient
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.RowColSelector(RowColSelection.RowEq(0))),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = numberKey(context)
            )
        ),
        // Caps lock (StickyOn) keeps the normal dark key; only the glyph changes (pixel spec v1.1 items 35, 37)
        KeyedBitmap(
            qualifiers = setOf(KeyQualifier.VisualStyle(KeyVisualStyle.StickyOn)),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = letterKey(context)
            )
        ),
        // Base letter/functional keys
        KeyedBitmap(
            qualifiers = emptySet(),
            bitmap = KeyBackground(
                foregroundColor = AccentOrange.toArgb(),
                hintColor = HintGrey,
                outlineColor = null,
                background = letterKey(context)
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

                // WrenBoard 1.5 (item 10, candidate): Tab's 1.5 measurement has our letters 14% SMALL
                // (x-height 28px vs SwiftKey 32px, tall letters 40 vs 45): 0.74 * 1.14 = 0.84.
                textSizeMultiplier = 0.84f,
                // PARITY 6: hint symbols at SwiftKey's size (Tab measured 1.3-size, target x1.2)
                hintSizeMultiplier = 1.2f,
                // WrenBoard 1.3 (teardown item 5): no orange transformer marker under suggestions.
                showSuggestionSourceIndicators = false,

                centerHints = true,
                // Pixel spec v1.1 items 3-9: SwiftKey's label positions, 13.2 dp Regular (400) hints,
                // 15.2 dp "123" and ",!?".
                swiftKeyLabelGeometry = true,
                hintTextSizeDp = 13.2f,
                switchLabelSizeDp = 15.2f,
                hintWeight = 400f,
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
