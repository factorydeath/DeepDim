package com.deepdim;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Виджет с пятью быстрыми пресетами. */
public final class DimWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_OFF = WidgetHelper.ACTION_OFF;
    public static final String ACTION_READ = WidgetHelper.ACTION_READ;
    public static final String ACTION_NIGHT = WidgetHelper.ACTION_NIGHT;
    public static final String ACTION_CINEMA = WidgetHelper.ACTION_CINEMA;
    public static final String ACTION_MAX = WidgetHelper.ACTION_MAX;
    public static final String ACTION_OPEN = WidgetHelper.ACTION_OPEN;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!WidgetHelper.handle(context, intent)) {
            super.onReceive(context, intent);
        }
    }

    /** Вызывается сервисом при каждом изменении состояния. */
    public static void refresh(Context context) {
        WidgetHelper.refresh(context, DimWidgetProvider.class);
    }

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_deepdim);
        boolean on = WidgetHelper.isOn(context);
        int percent = WidgetHelper.percent(context);

        views.setTextViewText(R.id.widgetStatus,
                WidgetHelper.status(context, on, percent));

        String lastPreset = Prefs.get(context).lastPreset();
        WidgetHelper.styleButton(context, views, R.id.widgetOff, !on);
        WidgetHelper.styleButton(context, views, R.id.widgetRead,
                on && Preset.READ.id.equals(lastPreset));
        WidgetHelper.styleButton(context, views, R.id.widgetNight,
                on && Preset.NIGHT.id.equals(lastPreset));
        WidgetHelper.styleButton(context, views, R.id.widgetCinema,
                on && Preset.CINEMA.id.equals(lastPreset));
        WidgetHelper.styleButton(context, views, R.id.widgetMax,
                on && Preset.MAX.id.equals(lastPreset));

        views.setOnClickPendingIntent(R.id.widgetStatus,
                WidgetHelper.pending(context, DimWidgetProvider.class, WidgetHelper.ACTION_OPEN));
        views.setOnClickPendingIntent(R.id.widgetOff,
                WidgetHelper.pending(context, DimWidgetProvider.class, WidgetHelper.ACTION_OFF));
        views.setOnClickPendingIntent(R.id.widgetRead,
                WidgetHelper.pending(context, DimWidgetProvider.class, WidgetHelper.ACTION_READ));
        views.setOnClickPendingIntent(R.id.widgetNight,
                WidgetHelper.pending(context, DimWidgetProvider.class, WidgetHelper.ACTION_NIGHT));
        views.setOnClickPendingIntent(R.id.widgetCinema,
                WidgetHelper.pending(context, DimWidgetProvider.class, WidgetHelper.ACTION_CINEMA));
        views.setOnClickPendingIntent(R.id.widgetMax,
                WidgetHelper.pending(context, DimWidgetProvider.class, WidgetHelper.ACTION_MAX));
        return views;
    }
}
