/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.inputmethod.keyboard;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;

import org.futo.inputmethod.keyboard.internal.KeyDrawParams;
import org.futo.inputmethod.keyboard.internal.KeyboardIconsSet;
import org.futo.inputmethod.keyboard.internal.KeyVisualAttributes;
import org.futo.inputmethod.latin.uix.DynamicThemeProvider;
import org.futo.inputmethod.latin.R;
import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.uix.theme.KeyDrawingConfiguration;
import org.futo.inputmethod.latin.uix.theme.KeyOutline;
import org.futo.inputmethod.latin.utils.TypefaceUtils;

import java.util.HashSet;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A view that renders a virtual {@link Keyboard}.
 *
 * @attr ref R.styleable#KeyboardView_keyBackground
 * @attr ref R.styleable#KeyboardView_functionalKeyBackground
 * @attr ref R.styleable#KeyboardView_spacebarBackground
 * @attr ref R.styleable#KeyboardView_spacebarIconWidthRatio
 * @attr ref R.styleable#Keyboard_Key_keyLabelFlags
 * @attr ref R.styleable#KeyboardView_keyHintLetterPadding
 * @attr ref R.styleable#KeyboardView_keyPopupHintLetter
 * @attr ref R.styleable#KeyboardView_keyPopupHintLetterPadding
 * @attr ref R.styleable#KeyboardView_keyShiftedLetterHintPadding
 * @attr ref R.styleable#KeyboardView_keyTextShadowRadius
 * @attr ref R.styleable#KeyboardView_verticalCorrection
 * @attr ref R.styleable#Keyboard_Key_keyTypeface
 * @attr ref R.styleable#Keyboard_Key_keyLetterSize
 * @attr ref R.styleable#Keyboard_Key_keyLabelSize
 * @attr ref R.styleable#Keyboard_Key_keyLargeLetterRatio
 * @attr ref R.styleable#Keyboard_Key_keyLargeLabelRatio
 * @attr ref R.styleable#Keyboard_Key_keyHintLetterRatio
 * @attr ref R.styleable#Keyboard_Key_keyShiftedLetterHintRatio
 * @attr ref R.styleable#Keyboard_Key_keyHintLabelRatio
 * @attr ref R.styleable#Keyboard_Key_keyLabelOffCenterRatio
 * @attr ref R.styleable#Keyboard_Key_keyHintLabelOffCenterRatio
 * @attr ref R.styleable#Keyboard_Key_keyPreviewTextRatio
 * @attr ref R.styleable#Keyboard_Key_keyTextColor
 * @attr ref R.styleable#Keyboard_Key_keyTextColorDisabled
 * @attr ref R.styleable#Keyboard_Key_keyTextShadowColor
 * @attr ref R.styleable#Keyboard_Key_keyHintLetterColor
 * @attr ref R.styleable#Keyboard_Key_keyHintLabelColor
 * @attr ref R.styleable#Keyboard_Key_keyShiftedLetterHintInactivatedColor
 * @attr ref R.styleable#Keyboard_Key_keyShiftedLetterHintActivatedColor
 * @attr ref R.styleable#Keyboard_Key_keyPreviewTextColor
 */
public class KeyboardView extends View {
    // XML attributes
    private final KeyVisualAttributes mKeyVisualAttributes;
    // Default keyLabelFlags from {@link KeyboardTheme}.
    // Currently only "alignHintLabelToBottom" is supported.
    private final int mDefaultKeyLabelFlags;
    private final float mKeyHintLetterPadding;
    private final String mKeyPopupHintLetter;
    private final float mKeyPopupHintLetterPadding;
    private final float mKeyShiftedLetterHintPadding;
    private final float mKeyTextShadowRadius;
    private final float mVerticalCorrection;
    private final Drawable mKeyboardBackground;
    protected final DynamicThemeProvider mDrawableProvider;
    private final float mSpacebarIconWidthRatio;
    private final Rect mKeyBackgroundPadding = new Rect();
    private static final float KET_TEXT_SHADOW_RADIUS_DISABLED = -1.0f;

    // The maximum key label width in the proportion to the key width.
    private static final float MAX_LABEL_RATIO = 0.90f;

    // Main keyboard
    // TODO: Consider having a base keyboard object to make this @Nonnull
    @Nullable
    private Keyboard mKeyboard;
    @Nonnull
    private final KeyDrawParams mKeyDrawParams = new KeyDrawParams();

    // Drawing
    /** True if all keys should be drawn */
    private boolean mInvalidateAllKeys;
    /** The keys that should be drawn */
    private final HashSet<Key> mInvalidatedKeys = new HashSet<>();
    /** The working rectangle for clipping */
    private final Rect mClipRect = new Rect();
    /** The keyboard bitmap buffer for faster updates */
    private Bitmap mOffscreenBuffer;
    /** The canvas for the above mutable keyboard bitmap */
    @Nonnull
    private final Canvas mOffscreenCanvas = new Canvas();
    @Nonnull
    private final Paint mPaint = new Paint();
    private final Paint.FontMetrics mFontMetrics = new Paint.FontMetrics();

