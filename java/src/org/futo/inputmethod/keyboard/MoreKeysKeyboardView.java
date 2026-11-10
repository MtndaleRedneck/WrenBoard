/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import org.futo.inputmethod.accessibility.AccessibilityUtils;
import org.futo.inputmethod.accessibility.MoreKeysKeyboardAccessibilityDelegate;
import org.futo.inputmethod.keyboard.internal.KeyDrawParams;
import org.futo.inputmethod.latin.R;
import org.futo.inputmethod.latin.common.Constants;
import org.futo.inputmethod.latin.common.CoordinateUtils;
import org.futo.inputmethod.latin.uix.theme.KeyDrawingConfiguration;

/**
 * A view that renders a virtual {@link MoreKeysKeyboard}. It handles rendering of keys and
 * detecting key presses and touch movements.
 */
public class MoreKeysKeyboardView extends KeyboardView implements MoreKeysPanel {
    private final int[] mCoordinates = CoordinateUtils.newInstance();

    private final Drawable mDivider;
    protected final MoreKeysDetector mKeyDetector;
    private Controller mController = EMPTY_CONTROLLER;
    protected KeyboardActionListener mListener;
    private int mOriginX;
    private int mOriginY;
    private Key mCurrentKey;

    private int mActivePointerId;

    private final float sideAllowance;
    private int originalPaddingLeft, originalPaddingTop, originalPaddingRight, originalPaddingBottom;

    protected MoreKeysKeyboardAccessibilityDelegate mAccessibilityDelegate;

