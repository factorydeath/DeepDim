package com.deepdim;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Полный отчёт о root, подсветке, правах и состоянии службы. */
public final class DiagnosticsActivity extends Activity {

    private TextView diagStatus;
    private TextView diagText;
    private TextView btnRun;
    private TextView btnCopy;

    private Prefs prefs;
    private boolean running;
    private String lastReport = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        Ui.edgeToEdge(this);
        prefs = Prefs.get(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        diagStatus = findViewById(R.id.diagStatus);
        diagText = findViewById(R.id.diagText);
        btnRun = findViewById(R.id.btnRun);
        btnCopy = findViewById(R.id.btnCopy);

        btnRun.setOnClickListener(v -> runDiagnostics());
        btnCopy.setOnClickListener(v -> copyReport());

        runDiagnostics();
    }

    private void runDiagnostics() {
        if (running) return;
        running = true;
        btnRun.setEnabled(false);
        btnRun.setAlpha(0.55f);
        diagStatus.setText(R.string.diag_progress);
        diagText.setText("");

        Thread thread = new Thread(() -> {
            String report;
            try {
                report = buildReport();
            } catch (Throwable t) {
                report = getString(R.string.diag_failed) + "\n\n" + t;
            }
            final String result = report;
            runOnUiThread(() -> {
                lastReport = result;
                diagText.setText(result);
                diagStatus.setText(R.string.diag_ready);
                btnRun.setEnabled(true);
                btnRun.setAlpha(1f);
                running = false;
            });
        }, "deepdim-diagnostics");
        thread.setDaemon(true);
        thread.start();
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder(4096);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());

        sb.append("DeepDim diagnostics\n");
        sb.append("time: ").append(time).append("\n\n");

        sb.append("== device ==\n");
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("patch: ").append(Build.VERSION.SECURITY_PATCH).append('\n');
        if (Build.SUPPORTED_ABIS.length > 0) {
            sb.append("abi: ").append(Build.SUPPORTED_ABIS[0]).append('\n');
        }
        sb.append("app: ").append(appVersion()).append("\n\n");

        boolean root = RootShell.isAvailable();
        sb.append("== root ==\n");
        sb.append("available: ").append(root ? "yes" : "no").append('\n');
        sb.append("su binary: ").append(root ? "ok" : "not found").append("\n\n");

        if (root) {
            String pkg = getPackageName();
            String command = "echo '== id =='\n"
                    + "id\n"
                    + "echo '== selinux =='\n"
                    + "getenforce 2>/dev/null || echo unknown\n"
                    + "echo '== appops =='\n"
                    + "appops get " + pkg + " SYSTEM_ALERT_WINDOW 2>&1\n"
                    + "appops get " + pkg + " WRITE_SETTINGS 2>&1\n"
                    + "appops get " + pkg + " GET_USAGE_STATS 2>&1\n"
                    + "appops get " + pkg + " SCHEDULE_EXACT_ALARM 2>&1\n"
                    + "echo '== secure settings =='\n"
                    + "settings get system screen_brightness\n"
                    + "settings get system screen_brightness_mode\n"
                    + "settings get secure reduce_bright_colors_activated\n"
                    + "settings get secure reduce_bright_colors_level\n"
                    + "echo '== display =='\n"
                    + "dumpsys display 2>/dev/null | grep -iE 'brightness' | head -n 8\n";
            RootShell.Result result = RootShell.run(command, 25000);
            sb.append("== root shell ==\n");
            sb.append(result.out.isEmpty() ? getString(R.string.diag_empty) : result.out)
                    .append("\n\n");
        }

        sb.append("== backlight ==\n");
        if (root) {
            List<Backlight.Node> nodes = Backlight.nodes(true);
            if (nodes.isEmpty()) {
                sb.append(getString(R.string.diag_no_backlight)).append('\n');
            } else {
                for (Backlight.Node node : nodes) {
                    int percent = node.max > 0
                            ? Math.round(node.current * 100f / node.max) : 0;
                    sb.append(node.path)
                            .append("  current=").append(node.current)
                            .append("  max=").append(node.max)
                            .append("  (").append(percent).append("%)\n");
                }
            }
        } else {
            sb.append(getString(R.string.diag_root_required)).append('\n');
        }
        sb.append('\n');