    public KeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.keyboardViewStyle);
    }

    public KeyboardView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);

        final TypedArray keyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.KeyboardView, defStyle, R.style.KeyboardView);

        final TypedArray keyAttr = context.obtainStyledAttributes(attrs,
                R.styleable.Keyboard_Key, defStyle, R.style.KeyboardView);

        mDrawableProvider = DynamicThemeProvider.obtainFromContext(context);

        boolean isMoreKeys = keyAttr.getBoolean(R.styleable.Keyboard_Key_isMoreKey, false);
        boolean isMoreKeysAction = keyAttr.getBoolean(R.styleable.Keyboard_Key_isAction, false);

        mKeyboardBackground = isMoreKeys ?  mDrawableProvider.getMoreKeysKeyboardBackground() :
                        mDrawableProvider.getKeyboardBackground();
        setBackground(null);
        setBackgroundColor(0);

        mSpacebarIconWidthRatio = keyboardViewAttr.getFloat(
                R.styleable.KeyboardView_spacebarIconWidthRatio, 1.0f);
        mKeyHintLetterPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyHintLetterPadding, 0.0f);
        mKeyPopupHintLetter = keyboardViewAttr.getString(
                R.styleable.KeyboardView_keyPopupHintLetter);
        mKeyPopupHintLetterPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyPopupHintLetterPadding, 0.0f);
        mKeyShiftedLetterHintPadding = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_keyShiftedLetterHintPadding, 0.0f);
        mKeyTextShadowRadius = keyboardViewAttr.getFloat(
                R.styleable.KeyboardView_keyTextShadowRadius, KET_TEXT_SHADOW_RADIUS_DISABLED);
        mVerticalCorrection = keyboardViewAttr.getDimension(
                R.styleable.KeyboardView_verticalCorrection, 0.0f);
        keyboardViewAttr.recycle();

        mDefaultKeyLabelFlags = keyAttr.getInt(R.styleable.Keyboard_Key_keyLabelFlags, 0);
        mKeyVisualAttributes = KeyVisualAttributes.newInstance(keyAttr, mDrawableProvider);

        if((isMoreKeys || isMoreKeysAction) && mKeyVisualAttributes != null) {
            mKeyVisualAttributes.mTextColor = mDrawableProvider.getMoreKeysTextColor();
        }

        keyAttr.recycle();

        mPaint.setAntiAlias(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForceDarkAllowed(false);
        }
    }

    @Nullable
    public KeyVisualAttributes getKeyVisualAttribute() {
        return mKeyVisualAttributes;
    }

    private static void blendAlpha(@Nonnull final Paint paint, final int alpha) {
        final int color = paint.getColor();
        paint.setARGB((paint.getAlpha() * alpha) / Constants.Color.ALPHA_OPAQUE,
                Color.red(color), Color.green(color), Color.blue(color));
    }

    public void setHardwareAcceleratedDrawingEnabled(final boolean enabled) {
        if (!enabled) return;
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see Keyboard
     * @see #getKeyboard()
     * @param keyboard the keyboard to display in this view
     */
    public void setKeyboard(@Nonnull final Keyboard keyboard) {
        mKeyboard = keyboard;
        final int keyHeight = keyboard.mMostCommonKeyHeight - keyboard.mVerticalGap;
        final int keyWidth = labelReferenceKeyWidth(keyboard);

        mKeyDrawParams.updateParams(keyWidth, Math.min(keyWidth, keyHeight), mKeyVisualAttributes);
        mKeyDrawParams.updateParams(keyWidth, Math.min(keyWidth, keyHeight), keyboard.mKeyVisualAttributes);
        invalidateAllKeys();
        requestLayout();
    }

    /**
     * Returns the current keyboard being displayed by this view.
     * @return the currently attached keyboard
     * @see #setKeyboard(Keyboard)
     */
    @Nullable
    public Keyboard getKeyboard() {
        return mKeyboard;
    }

    protected float getVerticalCorrection() {
        return mVerticalCorrection;
    }

    @Nonnull
    protected KeyDrawParams getKeyDrawParams() {
        return mKeyDrawParams;
    }

    /**
     * WrenBoard 1.6 (approved mock-up 05): label sizes follow the narrower of key width and height. Symbols
     * page 2 has 9 keys per row instead of 10, so its wider keys drew every symbol about 20% bigger than
     * the mock-up. Page 2 sizes its labels from 80% of its key width, which lands on the mock-up.
     */
    private static int labelReferenceKeyWidth(@Nonnull final Keyboard keyboard) {
        final int keyWidth = keyboard.mMostCommonKeyWidth;
        if (keyboard.mId != null && keyboard.mId.mElementId == KeyboardId.ELEMENT_SYMBOLS_SHIFTED) {
            // 9 wide keys per row: scale to the approved mock-up's measured page-2 glyphs ([ ] 52 px at
            // 1080x2316; full key width drew them 64 px, the 10-key width 58 px)
            return keyWidth * 8 / 10;
        }
        return keyWidth;
    }

    protected void updateKeyDrawParams(final int keyHeight) {
        final int keyWidth = labelReferenceKeyWidth(mKeyboard);
        mKeyDrawParams.updateParams(keyWidth, Math.min(keyWidth, keyHeight), mKeyVisualAttributes);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        // The main keyboard expands to the entire this {@link KeyboardView}.
        final int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();
        final int height = keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);

        mKeyboardBackground.setBounds(0, 0, getWidth(), getHeight());
        mKeyboardBackground.draw(canvas);

        if (canvas.isHardwareAccelerated()) {
            onDrawKeyboard(canvas);
            return;
        }

        final boolean bufferNeedsUpdates = mInvalidateAllKeys || !mInvalidatedKeys.isEmpty();
        if (bufferNeedsUpdates || mOffscreenBuffer == null) {
            if (maybeAllocateOffscreenBuffer()) {
                mInvalidateAllKeys = true;
                // TODO: Stop using the offscreen canvas even when in software rendering
                mOffscreenCanvas.setBitmap(mOffscreenBuffer);
            }
            onDrawKeyboard(mOffscreenCanvas);
        }
        canvas.drawBitmap(mOffscreenBuffer, 0.0f, 0.0f, null);
    }

    private boolean maybeAllocateOffscreenBuffer() {
        final int width = getWidth();
        final int height = getHeight();
        if (width == 0 || height == 0) {
            return false;
        }
        if (mOffscreenBuffer != null && mOffscreenBuffer.getWidth() == width
                && mOffscreenBuffer.getHeight() == height) {
            return false;
        }
        freeOffscreenBuffer();
        mOffscreenBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        return true;
    }

    private void freeOffscreenBuffer() {
        setLayerType(LAYER_TYPE_NONE, null);

        mOffscreenCanvas.setBitmap(null);
        mOffscreenCanvas.setMatrix(null);
        if (mOffscreenBuffer != null) {
            mOffscreenBuffer.recycle();
            mOffscreenBuffer = null;
        }
    }

    private void onDrawKeyboard(@Nonnull final Canvas canvas) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }

        final Paint paint = mPaint;
        final Drawable background = getBackground();
        // Calculate clip region and set.
        final boolean drawAllKeys = mInvalidateAllKeys || mInvalidatedKeys.isEmpty();
        final boolean isHardwareAccelerated = canvas.isHardwareAccelerated();
        final List<Integer> layers = mDrawableProvider.getLayers();
        if (drawAllKeys || isHardwareAccelerated || layers.size() > 1) {
            if (!isHardwareAccelerated && background != null) {
                // Need to draw keyboard background on {@link #mOffscreenBuffer}.
                canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR);
                background.draw(canvas);
            }
            // Draw all keys.
            for(int i=0; i<layers.size(); i++) {
                int layer = layers.get(i);
                for (final Key key : keyboard.getSortedKeys()) {
                    onDrawKey(key, canvas, paint, layer);
                }
            }
        } else {
            for (final Key key : mInvalidatedKeys) {
                if (!keyboard.hasKey(key)) {
                    continue;
                }
                if (background != null) {
                    // Need to redraw key's background on {@link #mOffscreenBuffer}.
                    final int x = key.getX() + getPaddingLeft();
                    final int y = key.getY() + getPaddingTop();
                    mClipRect.set(x, y, x + key.getWidth(), y + key.getHeight());
                    canvas.save();
                    canvas.clipRect(mClipRect);
                    canvas.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR);
                    background.draw(canvas);
                    canvas.restore();
                }
                onDrawKey(key, canvas, paint, 0);
            }
        }

        mInvalidatedKeys.clear();
        mInvalidateAllKeys = false;
    }

    private Rect getKeyDrawOffsetAndSize(final Key key, final KeyDrawingConfiguration cfg) {
        float keyDrawX = key.getDrawX() + getPaddingLeft();
        float keyDrawY = key.getY() + getPaddingTop();

        float keyWidth = key.getDrawWidth();
        float keyHeight = key.getHeight();

        final RectF gap = cfg.getBackgroundGap();
        if(gap != null) {
            if(gap.left != 1.0f) {
                keyDrawX = getPaddingLeft() + key.getX() + (key.getHorizontalGap() / 2.0f) * gap.left;
            }
            if(gap.left != 1.0f || gap.right != 1.0f) {
                keyWidth = key.getWidth() - (key.getHorizontalGap() / 2.0f * (gap.left + gap.right - 2.0f));
            }

            if(gap.top != 1.0f) {
                keyDrawY = getPaddingTop() + key.getY() + (key.getVerticalGap() / 2.0f) * (gap.top - 1.0f);
            }
            if(gap.top != 1.0f || gap.bottom != 1.0f) {
                keyHeight = key.getHeight() - (key.getVerticalGap() / 2.0f * (gap.top + gap.bottom - 2.0f));
            }
        }

        return new Rect(
            Math.round(keyDrawX),
            Math.round(keyDrawY),
            Math.round(keyWidth),
            Math.round(keyHeight)
        );
    }

    private void onDrawKey(@Nonnull final Key key, @Nonnull final Canvas canvas,
            @Nonnull final Paint paint, int layer) {
        final KeyVisualAttributes attr = key.getVisualAttributes();
        final KeyDrawParams params = mKeyDrawParams.mayCloneAndUpdateParams(key.getWidth(),
                Math.min(key.getHeight(), key.getWidth()), attr);
        params.mAnimAlpha = Constants.Color.ALPHA_OPAQUE;

        final KeyDrawingConfiguration kdc = mDrawableProvider.selectKeyDrawingConfiguration(mKeyboard, params, key, layer);
        if (kdc == null) return;

        final Rect drawOffsetAndSize = getKeyDrawOffsetAndSize(key, kdc);
        canvas.translate(drawOffsetAndSize.left, drawOffsetAndSize.top);


        final Drawable background = kdc.getBackground();
        if (background != null) {
            onDrawKeyBackground(key, canvas, background, drawOffsetAndSize.right, drawOffsetAndSize.bottom);
        }

        if(Color.alpha(kdc.getTextColor()) > 0) {
            onDrawKeyTopVisuals(key, canvas, paint, params, kdc, drawOffsetAndSize.right, drawOffsetAndSize.bottom);
        }

        canvas.translate(-drawOffsetAndSize.left, -drawOffsetAndSize.top);
    }

    // Draw key background.
    protected void onDrawKeyBackground(@Nonnull final Key key, @Nonnull final Canvas canvas,
            @Nonnull final Drawable background, final int keyWidth, final int keyHeight) {
        final int bgWidth, bgHeight, bgX, bgY;
        if (key.needsToKeepBackgroundAspectRatio(mDefaultKeyLabelFlags)
                // HACK: To disable expanding normal/functional key background.
                && !key.getHasCustomActionLabel()) {
            bgWidth = Math.min(keyWidth, keyHeight);
            bgHeight = Math.min(keyWidth, keyHeight);
            bgX = (keyWidth - bgWidth) / 2;
            bgY = (keyHeight - bgHeight) / 2;
        } else {
            final Rect padding = new Rect();
            background.getPadding(padding);

            bgWidth = keyWidth + padding.left + padding.right;
            bgHeight = keyHeight + padding.top + padding.bottom;
            bgX = -padding.left;
            bgY = -padding.top;
        }
        final Rect bounds = background.getBounds();
        if (bgWidth != bounds.right || bgHeight != bounds.bottom) {
            background.setBounds(0, 0, bgWidth, bgHeight);
        }
        canvas.translate(bgX, bgY);
        background.draw(canvas);
        canvas.translate(-bgX, -bgY);
    }

    private final Rect mTextBounds = new Rect();

    // WrenBoard 1.6 (item 10): true on SwiftKey's compact number/phone pad (number_basic / phone layouts).
    private boolean isSwiftKeyPadKeyboard() {
        final Keyboard keyboard = mKeyboard;
        if (keyboard == null || keyboard.mId == null || keyboard.mId.mElement == null) return false;
        return org.futo.inputmethod.v2keyboard.KeyboardKt.isSwiftKeyPad(keyboard.mId.mElement.getKind());
    }
    private Drawable mWrenClipboardHint;
    private Drawable getWrenClipboardHint() {
        if (mWrenClipboardHint == null) {
            final Drawable d = androidx.core.content.ContextCompat.getDrawable(getContext(), org.futo.inputmethod.latin.R.drawable.wren_clipboard_hint);
            if (d != null) mWrenClipboardHint = d.mutate();
        }
        return mWrenClipboardHint;
    }

    private Drawable mWrenMicHint;
    private Drawable getWrenMicHint() {
        if (mWrenMicHint == null) {
            final Drawable d = androidx.core.content.ContextCompat.getDrawable(getContext(), org.futo.inputmethod.latin.R.drawable.wren_mic_hint);
            if (d != null) mWrenMicHint = d.mutate();
        }
        return mWrenMicHint;
    }

    // WrenBoard 1.4 SwiftKey parity (items 9/11): icon names drawn at their full 32dp box
    // (Tab's 1.4 artwork), bypassing the textSize*1.75 icon cap.
    private static final java.util.Set<String> FULL_SIZE_ICON_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
            KeyboardIconsSet.NAME_SHIFT_KEY, KeyboardIconsSet.NAME_SHIFT_KEY_SHIFTED,
            KeyboardIconsSet.NAME_SHIFT_KEY_LOCKED, KeyboardIconsSet.NAME_DELETE_KEY,
            KeyboardIconsSet.NAME_ENTER_KEY,
            "action_up", "action_down", "action_left", "action_right"));

    // Draw key top visuals.
    protected void onDrawKeyTopVisuals(@Nonnull final Key key, @Nonnull final Canvas canvas,
            @Nonnull final Paint paint, @Nonnull final KeyDrawParams params, @Nonnull final KeyDrawingConfiguration kdc,
                                       final int keyWidth, final int keyHeight) {
        final float centerX = keyWidth * 0.5f;
        final float centerY = keyHeight * 0.5f;

        // Draw key label.
        final Drawable icon = kdc.getIcon();
        final Drawable hintIcon = kdc.getHintIcon();
        float labelX = centerX;
        float labelBaseline = centerY;
        final String label = kdc.getLabel();
        final Rect bgPadding = kdc.getBackgroundPadding();
        final KeyOutline outline = kdc.getOutline();

        float keyHintPaddingX = mKeyHintLetterPadding;
        float keyHintPaddingY = mKeyHintLetterPadding;
        if((bgPadding.left | bgPadding.right | bgPadding.top | bgPadding.bottom) != 0) {
            keyHintPaddingX = bgPadding.right;
            keyHintPaddingY = bgPadding.top;
        }

        // WrenBoard SwiftKey parity (pixel spec v1.1 items 3-9): label positions are measured from the visible
        // key top (one 0.4 dp hairline inside the drawn rect) as fractions of the row pitch (key + gap).
        final boolean skGeom = kdc.getSwiftKeyGeometry();
        final float skDensity = getResources().getDisplayMetrics().density;
        final float skTop = Math.max(1.0f, Math.round(0.4f * skDensity));
        final float skPitch = key.getHeight() + key.getVerticalGap();
        final float skBodyH = keyHeight - 2.0f * skTop;
        final boolean skBaselineLabel = skGeom && label != null && (
                (label.length() == 1 && Character.isLetter(label.charAt(0)))
                || (key.getCode() == Constants.CODE_SWITCH_ALPHA_SYMBOL && "123".equals(label))
                || ",".equals(label) || ".".equals(label));

        if (label != null && icon == null) {
            paint.setTypeface(kdc.getTextTypeface());
            paint.setTextSize(kdc.getTextSize());
            // WrenBoard 1.5 (Tab's exact 20:45 spec, candidate, targeted): only the symbols
            // page-2 shift key labelled "123" (CODE_SHIFT; the letters-page "123" key is
            // CODE_SWITCH_ALPHA_SYMBOL, the page-1 shift is "{&=", and "abc" is the alphabet
            // key - all explicitly left alone). Measured at 420dpi with one method across the
            // same run's shots: this key rendered 44x22 vs the letters "123" at 63x30; Tab's
            // target 62x29 is the letters label. Scale up by 30/22 to match it.
            if (key.getCode() == Constants.CODE_SHIFT && "123".equals(label)) {
                paint.setTextSize(kdc.getTextSize() * (30.0f / 22.0f));
            }
            // WrenBoard 1.5 (item 10, candidate): the global 0.84 multiplier gives +14%;
            // Tab's LIST has digits +20% (centred in the number row) - top digits up.
            final boolean isSingleDigit = label.length() == 1 && Character.isDigit(label.charAt(0));
            if (isSingleDigit) {
                paint.setTextSize(paint.getTextSize() * (1.20f / 1.14f));
            }
            final float labelCharHeight = TypefaceUtils.getReferenceCharHeight(paint);
            final float labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint);

            // Vertical label text alignment.
            labelBaseline = centerY + labelCharHeight / 2.0f;
            // WrenBoard 1.5 (item 10, candidate): SwiftKey's LETTERS sit lower in the key
            // (x-height top ~53% of key height vs our ~47%) - bias alphabet letter keys down
            // ~6% of key height. Digits/symbols stay centred (LIST item 10).
            if (label.length() == 1 && Character.isLetter(label.charAt(0))) {
                labelBaseline += keyHeight * 0.06f;
            }


            if(kdc.getCenteredHint() && kdc.getHintLabel() != null && !key.getHasHintLabel() && !key.getHasShiftedLetterHint()) {
                labelBaseline += kdc.getTextSize() * 0.1f;
            }

            // WrenBoard 1.4 (123-key hint/label spacing): when a key shows a hint icon
            // above a text label, place the label below the hint with the hint padding
            // as the gap, at every density and keyboard height, instead of shrinking
            // the hint (SwiftKey keeps the secondary glyph at its normal size and the
            // label clearly below it).
            if (hintIcon != null) {
                final float minLabelTop = keyHintPaddingY + kdc.getHintSize() + keyHintPaddingY;
                labelBaseline = minLabelTop + labelCharHeight;
                if (labelBaseline > keyHeight - 1.0f) {
                    labelBaseline = Math.max(centerY + labelCharHeight / 2.0f, keyHeight - 1.0f);
                }
            }

            if (skBaselineLabel) {
                // Letters, the letters-page "123", comma and period: baseline 39.8 dp below the key top on a
                // 52.6 dp row = 0.7568 x row pitch (items 3, 8, 9), whatever hint sits above.
                labelBaseline = skTop + 0.7568f * skPitch;
            } else if (skGeom && isSingleDigit) {
                // Number-row digits: glyph centre 0.0079 x row pitch below the key-body centre (item 7).
                paint.getTextBounds(label, 0, label.length(), mTextBounds);
                final float targetCentre = skTop + skBodyH / 2.0f + 0.0079f * skPitch;
                labelBaseline = targetCentre - (mTextBounds.top + mTextBounds.bottom) / 2.0f;
            }

            if (skGeom && isSwiftKeyPadKeyboard()) {
                // WrenBoard 1.6 (item 10, pixel spec v1.1 item 65): SwiftKey's number/phone pad labels are
                // Roboto Regular 18 dp on a 50.5 dp row pitch, every label on the digits' baseline with the
                // digit glyph centred in the key body.
                paint.setTextSize(18.0f / 50.5f * skPitch);
                paint.getTextBounds("0", 0, 1, mTextBounds);
                labelBaseline = skTop + skBodyH / 2.0f - (mTextBounds.top + mTextBounds.bottom) / 2.0f;
            }

            // Popup glyphs are centred by their visible ink. They must not inherit the
            // letters-page punctuation baseline, which moved all four period choices up.
            if (skGeom && key.getVisualStyle() == org.futo.inputmethod.v2keyboard.KeyVisualStyle.MoreKey) {
                paint.getTextBounds("!", 0, 1, mTextBounds);
                labelBaseline = keyHeight * 0.50f - (mTextBounds.top + mTextBounds.bottom) / 2.0f;
            }

            // Horizontal label text alignment
            if (key.isAlignLabelOffCenter()) {
                // The label is placed off center of the key. Used mainly on "phone number" layout.
                labelX = centerX + params.mLabelOffCenterRatio * labelCharWidth;
                paint.setTextAlign(Align.LEFT);
            } else {
                labelX = centerX;
                paint.setTextAlign(Align.CENTER);
            }
            if (key.getNeedsAutoXScale()) {
                final float ratio = Math.min(1.0f, (keyWidth * MAX_LABEL_RATIO) /
                        TypefaceUtils.getStringWidth(label, paint));
                if (key.getNeedsAutoScale()) {
                    final float autoSize = paint.getTextSize() * ratio;
                    paint.setTextSize(autoSize);
                } else {
                    paint.setTextScaleX(ratio);
                }
            }

            if (key.isEnabled()) {
                if(outline != null) {
                    paint.setStrokeWidth(outline.getWidthPx());
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeMiter(3.0f);
                    paint.setColor(outline.getColor());
                    canvas.drawText(label, 0, label.length(), labelX, labelBaseline, paint);

                    paint.setStyle(Paint.Style.FILL);
                    paint.setStrokeWidth(0);
                }

                paint.setColor(kdc.getTextColor());
                // Set a drop shadow for the text if the shadow radius is positive value.
                if (mKeyTextShadowRadius > 0.0f) {
                    paint.setShadowLayer(mKeyTextShadowRadius, 0.0f, 0.0f, params.mTextShadowColor);
                } else {
                    paint.clearShadowLayer();
                }
            } else {
                // Make label invisible
                paint.setColor(Color.TRANSPARENT);
                paint.clearShadowLayer();
            }

            if(outline == null) blendAlpha(paint, params.mAnimAlpha);

            canvas.drawText(label, 0, label.length(), labelX, labelBaseline, paint);

            // Turn off drop shadow and reset x-scale.
            paint.clearShadowLayer();
            paint.setTextScaleX(1.0f);
        }

        // Draw hint label.
        final String hintLabel = kdc.getHintLabel();
        if (hintLabel != null) {
            paint.setTextSize(kdc.getHintSize());
            paint.setColor(kdc.getHintColor());

            // Bold explicit hints
            paint.setTypeface(kdc.getHintTypeface());

            blendAlpha(paint, params.mAnimAlpha);
            final float labelCharHeight = TypefaceUtils.getReferenceCharHeight(paint);
            final float labelCharWidth = TypefaceUtils.getReferenceCharWidth(paint);
            float hintX, hintBaseline;
            if (key.getHasHintLabel()) {
                // The hint label is placed just right of the key label. Used mainly on
                // "phone number" layout.
                hintX = labelX + params.mHintLabelOffCenterRatio * labelCharWidth;
                if (key.isAlignHintLabelToBottom(mDefaultKeyLabelFlags)) {
                    hintBaseline = labelBaseline;
                } else {
                    hintBaseline = centerY + labelCharHeight / 2.0f;
                }
                paint.setTextAlign(Align.CENTER);
            } else if (key.getHasShiftedLetterHint()) {
                // The hint label is placed at top-right corner of the key. Used mainly on tablet.
                hintX = keyWidth - mKeyShiftedLetterHintPadding - labelCharWidth / 2.0f;
                paint.getFontMetrics(mFontMetrics);
                hintBaseline = -mFontMetrics.top;
                paint.setTextAlign(Align.CENTER);
            } else { // key.hasHintLetter()
                // The hint letter is placed at top-right corner of the key. Used mainly on phone.
                final float hintDigitWidth = TypefaceUtils.getReferenceDigitWidth(paint);
                final float hintLabelWidth = TypefaceUtils.getStringWidth(hintLabel, paint);
                hintX = keyWidth - keyHintPaddingX - Math.max(hintDigitWidth, hintLabelWidth) / 2.0f;
                hintBaseline = -paint.ascent() + keyHintPaddingY;

                if (kdc.getCenteredHint()) {
                    hintX = centerX;
                    hintBaseline += paint.ascent() / 3.0f;
                    if (skGeom) {
                        // Hint glyph top 8.4 dp below the key top = 0.1588 x row pitch (item 6). One shared
                        // baseline from the '%' reference glyph, as SwiftKey's hints share a baseline.
                        paint.getTextBounds("%", 0, 1, mTextBounds);
                        hintBaseline = skTop + 0.1588f * skPitch - mTextBounds.top
                                - params.mHintLabelVerticalAdjustment * labelCharHeight;
                    }
                }
                paint.setTextAlign(Align.CENTER);
            }
            final float adjustmentY = params.mHintLabelVerticalAdjustment * labelCharHeight;
            canvas.drawText(
                    hintLabel, 0, hintLabel.length(), hintX, hintBaseline + adjustmentY, paint);
        } else if(hintIcon != null) {
            float size = kdc.getHintSize();

            // WrenBoard 1.4: labels on hint-icon keys are moved below the hint (see the
            // label block above), so the hint keeps its normal size. Shrink only as a
            // last resort when a key is too short for both (extreme keyboard heights).
            if (label != null && icon == null) {
                paint.setTextSize(kdc.getTextSize());
                final float labelTop = labelBaseline - TypefaceUtils.getReferenceCharHeight(paint);
                final float maxHintBottom = labelTop - keyHintPaddingY;
                if (keyHintPaddingY + size > maxHintBottom) {
                    size = Math.max(maxHintBottom - keyHintPaddingY, size * 0.5f);
                }
            }

            int iconWidth = (int)size;
            int iconHeight = (int)size;

            int hintX = keyWidth - iconWidth - (int)keyHintPaddingX;
            int hintY = (int)keyHintPaddingY;


            if(kdc.getCenteredHint()) {
                hintX = (int)centerX - iconWidth / 2;
            }

            Drawable hintDrawable = hintIcon;
            if (skGeom) {
                if ("action_clipboard_history".equals(key.getEffectiveHintIcon()) && getWrenClipboardHint() != null) {
                    // Clipboard glyph on the letters-page 123 key, drawn at its own size (7.8 x 10.8 dp: body
                    // 7.8 x 9.6 dp, 1.2 dp stroke, clip tab 3.9 x 2.1 dp, two 3.6 x 1.1 dp lines), tab top
                    // 8.0 dp below the key top on a 52.6 dp row (pixel spec v1.1 item 68).
                    hintDrawable = getWrenClipboardHint();
                    final float rowScale = skPitch / (52.6f * skDensity);
                    iconWidth = Math.round(hintDrawable.getIntrinsicWidth() * rowScale * 0.72f);
                    iconHeight = Math.round(hintDrawable.getIntrinsicHeight() * rowScale * 0.72f);
                    hintX = (int)(centerX - iconWidth / 2.0f);
                    hintY = Math.round(skTop + (8.0f / 52.6f) * skPitch);
                } else if ("action_voice_input".equals(key.getEffectiveHintIcon()) && getWrenMicHint() != null) {
                    // WrenBoard 1.6 (item 51, approved mock-up 01): mic on the comma key drawn at its own size,
                    // 7.1 x 14.2 dp on a 52.6 dp row, top 6.0 dp below the key top.
                    hintDrawable = getWrenMicHint();
                    final float rowScale = skPitch / (52.6f * skDensity);
                    iconWidth = Math.round(hintDrawable.getIntrinsicWidth() * rowScale);
                    iconHeight = Math.round(hintDrawable.getIntrinsicHeight() * rowScale);
                    hintX = (int)(centerX - iconWidth / 2.0f);
                    hintY = Math.round(skTop + (6.0f / 52.6f) * skPitch);
                } else {
                    // Mic and other hint glyphs: top aligned with the hint symbols (item 6).
                    hintY = Math.round(skTop + 0.1588f * skPitch);
                }
            }

            hintDrawable.setTint(kdc.getHintColor());
            drawIcon(canvas, hintDrawable, hintX, hintY, iconWidth, iconHeight);
        }

        // Draw key icon.
        if (icon != null) {
            final float size = kdc.getTextSize() * 1.75f;
            final float iconDm = getResources().getDisplayMetrics().density;

            int iconWidth;
            Integer iconHeightOverride = null;
            if (key.getCode() == Constants.CODE_SPACE && icon instanceof NinePatchDrawable) {
                iconWidth = (int)(keyWidth * mSpacebarIconWidthRatio);
            } else if (KeyboardIconsSet.NAME_SPACE_KEY_FOR_NUMBER_LAYOUT.equals(key.getIconId())) {

                // space glyph is a bracket 32.0 dp wide x 9.2 dp tall (3 dp stroke lives in the vector).
                iconWidth = Math.round(32.0f * iconDm);
                iconHeightOverride = Math.round(9.2f * iconDm);
            } else if (KeyboardIconsSet.NAME_EMOJI_NORMAL_KEY.equals(key.getIconId())
                    || KeyboardIconsSet.NAME_EMOJI_ACTION_KEY.equals(key.getIconId())
                    || "action_emoji".equals(key.getIconId())
                    || "action_0".equals(key.getIconId())) {
                // Candidate: 50px icon bounds at the 420dpi reference surface. Actual vector
                // ink can be smaller than its viewport; measure the render before acceptance.
                // smile.xml uses 22/24 of its viewport for visible stroke. A 55px
                // drawing box at420 is the candidate for50px ink, verified by render.
                iconWidth = Math.round(20.95f * iconDm);
                // Keep this explicit measured size out of the generic text-size cap below.
                iconHeightOverride = iconWidth;
            } else if (KeyboardIconsSet.NAME_TAB_KEY.equals(key.getIconId())) {
                // WrenBoard 1.5 (Tab's exact 20:45 spec, candidate): tab key icon is a bold
                // arrow-into-bar 20dp wide x 11dp tall (2.5dp stroke lives in the vector),
                // tinted to the key label colour below. Function unchanged (types a tab).
                iconWidth = (int)(20.0f * iconDm);
                iconHeightOverride = (int)(11.0f * iconDm);
            } else if (FULL_SIZE_ICON_IDS.contains(key.getIconId())) {
                // WrenBoard 1.4 SwiftKey parity (items 9/11): the 32dp key artwork (shift states, delete,
                // enter, arrow row) renders at its full designed box; every other icon keeps the cap below.
                iconWidth = Math.min(Math.min(icon.getIntrinsicWidth(), keyWidth), keyHeight);
            } else {
                iconWidth = Math.min(icon.getIntrinsicWidth(), keyWidth);
            }
            int iconHeight = icon.getIntrinsicHeight();

            if(iconHeightOverride == null && !FULL_SIZE_ICON_IDS.contains(key.getIconId()) && iconWidth > size) {
                //iconHeight = (int)((float)iconHeight / (float)iconWidth * (float)size);
                iconWidth = (int)size;
            }

            iconHeight = (iconHeightOverride != null) ? iconHeightOverride : iconWidth;

            final int iconY;
            if (key.isAlignIconToBottom()) {
                iconY = keyHeight - iconHeight;
            } else {
                iconY = (keyHeight - iconHeight) / 2; // Align vertically center.
            }
            final int iconX = (keyWidth - iconWidth) / 2; // Align horizontally center.

            icon.setTint(kdc.getTextColor());
            drawIcon(canvas, icon, iconX, iconY, iconWidth, iconHeight);
        }

        if (key.getHasPopupHint() && !key.getMoreKeys().isEmpty()) {
            drawKeyPopupHint(key, canvas, paint, params);
        }
    }

    // Draw popup hint "..." at the bottom right corner of the key.
    protected void drawKeyPopupHint(@Nonnull final Key key, @Nonnull final Canvas canvas,
            @Nonnull final Paint paint, @Nonnull final KeyDrawParams params) {
        if (TextUtils.isEmpty(mKeyPopupHintLetter)) {
            return;
        }
        final int keyWidth = key.getDrawWidth();
        final int keyHeight = key.getHeight();

        paint.setTypeface(params.mTypeface);
        paint.setTextSize(params.mHintLetterSize);
        paint.setColor(params.mHintLabelColor);
        paint.setTextAlign(Align.CENTER);
        final float hintX = keyWidth - mKeyHintLetterPadding
                - TypefaceUtils.getReferenceCharWidth(paint) / 2.0f;
        final float hintY = keyHeight - mKeyPopupHintLetterPadding;
        canvas.drawText(mKeyPopupHintLetter, hintX, hintY, paint);
    }

    protected static void drawIcon(@Nonnull final Canvas canvas,@Nonnull final Drawable icon,
            final int x, final int y, final int width, final int height) {
        canvas.translate(x, y);
        icon.setBounds(0, 0, width, height);
        icon.draw(canvas);
        canvas.translate(-x, -y);
    }

    public Paint newLabelPaint(@Nullable final Key key) {
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        if (key == null) {
            paint.setTypeface(mKeyDrawParams.mTypeface);
            paint.setTextSize(mKeyDrawParams.mLabelSize);
        } else {
            paint.setColor(key.selectTextColor(mDrawableProvider, mKeyDrawParams));
            paint.setTypeface(mDrawableProvider.selectKeyTypeface(key.selectTypeface(mKeyDrawParams)));
            paint.setTextSize(key.selectTextSize(mKeyDrawParams));
        }
        return paint;
    }

    /**
     * Requests a redraw of the entire keyboard. Calling {@link #invalidate} is not sufficient
     * because the keyboard renders the keys to an off-screen buffer and an invalidate() only
     * draws the cached buffer.
     * @see #invalidateKey(Key)
     */
    public void invalidateAllKeys() {
        mInvalidatedKeys.clear();
        mInvalidateAllKeys = true;
        invalidate();
    }

    /**
     * Invalidates a key so that it will be redrawn on the next repaint. Use this method if only
     * one key is changing it's content. Any changes that affect the position or size of the key
     * may not be honored.
     * @param key key in the attached {@link Keyboard}.
     * @see #invalidateAllKeys
     */
    public void invalidateKey(@Nullable final Key key) {
        if (mInvalidateAllKeys || key == null) {
            return;
        }
        mInvalidatedKeys.add(key);
        final int x = key.getX() + getPaddingLeft();
        final int y = key.getY() + getPaddingTop();
        invalidate(x, y, x + key.getWidth(), y + key.getHeight());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        freeOffscreenBuffer();
    }

    public void deallocateMemory() {
        freeOffscreenBuffer();
    }
}
