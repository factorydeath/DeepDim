package com.deepdim;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.List;

/**
 * Применяет и откатывает аппаратные слои: системную яркость, sysfs-подсветку
 * и системный Reduce Bright Colors (Extra Dim). Все методы синхронные и
 * должны вызываться из одного фонового потока.
 */
public final class BrightnessEngine {

    private static final String TAG = "DeepDim/Brightness";

    private final Context context;
    private final Prefs prefs;
    private final boolean root;

    public BrightnessEngine(Context context, Prefs prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.root = RootShell.isAvailable();
    }

    public boolean hasRoot() {
        return root;
    }

    public synchronized void applyAll(DimState state) {
        if (!state.enabled) {
            restoreAll();
            return;
        }

        boolean useSysfs = root && prefs.sysfsEnabled()
                && !Backlight.nodes(false).isEmpty();

        if (state.hwEnabled) {
            if (useSysfs) {
                if (!"sysfs".equals(prefs.hwMethod())) {
                    restoreSystem();
                }
                if (writeSysfs(state.hw)) {
                    prefs.setHwMethod("sysfs");
                } else {
                    applySystem(state.hw);
                    prefs.setHwMethod("system");
                }
            } else {
                if ("sysfs".equals(prefs.hwMethod())) {
                    restoreSysfs();
                }
                applySystem(state.hw);
                prefs.setHwMethod("system");
            }
        } else {
            restoreSystem();
            restoreSysfs();
        }

        if (state.extraEnabled && root && state.extra > 0) {
            applyExtra(state.extra);
        } else {
            restoreExtra();
        }
        prefs.setHwMethod(state.hwEnabled ? prefs.hwMethod() : "");
    }

    public synchronized void restoreAll() {
        restoreSystem();
        restoreSysfs();
        restoreExtra();
        prefs.setHwMethod("");
    }

    public static void restoreStale(Context context) {
        Prefs prefs = Prefs.get(context);
        if (!prefs.journalDirty()) return;
        Log.i(TAG, "restoreStale: откатываем яркость после сбоя/перезагрузки");
        new BrightnessEngine(context, prefs).restoreAll();
    }

    // ------------------------------------------------------------ system

    private void captureSystem() {
        if (prefs.origSys() >= 0) return;
        ContentResolver cr = context.getContentResolver();
        int brightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
        int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        prefs.setOrigSys(clamp(brightness, 0, 255), mode);
        Log.d(TAG, "captured system brightness=" + brightness + " mode=" + mode);
    }

