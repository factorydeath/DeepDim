package com.deepdim;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Большой переключатель затемнения. */
public final class ToggleWidgetProvider extends AppWidgetProvider {

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
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_toggle);
        boolean on = WidgetHelper.isOn(context);
        int percent = WidgetHelper.percent(context);

        views.setTextViewText(R.id.toggleStatus, WidgetHelper.status(context, on, percent));
        views.setTextViewText(R.id.toggleState,
                context.getString(on ? R.string.widget_toggle_on : R.string.widget_toggle_off));
        views.setImageViewResource(R.id.toggleIcon, on ? R.drawable.ic_moon : R.drawable.ic_sun);
        views.setInt(R.id.toggleIcon, "setColorFilter",
                context.getColor(on ? R.color.accent : R.color.text3));
        WidgetHelper.stylePill(context, views, R.id.toggleState, on);

        views.setOnClickPendingIntent(R.id.toggleRoot,
                WidgetHelper.pending(context, ToggleWidgetProvider.class, WidgetHelper.ACTION_TOGGLE));
        views.setOnClickPendingIntent(R.id.toggleOpen,
                WidgetHelper.pending(context, ToggleWidgetProvider.class, WidgetHelper.ACTION_OPEN));
        return views;
    }
}
