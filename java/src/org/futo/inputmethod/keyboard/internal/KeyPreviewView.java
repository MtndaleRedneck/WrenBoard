/*
 * Copyright (C) 2014 The Android Open Source Project
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

package org.futo.inputmethod.keyboard.internal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;

import androidx.annotation.Nullable;

import org.futo.inputmethod.keyboard.Key;
import org.futo.inputmethod.latin.ATLCompat;
import org.futo.inputmethod.latin.R;
import org.futo.inputmethod.latin.uix.DynamicThemeProvider;
import org.futo.inputmethod.v2keyboard.Direction;
import org.futo.inputmethod.v2keyboard.KeyDataKt;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

import kotlin.Pair;

/**
 * The pop up key preview view.
 */
public class KeyPreviewView extends androidx.appcompat.widget.AppCompatTextView {
    public static final int POSITION_MIDDLE = 0;
    public static final int POSITION_LEFT = 1;
    public static final int POSITION_RIGHT = 2;

    private final Rect mBackgroundPadding = new Rect();
    private static final HashSet<String> sNoScaleXTextSet = new HashSet<>();

    private final DynamicThemeProvider mDrawableProvider;

    public KeyPreviewView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyPreviewView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // WrenBoard 1.5 item 5b (Tab's target, sign-off via rendered before/after at 420dpi): the
        // glyph centre sits at 26% of the box height from the top ("centred on the row above").
        // Gravity.CENTER only looked like that while the old balloon background carried large
        // bottom padding; the flat raised box has none, so position it explicitly.
        setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        setIncludeFontPadding(false);

