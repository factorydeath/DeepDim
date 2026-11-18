package com.deepdim;

import android.content.Context;

/** Полный снимок состояния затемнения для UI и сервиса. */
public final class DimState {
    public boolean enabled;
    public int dim;
    public int warmth;
    public boolean warmEnabled;
    public boolean hwEnabled;
    public int hw;
    public boolean extraEnabled;
    public int extra;

    /** Доп. затемнение активного приложения (0, если правила нет). */
    public int appBoost;
    public String activePackage = "";

    public static DimState fromPrefs(Context context) {
        Prefs p = Prefs.get(context);
        DimState s = new DimState();
        s.enabled = p.enabled();
        s.dim = p.dim();
        s.warmth = p.warmth();
        s.warmEnabled = p.warmEnabled();
        s.hwEnabled = p.hwEnabled();
        s.hw = p.hw();
        s.extraEnabled = p.extraEnabled();
        s.extra = p.extra();
        return s;
    }

    public DimState copy() {
        DimState s = new DimState();
        s.enabled = enabled;
        s.dim = dim;
        s.warmth = warmth;
        s.warmEnabled = warmEnabled;
        s.hwEnabled = hwEnabled;
        s.hw = hw;
        s.extraEnabled = extraEnabled;
        s.extra = extra;
        s.appBoost = appBoost;
        s.activePackage = activePackage;
        return s;
    }

    public int effectiveDim() {
        return Math.max(0, Math.min(95, dim + appBoost));
    }

    public int effectiveWarmth() {
        return warmEnabled ? Math.max(0, Math.min(100, warmth)) : 0;
    }
}
