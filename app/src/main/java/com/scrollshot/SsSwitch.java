package com.scrollshot;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.animation.PathInterpolator;
import android.widget.CheckBox;

/**
 * iOS-style capsule toggle: a pill-shaped track that switches between
 * grey and green, with a round white knob that slides across.
 *
 * Extends CheckBox on purpose: the compatibility layout still uses a
 * plain CheckBox for the same id, and MainActivity looks the view up
 * as CheckBox, so both layouts keep working without a cast.
 */
public class SsSwitch extends CheckBox {

    private static final float KNOB_INSET_DP = 2f;
    private static final long ANIM_MS = 180L;
    private static final ArgbEvaluator EVALUATOR = new ArgbEvaluator();

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF trackRect = new RectF();
    private final RectF thumbRect = new RectF();
    private final int offColor;
    private final int onColor;
    private final float insetPx;
    private float fraction;
    private ValueAnimator animator;

    public SsSwitch(Context context) {
        this(context, null);
    }

    public SsSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        offColor = context.getResources().getColor(R.color.ss_separator, context.getTheme());
        onColor = context.getResources().getColor(R.color.scrollshot_blue, context.getTheme());
        insetPx = KNOB_INSET_DP * context.getResources().getDisplayMetrics().density;
        thumbPaint.setColor(0xFFFFFFFF);
        // The check box glyph and its label are drawn by us instead.
        setButtonDrawable(null);
        setText(null);
        fraction = isChecked() ? 1f : 0f;
    }

    @Override
    public void setChecked(boolean checked) {
        boolean changed = isChecked() != checked;
        super.setChecked(checked);
        float target = checked ? 1f : 0f;
        if (!changed) {
            fraction = target;
            invalidate();
            return;
        }
        animateTo(target);
    }

    private void animateTo(float target) {
        if (animator != null) animator.cancel();
        // Not on screen yet (inflation / state restore): jump straight
        // to the target instead of animating from the wrong side.
        if (getWindowToken() == null) {
            fraction = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(fraction, target);
        animator.setDuration(ANIM_MS);
        animator.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1f));
        animator.addUpdateListener(a -> {
            fraction = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float radius = h / 2f;
        trackRect.set(0, 0, w, h);
        trackPaint.setColor((Integer) EVALUATOR.evaluate(fraction, offColor, onColor));
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint);

        float diameter = h - insetPx * 2f;
        float travel = w - diameter - insetPx * 2f;
        float left = insetPx + travel * fraction;
        thumbRect.set(left, insetPx, left + diameter, insetPx + diameter);
        canvas.drawRoundRect(thumbRect, diameter / 2f, diameter / 2f, thumbPaint);
    }
}
