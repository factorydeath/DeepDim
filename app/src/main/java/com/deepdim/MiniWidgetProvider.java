package com.deepdim;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Компактный виджет: статус и переключение одним тапом. */
public final class MiniWidgetProvider extends AppWidgetProvider {

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

    static RemoteViews build(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_mini);
        boolean on = WidgetHelper.isOn(context);
        int percent = WidgetHelper.percent(context);

        views.setTextViewText(R.id.miniStatus, WidgetHelper.status(context, on, percent));
        views.setTextViewText(R.id.miniValue, percent + "%");
        views.setTextColor(R.id.miniValue,
                context.getColor(on ? R.color.accent : R.color.text3));
        views.setInt(R.id.miniIcon, "setColorFilter",
                context.getColor(on ? R.color.accent : R.color.text3));

        views.setOnClickPendingIntent(R.id.miniRoot,
                WidgetHelper.pending(context, MiniWidgetProvider.class, WidgetHelper.ACTION_TOGGLE));
        views.setOnClickPendingIntent(R.id.miniOpen,
                WidgetHelper.pending(context, MiniWidgetProvider.class, WidgetHelper.ACTION_OPEN));
        return views;
    }
}
