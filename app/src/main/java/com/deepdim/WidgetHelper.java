package com.deepdim;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.RemoteViews;

/** Общая логика всех виджетов DeepDim: действия, состояние и обновление. */
final class WidgetHelper {

    static final String ACTION_OFF = "com.deepdim.widget.OFF";
    static final String ACTION_READ = "com.deepdim.widget.READ";
    static final String ACTION_NIGHT = "com.deepdim.widget.NIGHT";
    static final String ACTION_CINEMA = "com.deepdim.widget.CINEMA";
    static final String ACTION_MAX = "com.deepdim.widget.MAX";
    static final String ACTION_TOGGLE = "com.deepdim.widget.TOGGLE";
    static final String ACTION_MINUS = "com.deepdim.widget.MINUS";
    static final String ACTION_PLUS = "com.deepdim.widget.PLUS";
    static final String ACTION_OPEN = "com.deepdim.widget.OPEN";

    private static final Class<?>[] PROVIDERS = {
            DimWidgetProvider.class,
            MiniWidgetProvider.class,
            PanelWidgetProvider.class,
            ToggleWidgetProvider.class
    };

    private WidgetHelper() {
    }

    static boolean handle(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return false;
        switch (action) {
            case ACTION_OPEN:
                openApp(context);
                return true;
            case ACTION_OFF:
                DimService.startAction(context, DimService.ACTION_DISABLE);
                refreshDelayed(context);
                return true;
            case ACTION_TOGGLE:
                DimService.startAction(context, isOn(context)
                        ? DimService.ACTION_DISABLE : DimService.ACTION_ENABLE);
                refreshDelayed(context);
                return true;
            case ACTION_MINUS:
                DimService.startNudge(context, -10);
                refreshDelayed(context);
                return true;
            case ACTION_PLUS:
                DimService.startNudge(context, 10);
                refreshDelayed(context);
                return true;
            case ACTION_READ:
                preset(context, Preset.READ);
                return true;
            case ACTION_NIGHT:
                preset(context, Preset.NIGHT);
                return true;
            case ACTION_CINEMA:
                preset(context, Preset.CINEMA);
                return true;
            case ACTION_MAX:
                preset(context, Preset.MAX);
                return true;
            default:
                return false;
        }
    }

    private static void preset(Context context, Preset preset) {
        DimService.startPreset(context, preset.id);
        refreshDelayed(context);
    }

    static boolean isOn(Context context) {
        DimService service = DimService.peek();
        return service != null ? service.getState().enabled : Prefs.get(context).enabled();
    }

    static int percent(Context context) {
        DimService service = DimService.peek();
        return service != null ? service.getState().effectiveDim() : Prefs.get(context).dim();
    }

    static String status(Context context, boolean on, int percent) {
        return context.getString(on
                ? R.string.widget_status_on : R.string.widget_status_off, percent);
    }

    static PendingIntent pending(Context context,
                                 Class<? extends AppWidgetProvider> provider,
                                 String action) {
        Intent intent = new Intent(context, provider).setAction(action);
        int request = (provider.getSimpleName() + ':' + action).hashCode();
        return PendingIntent.getBroadcast(context, request, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void styleButton(Context context, RemoteViews views, int viewId, boolean active) {
        views.setInt(viewId, "setBackgroundResource", active
                ? R.drawable.bg_widget_button_active : R.drawable.bg_widget_button);
        views.setTextColor(viewId, context.getColor(active
                ? R.color.accent : R.color.text));
    }

    static void stylePill(Context context, RemoteViews views, int viewId, boolean active) {
        views.setInt(viewId, "setBackgroundResource", active
                ? R.drawable.bg_widget_button_active : R.drawable.bg_widget_button);
        views.setTextColor(viewId, context.getColor(active
                ? R.color.accent : R.color.text2));
    }

    static void refreshAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) return;
            for (Class<?> provider : PROVIDERS) {
                refresh(manager, context, provider);
            }
        } catch (Throwable ignored) {
        }
    }

    static void refresh(Context context, Class<?> provider) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager != null) refresh(manager, context, provider);
        } catch (Throwable ignored) {
        }
    }

    private static void refresh(AppWidgetManager manager, Context context, Class<?> provider) {
        ComponentName component = new ComponentName(context, provider);
        int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) return;
        for (int id : ids) {
            manager.updateAppWidget(id, build(context, provider));
        }
    }

    static RemoteViews build(Context context, Class<?> provider) {
        if (provider == MiniWidgetProvider.class) return MiniWidgetProvider.build(context);
        if (provider == PanelWidgetProvider.class) return PanelWidgetProvider.build(context);
        if (provider == ToggleWidgetProvider.class) return ToggleWidgetProvider.build(context);
        return DimWidgetProvider.build(context);
    }

    private static void refreshDelayed(Context context) {
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> refreshAll(app), 420);
    }

    private static void openApp(Context context) {
        try {
            context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Throwable ignored) {
        }
    }
}