    public MoreKeysKeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.moreKeysKeyboardViewStyle);
    }

    public MoreKeysKeyboardView(final Context context, final AttributeSet attrs,
            final int defStyle) {
        super(context, attrs, defStyle);
        final TypedArray moreKeysKeyboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.MoreKeysKeyboardView, defStyle, R.style.MoreKeysKeyboardView);
        mDivider = moreKeysKeyboardViewAttr.getDrawable(R.styleable.MoreKeysKeyboardView_divider);
        if (mDivider != null) {
            // TODO: Drawable itself should have an alpha value.
            mDivider.setAlpha(128);
        }
        moreKeysKeyboardViewAttr.recycle();

        sideAllowance = getResources().getDimension(
                R.dimen.config_more_keys_keyboard_slide_allowance);
        mKeyDetector = new MoreKeysDetector(sideAllowance);
        originalPaddingLeft = getPaddingLeft(); originalPaddingTop = getPaddingTop();
        originalPaddingRight = getPaddingRight(); originalPaddingBottom = getPaddingBottom();
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard != null) {
            final int width = keyboard.mOccupiedWidth + getPaddingLeft() + getPaddingRight();
            final int height = keyboard.mOccupiedHeight + getPaddingTop() + getPaddingBottom();
            setMeasuredDimension(width, height);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        final Keyboard keyboard = getKeyboard();
        if (!(keyboard instanceof MoreKeysKeyboard) || !((MoreKeysKeyboard)keyboard).mApprovedPeriodPopup) {
            super.onDraw(canvas);
            return;
        }
        final float dp = getResources().getDisplayMetrics().density;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF333333);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 5*dp, 5*dp, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp);
        paint.setColor(0xFFBBBBBB);
        canvas.drawRoundRect(dp/2, dp/2, getWidth()-dp/2, getHeight()-dp/2, 5*dp, 5*dp, paint);
        paint.setStyle(Paint.Style.FILL);
        final float top = 4f * dp, bottom = getHeight() - 4f * dp;
        for (final Key key : keyboard.getSortedKeys()) {
            final float left = getPaddingLeft()+key.getX();
            final float right = left+key.getWidth();
            if (key == mCurrentKey) {
                paint.setShader(new android.graphics.LinearGradient(0, top, 0, bottom,
                        0xF5646464, 0xF5373737, android.graphics.Shader.TileMode.CLAMP));
                float inset = dp * 0.5f;
                canvas.drawRoundRect(left+inset, top, right-inset, bottom, 5*dp, 5*dp, paint);
                paint.setShader(null);
                paint.setColor(0xFF181818);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp*0.5f);
                canvas.drawRoundRect(left+inset, top, right-inset, bottom, 5*dp, 5*dp, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(0xFFFF8710);
            paint.setTypeface(android.graphics.Typeface.DEFAULT);
            // The approved reference specifies visible glyph height, not a font-scale guess.
            paint.setTextSize(31f * dp);
            // Size the visible cap ink, independent of system font metrics.
            final Rect capInk = new Rect();
            paint.getTextBounds("!", 0, 1, capInk);
            if(capInk.height() > 0) paint.setTextSize(paint.getTextSize() * (0.24f * getHeight()) / capInk.height());
            paint.setTextAlign(Paint.Align.CENTER);
            final String label = key.getLabel();
            if (label != null) {
                final Rect ink = new Rect();
                paint.getTextBounds(label, 0, label.length(), ink);
                canvas.drawText(label, (left+right)/2, getHeight() * 0.455f, paint);
            }
        }
    }

    @Override
    protected void onDrawKeyTopVisuals(final Key key, final Canvas canvas, final Paint paint,
           final KeyDrawParams params, final KeyDrawingConfiguration kdc,
                                       final int keyWidth, final int keyHeight) {
        if (!key.isSpacer() || mDivider == null) {
            // S7: SwiftKey panel glyphs are ~1.8x the cap height, centered on the upper row
            // of the two-row-tall cell.
            final KeyDrawingConfiguration scaled = kdc.copy(
                    kdc.getBackground(), kdc.getBackgroundPadding(), kdc.getBackgroundGap(),
                    kdc.getIcon(), kdc.getHintIcon(), kdc.getLabel(), kdc.getHintLabel(),
                    kdc.getTextColor(), kdc.getHintColor(),
                    kdc.getTextSize() * 1.8f, kdc.getHintSize(),
                    kdc.getTextTypeface(), kdc.getHintTypeface(),
                    kdc.getCenteredHint(), kdc.getOutline(), false);
            canvas.save();
            canvas.translate(0, -keyHeight / 4.0f);
            super.onDrawKeyTopVisuals(key, canvas, paint, params, scaled, keyWidth, keyHeight);
            canvas.restore();
            return;
        }
        final int iconWidth = Math.min(mDivider.getIntrinsicWidth(), keyWidth);
        final int iconHeight = mDivider.getIntrinsicHeight();
        final int iconX = (keyWidth - iconWidth) / 2; // Align horizontally center
        final int iconY = (keyHeight - iconHeight) / 2; // Align vertically center
        drawIcon(canvas, mDivider, iconX, iconY, iconWidth, iconHeight);
    }

    @Override
    public void setKeyboard(final Keyboard keyboard) {
        super.setKeyboard(keyboard);
        if (keyboard instanceof MoreKeysKeyboard && ((MoreKeysKeyboard)keyboard).mApprovedPeriodPopup) {
            int inset = Math.round(4f * getResources().getDisplayMetrics().density);
            setPadding(inset, 0, inset, 0);
        } else {
            setPadding(originalPaddingLeft, originalPaddingTop, originalPaddingRight, originalPaddingBottom);
        }
        if (AccessibilityUtils.getInstance().isAccessibilityEnabled()) {
            if (mAccessibilityDelegate == null) {
                mAccessibilityDelegate = new MoreKeysKeyboardAccessibilityDelegate(
                        this, mKeyDetector);
                mAccessibilityDelegate.setOpenAnnounce(R.string.spoken_open_more_keys_keyboard);
                mAccessibilityDelegate.setCloseAnnounce(R.string.spoken_close_more_keys_keyboard);
            }
            mAccessibilityDelegate.setKeyboard(keyboard);

            // No vertical correction
            mKeyDetector.setKeyboard(keyboard, -getPaddingLeft(), -getPaddingTop());
        } else {
            mAccessibilityDelegate = null;

            // With vertical correction
            mKeyDetector.setKeyboard(
                    keyboard, -getPaddingLeft(), -getPaddingTop() + getVerticalCorrection());
        }
    }

    @Override
    public void showMoreKeysPanel(final View parentView, final Controller controller,
            final int pointX, final int pointY, final KeyboardActionListener listener, final int[] touchOrigin,
            final boolean strongCaptive) {
        mController = controller;
        mListener = listener;
        final View container = getContainerView();
        // The coordinates of panel's left-top corner in parentView's coordinate system.
        // We need to consider background drawable paddings.
        final int x = pointX - getDefaultCoordX() - container.getPaddingLeft() - getPaddingLeft();
        final int y = pointY - container.getMeasuredHeight() + container.getPaddingBottom()
                + getPaddingBottom();

        parentView.getLocationInWindow(mCoordinates);
        // Ensure the horizontal position of the panel does not extend past the parentView edges.
        final int maxX = parentView.getMeasuredWidth() - container.getMeasuredWidth();
        final int panelX = Math.max(0, Math.min(maxX, x)) + CoordinateUtils.x(mCoordinates);
        final int panelY = y + CoordinateUtils.y(mCoordinates);
        container.setX(panelX);
        container.setY(panelY);

        if(AccessibilityUtils.getInstance().isAccessibilityEnabled()) {
            mOriginX = (int)container.getX() - CoordinateUtils.x(mCoordinates);
            mOriginY = (int)container.getY() - CoordinateUtils.y(mCoordinates);

            mKeyDetector.setExtraAllowance(0);
        } else {
            // WrenBoard P0-1: map the finger against the panel exactly where it is drawn. The old origin was taken
            // from the touch point, so a panel that had been moved (clamped at the keyboard edge, or centred on the
            // key rather than on the finger) typed a different key than the one highlighted under the finger.
            mOriginX = (int)container.getX() - CoordinateUtils.x(mCoordinates) + container.getPaddingLeft();

            // For basing off of touch position: CoordinateUtils.y(touchOrigin) - container.getMeasuredHeight()
            // Using container Y instead though
            mOriginY = (int)container.getY() - CoordinateUtils.y(mCoordinates);

            // If we hit the key too low, keyDetector may not even register the moreKey hit. Need to set extra allowance
            int touchOriginY = CoordinateUtils.y(touchOrigin) - container.getMeasuredHeight();
            int extraAllowance = Math.max(0, touchOriginY - mOriginY - (int)(sideAllowance));
            if(strongCaptive) extraAllowance = Integer.MAX_VALUE;
            mKeyDetector.setExtraAllowance(extraAllowance);
        }
        controller.onShowMoreKeysPanel(this);
        if (org.futo.inputmethod.latin.BuildConfig.DEBUG) {
            org.futo.inputmethod.latin.uix.WbDebug.logMoreKeys(this, getKeyboard(), mOriginX, mOriginY,
                    CoordinateUtils.x(mCoordinates), CoordinateUtils.y(mCoordinates), parentView);
        }
        final MoreKeysKeyboardAccessibilityDelegate accessibilityDelegate = mAccessibilityDelegate;
        if (accessibilityDelegate != null
                && AccessibilityUtils.getInstance().isAccessibilityEnabled()) {
            accessibilityDelegate.onShowMoreKeysKeyboard();
        }
    }

    /**
     * Returns the default x coordinate for showing this panel.
     */
    protected int getDefaultCoordX() {
        return ((MoreKeysKeyboard)getKeyboard()).getDefaultCoordX();
    }

    // WrenBoard 1.4: state for the designated-default ("!default!") no-slide handling
    private int mDownX;
    private int mDownY;
    private boolean mMovedAfterDown;
    private int mTouchSlop = -1;

    private Key getDesignatedDefaultKey() {
        return ((MoreKeysKeyboard)getKeyboard()).getDesignatedDefaultKey();
    }

    @Override
    public void onDownEvent(final int x, final int y, final int pointerId, final long eventTime) {
        mActivePointerId = pointerId;
        mDownX = x;
        mDownY = y;
        mMovedAfterDown = false;
        if (mTouchSlop < 0) {
            mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        }
        // A popup with a designated default selects it on open, even when the panel's edge
        // shift has moved another item under the finger (legacy period popup at the right edge).
        final Key designated = getDesignatedDefaultKey();
        mCurrentKey = (designated != null) ? selectKey(designated) : detectKey(x, y);
    }

    @Override
    public void onMoveEvent(final int x, final int y, final int pointerId, final long eventTime) {
        if (mActivePointerId != pointerId) {
            return;
        }
        if (getDesignatedDefaultKey() != null && !mMovedAfterDown) {
            final int dx = x - mDownX;
            final int dy = y - mDownY;
            if (dx * dx + dy * dy <= mTouchSlop * mTouchSlop) {
                return; // sub-slop wobble keeps the designated default selected
            }
            mMovedAfterDown = true;
        }
        final boolean hasOldKey = (mCurrentKey != null);
        mCurrentKey = detectKey(x, y);
        if (hasOldKey && mCurrentKey == null) {
            // A more keys keyboard is canceled when detecting no key.
            mController.onCancelMoreKeysPanel();
        }
    }

    @Override
    public void onUpEvent(final int x, final int y, final int pointerId, final long eventTime) {
        if (mActivePointerId != pointerId) {
            return;
        }
        final Key designated = getDesignatedDefaultKey();
        if (designated != null && !mMovedAfterDown) {
            // Released without sliding: type the designated default item.
            mCurrentKey = designated;
        } else {
            // Calling {@link #detectKey(int,int,int)} here is harmless because the last move event
            // and the following up event share the same coordinates.
            mCurrentKey = detectKey(x, y);
        }
        if (mCurrentKey != null) {
            updateReleaseKeyGraphics(mCurrentKey);
            onKeyInput(mCurrentKey, x, y);
            mCurrentKey = null;
        }
    }

    /**
     * Performs the specific action for this panel when the user presses a key on the panel.
     */
    protected void onKeyInput(final Key key, final int x, final int y) {
        dismissMoreKeysPanel();

        final int code = key.getCode();
        if (code == Constants.CODE_OUTPUT_TEXT) {
            mListener.onTextInput(mCurrentKey.getOutputText());
        } else if (code != Constants.CODE_UNSPECIFIED) {
            if (getKeyboard().hasProximityCharsCorrection(code)) {
                mListener.onCodeInput(code, x, y, false /* isKeyRepeat */);
            } else {
                mListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE,
                        false /* isKeyRepeat */);
            }
        }
    }

    private Key detectKey(int x, int y) {
        return selectKey(mKeyDetector.detectHitKey(x, y));
    }

    private Key selectKey(final Key newKey) {
        final Key oldKey = mCurrentKey;
        if (newKey == oldKey) {
            return newKey;
        } else {
            if(mAccessibilityDelegate != null && AccessibilityUtils.getInstance().isAccessibilityEnabled() && newKey != null) {
                mAccessibilityDelegate.onKeyHovered(newKey);
            }
        }
        // A new key is detected.
        if (oldKey != null) {
            updateReleaseKeyGraphics(oldKey);
            invalidateKey(oldKey);
        }
        if (newKey != null) {
            updatePressKeyGraphics(newKey);
            invalidateKey(newKey);
        }
        return newKey;
    }

    private void updateReleaseKeyGraphics(final Key key) {
        key.onReleased();
        invalidateKey(key);
    }

    private void updatePressKeyGraphics(final Key key) {
        key.onPressed();
        invalidateKey(key);
    }

    @Override
    public void dismissMoreKeysPanel() {
        if (!isShowingInParent()) {
            return;
        }
        final MoreKeysKeyboardAccessibilityDelegate accessibilityDelegate = mAccessibilityDelegate;
        if (accessibilityDelegate != null
                && AccessibilityUtils.getInstance().isAccessibilityEnabled()) {
            accessibilityDelegate.onDismissMoreKeysKeyboard();
        }
        mController.onDismissMoreKeysPanel();
    }

    @Override
    public int translateX(final int x) {
        return x - mOriginX;
    }

    @Override
    public int translateY(final int y) {
        return y - mOriginY;
    }

    @Override
    public boolean onTouchEvent(final MotionEvent me) {
        final int action = me.getActionMasked();
        final long eventTime = me.getEventTime();
        final int index = me.getActionIndex();
        final int x = (int)me.getX(index);
        final int y = (int)me.getY(index);
        final int pointerId = me.getPointerId(index);
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN:
            onDownEvent(x, y, pointerId, eventTime);
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
            onUpEvent(x, y, pointerId, eventTime);
            break;
        case MotionEvent.ACTION_MOVE:
            onMoveEvent(x, y, pointerId, eventTime);
            break;
        }
        return true;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        return (mAccessibilityDelegate != null && AccessibilityUtils.getInstance().isTouchExplorationEnabled() && mAccessibilityDelegate.dispatchHoverEvent(event))
                || super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return (mAccessibilityDelegate != null && AccessibilityUtils.getInstance().isTouchExplorationEnabled() && mAccessibilityDelegate.dispatchKeyEvent(event))
                || super.dispatchKeyEvent(event);
    }

    @Override
    public void onFocusChanged(boolean gainFocus, int direction,
                               Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if(mAccessibilityDelegate != null && AccessibilityUtils.getInstance().isTouchExplorationEnabled()) {
            mAccessibilityDelegate.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        }
    }

    private View getContainerView() {
        return (View)getParent();
    }

    @Override
    public void showInParent(final ViewGroup parentView) {
        removeFromParent();
        parentView.addView(getContainerView());

        if(mAccessibilityDelegate != null && AccessibilityUtils.getInstance().isTouchExplorationEnabled()) {
            parentView.setTouchDelegate(new MyTouchDelegate(parentView, getContainerView()));
        }
    }

    class MyTouchDelegate extends TouchDelegate {
        public MyTouchDelegate(View parentView, View delegateView) {
            super(new Rect(Integer.MIN_VALUE / 2,  Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2 - 1, Integer.MAX_VALUE / 2 - 1), delegateView);
        }

        @Override
        public boolean onTouchExplorationHoverEvent(@NonNull MotionEvent event) {
            MotionEvent copy = MotionEvent.obtain(event);
            copy.offsetLocation(-getContainerView().getX(), -getContainerView().getY());

            // Dismiss the panel if we exit outside of the range
            if(copy.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                if (mKeyDetector.detectHitKey(
                        (int)copy.getX(),
                        (int)copy.getY()
                ) == null) {
                    dismissMoreKeysPanel();
                }

                return true;
            } else {
                return MoreKeysKeyboardView.this.dispatchHoverEvent(copy);
            }
        }
    }

    @Override
    public void removeFromParent() {
        final View containerView = getContainerView();
        final ViewGroup currentParent = (ViewGroup)containerView.getParent();
        if (currentParent != null) {
            currentParent.removeView(containerView);
            currentParent.setTouchDelegate(null);
        }
    }

    @Override
    public boolean isShowingInParent() {
        return (getContainerView().getParent() != null);
    }
}
