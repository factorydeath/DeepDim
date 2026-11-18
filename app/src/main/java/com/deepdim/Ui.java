package com.deepdim;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Locale;

/** Мелкие общие вещи без AndroidX. */
public final class Ui {

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int sp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().scaledDensity);
    }

    public static void toast(Context context, String text) {
        try {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    public static android.app.AlertDialog.Builder dialog(Context context) {
        return new android.app.AlertDialog.Builder(context, R.style.Theme_DeepDim_Dialog);
    }

    public static void edgeToEdge(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        View root = activity.findViewById(R.id.root);
        if (root != null) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int left = 0, top = 0, right = 0, bottom = 0;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(left, top, right, bottom);
                return insets;
            });
            root.requestApplyInsets();
        }

        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        }
    }

    public static boolean accessibilityEnabled(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) return false;
            String pkg = context.getPackageName().toLowerCase(Locale.ROOT);
            return enabled.toLowerCase(Locale.ROOT).contains(pkg)
                    && enabled.toLowerCase(Locale.ROOT).contains("volumekeyservice");
        } catch (Throwable t) {
            return false;
        }
    }

    public static void openAccessibilitySettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }

    public static void openOverlaySettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable t) {
            toast(activity, activity.getString(R.string.settings_open_failed));
        }
    }

    public static void openWriteSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable t) {
            toast(activity, activity.getString(R.string.settings_open_failed));
        }
    }

    public static void openExactAlarmSettings(Activity activity) {
        if (android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable ignored) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + activity.getPackageName())));
            } catch (Throwable ignored2) {
            }
        }
    }

    public static void openUsageAccess(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    public static void openBatterySettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable t) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + activity.getPackageName())));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void openNotificationSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
        } catch (Throwable ignored) {
        }
    }

    public static android.graphics.drawable.Drawable appIcon(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationIcon(pkg);
        } catch (Throwable t) {
            return context.getDrawable(R.drawable.ic_apps);
        }
    }

    public static String appLabel(Context context, String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return pkg;
        }
    }
}
