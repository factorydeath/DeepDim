package com.deepdim;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Настройки: root, поведение, правила приложений, сброс. */
public final class SettingsActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 51;

    private Prefs prefs;
    private boolean updating;
    private Boolean rootAvailable;
    private boolean rootChecking;
    private boolean sysfsLoaded;

    private TextView rootBadgeSettings;
    private TextView rootState;
    private TextView rootDesc;
    private TextView btnCheckRoot;
    private TextView btnGrantRoot;
    private TextView btnDiagRoot;
    private TextView versionSettings;

    private TextView sysfsSubtitle;
    private TextView volumeSubtitle;
    private Switch sysfsToggle;

    private TextView btnAddApp;
    private TextView appsEmpty;
    private TextView usageStatus;
    private TextView btnUsage;
    private LinearLayout rulesContainer;
    private LinearLayout permissionsContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        Ui.edgeToEdge(this);
        prefs = Prefs.get(this);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        rootBadgeSettings = findViewById(R.id.rootBadgeSettings);
        rootState = findViewById(R.id.rootState);
        rootDesc = findViewById(R.id.rootDesc);
        btnCheckRoot = findViewById(R.id.btnCheckRoot);
        btnGrantRoot = findViewById(R.id.btnGrantRoot);
        btnDiagRoot = findViewById(R.id.btnDiagRoot);
        versionSettings = findViewById(R.id.versionSettings);

        btnAddApp = findViewById(R.id.btnAddApp);
        appsEmpty = findViewById(R.id.appsEmpty);
        usageStatus = findViewById(R.id.usageStatus);
        btnUsage = findViewById(R.id.btnUsage);
        rulesContainer = findViewById(R.id.rulesContainer);
        permissionsContainer = findViewById(R.id.permissionsContainer);

        setupRootCard();
        setupBehavior();
        setupApps();
        setupReset();
        fillVersion();

        checkRoot(false);
        refreshUsageUi();
        refreshRules();
        refreshPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateVolumeSubtitle();
        refreshUsageUi();
        refreshRules();
        refreshPermissions();
        if (rootAvailable == null) checkRoot(false);
        else applyRootUi();
        if (Boolean.TRUE.equals(rootAvailable) && !sysfsLoaded) loadSysfsSummary();
    }

    // ---------------------------------------------------------------- root

    private void setupRootCard() {
        btnCheckRoot.setOnClickListener(v -> checkRoot(true));
        btnGrantRoot.setOnClickListener(v -> grantRoot());
        btnDiagRoot.setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
    }

    private void checkRoot(boolean force) {
        if (rootChecking) return;
        rootChecking = true;
        rootState.setText(R.string.root_checking);
        if (force) RootShell.resetCache();
        Thread thread = new Thread(() -> {
            boolean root = RootShell.isAvailable();
            runOnUiThread(() -> {
                rootChecking = false;
                rootAvailable = root;
                applyRootUi();
                if (root) loadSysfsSummary();
            });
        }, "deepdim-settings-root");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyRootUi() {
        boolean root = Boolean.TRUE.equals(rootAvailable);
        rootBadgeSettings.setText(root ? R.string.root_badge : R.string.rootless_badge);
        rootBadgeSettings.setTextColor(getColor(root ? R.color.green : R.color.accent));
        rootBadgeSettings.setBackgroundResource(root
                ? R.drawable.bg_badge_root : R.drawable.bg_badge_amber);
        rootState.setText(root ? R.string.root_ok : R.string.root_no);
        rootDesc.setText(root ? R.string.root_ok_desc : R.string.root_no_desc);

        if (sysfsToggle != null) {
            sysfsToggle.setEnabled(root);
            View row = findViewById(R.id.rowSysfs);
            if (row != null) row.setAlpha(root ? 1f : 0.5f);
            updating = true;
            sysfsToggle.setChecked(root && prefs.sysfsEnabled());
            updating = false;
        }
    }

    private void grantRoot() {
        btnGrantRoot.setEnabled(false);
        btnGrantRoot.setText(R.string.grant_running);
        if (!Boolean.TRUE.equals(rootAvailable)) RootShell.resetCache();
        RootSetup.run(this, status -> {
            btnGrantRoot.setEnabled(true);
            btnGrantRoot.setText(R.string.btn_grant);
            rootAvailable = status.root;
            sysfsLoaded = false;
            applyRootUi();
            Ui.toast(this, getString(status.granted
                    ? R.string.grant_done : R.string.grant_fail));
            if (status.root) loadSysfsSummary();
        });
    }

    private void loadSysfsSummary() {
        if (!Boolean.TRUE.equals(rootAvailable)) return;
        sysfsLoaded = true;
        Thread thread = new Thread(() -> {
            String summary;
            try {
                if (Backlight.nodes(true).isEmpty()) {
                    summary = getString(R.string.sysfs_missing);
                } else {
                    summary = getString(R.string.sysfs_found, Backlight.summary());
                }
            } catch (Throwable t) {
                summary = getString(R.string.sysfs_missing);
            }
            final String text = summary;
            runOnUiThread(() -> {
                if (sysfsSubtitle != null) sysfsSubtitle.setText(text);
            });
        }, "deepdim-settings-sysfs");
        thread.setDaemon(true);
        thread.start();
    }

    // ------------------------------------------------------------- behavior

    private void setupBehavior() {
        BehaviorRow boot = bindSwitchRow(R.id.rowBoot, R.string.start_on_boot,
                R.string.start_on_boot_hint, prefs.startOnBoot());
        boot.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            prefs.setStartOnBoot(checked);
        });

        BehaviorRow handle = bindSwitchRow(R.id.rowHandle, R.string.show_handle,
                R.string.show_handle_hint, prefs.showHandle());
        handle.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            DimService service = DimService.peek();
            if (service != null) service.setShowHandle(checked);
            else prefs.setShowHandle(checked);
        });

        BehaviorRow volume = bindSwitchRow(R.id.rowVolume, R.string.volume_keys,
                R.string.volume_keys_hint, prefs.volumeKeys());
        volumeSubtitle = volume.subtitle;
        volume.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            prefs.setVolumeKeys(checked);
            updateVolumeSubtitle();
            if (checked && !Ui.accessibilityEnabled(this)) {
                Ui.dialog(this)
                        .setTitle(R.string.accessibility_needed_title)
                        .setMessage(R.string.volume_off)
                        .setPositiveButton(R.string.open,
                                (dialog, which) -> Ui.openAccessibilitySettings(this))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
            }
        });

        BehaviorRow sysfs = bindSwitchRow(R.id.rowSysfs, R.string.sysfs,
                R.string.sysfs_hint, prefs.sysfsEnabled());
        sysfsToggle = sysfs.toggle;
        sysfsSubtitle = sysfs.subtitle;
        sysfs.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (checked && !Boolean.TRUE.equals(rootAvailable)) {
                updating = true;
                sysfs.toggle.setChecked(false);
                updating = false;
                Ui.toast(this, getString(R.string.toast_no_root));
                return;
            }
            prefs.setSysfsEnabled(checked);
            if (checked) Backlight.invalidate();
            DimService service = DimService.peek();
            if (service != null) service.setSysfsEnabled(checked);
        });

        BehaviorRow haptics = bindSwitchRow(R.id.rowHaptics, R.string.haptics,
                R.string.haptics_hint, prefs.haptics());
        haptics.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            prefs.setHaptics(checked);
            if (checked) {
                haptics.root.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            }
        });

        BehaviorRow clean = bindSwitchRow(R.id.rowCleanShots, R.string.clean_shots,
                R.string.clean_shots_hint, prefs.cleanScreenshots());
        clean.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (checked && !Boolean.TRUE.equals(rootAvailable)
                    && !Settings.System.canWrite(this)) {
                updating = true;
                clean.toggle.setChecked(false);
                updating = false;
                Ui.toast(this, getString(R.string.clean_shots_need_access));
                return;
            }
            prefs.setCleanScreenshots(checked);
            DimService service = DimService.peek();
            if (service != null) service.onCleanScreenshotsChanged();
        });

        updateVolumeSubtitle();
        setupLanguageRow();
    }

    private void setupLanguageRow() {
        View row = findViewById(R.id.rowLanguage);
        if (row == null) return;
        ImageView icon = row.findViewById(R.id.rowIcon);
        TextView title = row.findViewById(R.id.rowTitle);
        TextView subtitle = row.findViewById(R.id.rowSubtitle);
        if (icon != null) icon.setImageResource(R.drawable.ic_gear);
        if (title != null) title.setText(R.string.language);
        if (subtitle != null) subtitle.setText(R.string.language_hint);
        row.setOnClickListener(v -> openLanguageSettings());
    }

    private void openLanguageSettings() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                startActivity(new Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName())));
                return;
            } catch (Throwable ignored) {
            }
        }
        Ui.toast(this, getString(R.string.language_system));
    }

    private BehaviorRow bindSwitchRow(int includeId, int title, int subtitle,
                                      boolean checked) {
        BehaviorRow row = new BehaviorRow();
        row.root = findViewById(includeId);
        row.title = row.root.findViewById(R.id.switchTitle);
        row.subtitle = row.root.findViewById(R.id.switchSubtitle);
        row.toggle = row.root.findViewById(R.id.switchToggle);
        row.title.setText(title);
        row.subtitle.setText(subtitle);
        row.toggle.setChecked(checked);
        row.root.setOnClickListener(v -> {
            if (row.toggle.isEnabled()) row.toggle.toggle();
        });
        return row;
    }

    private void updateVolumeSubtitle() {
        if (volumeSubtitle == null) return;
        boolean enabled = Ui.accessibilityEnabled(this);
        volumeSubtitle.setText(enabled ? R.string.volume_on : R.string.volume_off);
        volumeSubtitle.setTextColor(getColor(enabled ? R.color.green : R.color.text3));
    }

    // ------------------------------------------------------------ permissions

    private void refreshPermissions() {
        if (permissionsContainer == null) return;
        permissionsContainer.removeAllViews();

        addPermission(R.drawable.ic_moon, R.string.perm_overlay,
                R.string.access_overlay_hint,
                Settings.canDrawOverlays(this) || Ui.accessibilityEnabled(this),
                false, () -> Ui.openOverlaySettings(this));

        addPermission(R.drawable.ic_warning, R.string.perm_notif,
                R.string.access_notif_hint, notificationsAllowed(), false,
                this::requestNotifications);

        addPermission(R.drawable.ic_shield, R.string.perm_write,
                R.string.access_write_hint, Settings.System.canWrite(this), false,
                () -> Ui.openWriteSettings(this));

        addPermission(R.drawable.ic_apps, R.string.perm_usage,
                R.string.access_usage_hint, hasUsageAccess(), false,
                () -> Ui.openUsageAccess(this));

        addPermission(R.drawable.ic_clock, R.string.perm_alarms,
                R.string.access_alarms_hint,
                ScheduleController.exactAlarmsAllowed(this), false,
                () -> Ui.openExactAlarmSettings(this));

        addPermission(R.drawable.ic_sun, R.string.perm_battery,
                R.string.access_battery_hint, batteryUnrestricted(), false,
                () -> Ui.openBatterySettings(this));

        addPermission(R.drawable.ic_gear, R.string.perm_accessibility,
                R.string.access_a11y_hint, Ui.accessibilityEnabled(this), true,
                () -> Ui.openAccessibilitySettings(this));
    }

    private void addPermission(int icon, int title, int hint, boolean ok,
                               boolean optional, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.perm_row, permissionsContainer, false);
        ImageView rowIcon = row.findViewById(R.id.permIcon);
        TextView rowTitle = row.findViewById(R.id.permTitle);
        TextView rowHint = row.findViewById(R.id.permHint);
        TextView rowStatus = row.findViewById(R.id.permStatus);

        rowIcon.setImageResource(icon);
        rowTitle.setText(title);
        rowHint.setText(hint);
        if (ok) {
            rowStatus.setText(R.string.access_ok);
            rowStatus.setTextColor(getColor(R.color.green));
            rowStatus.setBackgroundResource(R.drawable.bg_badge_root);
        } else {
            rowStatus.setText(optional ? R.string.access_optional : R.string.access_off);
            rowStatus.setTextColor(getColor(R.color.accent));
            rowStatus.setBackgroundResource(R.drawable.bg_badge_amber);
        }
        row.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                Ui.toast(this, t.toString());
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 2);
        permissionsContainer.addView(row, params);
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT < 33) return true;
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryUnrestricted() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed()) {
            try {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIFICATIONS);
            } catch (Throwable ignored) {
            }
        } else {
            Ui.openNotificationSettings(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) refreshPermissions();
    }

    // ----------------------------------------------------------------- apps

    private void setupApps() {
        btnAddApp.setOnClickListener(v -> showAppPicker());
        btnUsage.setOnClickListener(v -> Ui.openUsageAccess(this));
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = getSystemService(AppOpsManager.class);
            if (ops == null) return false;
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshUsageUi() {
        boolean allowed = hasUsageAccess();
        usageStatus.setVisibility(allowed ? View.GONE : View.VISIBLE);
        btnUsage.setVisibility(allowed ? View.GONE : View.VISIBLE);
    }

    private void refreshRules() {
        rulesContainer.removeAllViews();
        Map<String, AppRule> rules = prefs.rules();
        appsEmpty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);

        List<AppRule> list = new ArrayList<>(rules.values());
        Collections.sort(list, (a, b) -> Ui.appLabel(this, a.pkg)
                .compareToIgnoreCase(Ui.appLabel(this, b.pkg)));

        for (AppRule rule : list) {
            View row = getLayoutInflater().inflate(R.layout.row_link, rulesContainer, false);
            TextView title = row.findViewById(R.id.rowTitle);
            TextView subtitle = row.findViewById(R.id.rowSubtitle);
            ImageView icon = row.findViewById(R.id.rowIcon);
            title.setText(Ui.appLabel(this, rule.pkg));
            subtitle.setText(getString(R.string.apps_rule_fmt, rule.boost, rule.warm));
            icon.setImageDrawable(Ui.appIcon(this, rule.pkg));
            row.setOnClickListener(v -> showRuleDialog(rule));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = Ui.dp(this, 4);
            rulesContainer.addView(row, params);
        }
    }

    private void showRuleDialog(AppRule rule) {
        final int[] values = {clamp(rule.boost, 0, 60), clamp(rule.warm, 0, 60)};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 22);
        box.setPadding(side, Ui.dp(this, 6), side, 0);

        TextView boostTitle = new TextView(this);
        boostTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        boostTitle.setTextColor(getColor(R.color.text2));
        boostTitle.setText(getString(R.string.rule_boost_fmt, values[0]));
        box.addView(boostTitle);

        ParamSlider boostSlider = new ParamSlider(this);
        boostSlider.setProgress(values[0]);
        box.addView(boostSlider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        TextView warmTitle = new TextView(this);
        warmTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        warmTitle.setTextColor(getColor(R.color.text2));
        warmTitle.setText(getString(R.string.rule_warm_fmt, values[1]));
        LinearLayout.LayoutParams warmParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        warmParams.topMargin = Ui.dp(this, 14);
        box.addView(warmTitle, warmParams);

        ParamSlider warmSlider = new ParamSlider(this);
        warmSlider.setProgress(values[1]);
        box.addView(warmSlider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        boostSlider.setOnProgressChanged((value, fromUser) -> {
            values[0] = value;
            boostTitle.setText(getString(R.string.rule_boost_fmt, value));
        });
        warmSlider.setOnProgressChanged((value, fromUser) -> {
            values[1] = value;
            warmTitle.setText(getString(R.string.rule_warm_fmt, value));
        });

        AlertDialog dialog = Ui.dialog(this)
                .setTitle(Ui.appLabel(this, rule.pkg))
                .setIcon(Ui.appIcon(this, rule.pkg))
                .setView(box)
                .setPositiveButton(R.string.apps_save, (d, which) -> {
                    prefs.putRule(new AppRule(rule.pkg, values[0], values[1]));
                    notifyRulesChanged();
                })
                .setNegativeButton(R.string.cancel, null)
                .setNeutralButton(R.string.apps_remove, (d, which) -> {
                    prefs.removeRule(rule.pkg);
                    notifyRulesChanged();
                })
                .create();
        dialog.show();
    }

    private void showAppPicker() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            resolved = new ArrayList<>();
        }

        Map<String, String> unique = new HashMap<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName()) || unique.containsKey(pkg)) continue;
            String label;
            try {
                label = info.loadLabel(pm).toString();
            } catch (Throwable t) {
                label = pkg;
            }
            unique.put(pkg, label);
        }

        List<AppEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : unique.entrySet()) {
            entries.add(new AppEntry(entry.getKey(), entry.getValue()));
        }
        Collections.sort(entries, (a, b) -> a.label.compareToIgnoreCase(b.label));

        if (entries.isEmpty()) {
            Ui.toast(this, getString(R.string.apps_empty));
            return;
        }

        showAppSearchDialog(entries);
    }

    private void showAppSearchDialog(List<AppEntry> entries) {
        int side = Ui.dp(this, 18);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(side, Ui.dp(this, 4), side, 0);

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint(R.string.apps_search);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        search.setTextColor(getColor(R.color.text));
        search.setHintTextColor(getColor(R.color.text3));
        search.setBackgroundResource(R.drawable.bg_card_soft);
        search.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        box.addView(search, searchParams);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(R.drawable.bg_row);
        list.setDrawSelectorOnTop(true);
        AppAdapter adapter = new AppAdapter(entries);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 350));
        listParams.topMargin = Ui.dp(this, 8);
        box.addView(list, listParams);

        AlertDialog dialog = Ui.dialog(this)
                .setTitle(R.string.apps_pick)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .create();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.filter(s == null ? "" : s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry entry = adapter.getItem(position);
            dialog.dismiss();
            AppRule existing = prefs.rules().get(entry.pkg);
            showRuleDialog(existing != null
                    ? existing : new AppRule(entry.pkg, 15, 10));
        });

        dialog.show();
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> all;
        private final List<AppEntry> shown;

        AppAdapter(List<AppEntry> entries) {
            all = new ArrayList<>(entries);
            shown = new ArrayList<>(entries);
        }

        void filter(String query) {
            String needle = query == null ? "" : query.trim().toLowerCase(java.util.Locale.getDefault());
            shown.clear();
            if (needle.isEmpty()) {
                shown.addAll(all);
            } else {
                for (AppEntry entry : all) {
                    if (entry.label.toLowerCase(java.util.Locale.getDefault()).contains(needle)
                            || entry.pkg.toLowerCase(java.util.Locale.getDefault()).contains(needle)) {
                        shown.add(entry);
                    }
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public AppEntry getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.row_link, parent, false);
                row.setClickable(false);
                row.setFocusable(false);
            }
            AppEntry entry = getItem(position);
            TextView title = row.findViewById(R.id.rowTitle);
            TextView subtitle = row.findViewById(R.id.rowSubtitle);
            ImageView icon = row.findViewById(R.id.rowIcon);
            ImageView chevron = row.findViewById(R.id.rowChevron);
            title.setText(entry.label);
            subtitle.setText(entry.pkg);
            icon.setImageDrawable(Ui.appIcon(SettingsActivity.this, entry.pkg));
            if (chevron != null) chevron.setVisibility(View.GONE);
            return row;
        }
    }

    private void notifyRulesChanged() {
        refreshRules();
        DimService service = DimService.peek();
        if (service != null) service.rulesChanged();
    }

    // ---------------------------------------------------------------- reset

    private void setupReset() {
        findViewById(R.id.btnRestoreNow).setOnClickListener(v -> {
            DimService service = DimService.peek();
            if (service != null) service.restoreNow();
            else DimService.startAction(this, DimService.ACTION_RESTORE);
            Ui.toast(this, getString(R.string.toast_restored));
        });

        findViewById(R.id.btnResetAll).setOnClickListener(v ->
                Ui.dialog(this)
                        .setTitle(R.string.reset_all)
                        .setMessage(R.string.reset_all_confirm)
                        .setPositiveButton(R.string.reset_all, (dialog, which) -> {
                            DimService service = DimService.peek();
                            if (service != null) service.setEnabled(false);
                            else DimService.startAction(this, DimService.ACTION_RESTORE);
                            waitForRestoreThenClear();
                        })
                        .setNegativeButton(R.string.cancel, null)
                        .show());
    }

    /**
     * Сброс нельзя делать до отката яркости: журнал исходных значений нужен
     * движку, чтобы вернуть панель в прежнее состояние. Ждём, пока он очистится.
     */
    private void waitForRestoreThenClear() {
        Thread thread = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 8000L;
            while (prefs.journalDirty() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(150L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                Thread.sleep(150L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            runOnUiThread(() -> {
                prefs.clearAll();
                if (isFinishing() || isDestroyed()) return;
                Ui.toast(this, getString(R.string.reset_done));
                recreate();
            });
        }, "deepdim-reset");
        thread.setDaemon(true);
        thread.start();
    }

    private void fillVersion() {
        try {
            String name = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            versionSettings.setText(getString(R.string.version_fmt,
                    name, Build.VERSION.RELEASE));
        } catch (Throwable t) {
            versionSettings.setText(R.string.app_name);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static final class BehaviorRow {
        View root;
        TextView title;
        TextView subtitle;
        Switch toggle;
    }

    private static final class AppEntry {
        final String pkg;
        final String label;

        AppEntry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }
}