    private boolean applySystem(int percent) {
        percent = clamp(percent, 0, 100);
        captureSystem();
        int value = Math.round(percent * 255f / 100f);

        boolean ok = false;
        if (root) {
            RootShell.Result r = RootShell.run(
                    "settings put system screen_brightness_mode 0\n"
                            + "settings put system screen_brightness " + value,
                    9000);
            ok = r.ok();
        }

        if (Settings.System.canWrite(context)) {
            try {
                Settings.System.putInt(context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(context.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, value);
                ok = true;
            } catch (Throwable t) {
                Log.w(TAG, "write settings failed", t);
            }
        }

        // На Android 16.1+ это современный путь и он умеет Even Dimmer на 0%.
        if (ok || Settings.System.canWrite(context)) {
            tryDisplayManagerPercent(percent);
        }
        Log.d(TAG, "applySystem percent=" + percent + " value=" + value + " ok=" + ok);
        return ok;
    }

    private void restoreSystem() {
        int original = prefs.origSys();
        if (original < 0) return;
        int mode = prefs.origMode();
        if (mode < 0) mode = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;

        boolean ok = false;
        if (root) {
            RootShell.Result r = RootShell.run(
                    "settings put system screen_brightness_mode " + mode + "\n"
                            + "settings put system screen_brightness " + original,
                    9000);
            ok = r.ok();
        }

        if (Settings.System.canWrite(context)) {
            try {
                if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    Settings.System.putInt(context.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                } else {
                    Settings.System.putInt(context.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS, original);
                    Settings.System.putInt(context.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                }
                ok = true;
            } catch (Throwable t) {
                Log.w(TAG, "restore settings failed", t);
            }
        }

        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
            tryDisplayManagerPercent(Math.round(original * 100f / 255f));
        }

        if (ok || root) prefs.setOrigSys(-1, -1);
        Log.d(TAG, "restoreSystem original=" + original + " mode=" + mode + " ok=" + ok);
    }

    private boolean tryDisplayManagerPercent(int percent) {
        if (Build.VERSION.SDK_INT < 36) return false;
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) return false;
            dm.setBrightness(Display.DEFAULT_DISPLAY, clamp(percent, 0, 100),
                    DisplayManager.BRIGHTNESS_UNIT_PERCENTAGE);
            return true;
        } catch (Throwable t) {
            Log.d(TAG, "DisplayManager.setBrightness недоступен: " + t);
            return false;
        }
    }

    public String readSystemInfo() {
        ContentResolver cr = context.getContentResolver();
        int brightness = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1);
        int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
        String dm = "";
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                if (manager != null) {
                    float p = manager.getBrightness(Display.DEFAULT_DISPLAY,
                            DisplayManager.BRIGHTNESS_UNIT_PERCENTAGE);
                    dm = ", display=" + p + "%";
                }
            } catch (Throwable ignored) {
            }
        }
        return "screen_brightness=" + brightness + ", mode=" + mode + dm;
    }

    // ------------------------------------------------------------- sysfs

    private void captureSysfs() {
        if (!prefs.origSysfs().isEmpty()) return;
        List<Backlight.Node> nodes = Backlight.nodes(true);
        if (nodes.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject();
            for (Backlight.Node n : nodes) {
                obj.put(n.path, n.current);
            }
            prefs.setOrigSysfs(obj.toString());
            Log.d(TAG, "captured sysfs " + obj);
        } catch (Exception e) {
            Log.w(TAG, "captureSysfs", e);
        }
    }

    private boolean writeSysfs(int percent) {
        List<Backlight.Node> nodes = Backlight.nodes(false);
        if (nodes.isEmpty()) return false;
        captureSysfs();
        int success = 0;
        for (Backlight.Node n : nodes) {
            int got = Backlight.write(n.path, n.max, percent);
            if (got >= 0) success++;
        }
        Log.d(TAG, "writeSysfs " + percent + "% success=" + success + "/" + nodes.size());
        return success > 0;
    }

    private void restoreSysfs() {
        String raw = prefs.origSysfs();
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(raw);
            List<Backlight.Node> nodes = Backlight.nodes(false);
            Iterator<String> keys = obj.keys();
            boolean allOk = true;
            while (keys.hasNext()) {
                String path = keys.next();
                int value = obj.getInt(path);
                int max = -1;
                for (Backlight.Node n : nodes) {
                    if (n.path.equals(path)) {
                        max = n.max;
                        break;
                    }
                }
                if (max <= 0) continue;
                if (Backlight.writeRaw(path, value) < 0) allOk = false;
            }
            if (allOk) prefs.setOrigSysfs("");
        } catch (Exception e) {
            Log.w(TAG, "restoreSysfs", e);
        }
    }

    // ------------------------------------------------------------- extra

    private void captureExtra() {
        if (!prefs.origExtraLevel().isEmpty()) return;
        RootShell.Result r = RootShell.run(
                "echo ACT=$(settings get secure reduce_bright_colors_activated)\n"
                        + "echo LVL=$(settings get secure reduce_bright_colors_level)",
                9000);
        String act = "0";
        String lvl = "null";
        for (String line : r.out.split("\n")) {
            line = line.trim();
            if (line.startsWith("ACT=")) act = line.substring(4).trim();
            if (line.startsWith("LVL=")) lvl = line.substring(4).trim();
        }
        if (lvl.isEmpty()) lvl = "null";
        if (act.isEmpty()) act = "0";
        prefs.setOrigExtra(lvl, act);
        Log.d(TAG, "captured extra act=" + act + " level=" + lvl);
    }

    private boolean applyExtra(int level) {
        captureExtra();
        level = clamp(level, 0, 100);
        RootShell.Result r = RootShell.run(
                "settings put secure reduce_bright_colors_level " + level + "\n"
                        + "settings put secure reduce_bright_colors_activated 1\n"
                        + "settings get secure reduce_bright_colors_activated",
                9000);
        String last = "";
        String[] lines = r.out.split("\n");
        if (lines.length > 0) last = lines[lines.length - 1].trim();
        boolean ok = "1".equals(last);
        Log.d(TAG, "applyExtra level=" + level + " ok=" + ok + " out=" + r.out);
        return ok;
    }

    private void restoreExtra() {
        String level = prefs.origExtraLevel();
        if (level == null || level.isEmpty()) return;
        String act = prefs.origExtraActivated();
        if (act == null || act.isEmpty()) act = "0";
        StringBuilder cmd = new StringBuilder();
        if (!"null".equals(level) && !level.isEmpty()) {
            cmd.append("settings put secure reduce_bright_colors_level ").append(level).append('\n');
        }
        cmd.append("settings put secure reduce_bright_colors_activated ")
                .append("1".equals(act) ? "1" : "0");
        RootShell.Result r = RootShell.run(cmd.toString(), 9000);
        if (r.ok()) prefs.setOrigExtra("", "");
        Log.d(TAG, "restoreExtra act=" + act + " level=" + level + " ok=" + r.ok());
    }

    public String readExtraInfo() {
        if (!root) return "secure settings недоступны без root";
        RootShell.Result r = RootShell.run(
                "echo -n 'activated='; settings get secure reduce_bright_colors_activated\n"
                        + "echo -n 'level='; settings get secure reduce_bright_colors_level",
                9000);
        return r.out.replace('\n', ' ');
    }

    public boolean root() {
        return root;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
