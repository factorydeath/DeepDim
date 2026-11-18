package com.deepdim;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Панель: глубина, точная подстройка и открытие приложения. */
public final class PanelWidgetProvider extends AppWidgetProvider {

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
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_panel);
        boolean on = WidgetHelper.isOn(context);
        int percent = WidgetHelper.percent(context);

        views.setTextViewText(R.id.panelValue, percent + "%");
        views.setTextColor(R.id.panelValue,
                context.getColor(on ? R.color.accent : R.color.text3));
        views.setTextViewText(R.id.panelStatus, WidgetHelper.status(context, on, percent));
        views.setTextViewText(R.id.panelBadge,
                context.getString(on ? R.string.widget_live : R.string.widget_toggle_off));
        views.setTextColor(R.id.panelBadge,
                context.getColor(on ? R.color.accent : R.color.text3));
        views.setTextViewText(R.id.panelToggle,
                context.getString(on ? R.string.widget_toggle_on : R.string.widget_toggle_off));
        WidgetHelper.styleButton(context, views, R.id.panelToggle, on);
        WidgetHelper.styleButton(context, views, R.id.panelMinus, false);
        WidgetHelper.styleButton(context, views, R.id.panelPlus, false);
        WidgetHelper.styleButton(context, views, R.id.panelOpen, false);
        views.setInt(R.id.panelIcon, "setColorFilter",
                context.getColor(on ? R.color.accent : R.color.text3));

        views.setOnClickPendingIntent(R.id.panelRoot,
                WidgetHelper.pending(context, PanelWidgetProvider.class, WidgetHelper.ACTION_OPEN));
        views.setOnClickPendingIntent(R.id.panelMinus,
                WidgetHelper.pending(context, PanelWidgetProvider.class, WidgetHelper.ACTION_MINUS));
        views.setOnClickPendingIntent(R.id.panelPlus,
                WidgetHelper.pending(context, PanelWidgetProvider.class, WidgetHelper.ACTION_PLUS));
        views.setOnClickPendingIntent(R.id.panelToggle,
                WidgetHelper.pending(context, PanelWidgetProvider.class, WidgetHelper.ACTION_TOGGLE));
        views.setOnClickPendingIntent(R.id.panelOpen,
                WidgetHelper.pending(context, PanelWidgetProvider.class, WidgetHelper.ACTION_OPEN));
        return views;
    }
}
