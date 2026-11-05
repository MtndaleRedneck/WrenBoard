package org.futo.inputmethod.latin.uix.theme

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.futo.inputmethod.keyboard.Key
import org.futo.inputmethod.keyboard.Keyboard
import org.futo.inputmethod.keyboard.internal.KeyDrawParams
import org.futo.inputmethod.latin.uix.DynamicThemeProvider
import org.futo.inputmethod.latin.uix.KeyboardColorScheme
import kotlin.math.roundToInt

data class KeyOutline(
    val color: Int,
    val widthPx: Int
)

data class KeyDrawingConfiguration(
    val background: Drawable?,
    val backgroundPadding: Rect?,
    val backgroundGap: RectF?,
    val icon: Drawable?,
    val hintIcon: Drawable?,
    val label: String?,
    val hintLabel: String?,
    val textColor: Int,
    val hintColor: Int,
    val textSize: Float,
    val hintSize: Float,
    val textTypeface: Typeface,
    val hintTypeface: Typeface,
    val centeredHint: Boolean = false,
    val outline: KeyOutline? = null,
    val swiftKeyGeometry: Boolean = false,
)

data class CachedKeyedMatcher<T>(
    val cacheId: Int,
    val full: List<T>,
    val matcher: (Keyboard, Key, Int, T) -> Boolean
) {
    fun find(keyboard: Keyboard, key: Key, layer: Int): T? {
        val idx = key.themeCache.getOrPut(cacheId) {
            full.indexOfFirst { matcher(keyboard, key, layer, it) }
        }
        return if(idx == -1) null else full[idx]
    }
}

internal fun<T> keyedBitmapMatcher(popup: Boolean = false) = { keyboard: Keyboard, key: Key, layer: Int, entry: KeyedBitmap<T> ->
    matchesKey(entry.qualifiers, keyboard.mId.mKeyboardLayoutSetName, keyboard, key, layer, popup)
}

