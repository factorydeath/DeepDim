package com.deepdim;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/** Определяет активное приложение через UsageStats (root выдаёт app-op автоматически). */
public final class ForegroundMonitor {

    public interface Callback {
        void onForeground(String pkg);
    }

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final UsageStatsManager usage;
    private boolean running;
    private String lastPackage = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            String pkg = query();
            if (pkg != null && !pkg.isEmpty() && !pkg.equals(lastPackage)) {
                lastPackage = pkg;
                callback.onForeground(pkg);
            }
            handler.postDelayed(this, 2500);
        }
    };

    public ForegroundMonitor(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.usage = (UsageStatsManager) this.context.getSystemService(Context.USAGE_STATS_SERVICE);
    }

    public boolean hasPermission() {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    public void start() {
        if (running || usage == null || !hasPermission()) return;
        running = true;
        lastPackage = "";
        handler.post(tick);
    }

    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    private String query() {
        long now = System.currentTimeMillis();
        try {
            UsageEvents events = usage.queryEvents(now - 8000L, now);
            if (events == null) return null;
            UsageEvents.Event event = new UsageEvents.Event();
            String pkg = null;
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                int type = event.getEventType();
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                        || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    pkg = event.getPackageName();
                }
            }
            return pkg;
        } catch (Throwable t) {
            return null;
        }
    }
}
