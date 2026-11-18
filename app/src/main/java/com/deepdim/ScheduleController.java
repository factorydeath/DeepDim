package com.deepdim;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;
import java.util.Locale;

/** Ночное окно: вычисление границ и точный будильник. */
public final class ScheduleController {

    public static final String ACTION_ALARM = "com.deepdim.action.SCHEDULE_ALARM";
    private static final int REQUEST_CODE = 0x0D1A;

    private ScheduleController() {
    }

    public static boolean inWindow(Prefs p, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return inWindow(p.scheduleStart(), p.scheduleEnd(), minutes);
    }

    public static boolean inWindow(int start, int end, int minutes) {
        if (start == end) return false;
        if (start < end) return minutes >= start && minutes < end;
        return minutes >= start || minutes < end;
    }

    /** Ближайшая точка старта или конца окна. */
    public static long nextBoundaryMillis(Prefs p, long now) {
        long start = nextOccurrence(p.scheduleStart(), false, now);
        long end = nextOccurrence(p.scheduleEnd(), true, now);
        return Math.min(start, end);
    }

    /** Конец текущего ночного окна (или 0, если мы не в нём). */
    public static long currentWindowEndMillis(Prefs p, long now) {
        if (!inWindow(p, now)) return 0L;
        long end = nextOccurrence(p.scheduleEnd(), true, now);
        return end > now ? end : 0L;
    }

    private static long nextOccurrence(int minutes, boolean end, long now) {
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(now);
        long best = Long.MAX_VALUE;
        for (int offset = -1; offset <= 2; offset++) {
            Calendar c = (Calendar) base.clone();
            c.add(Calendar.DAY_OF_YEAR, offset);
            c.set(Calendar.HOUR_OF_DAY, minutes / 60);
            c.set(Calendar.MINUTE, minutes % 60);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (end && minutes <= 0) {
                // полночь как конец окна
            }
            long t = c.getTimeInMillis();
            if (t > now && t < best) best = t;
        }
        // Если конец окна раньше старта (например 07:00 после 22:00),
        // то конец всегда лежит в следующих сутках после старта.
        return best == Long.MAX_VALUE ? now + 24L * 60L * 60L * 1000L : best;
    }

    public static void scheduleNext(Context context) {
        Prefs p = Prefs.get(context);
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent intent = new Intent(context, ScheduleReceiver.class).setAction(ACTION_ALARM);
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (!p.scheduleEnabled()) {
            am.cancel(pi);
            return;
        }

        long at = nextBoundaryMillis(p, System.currentTimeMillis());
        try {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent intent = new Intent(context, ScheduleReceiver.class).setAction(ACTION_ALARM);
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
    }

    public static boolean exactAlarmsAllowed(Context context) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static String format(int minutes) {
        return String.format(Locale.getDefault(), "%02d:%02d", minutes / 60, minutes % 60);
    }
}
