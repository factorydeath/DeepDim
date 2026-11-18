package com.deepdim;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ядро: состояние, слои, расписание, уведомление, мониторинг приложений. */
public final class DimService extends Service {

    private static final String TAG = "DeepDim/Service";

    public static final String ACTION_START = "com.deepdim.action.START";
    public static final String ACTION_ENABLE = "com.deepdim.action.ENABLE";
    public static final String ACTION_DISABLE = "com.deepdim.action.DISABLE";
    public static final String ACTION_TOGGLE = "com.deepdim.action.TOGGLE";
    public static final String ACTION_PRESET = "com.deepdim.action.PRESET";
    public static final String ACTION_NUDGE = "com.deepdim.action.NUDGE";
    public static final String ACTION_SCHEDULE = "com.deepdim.action.SCHEDULE";
    public static final String ACTION_RESTORE = "com.deepdim.action.RESTORE";
    public static final String ACTION_SLEEP = "com.deepdim.action.SLEEP";
    private static final String ACTION_KEEP = "com.deepdim.action.KEEP";
    public static final String EXTRA_PRESET = "preset";
    public static final String EXTRA_DELTA = "delta";
    public static final String EXTRA_MINUTES = "minutes";

    private static final int NOTIFICATION_ID = 0x0D1D;
    private static final int REQ_DISABLE = 11;
    private static final int REQ_MINUS = 12;
    private static final int REQ_PLUS = 13;

    public interface Listener {
        void onStateChanged(DimState state);
    }

    public final class LocalBinder extends Binder {
        public DimService service() {
            return DimService.this;
        }
    }

    private static volatile DimService sInstance;

    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final DimState state = new DimState();

    private Prefs prefs;
    private OverlayController overlay;
    private BrightnessEngine engine;
    private ForegroundMonitor monitor;
    private ExecutorService worker;
    private BroadcastReceiver screenReceiver;

    private boolean screenOn = true;
    private boolean foregroundStarted;
    private boolean scheduleTickRunning;
    private boolean evaluatingSchedule;
    private boolean stopping;
    private boolean warnedTooDark;
    private int clients;
    private boolean started;

    private final Runnable scheduleApplyRunnable = new Runnable() {
        @Override
        public void run() {
            final DimState snapshot = state.copy();
            worker.execute(() -> {
                try {
                    engine().applyAll(appliedState(snapshot));
                } catch (Throwable t) {
                    Log.e(TAG, "applyAll failed", t);
                }
                if (!snapshot.enabled) {
                    main.post(DimService.this::maybeStopIfIdle);
                }
            });
        }
    };

    private final Runnable scheduleTick = new Runnable() {
        @Override
        public void run() {
            if (!prefs.scheduleEnabled() || stopping) {
                scheduleTickRunning = false;
                return;
            }
            evaluateSchedule();
            main.postDelayed(this, 30000);
        }
    };

    private final Runnable notificationRunnable = this::updateNotification;

    private final Runnable sleepRunnable = new Runnable() {
        @Override
        public void run() {
            long until = prefs.sleepUntil();
            if (until <= 0L) return;
            long now = System.currentTimeMillis();
            if (until > now) {
                if (foregroundStarted) updateNotification();
                main.postDelayed(this, Math.min(60000L, Math.max(1000L, until - now + 120L)));
                return;
            }
            prefs.setSleepUntil(0L);
            prefs.setSleepMinutes(0);
            if (state.enabled) {
                setEnabled(false);
                toast(getString(R.string.sleep_done));
            } else {
                stopForegroundNow();
                maybeStopIfIdle();
            }
            render();
        }
    };

    // ------------------------------------------------------------ lifecycle

    public static DimService peek() {
        return sInstance;
    }

    public static boolean isActive() {
        DimService s = sInstance;
        return s != null && s.state.enabled;
    }

    public static void startAction(Context context, String action) {
        Intent intent = new Intent(context, DimService.class).setAction(action);
        launch(context, intent, !ACTION_DISABLE.equals(action) && !ACTION_RESTORE.equals(action));
    }

    public static void startPreset(Context context, String preset) {
        Intent intent = new Intent(context, DimService.class)
                .setAction(ACTION_PRESET)
                .putExtra(EXTRA_PRESET, preset);
        launch(context, intent, true);
    }

    public static void startNudge(Context context, int delta) {
        Intent intent = new Intent(context, DimService.class)
                .setAction(ACTION_NUDGE)
                .putExtra(EXTRA_DELTA, delta);
        launch(context, intent, true);
    }

