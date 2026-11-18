package com.deepdim;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

public final class App extends Application {

    public static final String CHANNEL_DIM = "dim";

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel(this);
    }

    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_DIM,
                context.getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(channel);
    }
}
