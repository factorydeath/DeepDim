package com.deepdim;

/** Готовые настроения. */
public enum Preset {
    OFF("off", R.string.preset_off, R.drawable.ic_close,
            false, 0, 0, 0, 0, false, false),
    READ("read", R.string.preset_read, R.drawable.ic_book,
            true, 30, 12, 28, 25, true, true),
    NIGHT("night", R.string.preset_night, R.drawable.ic_moon,
            true, 65, 25, 6, 70, true, true),
    CINEMA("cinema", R.string.preset_cinema, R.drawable.ic_film,
            true, 78, 8, 0, 85, true, true),
    MAX("max", R.string.preset_max, R.drawable.ic_flame,
            true, 92, 0, 0, 100, true, false);

    public final String id;
    public final int labelRes;
    public final int iconRes;
    public final boolean on;
    public final int dim;
    public final int warmth;
    public final int hw;
    public final int extra;
    public final boolean hwEnabled;
    public final boolean warmEnabled;

    Preset(String id, int labelRes, int iconRes, boolean on, int dim, int warmth,
           int hw, int extra, boolean hwEnabled, boolean warmEnabled) {
        this.id = id;
        this.labelRes = labelRes;
        this.iconRes = iconRes;
        this.on = on;
        this.dim = dim;
        this.warmth = warmth;
        this.hw = hw;
        this.extra = extra;
        this.hwEnabled = hwEnabled;
        this.warmEnabled = warmEnabled;
    }

    public boolean extraEnabled() {
        return extra > 0;
    }

    public static Preset byId(String id) {
        if (id != null) {
            for (Preset p : values()) {
                if (p.id.equals(id)) return p;
            }
        }
        return NIGHT;
    }
}