    public static void startSleep(Context context, int minutes) {
        Intent intent = new Intent(context, DimService.class)
                .setAction(ACTION_SLEEP)
                .putExtra(EXTRA_MINUTES, minutes);
        launch(context, intent, false);
    }

    private static void launch(Context context, Intent intent, boolean foreground) {
        try {
            if (foreground && Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "launch failed", t);
            try {
                context.startService(intent);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void onAccessibilityChanged() {
        DimService s = sInstance;
        if (s != null) s.main.post(s::syncOverlay);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        prefs = Prefs.get(this);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "deepdim-worker");
            t.setDaemon(true);
            return t;
        });
        overlay = new OverlayController(this, false);
        App.createChannel(this);
        copyPrefsToState();

        long sleepUntil = prefs.sleepUntil();
        if (state.enabled && sleepUntil > 0L && sleepUntil <= System.currentTimeMillis()) {
            state.enabled = false;
            prefs.setEnabled(false);
            prefs.setSleepUntil(0L);
            prefs.setSleepMinutes(0);
        }

        if (state.enabled) {
            if (!canDim()) {
                state.enabled = false;
                prefs.setEnabled(false);
            } else {
                ensureForeground();
                scheduleSleepCheck();
                main.post(() -> {
                    syncOverlay();
                    startMonitorIfNeeded();
                    scheduleApply(0);
                    render();
                });
            }
        }

        registerScreenReceiver();
        if (prefs.scheduleEnabled()) {
            startScheduleTick();
            main.post(this::evaluateSchedule);
        }
        Log.i(TAG, "service created, enabled=" + state.enabled);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        String action = intent != null && intent.getAction() != null
                ? intent.getAction() : ACTION_START;

        if (!ACTION_DISABLE.equals(action) && !ACTION_RESTORE.equals(action)
                && !ACTION_SLEEP.equals(action)) {
            ensureForeground();
        }

        switch (action) {
            case ACTION_START:
                applyCurrentState();
                break;
            case ACTION_ENABLE:
                setEnabled(true);
                break;
            case ACTION_DISABLE:
                setEnabled(false);
                break;
            case ACTION_TOGGLE:
                setEnabled(!state.enabled);
                break;
            case ACTION_PRESET:
                applyPreset(Preset.byId(intent != null
                        ? intent.getStringExtra(EXTRA_PRESET) : "night").id);
                break;
            case ACTION_NUDGE:
                nudgeDim(intent != null ? intent.getIntExtra(EXTRA_DELTA, 0) : 0);
                break;
            case ACTION_SCHEDULE:
                evaluateSchedule();
                if (!state.enabled) {
                    stopForegroundNow();
                    maybeStopIfIdle();
                }
                break;
            case ACTION_RESTORE:
                restoreNow();
                break;
            case ACTION_SLEEP:
                setSleepTimer(intent != null ? intent.getIntExtra(EXTRA_MINUTES, 0) : 0);
                break;
            case ACTION_KEEP:
                break;
            default:
                applyCurrentState();
                break;
        }

        if (prefs.scheduleEnabled()) ScheduleController.scheduleNext(this);
        if (prefs.sleepUntil() > 0L) scheduleSleepCheck();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        clients++;
        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        clients = Math.max(0, clients - 1);
        if (state.enabled) ensureStarted();
        else maybeStopIfIdle();
        return false;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlay != null) overlay.onConfigurationChanged();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroy, enabled=" + state.enabled);
        stopping = true;
        sInstance = null;
        main.removeCallbacksAndMessages(null);
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            screenReceiver = null;
        }
        if (monitor != null) monitor.stop();
        if (overlay != null) overlay.destroy();
        VolumeKeyService.syncOverlay(false, 0, 0);

        final boolean wasEnabled = state.enabled;
        state.enabled = false;
        if (wasEnabled) prefs.setEnabled(false);
        if (worker != null) {
            worker.execute(() -> {
                try {
                    engine().applyAll(state.copy());
                } catch (Throwable ignored) {
                }
                worker.shutdown();
            });
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------- public

    public DimState getState() {
        return state.copy();
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void setEnabled(boolean enabled) {
        if (enabled) {
            if (state.enabled) {
                ensureForeground();
                return;
            }
            if (!canDim()) {
                toast(getString(R.string.toast_overlay_needed));
                prefs.setEnabled(false);
                state.enabled = false;
                stopForegroundNow();
                render();
                maybeStopIfIdle();
                return;
            }
            long sleepUntil = prefs.sleepUntil();
            if (sleepUntil > 0L && sleepUntil <= System.currentTimeMillis()) {
                prefs.setSleepUntil(0L);
                prefs.setSleepMinutes(0);
            }
            state.enabled = true;
            if (prefs.scheduleEnabled() && ScheduleController.inWindow(prefs, System.currentTimeMillis())) {
                long end = ScheduleController.currentWindowEndMillis(prefs, System.currentTimeMillis());
                if (end > 0) prefs.setScheduleSnoozeUntil(end);
            }
            ensureForeground();
            ensureStarted();
            scheduleSleepCheck();
            syncOverlay();
            startMonitorIfNeeded();
            startScheduleTick();
            scheduleApply(60);
            persistAll();
            render();
        } else {
            boolean was = state.enabled;
            state.enabled = false;
            state.appBoost = 0;
            state.activePackage = "";
            prefs.setSleepUntil(0L);
            prefs.setSleepMinutes(0);
            main.removeCallbacks(sleepRunnable);
            if (prefs.scheduleActive()) {
                prefs.setScheduleActive(false);
                long end = ScheduleController.currentWindowEndMillis(prefs, System.currentTimeMillis());
                prefs.setScheduleSnoozeUntil(end > 0 ? end : System.currentTimeMillis());
            }
            if (monitor != null) monitor.stop();
            syncOverlay();
            stopForegroundNow();
            main.removeCallbacks(scheduleApplyRunnable);
            scheduleApply(0);
            persistAll();
            render();
            if (!was) maybeStopIfIdle();
        }
    }

    public void setDim(int value) {
        int clamped = clamp(value, 0, 95);
        if (clamped == state.dim) return;
        state.dim = clamped;
        prefs.setDim(clamped);
        prefs.setLastPreset("");
        if (clamped >= 88 && !warnedTooDark) {
            warnedTooDark = true;
            toast(getString(R.string.toast_too_dark));
        }
        if (clamped < 80) warnedTooDark = false;
        syncOverlay();
        if (prefs.cleanScreenshots()) scheduleApply(120);
        render();
    }

    public void setWarmth(int value) {
        int clamped = clamp(value, 0, 100);
        if (state.warmth == clamped) return;
        state.warmth = clamped;
        prefs.setWarmth(clamped);
        prefs.setLastPreset("");
        syncOverlay();
        render();
    }

    public void setWarmEnabled(boolean enabled) {
        state.warmEnabled = enabled;
        prefs.setWarmEnabled(enabled);
        prefs.setLastPreset("");
        syncOverlay();
        render();
    }

    public void setHwEnabled(boolean enabled) {
        state.hwEnabled = enabled;
        prefs.setHwEnabled(enabled);
        prefs.setLastPreset("");
        scheduleApply(40);
        render();
    }

    public void setHw(int value) {
        int clamped = clamp(value, 0, 100);
        if (state.hw == clamped) return;
        state.hw = clamped;
        prefs.setHw(clamped);
        prefs.setLastPreset("");
        scheduleApply(140);
        render();
    }

    public void setExtraEnabled(boolean enabled) {
        state.extraEnabled = enabled;
        prefs.setExtraEnabled(enabled);
        prefs.setLastPreset("");
        scheduleApply(40);
        render();
    }

    public void setExtra(int value) {
        int clamped = clamp(value, 0, 100);
        if (state.extra == clamped) return;
        state.extra = clamped;
        prefs.setExtra(clamped);
        prefs.setLastPreset("");
        scheduleApply(140);
        render();
    }

    public void applyPreset(String id) {
        Preset preset = Preset.byId(id);
        if (!preset.on) {
            prefs.setLastPreset(preset.id);
            setEnabled(false);
            return;
        }
        if (!canDim()) {
            toast(getString(R.string.toast_overlay_needed));
            return;
        }
        state.dim = preset.dim;
        state.warmth = preset.warmth;
        state.warmEnabled = preset.warmEnabled;
        state.hwEnabled = preset.hwEnabled;
        state.hw = preset.hw;
        state.extraEnabled = preset.extraEnabled();
        state.extra = preset.extra;
        prefs.setLastPreset(preset.id);
        prefs.setDim(state.dim);
        prefs.setWarmth(state.warmth);
        prefs.setWarmEnabled(state.warmEnabled);
        prefs.setHwEnabled(state.hwEnabled);
        prefs.setHw(state.hw);
        prefs.setExtraEnabled(state.extraEnabled);
        prefs.setExtra(state.extra);
        if (preset.dim >= 90) toast(getString(R.string.toast_too_dark));
        if (state.enabled) {
            syncOverlay();
            scheduleApply(60);
            persistAll();
            render();
        } else {
            setEnabled(true);
        }
    }

    public void nudgeDim(int delta) {
        if (!state.enabled) return;
        setDim(state.dim + delta);
    }

    public void setSleepTimer(int minutes) {
        if (minutes <= 0) {
            cancelSleepTimer();
            return;
        }
        if (!state.enabled) {
            toast(getString(R.string.sleep_need_active));
            maybeStopIfIdle();
            return;
        }
        ensureForeground();
        long until = System.currentTimeMillis() + minutes * 60000L;
        prefs.setSleepUntil(until);
        prefs.setSleepMinutes(minutes);
        scheduleSleepCheck();
        toast(getString(R.string.sleep_set, minutes));
        render();
    }

    public void cancelSleepTimer() {
        prefs.setSleepUntil(0L);
        prefs.setSleepMinutes(0);
        main.removeCallbacks(sleepRunnable);
        toast(getString(R.string.sleep_cancelled));
        render();
    }

    private void scheduleSleepCheck() {
        main.removeCallbacks(sleepRunnable);
        long until = prefs.sleepUntil();
        if (until <= 0L) return;
        long delay = until - System.currentTimeMillis();
        if (delay <= 0L) {
            main.post(sleepRunnable);
        } else {
            main.postDelayed(sleepRunnable, Math.min(60000L, delay + 120L));
        }
    }

    public void setShowHandle(boolean show) {
        prefs.setShowHandle(show);
        syncOverlay();
    }

    public void setSysfsEnabled(boolean enabled) {
        prefs.setSysfsEnabled(enabled);
        if (enabled) Backlight.invalidate();
        scheduleApply(0);
    }

    public void rulesChanged() {
        startMonitorIfNeeded();
        String pkg = state.activePackage;
        if (pkg != null && !pkg.isEmpty()) onForegroundApp(pkg);
        render();
    }

    public void onScheduleChanged() {
        ScheduleController.scheduleNext(this);
        evaluateSchedule();
        if (!prefs.scheduleEnabled()) {
            stopScheduleTick();
        } else {
            startScheduleTick();
        }
    }

    public void restoreNow() {
        setEnabled(false);
    }

    public void refreshOverlay() {
        syncOverlay();
    }

    /** Режим чистых скриншотов: вуаль скрыта, диммирование идёт через яркость и Extra Dim. */
    public void onCleanScreenshotsChanged() {
        syncOverlay();
        scheduleApply(0);
        render();
    }

    // ------------------------------------------------------------- internals

    private void applyCurrentState() {
        if (state.enabled) {
            if (!canDim()) {
                state.enabled = false;
                prefs.setEnabled(false);
                toast(getString(R.string.toast_overlay_needed));
                stopForegroundNow();
                maybeStopIfIdle();
                return;
            }
            ensureForeground();
            syncOverlay();
            startMonitorIfNeeded();
            startScheduleTick();
            scheduleApply(0);
            render();
        } else {
            syncOverlay();
            scheduleApply(0);
            stopForegroundNow();
            maybeStopIfIdle();
        }
    }

    private void copyPrefsToState() {
        DimState p = DimState.fromPrefs(this);
        state.enabled = p.enabled;
        state.dim = p.dim;
        state.warmth = p.warmth;
        state.warmEnabled = p.warmEnabled;
        state.hwEnabled = p.hwEnabled;
        state.hw = p.hw;
        state.extraEnabled = p.extraEnabled;
        state.extra = p.extra;
    }

    private void persistAll() {
        prefs.setEnabled(state.enabled);
        prefs.setDim(state.dim);
        prefs.setWarmth(state.warmth);
        prefs.setWarmEnabled(state.warmEnabled);
        prefs.setHwEnabled(state.hwEnabled);
        prefs.setHw(state.hw);
        prefs.setExtraEnabled(state.extraEnabled);
        prefs.setExtra(state.extra);
    }

    private void syncOverlay() {
        if (overlay == null) return;
        if (prefs.cleanScreenshots()) {
            overlay.hideDim();
            overlay.setHandleWanted(false);
            VolumeKeyService.syncOverlay(false, 0, 0);
            return;
        }
        boolean active = state.enabled && screenOn;
        if (!active) {
            overlay.hideDim();
            overlay.setHandleWanted(false);
            VolumeKeyService.syncOverlay(false, 0, 0);
            return;
        }

        int dim = state.effectiveDim();
        int warm = state.effectiveWarmth();

        if (VolumeKeyService.overlayConnected()) {
            overlay.hideDim();
            overlay.setHandleWanted(false);
            VolumeKeyService.syncOverlay(true, dim, warm);
        } else {
            VolumeKeyService.syncOverlay(false, 0, 0);
            // Вуаль добавляется первой, кнопка сброса — поверх неё,
            // иначе при 95% её почти не видно.
            boolean ok = overlay.showDim(dim, warm);
            overlay.setHandleWanted(ok && prefs.showHandle());
            if (!ok && !VolumeKeyService.overlayConnected()) {
                toast(getString(R.string.toast_overlay_needed));
            }
        }
    }

    private void startMonitorIfNeeded() {
        Map<String, AppRule> rules = prefs.rules();
        if (rules.isEmpty()) {
            if (monitor != null) monitor.stop();
            return;
        }
        if (monitor == null) {
            monitor = new ForegroundMonitor(this, this::onForegroundApp);
        }
        if (!monitor.hasPermission()) {
            monitor.stop();
            return;
        }
        monitor.start();
    }

    private void onForegroundApp(String pkg) {
        AppRule rule = prefs.rules().get(pkg);
        state.activePackage = pkg == null ? "" : pkg;
        state.appBoost = rule == null ? 0 : clamp(rule.boost, 0, 60);
        syncOverlay();
        if (prefs.cleanScreenshots()) scheduleApply(80);
        render();
    }

    private boolean canDim() {
        if (prefs.cleanScreenshots()) return true;
        return VolumeKeyService.overlayConnected()
                || (overlay != null && overlay.canDraw())
                || Settings.canDrawOverlays(this);
    }

    /**
     * В режиме чистых скриншотов вуаль не рисуется вообще. Чтобы затемнение
     * оставалось видимым, глубина раскладывается на системные слои, которые
     * не попадают в скриншот. С root используется Extra Dim, без root —
     * аппаратная яркость. Одновременно оба слоя не включаются, иначе экран
     * затемняется дважды и уходит почти в чёрный.
     */
    private DimState appliedState(DimState snapshot) {
        if (!snapshot.enabled || !prefs.cleanScreenshots()) return snapshot;
        int level = snapshot.effectiveDim();
        if (RootShell.isAvailable()) {
            snapshot.extraEnabled = true;
            snapshot.extra = level;
            snapshot.hwEnabled = false;
        } else {
            snapshot.extraEnabled = false;
            snapshot.hwEnabled = true;
            snapshot.hw = Math.max(0, 100 - level);
        }
        return snapshot;
    }

    private void scheduleApply(long delayMs) {
        if (worker == null) return;
        main.removeCallbacks(scheduleApplyRunnable);
        main.postDelayed(scheduleApplyRunnable, Math.max(0, delayMs));
    }

    private BrightnessEngine engine() {
        if (engine == null) engine = new BrightnessEngine(this, prefs);
        return engine;
    }

    private void ensureForeground() {
        if (foregroundStarted) return;
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed", t);
        }
    }

    /**
     * Сервис мог быть создан только через bindService. В этом случае после
     * выхода из приложения система уничтожит его и снимет затемнение. Явный
     * startService закрепляет сервис как запущенный, пока затемнение активно.
     */
    private void ensureStarted() {
        if (started) return;
        started = true;
        try {
            startService(new Intent(this, DimService.class).setAction(ACTION_KEEP));
        } catch (Throwable t) {
            started = false;
            Log.w(TAG, "ensureStarted failed", t);
        }
    }

    private void stopForegroundNow() {
        if (!foregroundStarted) return;
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
        foregroundStarted = false;
        main.removeCallbacks(notificationRunnable);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent minus = PendingIntent.getService(this, REQ_MINUS,
                new Intent(this, DimService.class).setAction(ACTION_NUDGE).putExtra(EXTRA_DELTA, -10),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent plus = PendingIntent.getService(this, REQ_PLUS,
                new Intent(this, DimService.class).setAction(ACTION_NUDGE).putExtra(EXTRA_DELTA, 10),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent off = PendingIntent.getService(this, REQ_DISABLE,
                new Intent(this, DimService.class).setAction(ACTION_DISABLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text;
        if (state.extraEnabled) {
            text = getString(R.string.notif_text, state.effectiveDim(), state.extra, state.hw);
        } else {
            text = getString(R.string.notif_text_rootless, state.effectiveDim(), state.hw);
        }

        Notification.Builder b = new Notification.Builder(this, App.CHANNEL_DIM)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(0xFFFFB454)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(0, getString(R.string.notif_minus), minus)
                .addAction(0, getString(R.string.notif_plus), plus)
                .addAction(0, getString(R.string.notif_off), off);
        long sleepUntil = prefs.sleepUntil();
        if (sleepUntil > System.currentTimeMillis()) {
            long minutes = Math.max(1L,
                    (sleepUntil - System.currentTimeMillis() + 59999L) / 60000L);
            b.setSubText(getString(R.string.notif_sleep, minutes));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private void updateNotification() {
        if (!foregroundStarted) return;
        try {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {
        }
    }

    private void render() {
        DimState snapshot = state.copy();
        for (Listener l : listeners) {
            try {
                l.onStateChanged(snapshot);
            } catch (Throwable t) {
                Log.w(TAG, "listener failed", t);
            }
        }
        main.removeCallbacks(notificationRunnable);
        main.postDelayed(notificationRunnable, 180);
        if (foregroundStarted) updateNotification();
        updateTile();
        WidgetHelper.refreshAll(this);
    }

    private void updateTile() {
        try {
            TileService.requestListeningState(this,
                    new ComponentName(this, DimTileService.class));
        } catch (Throwable ignored) {
        }
    }

    private void maybeStopIfIdle() {
        if (stopping) return;
        if (!state.enabled && clients == 0) {
            Log.i(TAG, "stopping idle service");
            stopSelf();
        }
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    screenOn = false;
                    syncOverlay();
                    if (monitor != null) monitor.stop();
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    screenOn = true;
                    if (state.enabled) {
                        syncOverlay();
                        startMonitorIfNeeded();
                        scheduleApply(700);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    private void startScheduleTick() {
        if (scheduleTickRunning || !prefs.scheduleEnabled()) return;
        scheduleTickRunning = true;
        main.postDelayed(scheduleTick, 30000);
    }

    private void stopScheduleTick() {
        scheduleTickRunning = false;
        main.removeCallbacks(scheduleTick);
    }

    private void evaluateSchedule() {
        if (evaluatingSchedule || stopping) return;
        evaluatingSchedule = true;
        try {
            if (!prefs.scheduleEnabled()) {
                if (prefs.scheduleActive()) restorePreSchedule();
                ScheduleController.cancel(this);
                return;
            }

            long now = System.currentTimeMillis();
            boolean inWindow = ScheduleController.inWindow(prefs, now)
                    && now >= prefs.scheduleSnoozeUntil();

            if (inWindow && !prefs.scheduleActive()) {
                if (!canDim()) return;
                prefs.savePreSchedule(state.enabled, state.dim, state.warmth, state.warmEnabled,
                        state.hwEnabled, state.hw, state.extraEnabled, state.extra);
                prefs.setScheduleActive(true);
                state.enabled = true;
                state.dim = prefs.scheduleDim();
                state.warmth = prefs.scheduleWarm();
                state.warmEnabled = state.warmth > 0;
                state.hwEnabled = true;
                state.hw = prefs.scheduleHw();
                state.extraEnabled = true;
                state.extra = prefs.scheduleExtra();
                ensureForeground();
                syncOverlay();
                startMonitorIfNeeded();
                scheduleApply(80);
                persistAll();
                render();
                Log.i(TAG, "schedule: night on");
            } else if (!inWindow && prefs.scheduleActive()) {
                restorePreSchedule();
                Log.i(TAG, "schedule: night off");
            }
            ScheduleController.scheduleNext(this);
        } finally {
            evaluatingSchedule = false;
        }
    }

    private void restorePreSchedule() {
        if (!prefs.scheduleActive()) return;
        state.enabled = prefs.preEnabled();
        state.dim = prefs.preDim();
        state.warmth = prefs.preWarm();
        state.warmEnabled = prefs.preWarmEnabled();
        state.hwEnabled = prefs.preHwEnabled();
        state.hw = prefs.preHw();
        state.extraEnabled = prefs.preExtraEnabled();
        state.extra = prefs.preExtra();
        prefs.setScheduleActive(false);
        persistAll();
        if (state.enabled) {
            ensureForeground();
            syncOverlay();
            startMonitorIfNeeded();
            scheduleApply(80);
        } else {
            syncOverlay();
            if (monitor != null) monitor.stop();
            stopForegroundNow();
            scheduleApply(0);
        }
        render();
    }

    private void toast(String message) {
        main.post(() -> {
            try {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        });
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
