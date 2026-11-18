package com.deepdim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Тонкий горизонтальный слайдер в стиле DeepDim. */
public final class ParamSlider extends View {

    public interface OnProgressChanged {
        void onProgressChanged(int value, boolean fromUser);
    }

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int colorTrack;
    private final int colorStroke2;
    private final int colorAccent;
    private final int colorAccent2;
    private final int colorText;
    private final int colorText3;

    private int progress = 0;
    private boolean sliderEnabled = true;
    private boolean dragging;
    private float left;
    private float right;
    private float centerY;
    private long lastHapticAt;
    private OnProgressChanged listener;

    public ParamSlider(Context context) {
        this(context, null);
    }

    public ParamSlider(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ParamSlider(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        colorTrack = context.getColor(R.color.stroke);
        colorStroke2 = context.getColor(R.color.stroke2);
        colorAccent = context.getColor(R.color.accent);
        colorAccent2 = context.getColor(R.color.accent2);
        colorText = context.getColor(R.color.text);
        colorText3 = context.getColor(R.color.text3);

        trackPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(colorTrack);

        fillPaint.setStyle(Paint.Style.FILL);

        thumbPaint.setStyle(Paint.Style.FILL);
        thumbPaint.setColor(colorText);

        thumbRing.setStyle(Paint.Style.STROKE);
        thumbRing.setStrokeWidth(dp(2.2f));
        thumbRing.setColor(colorAccent);

        setClickable(true);
        setFocusable(true);
        updateContentDescription();
    }

    public void setOnProgressChanged(OnProgressChanged listener) {
        this.listener = listener;
    }

    public void setProgress(int value) {
        int clamped = clamp(value, 0, 100);
        if (clamped == progress) return;
        progress = clamped;
        updateContentDescription();
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    public void setSliderEnabled(boolean enabled) {
        if (sliderEnabled == enabled) return;
        sliderEnabled = enabled;
        setAlpha(enabled ? 1f : 0.42f);
        invalidate();
    }

    public boolean isSliderEnabled() {
        return sliderEnabled;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = resolveSize((int) dp(54), heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float pad = dp(10);
        left = pad;
        right = w - pad;
        centerY = h / 2f;
        if (right < left) right = left;
        fillPaint.setShader(new LinearGradient(left, 0, right, 0,
                new int[]{colorAccent2, colorAccent},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (right <= left) return;

        float trackH = dp(5);
        float thumbR = dp(8.5f);
        float usable = Math.max(1f, right - left);
        float x = left + usable * progress / 100f;

        rect.set(left, centerY - trackH / 2f, right, centerY + trackH / 2f);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, trackPaint);

        if (progress > 0) {
            rect.set(left, centerY - trackH / 2f, x, centerY + trackH / 2f);
            canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, fillPaint);
        }

        thumbPaint.setColor(sliderEnabled ? colorText : colorText3);
        canvas.drawCircle(x, centerY, thumbR, thumbPaint);
        thumbRing.setColor(sliderEnabled ? colorAccent : colorStroke2);
        canvas.drawCircle(x, centerY, thumbR + dp(1.6f), thumbRing);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!sliderEnabled || !isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                handleTouch(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) handleTouch(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    handleTouch(event.getX());
                    dragging = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void handleTouch(float x) {
        float usable = Math.max(1f, right - left);
        int next = Math.round((x - left) / usable * 100f);
        next = clamp(next, 0, 100);
        if (next != progress) {
            progress = next;
            updateContentDescription();
            invalidate();
            tick();
        }
        if (listener != null) listener.onProgressChanged(progress, true);
    }

    private void tick() {
        long now = SystemClock.uptimeMillis();
        if (now - lastHapticAt < 42L) return;
        lastHapticAt = now;
        if (Prefs.get(getContext()).haptics()) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void updateContentDescription() {
        setContentDescription(progress + "%");
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
