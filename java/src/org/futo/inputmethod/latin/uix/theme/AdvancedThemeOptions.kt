package org.futo.inputmethod.latin.uix.theme

import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class KeyBackground(
    val foregroundColor: Int?,
    val outlineColor: Int?,
    val outlineWidth: Dp = 3.dp,
    val padding: Rect = Rect(0,0,0,0),
    val gap: RectF = RectF(1.0f,1.0f,1.0f,1.0f),
    val background: Drawable,
    val hintColor: Int? = null
)

data class KeyIcon(
    val drawable: Drawable
)

data class AdvancedThemeOptions(
    val backgroundShader: String? = null,
    val backgroundImage: ImageBitmap? = null,
    val backgroundImageVisibleArea: Rect? = null,
    val thumbnailImage: ImageBitmap? = null,
    val thumbnailScale: Float = 1.0f,
    val keyRoundness: Float = 1.0f,
    val actionBarOpacity: Float = 0.5f,
    val keyBorders: Boolean? = null,
    val keyBackgrounds: KeyedBitmaps<KeyBackground>? = null,
    val keyIcons: KeyedBitmaps<KeyIcon>? = null,
    val font: Typeface? = null,
    val themeName: String? = null,
    val themeAuthor: String? = null,

    val textSizeMultiplier: Float = 1.0f,
    val hintSizeMultiplier: Float = 1.0f,
    val textWeight: Float? = null,
    val hintWeight: Float? = null,

    val centerHints: Boolean = false,

    /** Draw the small transformer/swipe source markers under suggestions. WrenBoard 1.3 (teardown item 5):
     *  Ezzy wants the orange transformer marker gone on the WrenBoard theme. */
    val showSuggestionSourceIndicators: Boolean = true,

    /** WrenBoard SwiftKey parity (pixel spec v1.1 items 3-9): place letter, digit, hint and bottom-row labels
     *  at SwiftKey's measured positions (fractions of the row pitch from the visible key top). */
    val swiftKeyLabelGeometry: Boolean = false,
    /** Explicit hint text size in dp (overrides hintSizeMultiplier), e.g. 13.2 for SwiftKey. */
    val hintTextSizeDp: Float? = null,
    /** Explicit size in dp for the letters-page "123" label and the ",!?" hint, e.g. 15.2 for SwiftKey. */
    val switchLabelSizeDp: Float? = null,
)
