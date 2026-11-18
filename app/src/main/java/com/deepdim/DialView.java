package com.deepdim;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
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

import java.util.Locale;

/**
 * Круговой регулятор глубины вуали. 270° дуги, живой drag,
 * крупная цифра в центре и подписи состояния.
 */
public final class DialView extends View {

    public interface OnDimChanged {
        void onDimChanged(int value, boolean fromUser);
    }

    private static final int MAX = 95;
    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;

    private final Paint arcTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bigPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private final int colorText;
    private final int colorText2;
    private final int colorText3;
    private final int colorAccent;
    private final int colorAccent2;
    private final int colorTrack;
    private final int colorStroke2;

    private int value = 50;
    private boolean active;
    private boolean dragging;
    private float cx;
    private float cy;
    private float radius;
    private float stroke;
    private float lastAngle = -1f;
    private long lastHapticAt;
    private OnDimChanged listener;

    public DialView(Context context) {
        this(context, null);
    }

    public DialView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DialView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        colorText = context.getColor(R.color.text);
        colorText2 = context.getColor(R.color.text2);
        colorText3 = context.getColor(R.color.text3);
        colorAccent = context.getColor(R.color.accent);
        colorAccent2 = context.getColor(R.color.accent2);
        colorTrack = context.getColor(R.color.stroke);
        colorStroke2 = context.getColor(R.color.stroke2);

        stroke = dp(9);
        arcTrack.setStyle(Paint.Style.STROKE);
        arcTrack.setStrokeWidth(stroke);
        arcTrack.setStrokeCap(Paint.Cap.ROUND);
        arcTrack.setColor(colorTrack);

        arcFill.setStyle(Paint.Style.STROKE);
        arcFill.setStrokeWidth(stroke);
        arcFill.setStrokeCap(Paint.Cap.ROUND);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(dp(1.2f));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickPaint.setColor(colorStroke2);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(colorAccent);

        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setColor(colorAccent);

        bigPaint.setTextSize(sp(56));
        bigPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        bigPaint.setTextAlign(Paint.Align.CENTER);
        bigPaint.setColor(colorText);

        labelPaint.setTextSize(sp(10));
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setLetterSpacing(0.22f);
        labelPaint.setColor(colorText2);

        statePaint.setTextSize(sp(8));
        statePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statePaint.setTextAlign(Paint.Align.CENTER);
        statePaint.setLetterSpacing(0.18f);
        statePaint.setColor(colorText3);

