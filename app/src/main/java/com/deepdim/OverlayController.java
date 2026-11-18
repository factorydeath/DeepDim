package com.deepdim;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * Полноэкранная затемняющая вуаль. Может жить как обычный overlay
 * (TYPE_APPLICATION_OVERLAY) или как accessibility overlay — второй вариант
 * перекрывает системные панели и не скрывается приложениями с hideOverlayWindows.
 */
public final class OverlayController {

    private final Context context;
    private final WindowManager wm;
    private final boolean accessibilityType;
    private final boolean allowHandle;

    private DimView dimView;
    private WindowManager.LayoutParams dimParams;
    private boolean dimAdded;
    private boolean dimHiding;

    private ImageView handleView;
    private WindowManager.LayoutParams handleParams;
    private boolean handleAdded;
    private boolean handleHiding;
    private boolean handleWanted;

    private int dimPercent;
    private int warmPercent;

    public OverlayController(Context context, boolean accessibilityType) {
        this.context = context.getApplicationContext();
        this.accessibilityType = accessibilityType;
        this.allowHandle = !accessibilityType;
        this.wm = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isAccessibilityType() {
        return accessibilityType;
    }

    public boolean canDraw() {
        if (accessibilityType) return true;
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable t) {
            return false;
        }
    }

    private int windowType() {
        return accessibilityType
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    // ---------------------------------------------------------------- veil

    public boolean showDim(int dim, int warm) {
        if (!canDraw() || wm == null) return false;
        this.dimPercent = clamp(dim, 0, 97);
        this.warmPercent = clamp(warm, 0, 100);
        if (dimView == null) {
            dimView = new DimView(context);
            dimParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            dimParams.gravity = Gravity.TOP | Gravity.START;
            dimParams.x = 0;
            dimParams.y = 0;
            dimParams.setTitle("DeepDim veil");
            if (Build.VERSION.SDK_INT >= 30) {
                dimParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= 28) {
                dimParams.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        dimHiding = false;
        dimView.invalidate();
        if (!dimAdded) {
            try {
                dimView.setAlpha(0f);
                wm.addView(dimView, dimParams);
                dimAdded = true;
                dimView.animate().alpha(1f).setDuration(230).start();
            } catch (Throwable t) {
                Toast.makeText(context, context.getString(R.string.overlay_failed),
                        Toast.LENGTH_SHORT).show();
                return false;
            }
        } else {
            dimView.animate().cancel();
            dimView.setAlpha(1f);
        }
        return true;
    }

    public void updateDim(int dim, int warm) {
        this.dimPercent = clamp(dim, 0, 97);
        this.warmPercent = clamp(warm, 0, 100);
        if (dimView != null && dimAdded) dimView.invalidate();
    }

    public void hideDim() {
        if (!dimAdded || dimView == null || dimHiding) return;
        final DimView view = dimView;
        dimHiding = true;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(165).withEndAction(() -> {
            if (!dimHiding || dimView != view) return;
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
            dimAdded = false;
            dimHiding = false;
            view.setAlpha(1f);
        }).start();
    }

    // -------------------------------------------------------------- handle

    public void setHandleWanted(boolean wanted) {
        this.handleWanted = wanted;
        if (!allowHandle) return;
        if (wanted) addHandle(); else removeHandle();
    }

    private void addHandle() {
        if (wm == null || !canDraw()) return;
        if (handleView == null) {
            handleView = new ImageView(context);
            handleView.setImageResource(R.drawable.ic_moon);
            handleView.setColorFilter(Color.parseColor("#1B1206"));
            handleView.setBackgroundResource(R.drawable.bg_handle);
            handleView.setPadding(dp(11), dp(11), dp(11), dp(11));
            handleView.setContentDescription(context.getString(R.string.show_handle));
            handleView.setOnTouchListener(new HandleTouch());

            handleParams = new WindowManager.LayoutParams(
                    dp(42), dp(42),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            handleParams.gravity = Gravity.TOP | Gravity.END;
            handleParams.x = dp(5);
            handleParams.y = initialHandleY();
            handleParams.setTitle("DeepDim reset");
        }
        handleHiding = false;
        handleView.animate().cancel();
        handleView.setAlpha(0.78f);
        if (handleAdded) return;
        try {
            handleView.setAlpha(0f);
            wm.addView(handleView, handleParams);
            handleAdded = true;
            handleView.animate().alpha(0.78f).setDuration(220).start();
        } catch (Throwable ignored) {
        }
    }

    private void removeHandle() {
        if (!handleAdded || handleView == null || handleHiding) return;
        final ImageView view = handleView;
        handleHiding = true;
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            if (!handleHiding || handleView != view) return;
            try {
                wm.removeView(view);
            } catch (Throwable ignored) {
            }
            handleAdded = false;
            handleHiding = false;
            view.setAlpha(0.78f);
        }).start();
    }

    private int initialHandleY() {
        int height = context.getResources().getDisplayMetrics().heightPixels;
        return Math.max(dp(40), (int) (height * 0.33f));
    }

    public void onConfigurationChanged() {
        if (handleAdded && handleParams != null && wm != null) {
            handleParams.y = Math.max(dp(40),
                    Math.min(initialHandleY(),
                            context.getResources().getDisplayMetrics().heightPixels - dp(60)));
            try {
                wm.updateViewLayout(handleView, handleParams);
            } catch (Throwable ignored) {
            }
        }
    }

    public void destroy() {
        dimHiding = false;
        handleHiding = false;
        if (dimAdded && dimView != null) {
            dimView.animate().cancel();
            try {
                wm.removeView(dimView);
            } catch (Throwable ignored) {
            }
            dimAdded = false;
        }
        if (handleAdded && handleView != null) {
            handleView.animate().cancel();
            try {
                wm.removeView(handleView);
            } catch (Throwable ignored) {
            }
            handleAdded = false;
        }
        dimView = null;
        handleView = null;
    }

    // --------------------------------------------------------------- touch

    private final class HandleTouch implements View.OnTouchListener {
        private float downRawY;
        private int startY;
        private long downTime;
        private boolean moved;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawY = event.getRawY();
                    startY = handleParams.y;
                    downTime = event.getEventTime();
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = event.getRawY() - downRawY;
                    if (Math.abs(dy) > dp(7)) moved = true;
                    if (moved) {
                        int max = context.getResources().getDisplayMetrics().heightPixels - dp(54);
                        handleParams.y = (int) Math.max(dp(4), Math.min(max, startY + dy));
                        try {
                            wm.updateViewLayout(handleView, handleParams);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        if (event.getEventTime() - downTime > 620) {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            disable();
                        } else {
                            v.performClick();
                            openApp();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }

    private void openApp() {
        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void disable() {
        try {
            context.startService(new Intent(context, DimService.class)
                    .setAction(DimService.ACTION_DISABLE));
            Toast.makeText(context, R.string.toast_dim_off, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ----------------------------------------------------------------- view

    private final class DimView extends View {
        DimView(Context context) {
            super(context);
            setWillNotDraw(false);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int alpha = Math.round(255f * (dimPercent / 100f));
            canvas.drawColor(Color.argb(alpha, 0, 0, 0));
            if (warmPercent > 0) {
                int warmAlpha = Math.round(255f * 0.30f * (warmPercent / 100f));
                canvas.drawColor(Color.argb(warmAlpha, 255, 105, 20));
            }
        }
    }
}
