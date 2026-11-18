package com.deepdim;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Единое хранилище настроек и журнала изменений яркости. */
public final class Prefs {

    private static volatile Prefs sInstance;

    private final SharedPreferences sp;

    private Prefs(Context context) {
        sp = context.getApplicationContext()
                .getSharedPreferences("deepdim", Context.MODE_PRIVATE);
    }

    public static Prefs get(Context context) {
        Prefs p = sInstance;
        if (p == null) {
            synchronized (Prefs.class) {
                p = sInstance;
                if (p == null) {
                    p = new Prefs(context);
                    sInstance = p;
                }
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ main

    public boolean enabled() { return sp.getBoolean("enabled", false); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    public int dim() { return sp.getInt("dim", 50); }
    public void setDim(int v) { sp.edit().putInt("dim", clamp(v, 0, 95)).apply(); }

    public int warmth() { return sp.getInt("warmth", 0); }
    public void setWarmth(int v) { sp.edit().putInt("warmth", clamp(v, 0, 100)).apply(); }

    public boolean warmEnabled() { return sp.getBoolean("warm_enabled", true); }
    public void setWarmEnabled(boolean v) { sp.edit().putBoolean("warm_enabled", v).apply(); }

    public boolean hwEnabled() { return sp.getBoolean("hw_enabled", true); }
    public void setHwEnabled(boolean v) { sp.edit().putBoolean("hw_enabled", v).apply(); }

    public int hw() { return sp.getInt("hw", 15); }
    public void setHw(int v) { sp.edit().putInt("hw", clamp(v, 0, 100)).apply(); }

    public boolean extraEnabled() { return sp.getBoolean("extra_enabled", true); }
    public void setExtraEnabled(boolean v) { sp.edit().putBoolean("extra_enabled", v).apply(); }

    public int extra() { return sp.getInt("extra", 60); }
    public void setExtra(int v) { sp.edit().putInt("extra", clamp(v, 0, 100)).apply(); }

    public String lastPreset() { return sp.getString("last_preset", ""); }
    public void setLastPreset(String v) { sp.edit().putString("last_preset", v == null ? "" : v).apply(); }

    // -------------------------------------------------------------- behavior

    public boolean startOnBoot() { return sp.getBoolean("start_on_boot", true); }
    public void setStartOnBoot(boolean v) { sp.edit().putBoolean("start_on_boot", v).apply(); }

    public boolean showHandle() { return sp.getBoolean("show_handle", true); }
    public void setShowHandle(boolean v) { sp.edit().putBoolean("show_handle", v).apply(); }

    public boolean volumeKeys() { return sp.getBoolean("volume_keys", false); }
    public void setVolumeKeys(boolean v) { sp.edit().putBoolean("volume_keys", v).apply(); }

    public boolean sysfsEnabled() { return sp.getBoolean("sysfs_enabled", false); }
    public void setSysfsEnabled(boolean v) { sp.edit().putBoolean("sysfs_enabled", v).apply(); }

    public boolean haptics() { return sp.getBoolean("haptics", true); }
    public void setHaptics(boolean v) { sp.edit().putBoolean("haptics", v).apply(); }

    public boolean cleanScreenshots() { return sp.getBoolean("clean_screenshots", false); }
    public void setCleanScreenshots(boolean v) {
        sp.edit().putBoolean("clean_screenshots", v).apply();
    }

    // ------------------------------------------------------------ sleep timer

    public long sleepUntil() { return sp.getLong("sleep_until", 0L); }
    public void setSleepUntil(long v) { sp.edit().putLong("sleep_until", Math.max(0L, v)).apply(); }

    public int sleepMinutes() { return sp.getInt("sleep_minutes", 0); }
    public void setSleepMinutes(int v) { sp.edit().putInt("sleep_minutes", Math.max(0, v)).apply(); }

    // -------------------------------------------------------------- schedule

    public boolean scheduleEnabled() { return sp.getBoolean("schedule_enabled", false); }
    public void setScheduleEnabled(boolean v) { sp.edit().putBoolean("schedule_enabled", v).apply(); }

    public int scheduleStart() { return sp.getInt("schedule_start", 22 * 60); }
    public void setScheduleStart(int v) { sp.edit().putInt("schedule_start", clamp(v, 0, 1439)).apply(); }

    public int scheduleEnd() { return sp.getInt("schedule_end", 7 * 60); }
    public void setScheduleEnd(int v) { sp.edit().putInt("schedule_end", clamp(v, 0, 1439)).apply(); }

    public int scheduleDim() { return sp.getInt("schedule_dim", 65); }
    public int scheduleWarm() { return sp.getInt("schedule_warm", 25); }
    public int scheduleHw() { return sp.getInt("schedule_hw", 6); }
    public int scheduleExtra() { return sp.getInt("schedule_extra", 70); }

    public boolean scheduleActive() { return sp.getBoolean("schedule_active", false); }
    public void setScheduleActive(boolean v) { sp.edit().putBoolean("schedule_active", v).apply(); }

    public long scheduleSnoozeUntil() { return sp.getLong("schedule_snooze_until", 0L); }
    public void setScheduleSnoozeUntil(long v) { sp.edit().putLong("schedule_snooze_until", v).apply(); }

    public void savePreSchedule(boolean enabled, int dim, int warm, boolean warmEn,
                                boolean hwEn, int hw, boolean extraEn, int extra) {
        sp.edit()
                .putBoolean("pre_enabled", enabled)
                .putInt("pre_dim", dim)
                .putInt("pre_warm", warm)
                .putBoolean("pre_warm_enabled", warmEn)
                .putBoolean("pre_hw_enabled", hwEn)
                .putInt("pre_hw", hw)
                .putBoolean("pre_extra_enabled", extraEn)
                .putInt("pre_extra", extra)
                .apply();
    }

    public boolean preEnabled() { return sp.getBoolean("pre_enabled", false); }
    public int preDim() { return sp.getInt("pre_dim", 50); }
    public int preWarm() { return sp.getInt("pre_warm", 0); }
    public boolean preWarmEnabled() { return sp.getBoolean("pre_warm_enabled", true); }
    public boolean preHwEnabled() { return sp.getBoolean("pre_hw_enabled", true); }
    public int preHw() { return sp.getInt("pre_hw", 15); }
    public boolean preExtraEnabled() { return sp.getBoolean("pre_extra_enabled", true); }
    public int preExtra() { return sp.getInt("pre_extra", 60); }

    // --------------------------------------------------------------- journal

    public int origSys() { return sp.getInt("orig_sys", -1); }
    public int origMode() { return sp.getInt("orig_mode", -1); }
    public void setOrigSys(int brightness, int mode) {
        sp.edit().putInt("orig_sys", brightness).putInt("orig_mode", mode).apply();
    }

    public String origSysfs() { return sp.getString("orig_sysfs", ""); }
    public void setOrigSysfs(String json) { sp.edit().putString("orig_sysfs", json == null ? "" : json).apply(); }

    public String hwMethod() { return sp.getString("hw_method", ""); }
    public void setHwMethod(String m) { sp.edit().putString("hw_method", m == null ? "" : m).apply(); }

    public String origExtraLevel() { return sp.getString("orig_extra_level", ""); }
    public String origExtraActivated() { return sp.getString("orig_extra_activated", ""); }
    public void setOrigExtra(String level, String activated) {
        sp.edit()
                .putString("orig_extra_level", level == null ? "" : level)
                .putString("orig_extra_activated", activated == null ? "" : activated)
                .apply();
    }

    public boolean journalDirty() {
        return origSys() >= 0 || !origSysfs().isEmpty() || !origExtraLevel().isEmpty();
    }

    public void clearJournal() {
        sp.edit()
                .remove("orig_sys")
                .remove("orig_mode")
                .remove("orig_sysfs")
                .remove("hw_method")
                .remove("orig_extra_level")
                .remove("orig_extra_activated")
                .apply();
    }

    // ----------------------------------------------------------------- rules

    public Map<String, AppRule> rules() {
        Map<String, AppRule> out = new LinkedHashMap<>();
        String raw = sp.getString("rules", "{}");
        try {
            JSONObject obj = new JSONObject(raw);
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String pkg = it.next();
                JSONObject o = obj.optJSONObject(pkg);
                if (o == null) continue;
                out.put(pkg, new AppRule(pkg, o.optInt("boost", 15), o.optInt("warm", 10)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public void putRule(AppRule rule) {
        Map<String, AppRule> all = rules();
        all.put(rule.pkg, rule);
        saveRules(all);
    }

    public void removeRule(String pkg) {
        Map<String, AppRule> all = rules();
        all.remove(pkg);
        saveRules(all);
    }

    private void saveRules(Map<String, AppRule> all) {
        JSONObject obj = new JSONObject();
        try {
            for (AppRule r : all.values()) {
                JSONObject o = new JSONObject();
                o.put("boost", clamp(r.boost, 0, 60));
                o.put("warm", clamp(r.warm, 0, 60));
                obj.put(r.pkg, o);
            }
        } catch (JSONException ignored) {
        }
        sp.edit().putString("rules", obj.toString()).apply();
    }

    // ----------------------------------------------------------------- misc

    public void clearAll() {
        sp.edit().clear().apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
