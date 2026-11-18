package com.deepdim;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Проверка root и автоматическая выдача app-op разрешений. */
public final class RootSetup {

    public static final class Status {
        public final boolean root;
        public final boolean granted;
        public final String log;

        Status(boolean root, boolean granted, String log) {
            this.root = root;
            this.granted = granted;
            this.log = log == null ? "" : log;
        }
    }

    public interface Callback {
        void onDone(Status status);
    }

    private RootSetup() {
    }

    public static void run(Context context, Callback callback) {
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> {
            boolean root = RootShell.isAvailable();
            boolean granted = false;
            String log = root ? "root: uid=0" : "su недоступен";
            if (root) {
                String pkg = app.getPackageName();
                String cmd = "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow\n"
                        + "appops set " + pkg + " WRITE_SETTINGS allow\n"
                        + "appops set " + pkg + " GET_USAGE_STATS allow\n"
                        + "appops set " + pkg + " SCHEDULE_EXACT_ALARM allow\n"
                        + "pm grant " + pkg + " android.permission.POST_NOTIFICATIONS 2>/dev/null\n"
                        + "echo DEEPDIM_GRANT_DONE";
                RootShell.Result r = RootShell.run(cmd, 25000);
                granted = r.out.contains("DEEPDIM_GRANT_DONE");
                log = r.out;
            }
            final Status status = new Status(root, granted, log);
            new Handler(Looper.getMainLooper()).post(() -> callback.onDone(status));
        }, "deepdim-root-setup");
        t.setDaemon(true);
        t.start();
    }
}
