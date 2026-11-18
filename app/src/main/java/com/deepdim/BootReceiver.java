package com.deepdim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Восстанавливает затемнение после перезагрузки или обновления приложения. */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        final Context app = context.getApplicationContext();
        final Prefs prefs = Prefs.get(app);
        if (prefs.scheduleEnabled()) {
            ScheduleController.scheduleNext(app);
        }

        if (!prefs.enabled()) {
            // Убираем возможные «хвосты» яркости после сбоя.
            Thread t = new Thread(() -> BrightnessEngine.restoreStale(app), "deepdim-boot-restore");
            t.setDaemon(true);
            t.start();
            return;
        }

        if (!prefs.startOnBoot()) {
            prefs.setEnabled(false);
            prefs.setScheduleActive(false);
            Thread t = new Thread(() -> BrightnessEngine.restoreStale(app), "deepdim-boot-restore");
            t.setDaemon(true);
            t.start();
            return;
        }

        DimService.startAction(app, DimService.ACTION_START);
    }
}
