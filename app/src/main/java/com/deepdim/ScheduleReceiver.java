package com.deepdim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Точный будильник границы ночного окна. */
public final class ScheduleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ScheduleController.ACTION_ALARM.equals(intent.getAction())) return;

        final Context app = context.getApplicationContext();
        final Prefs prefs = Prefs.get(app);
        if (!prefs.scheduleEnabled()) {
            ScheduleController.cancel(app);
            return;
        }

        long now = System.currentTimeMillis();
        boolean inWindow = ScheduleController.inWindow(prefs, now);
        boolean needService = inWindow || prefs.scheduleActive() || DimService.peek() != null;

        if (needService) {
            DimService.startAction(app, DimService.ACTION_SCHEDULE);
        }
        ScheduleController.scheduleNext(app);
    }
}