        setClickable(true);
        setFocusable(true);
        updateContentDescription();
    }

    public void setOnDimChanged(OnDimChanged listener) {
        this.listener = listener;
    }

    public void setValue(int newValue) {
        int clamped = clamp(newValue, 0, MAX);
        if (clamped == value) {
            updateContentDescription();
            return;
        }
        value = clamped;
        updateContentDescription();
        invalidate();
    }

    public int getValue() {
        return value;
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        updateContentDescription();
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int defaultSize = (int) dp(252);
        int width = resolveSize(defaultSize, widthMeasureSpec);
        int height = resolveSize(defaultSize, heightMeasureSpec);
        int size = Math.min(width, height);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        float pad = dp(14) + stroke / 2f;
        radius = Math.min(w, h) / 2f - pad;
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
        arcFill.setShader(new LinearGradient(
                0, h, w, 0,
                new int[]{colorAccent2, colorAccent, colorAccent2},
                new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;

        float progress = value / (float) MAX;
        float edgeAlpha = active ? 1f : 0.42f;

        canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, arcTrack);

        // Внутренние риски: создают ощущение шкалы.
        for (int i = 0; i <= 44; i++) {
            float angle = START_ANGLE + SWEEP_ANGLE * i / 44f;
            float rad = (float) Math.toRadians(angle);
            float inner = radius - dp(17);
            float outer = radius - dp(12.5f);
            float x1 = cx + inner * (float) Math.cos(rad);
            float y1 = cy + inner * (float) Math.sin(rad);
            float x2 = cx + outer * (float) Math.cos(rad);
            float y2 = cy + outer * (float) Math.sin(rad);
            float passed = i / 44f;
            int alpha;
            int color;
            if (passed <= progress + 0.001f) {
                color = colorAccent;
                alpha = (int) (150 * edgeAlpha);
            } else {
                color = colorStroke2;
                alpha = (int) (95 * edgeAlpha);
            }
            tickPaint.setColor(color);
            tickPaint.setAlpha(alpha);
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }

        if (progress > 0f) {
            arcFill.setAlpha((int) (255 * edgeAlpha));
            canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE * progress, false, arcFill);

            float endAngle = START_ANGLE + SWEEP_ANGLE * progress;
            float rad = (float) Math.toRadians(endAngle);
            float dotX = cx + radius * (float) Math.cos(rad);
            float dotY = cy + radius * (float) Math.sin(rad);

            glowPaint.setAlpha((int) (46 * edgeAlpha));
            canvas.drawCircle(dotX, dotY, dp(13), glowPaint);
            dotPaint.setAlpha((int) (255 * edgeAlpha));
            canvas.drawCircle(dotX, dotY, dp(5), dotPaint);
        }

        int bigColor = active ? colorText : colorText2;
        bigPaint.setColor(bigColor);
        bigPaint.setAlpha(active ? 255 : 190);

        String text = String.valueOf(value) + "%";
        Paint.FontMetrics bigFm = bigPaint.getFontMetrics();
        float bigBaseline = cy - (bigFm.ascent + bigFm.descent) / 2f - dp(10);
        canvas.drawText(text, cx, bigBaseline, bigPaint);

        labelPaint.setColor(active ? colorText2 : colorText3);
        canvas.drawText(getContext().getString(R.string.layer_veil).toUpperCase(Locale.getDefault()),
                cx, cy + dp(26), labelPaint);

        statePaint.setColor(active ? colorAccent : colorText3);
        statePaint.setAlpha(active ? 225 : 160);
        String state = getContext().getString(active ? R.string.active : R.string.inactive)
                .toUpperCase(Locale.getDefault());
        canvas.drawText(state, cx, cy + dp(45), statePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Тап по центру (цифра) не должен менять значение.
                if (radius > 0) {
                    float dx = event.getX() - cx;
                    float dy = event.getY() - cy;
                    float dead = radius * 0.52f;
                    if (dx * dx + dy * dy < dead * dead) {
                        dragging = false;
                        return true;
                    }
                }
                dragging = true;
                lastAngle = -1f;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                handleTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) handleTouch(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    handleTouch(event.getX(), event.getY());
                    dragging = false;
                    lastAngle = -1f;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                } else {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                lastAngle = -1f;
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

    private void handleTouch(float x, float y) {
        float angle = (float) Math.toDegrees(Math.atan2(y - cy, x - cx));
        angle = (angle + 360f) % 360f;

        float sweep;
        if (angle > 45f && angle < START_ANGLE) {
            // Нижний разрыв: притягиваем к ближайшему краю дуги.
            sweep = angle >= 90f ? 0f : SWEEP_ANGLE;
        } else {
            sweep = (angle - START_ANGLE + 360f) % 360f;
            if (sweep > SWEEP_ANGLE) sweep = SWEEP_ANGLE;
        }

        if (lastAngle >= 0f) {
            float diff = Math.abs(sweep - lastAngle);
            if (diff > SWEEP_ANGLE / 2f) {
                // Переход через разрыв — не даём скачку.
                sweep = sweep < lastAngle ? 0f : SWEEP_ANGLE;
            }
        }
        lastAngle = sweep;

        int next = Math.round(sweep / SWEEP_ANGLE * MAX);
        next = clamp(next, 0, MAX);
        if (next != value) {
            value = next;
            updateContentDescription();
            invalidate();
            tick();
        }
        if (listener != null) listener.onDimChanged(value, true);
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
        setContentDescription(getContext().getString(R.string.layer_veil)
                + ": " + value + "%");
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