        sb.append("== brightness ==\n");
        BrightnessEngine engine = new BrightnessEngine(this, prefs);
        sb.append(engine.readSystemInfo()).append('\n');
        if (root) sb.append(engine.readExtraInfo()).append('\n');
        sb.append('\n');

        sb.append("== permissions ==\n");
        sb.append("overlay: ").append(Settings.canDrawOverlays(this) ? "yes" : "no").append('\n');
        sb.append("write_settings: ")
                .append(Settings.System.canWrite(this) ? "yes" : "no").append('\n');
        sb.append("usage_stats: ").append(usageAccess() ? "yes" : "no").append('\n');
        sb.append("notifications: ").append(notificationPermission() ? "yes" : "no").append('\n');
        sb.append("accessibility: ")
                .append(Ui.accessibilityEnabled(this) ? "yes" : "no").append('\n');
        sb.append('\n');

        DimService service = DimService.peek();
        DimState state = service != null ? service.getState() : DimState.fromPrefs(this);
        sb.append("== service ==\n");
        sb.append("instance: ").append(service != null ? "running" : "not running").append('\n');
        sb.append("active: ").append(state.enabled ? "yes" : "no").append('\n');
        sb.append("veil_engine: ").append(VolumeKeyService.overlayConnected()
                ? "accessibility" : "application").append('\n');
        sb.append("dim=").append(state.dim)
                .append(" app_boost=").append(state.appBoost)
                .append(" active_pkg=")
                .append(state.activePackage == null || state.activePackage.isEmpty()
                        ? "-" : state.activePackage)
                .append('\n');
        sb.append("warm=").append(state.warmth)
                .append(" enabled=").append(state.warmEnabled).append('\n');
        sb.append("hw=").append(state.hw)
                .append(" enabled=").append(state.hwEnabled)
                .append(" method=").append(prefs.hwMethod().isEmpty()
                        ? "-" : prefs.hwMethod())
                .append('\n');
        sb.append("extra=").append(state.extra)
                .append(" enabled=").append(state.extraEnabled).append("\n\n");

        sb.append("== schedule ==\n");
        sb.append("enabled: ").append(prefs.scheduleEnabled()).append('\n');
        sb.append("active: ").append(prefs.scheduleActive()).append('\n');
        sb.append("window: ")
                .append(ScheduleController.format(prefs.scheduleStart()))
                .append(" — ")
                .append(ScheduleController.format(prefs.scheduleEnd()))
                .append('\n');
        sb.append("exact_alarms: ")
                .append(ScheduleController.exactAlarmsAllowed(this) ? "yes" : "no")
                .append("\n\n");

        long sleepUntil = prefs.sleepUntil();
        long now = System.currentTimeMillis();
        sb.append("== sleep timer ==\n");
        if (sleepUntil > now) {
            sb.append("remaining: ")
                    .append(Math.max(1L, (sleepUntil - now + 59999L) / 60000L))
                    .append(" min\n");
        } else {
            sb.append("remaining: -\n");
        }
        sb.append('\n');

        sb.append("== journal ==\n");
        sb.append("orig_system: ").append(prefs.origSys())
                .append(" (mode ").append(prefs.origMode()).append(")\n");
        sb.append("orig_sysfs: ")
                .append(prefs.origSysfs().isEmpty() ? "-" : prefs.origSysfs()).append('\n');
        sb.append("orig_extra: ")
                .append(prefs.origExtraLevel().isEmpty() ? "-" : prefs.origExtraLevel())
                .append(" / ")
                .append(prefs.origExtraActivated().isEmpty()
                        ? "-" : prefs.origExtraActivated())
                .append('\n');
        sb.append("dirty: ").append(prefs.journalDirty()).append('\n');
        sb.append("rules: ").append(prefs.rules().size()).append('\n');

        return sb.toString();
    }

    private boolean usageAccess() {
        try {
            android.app.AppOpsManager ops = getSystemService(android.app.AppOpsManager.class);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean notificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void copyReport() {
        if (lastReport.isEmpty()) {
            Ui.toast(this, getString(R.string.diag_running));
            return;
        }
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("DeepDim diagnostics", lastReport));
                Ui.toast(this, getString(R.string.diag_copied));
            }
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.diag_failed));
        }
    }
}
