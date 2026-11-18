package com.deepdim;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Необязательная служба: кнопки громкости меняют вуаль и, главное,
 * даёт доступ к TYPE_ACCESSIBILITY_OVERLAY — он перекрывает системные панели
 * и не скрывается приложениями, вызывающими hideOverlayWindows().
 */
public final class VolumeKeyService extends AccessibilityService {

    private static volatile VolumeKeyService sInstance;
    private OverlayController overlay;
    private Prefs prefs;

    public static boolean overlayConnected() {
        VolumeKeyService s = sInstance;
        return s != null && s.overlay != null;
    }

    public static void syncOverlay(boolean show, int dim, int warm) {
        VolumeKeyService s = sInstance;
        if (s == null || s.overlay == null) return;
        if (show) s.overlay.showDim(dim, warm);
        else s.overlay.hideDim();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        prefs = Prefs.get(this);
        overlay = new OverlayController(this, true);
        if (prefs.enabled() && !prefs.cleanScreenshots()) {
            overlay.showDim(prefs.dim(), prefs.warmEnabled() ? prefs.warmth() : 0);
        }
        DimService.onAccessibilityChanged();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // События не нужны.
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (prefs == null) prefs = Prefs.get(this);
        if (!prefs.volumeKeys() || !DimService.isActive()) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            DimService.startNudge(this, code == KeyEvent.KEYCODE_VOLUME_UP ? 5 : -5);
        }
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        teardown();
        super.onDestroy();
    }

    private void teardown() {
        sInstance = null;
        if (overlay != null) {
            overlay.destroy();
            overlay = null;
        }
        DimService.onAccessibilityChanged();
    }
}