        mDrawableProvider = DynamicThemeProvider.obtainFromContext(context);
    }

    @Nullable
    private Drawable mIcon;

    private Key currKey;
    public void setPreviewVisual(final Key key, final KeyboardIconsSet iconsSet,
                                 final KeyDrawParams drawParams, int foregroundColor) {
        // What we show as preview should match what we show on a key top in onDraw().
        final String iconId = key.getIconId();
        if (!Objects.equals(iconId, KeyboardIconsSet.ICON_UNDEFINED) && key.getFlickDirection() == null) {
            setGravity(android.view.Gravity.CENTER);
            setPadding(getPaddingLeft(), 0, getPaddingRight(), 0);
            setCompoundDrawables(null, null, null, key.getPreviewIcon(iconsSet));
            mIcon = key.getPreviewIcon(iconsSet);
            currKey = key;
            mPreviewText = null;
            setText(null);
            return;
        }

        mIcon = null;
        setCompoundDrawables(null, null, null, null);
        setTextColor(foregroundColor);
        // S4: 1.44x the glyph as drawn on the key (key applies keyTextSizeMultiplier)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, key.selectTextSize(drawParams) * mDrawableProvider.getKeyTextSizeMultiplier() * 1.44f);
        setTypeface(mDrawableProvider.selectKeyTypeface(key.selectPreviewTypeface(drawParams)));
        // TODO Should take care of temporaryShiftLabel here.
        setTextAndScaleX(key.getWidth(), key.getPreviewLabel());

        currKey = key;

        // Item 5b v4: draw the preview text manually in onDraw (drawPreviewText) with the rendered
        // glyph bounds centred at 26% of the box height (Tab's target, sign-off via rendered
        // before/after at 420dpi). Framework positioning could not reach it: the padTop required
        // at this text size is negative, so v2/v3 rendered pixel-identical shots with the glyph
        // centre at 39.3% of the box. No setText here: the framework layout plays no role.
        setText(null);
        mPreviewText = key.getPreviewLabel();
        computePreviewScaleX(key.getWidth(), mPreviewText);
        invalidate();
    }

    private String mPreviewText;
    private float mPreviewScaleX = 1.0f;

    // Same scaling rule as setTextAndScaleX (fit the key width), without going through setText().
    private void computePreviewScaleX(final int maxWidth, final String text) {
        mPreviewScaleX = 1.0f;
        if (text == null || sNoScaleXTextSet.contains(text)) {
            return;
        }
        final float width = getTextWidth(text, getPaint());
        if (width <= maxWidth) {
            sNoScaleXTextSet.add(text);
            return;
        }
        mPreviewScaleX = maxWidth / width;
    }

    // Positions by RENDERED bounds: baseline = 26% of box height minus half the bounds' vertical
    // extent; bounds centred horizontally. scaleX from computePreviewScaleX is applied to the
    // paint BEFORE measuring so the centring uses the same width the draw produces.
    private void drawPreviewText(final Canvas canvas) {
        final android.graphics.Paint p = getPaint();
        p.setColor(getCurrentTextColor());
        p.setTextScaleX(mPreviewScaleX);
        final android.graphics.Rect b = new android.graphics.Rect();
        p.getTextBounds(mPreviewText, 0, mPreviewText.length(), b);
        final float baseline = getHeight() * 0.26f - (b.top + b.bottom) / 2f;
        final float x = (getWidth() - (b.right - b.left)) / 2f - b.left;
        canvas.drawText(mPreviewText, x, baseline, p);
    }

    private boolean drawFlickKeys(final Canvas canvas) {
        if(currKey == null) return false;

        Map<Direction, Key> flickKeys = currKey.getFlickKeys();
        if(flickKeys == null || flickKeys.isEmpty()) return false;

        if(currKey.getFlickDirection() != null) return false;

        int width = canvas.getWidth();
        int height = canvas.getHeight();

        int dim = Math.min(width, height);

        Paint paint = new Paint();
        paint.setTypeface(getTypeface());
        paint.setColor(getCurrentTextColor());
        paint.setTextSize(dim * 0.265f);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setAntiAlias(true);

        final float yp = 2.7f; // TODO
        final float offsMul = 0.33f;

        int cx = width / 2;
        int cy = (int)(height / 2 + paint.getTextSize() / yp);

        for(Direction dir : flickKeys.keySet()) {
            Key value = flickKeys.get(dir);
            Pair<Double, Double> vec = KeyDataKt.toVector(dir);

            int x = (int)(cx - (vec.getFirst() * width * offsMul));
            int y = (int)(cy - (vec.getSecond() * height * offsMul));
            canvas.drawText(value.getPreviewLabel(), x, y, paint);
        }

        paint.setTextSize(dim * 0.485f);

        if(mIcon != null) {
            /*int iconWidth = mIcon.getIntrinsicWidth();
            if(iconWidth > width) iconWidth = width;
            iconWidth = iconWidth * 8 / 10;

            mIcon.setBounds(
                    cx - iconWidth / 2,
                    height / 2 - iconWidth / 2,
                    cx + iconWidth / 2,
                    height / 2 + iconWidth / 2
            );
            mIcon.draw(canvas);*/
        } else {
            canvas.drawText(
                    currKey.getPreviewLabel(),
                    cx,
                    (int) (height / 2 + paint.getTextSize() / yp),
                    paint
            );
        }

        return true;
    }


    private Drawable mBackground = null;
    @Override
    public void setBackground(Drawable background) {
        mBackground = background;
        background.getPadding(mBackgroundPadding);
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        mBackground.setBounds(0, 0, getWidth(), getHeight());
        mBackground.draw(canvas);

        if(!drawFlickKeys(canvas)) {
            if (mPreviewText != null) {
                drawPreviewText(canvas);
            } else {
                super.onDraw(canvas);
            }
        }
    }

    private void setTextAndScaleX(int maxWidth, final String text) {
        setTextScaleX(1.0f);
        setText(text);
        if (sNoScaleXTextSet.contains(text)) {
            return;
        }

        final float width = getTextWidth(text, getPaint());
        if (width <= maxWidth) {
            sNoScaleXTextSet.add(text);
            return;
        }
        setTextScaleX(maxWidth / width);
    }

    public static void clearTextCache() {
        sNoScaleXTextSet.clear();
    }

    private static float getTextWidth(final String text, final TextPaint paint) {
        if (TextUtils.isEmpty(text)) {
            return 0.0f;
        }
        final int len = text.length();
        final float[] widths = new float[len];
        final int count = paint.getTextWidths(text, 0, len, widths);

        // ATL implementation seems wrong.
        if(ATLCompat.INSTANCE.getIsATL()) return count;

        float width = 0;
        for (int i = 0; i < count; i++) {
            width += widths[i];
        }
        return width;
    }

    // Background state set
    private static final int[][][] KEY_PREVIEW_BACKGROUND_STATE_TABLE = {
        { // POSITION_MIDDLE
            {},
            { R.attr.state_has_morekeys }
        },
        { // POSITION_LEFT
            { R.attr.state_left_edge },
            { R.attr.state_left_edge, R.attr.state_has_morekeys }
        },
        { // POSITION_RIGHT
            { R.attr.state_right_edge },
            { R.attr.state_right_edge, R.attr.state_has_morekeys }
        }
    };
    private static final int STATE_NORMAL = 0;
    private static final int STATE_HAS_MOREKEYS = 1;

    public void setPreviewBackground(final boolean hasMoreKeys, final int position) {
        //final Drawable background = getBackground();
        //if (background == null) {
        //    return;
        //}
        //final int hasMoreKeysState = hasMoreKeys ? STATE_HAS_MOREKEYS : STATE_NORMAL;
        //background.setState(KEY_PREVIEW_BACKGROUND_STATE_TABLE[position][hasMoreKeysState]);
    }
}