private var globalId = 0
class AdvancedThemeMatcher(
    val context: Context,
    val drawableProvider: DynamicThemeProvider,
    val scheme: KeyboardColorScheme
) {
    val theme = scheme.extended.advancedThemeOptions

    val backgroundList = theme.keyBackgrounds?.v ?: emptyList()
    val layers = (listOf(0) + backgroundList.map { getLayer(it.qualifiers) })
                    .sorted().distinct()

    val id = globalId.also {
        globalId += layers.size + 4
    }

    val iconList = theme.keyIcons?.v ?: emptyList()

    val backgrounds = buildMap {
        layers.forEachIndexed { index, i ->
            put(i, CachedKeyedMatcher(cacheId = id + index, full = backgroundList.filter { getLayer(it.qualifiers) == i}, keyedBitmapMatcher<KeyBackground>()))
        }
    }

    val icons = CachedKeyedMatcher(cacheId = id + layers.size +1, full = iconList, matcher = keyedBitmapMatcher<KeyIcon>())
    val popupBackgrounds = CachedKeyedMatcher(cacheId = id + layers.size + 2, full = backgroundList, matcher = keyedBitmapMatcher<KeyBackground>(true))
    val hintIcons = CachedKeyedMatcher(cacheId = id + layers.size + 3, full = iconList, matcher = { keyboard: Keyboard, key: Key, layer: Int, entry: KeyedBitmap<KeyIcon> ->
        val hintLabel = key.effectiveHintLabel
        val hintIcon = key.effectiveHintIcon
        matchesHint(entry.qualifiers, keyboard.mId.mKeyboardLayoutSetName, hintLabel, hintIcon)
    })

    fun findIcon(matcher: CachedKeyedMatcher<KeyedBitmap<KeyIcon>>, keyboard: Keyboard, key: Key): KeyIcon? {
        if(matcher.full.isEmpty()) return null

        val bitmap = matcher.find(keyboard, key, 0)
        if(bitmap == null) return null

        return bitmap.bitmap
    }

    fun findBackground(matcher: CachedKeyedMatcher<KeyedBitmap<KeyBackground>>, keyboard: Keyboard, key: Key, layer: Int): KeyBackground? {
        if(matcher.full.isEmpty()) return null

        val bitmap = matcher.find(keyboard, key, layer)
        if(bitmap == null) return null

        return bitmap.bitmap
    }

    fun matchMoreKeysKeyboardBackground(layout: String): Drawable?
        = theme.keyBackgrounds?.v?.firstOrNull {
            matchesMoreKeysKeyboardBackground(it.qualifiers, layout)
        }?.bitmap?.background

    fun matchKeyPopup(keyboard: Keyboard?, key: Key): KeyBackground? {
        if(popupBackgrounds.full.isEmpty()) return null
        if(keyboard == null) return null
        return popupBackgrounds.find(keyboard, key, 0)?.bitmap
    }

    val identityRect = Rect(0,0,0,0)
    val identityGap = RectF(1.0f, 1.0f, 1.0f, 1.0f)

    fun matchKeyDrawingConfiguration(keyboard: Keyboard?, params: KeyDrawParams, key: Key, layer: Int): KeyDrawingConfiguration? {
        if(keyboard == null) return KeyDrawingConfiguration(
            background = null,
            backgroundPadding = identityRect,
            backgroundGap = identityGap,
            icon = null,
            hintIcon = null,
            label = key.labelOverride ?: key.label,
            hintLabel = key.effectiveHintLabel,
            textColor = key.selectTextColor(drawableProvider, params),
            hintColor = key.selectHintTextColor(drawableProvider, params),
            textSize = key.selectTextSize(params).toFloat(),
            hintSize = key.selectHintTextSize(drawableProvider, params).toFloat(),
            textTypeface = key.selectTypeface(params),
            hintTypeface = key.selectHintTypeface(drawableProvider, params)
        )

        val foundBackground = backgrounds[layer]?.let { findBackground(it, keyboard, key, layer) }
        if(foundBackground == null && layer != 0) return null

        val backgroundPadding = foundBackground?.padding ?: identityRect
        val backgroundGap = foundBackground?.gap ?: identityGap
        val background = foundBackground?.background ?: key.selectBackground(drawableProvider)
        val textColor = foundBackground?.foregroundColor ?: key.selectTextColor(drawableProvider, params)

        val outline = foundBackground?.outlineColor?.let {
            KeyOutline(it, TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                foundBackground.outlineWidth.value,
                context.resources.displayMetrics
            ).roundToInt())
        }

        val hintColor = foundBackground?.hintColor ?: foundBackground?.foregroundColor?.let { fgCol ->
            Color(fgCol).let { it.copy(alpha = it.alpha*0.8f) }.toArgb()
        } ?: key.selectHintTextColor(drawableProvider, params)

        val icon = findIcon(icons, keyboard, key)?.drawable ?: key.getIconOverride(keyboard.mIconsSet, params.mAnimAlpha)
        val hintIcon = findIcon(hintIcons, keyboard, key)?.drawable

        var label: String? = key.labelOverride ?: key.label
        var hintLabel: String? = if(hintIcon == null) key.effectiveHintLabel else null

        // S9 + nits: SwiftKey's alpha/symbol switch labels are bigger - "123" x1.55 (42x20 -> 65x32),
        // lowercase "abc" x1.75 so it reads larger than "123" (Tab's cmp_symbols measure)
        val switchLabelBoost = when {
            key.code == org.futo.inputmethod.latin.common.Constants.CODE_SWITCH_ALPHA_SYMBOL ->
                if (label?.firstOrNull()?.isLowerCase() == true) 1.75f else 1.55f
            // nit: SwiftKey's comma glyph is 9x16 vs our 7x13
            label == "," -> 1.25f
            else -> 1.0f
        }
        val adv = scheme.extended.advancedThemeOptions
        val dm = context.resources.displayMetrics
        var textSize = key.selectTextSize(params).toFloat() * adv.textSizeMultiplier * switchLabelBoost
        var hintSize = key.selectHintTextSize(drawableProvider, params).toFloat() * adv.hintSizeMultiplier
        if (adv.swiftKeyLabelGeometry) {
            // Pixel spec v1.1 items 5, 8, 9: hints 13.2 dp; letters-page "123" and ",!?" 15.2 dp;
            // the comma at the letter size (no boost).
            if (label == ",") textSize = key.selectTextSize(params).toFloat() * adv.textSizeMultiplier
            adv.switchLabelSizeDp?.let { sz ->
                if (key.code == org.futo.inputmethod.latin.common.Constants.CODE_SWITCH_ALPHA_SYMBOL && label == "123") {
                    textSize = sz * dm.density
                }
            }
            adv.hintTextSizeDp?.let { hintSize = it * dm.density }
            adv.switchLabelSizeDp?.let { sz -> if (hintLabel == ",!?") hintSize = sz * dm.density }
        }

        var textTypeface = drawableProvider.selectKeyTypeface(key.selectTypeface(params))
        var hintTypeface = drawableProvider.selectKeyTypeface(key.selectHintTypeface(drawableProvider, params))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (scheme.extended.advancedThemeOptions.textWeight != null) {
                textTypeface = Typeface.create(
                    textTypeface,
                    scheme.extended.advancedThemeOptions.textWeight.roundToInt(),
                    false
                )
            }

            if (scheme.extended.advancedThemeOptions.hintWeight != null) {
                hintTypeface = Typeface.create(
                    hintTypeface,
                    scheme.extended.advancedThemeOptions.hintWeight.roundToInt(),
                    false
                )
            }
        }

        return KeyDrawingConfiguration(
            background = background,
            backgroundPadding = backgroundPadding,
            backgroundGap = backgroundGap,
            icon = icon,
            hintIcon = hintIcon ?: key.getHintIcon(keyboard.mIconsSet, params.mAnimAlpha),
            label = label,
            hintLabel = hintLabel,
            textColor = textColor,
            hintColor = hintColor,
            textSize = textSize,
            hintSize = hintSize,
            textTypeface = textTypeface,
            hintTypeface = hintTypeface,
            centeredHint = scheme.extended.advancedThemeOptions.centerHints,
            outline = outline,
            swiftKeyGeometry = scheme.extended.advancedThemeOptions.swiftKeyLabelGeometry
        )
    }
}
