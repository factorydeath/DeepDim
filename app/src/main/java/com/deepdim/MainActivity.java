package com.deepdim;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.app.TimePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Главный экран: регулятор, пресеты, слои, расписание и быстрые действия. */
public final class MainActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 41;

    private Prefs prefs;
    private DimService service;
    private boolean bound;
    private boolean updating;
    private Boolean rootAvailable;
    private boolean rootChecking;
    private boolean autoGrantTried;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private DialView dial;
    private Switch switchActive;
    private TextView activeTitle;
    private TextView statusText;
    private TextView rootBadge;
    private TextView warningText;
    private TextView versionText;
    private LinearLayout presetRow;

    private View permissionCard;
    private TextView permissionBody;
    private TextView btnGrantOverlay;

    private TextView startTime;
    private TextView endTime;
    private TextView scheduleHint;
    private Switch switchSchedule;

    private LinearLayout sleepRow;
    private TextView sleepHint;

    private LayerRow hwRow;
    private LayerRow extraRow;
    private LayerRow warmRow;

    private final List<TextView> presetChips = new ArrayList<>();
    private final List<TextView> sleepChips = new ArrayList<>();

    private static final int[] SLEEP_MINUTES = {15, 30, 60, 90, 0};

    private final DimService.Listener stateListener = state -> {
        if (isFinishing() || isDestroyed()) return;
        applyState(state);
    };

    private final Runnable sleepTick = new Runnable() {
        @Override
        public void run() {
            updateSleepUi();
            handler.postDelayed(this, 15000);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof DimService.LocalBinder)) return;
            service = ((DimService.LocalBinder) binder).service();
            bound = true;
            service.addListener(stateListener);
            refreshAll();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (service != null) service.removeListener(stateListener);
            service = null;
            bound = false;
            refreshAll();
        }
    };

    // --------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.edgeToEdge(this);
        prefs = Prefs.get(this);

        bindViews();
        setupLayers();
        setupPresets();
        setupLinks();
        setupSchedule();
        setupSleep();
        setupPermissionCard();

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        switchActive.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            setEnabled(checked);
        });

        dial.setOnDimChanged((value, fromUser) -> {
            if (!fromUser) return;
            if (service != null) service.setDim(value);
            else {
                prefs.setDim(value);
                refreshAll();
            }
        });

        rootBadge.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        handleIntent(getIntent());
        refreshAll();
        updateScheduleUi();
        refreshPermissionCard();
        refreshRoot(false);
        requestNotificationPermission();
        fillVersion();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, DimService.class);
        try {
            bound = bindService(intent, connection, BIND_AUTO_CREATE);
        } catch (Throwable t) {
            bound = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
        updateScheduleUi();
        updateSleepUi();
        refreshPermissionCard();
        handler.removeCallbacks(sleepTick);
        handler.post(sleepTick);
        if (rootAvailable == null) refreshRoot(false);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(sleepTick);
        if (bound) {
            if (service != null) service.removeListener(stateListener);
            try {
                unbindService(connection);
            } catch (Throwable ignored) {
            }
            bound = false;
            service = null;
        }
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        refreshAll();
        refreshPermissionCard();
    }

    // ------------------------------------------------------------------ views

    private void bindViews() {
        dial = findViewById(R.id.dial);
        switchActive = findViewById(R.id.switchActive);
        activeTitle = findViewById(R.id.activeTitle);
        statusText = findViewById(R.id.statusText);
        rootBadge = findViewById(R.id.rootBadge);
        warningText = findViewById(R.id.warningText);
        versionText = findViewById(R.id.versionText);
        presetRow = findViewById(R.id.presetRow);

        permissionCard = findViewById(R.id.permissionCard);
        permissionBody = findViewById(R.id.permissionBody);
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay);

        startTime = findViewById(R.id.startTime);
        endTime = findViewById(R.id.endTime);
        scheduleHint = findViewById(R.id.scheduleHint);
        switchSchedule = findViewById(R.id.switchSchedule);

        sleepRow = findViewById(R.id.sleepRow);
        sleepHint = findViewById(R.id.sleepHint);
    }

    private void setupLayers() {
        hwRow = bindLayer(R.id.rowHw, R.string.layer_hw, R.string.layer_hw_hint);
        extraRow = bindLayer(R.id.rowExtra, R.string.layer_extra, R.string.layer_extra_hint);
        warmRow = bindLayer(R.id.rowWarm, R.string.layer_warm, R.string.layer_warm_hint);

        hwRow.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            changeHwEnabled(checked);
        });
        extraRow.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            changeExtraEnabled(checked);
        });
        warmRow.toggle.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            changeWarmEnabled(checked);
        });

        hwRow.slider.setOnProgressChanged((value, fromUser) -> {
            if (!fromUser || updating) return;
            changeHw(value);
        });
        extraRow.slider.setOnProgressChanged((value, fromUser) -> {
            if (!fromUser || updating) return;
            changeExtra(value);
        });
        warmRow.slider.setOnProgressChanged((value, fromUser) -> {
            if (!fromUser || updating) return;
            changeWarm(value);
        });
    }

    private LayerRow bindLayer(int includeId, int title, int hint) {
        LayerRow row = new LayerRow();
        row.root = findViewById(includeId);
        row.title = row.root.findViewById(R.id.layerTitle);
        row.value = row.root.findViewById(R.id.layerValue);
        row.hint = row.root.findViewById(R.id.layerHint);
        row.toggle = row.root.findViewById(R.id.layerSwitch);
        row.slider = row.root.findViewById(R.id.layerSlider);
        row.title.setText(title);
        row.hint.setText(hint);
        return row;
    }

    private void setupPresets() {
        presetRow.removeAllViews();
        presetChips.clear();
        Preset[] presets = Preset.values();
        for (int i = 0; i < presets.length; i++) {
            Preset preset = presets[i];
            TextView chip = makeChip(preset.labelRes, preset.iconRes);
            chip.setTag(i);
            chip.setOnClickListener(v -> {
                tapFeedback(v);
                bounce(v);
                int index = (Integer) v.getTag();
                Preset selected = Preset.values()[index];
                if (service != null) service.applyPreset(selected.id);
                else DimService.startPreset(this, selected.id);
                refreshAll();
            });
            presetRow.addView(chip);
            presetChips.add(chip);
        }
    }

    private TextView makeChip(int textRes, int iconRes) {
        TextView chip = new TextView(this);
        chip.setText(textRes);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setTextColor(getColorStateList(R.color.chip_text));
        chip.setBackgroundResource(R.drawable.bg_chip);
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setIncludeFontPadding(false);
        chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 9),
                Ui.dp(this, 14), Ui.dp(this, 9));
        chip.setClickable(true);
        chip.setFocusable(true);
        if (iconRes != 0) {
            Drawable icon = getDrawable(iconRes);
            if (icon != null) {
                icon = icon.mutate();
                int size = Ui.dp(this, 14);
                icon.setBounds(0, 0, size, size);
                chip.setCompoundDrawablesRelative(icon, null, null, null);
                chip.setCompoundDrawablePadding(Ui.dp(this, 6));
                chip.setCompoundDrawableTintList(getColorStateList(R.color.chip_text));
            }
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(this, 8);
        chip.setLayoutParams(params);
        return chip;
    }

    private void tapFeedback(View view) {
        if (view == null || prefs == null || !prefs.haptics()) return;
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void bounce(View view) {
        view.animate().cancel();
        view.setScaleX(0.96f);
        view.setScaleY(0.96f);
        view.animate().scaleX(1f).scaleY(1f)
                .setDuration(160)
                .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f))
                .start();
    }

    private void setupLinks() {
        setupLink(R.id.rowTile, R.string.quick_tile, R.string.quick_tile_hint,
                R.drawable.ic_moon, this::addTile);
        setupLink(R.id.rowWidget, R.string.widget, R.string.widget_hint,
                R.drawable.ic_plus, this::pinWidget);
        setupLink(R.id.rowDiag, R.string.diagnostics, R.string.diagnostics_hint,
                R.drawable.ic_shield, () ->
                        startActivity(new Intent(this, DiagnosticsActivity.class)));
        setupLink(R.id.rowSettings, R.string.settings, R.string.settings_hint,
                R.drawable.ic_gear, () ->
                        startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void setupLink(int includeId, int title, int subtitle, int icon, Runnable action) {
        View row = findViewById(includeId);
        TextView rowTitle = row.findViewById(R.id.rowTitle);
        TextView rowSubtitle = row.findViewById(R.id.rowSubtitle);
        ImageView rowIcon = row.findViewById(R.id.rowIcon);
        rowTitle.setText(title);
        rowSubtitle.setText(subtitle);
        rowIcon.setImageResource(icon);
        row.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                Ui.toast(this, t.toString());
            }
        });
    }

    private void setupSchedule() {
        switchSchedule.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            prefs.setScheduleEnabled(checked);
            if (service != null) service.onScheduleChanged();
            else ScheduleController.scheduleNext(this);
            updateScheduleUi();
        });

        findViewById(R.id.btnStartTime).setOnClickListener(v -> pickTime(true));
        findViewById(R.id.btnEndTime).setOnClickListener(v -> pickTime(false));
    }

    private void setupSleep() {
        sleepRow.removeAllViews();
        sleepChips.clear();
        for (int minutes : SLEEP_MINUTES) {
            int label = minutes == 15 ? R.string.sleep_15
                    : minutes == 30 ? R.string.sleep_30
                    : minutes == 60 ? R.string.sleep_60
                    : minutes == 90 ? R.string.sleep_90
                    : R.string.sleep_cancel;
            int icon = minutes == 0 ? R.drawable.ic_close : R.drawable.ic_clock;
            TextView chip = makeChip(label, icon);
            chip.setTag(minutes);
            chip.setOnClickListener(v -> {
                tapFeedback(v);
                bounce(v);
                changeSleep((Integer) v.getTag());
            });
            sleepRow.addView(chip);
            sleepChips.add(chip);
        }
        updateSleepUi();
    }

    private void changeSleep(int minutes) {
        if (minutes == 0 && prefs.sleepUntil() <= System.currentTimeMillis()) {
            updateSleepUi();
            return;
        }
        if (minutes > 0 && !DimService.isActive() && !prefs.enabled()) {
            tapFeedback(sleepRow);
            Ui.toast(this, getString(R.string.sleep_need_active));
            return;
        }
        if (service != null) {
            if (minutes > 0) service.setSleepTimer(minutes);
            else service.cancelSleepTimer();
        } else {
            DimService.startSleep(this, minutes);
        }
        updateSleepUi();
    }

    private void updateSleepUi() {
        if (sleepRow == null || sleepHint == null) return;
        long now = System.currentTimeMillis();
        long until = prefs.sleepUntil();
        boolean active = until > now;
        boolean dimOn = DimService.isActive() || prefs.enabled();
        int selected = active ? prefs.sleepMinutes() : 0;
        for (TextView chip : sleepChips) {
            Object tag = chip.getTag();
            if (!(tag instanceof Integer)) continue;
            int minutes = (Integer) tag;
            chip.setSelected(active && minutes > 0 && minutes == selected);
            chip.setVisibility(minutes == 0 && !active ? View.GONE : View.VISIBLE);
        }
        sleepRow.setAlpha(dimOn ? 1f : 0.55f);
        if (active) {
            long remaining = until - now;
            if (remaining < 60000L) {
                sleepHint.setText(R.string.sleep_remaining_less);
            } else {
                long minutes = (remaining + 59999L) / 60000L;
                sleepHint.setText(getString(R.string.sleep_remaining, minutes));
            }
            sleepHint.setTextColor(getColor(R.color.accent));
        } else if (!dimOn) {
            sleepHint.setText(R.string.sleep_need_active);
            sleepHint.setTextColor(getColor(R.color.text3));
        } else {
            sleepHint.setText(R.string.sleep_hint);
            sleepHint.setTextColor(getColor(R.color.text3));
        }
    }

    private void pickTime(boolean start) {
        int minutes = start ? prefs.scheduleStart() : prefs.scheduleEnd();
        TimePickerDialog dialog = new TimePickerDialog(this,
                (view, hourOfDay, minute) -> {
                    if (start) prefs.setScheduleStart(hourOfDay * 60 + minute);
                    else prefs.setScheduleEnd(hourOfDay * 60 + minute);
                    updateScheduleUi();
                    if (service != null) service.onScheduleChanged();
                    else ScheduleController.scheduleNext(this);
                },
                minutes / 60, minutes % 60, true);
        dialog.show();
    }

    private void setupPermissionCard() {
        btnGrantOverlay.setOnClickListener(v -> {
            if (Boolean.TRUE.equals(rootAvailable)) {
                grantWithRoot(false);
            } else {
                Ui.openOverlaySettings(this);
            }
        });
    }

    // --------------------------------------------------------------- refresh

    private void refreshAll() {
        DimState state = service != null ? service.getState() : DimState.fromPrefs(this);
        applyState(state);
    }

    private void applyState(DimState state) {
        if (dial == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> applyState(state));
            return;
        }
        updating = true;

        switchActive.setChecked(state.enabled);
        activeTitle.setText(state.enabled ? R.string.active : R.string.inactive);
        dial.setActive(state.enabled);
        dial.setValue(state.dim);

        if (state.enabled) {
            if (state.appBoost > 0 && state.activePackage != null
                    && !state.activePackage.isEmpty()) {
                statusText.setText(getString(R.string.status_active_app,
                        state.appBoost, Ui.appLabel(this, state.activePackage)));
            } else {
                statusText.setText(R.string.status_active);
            }
        } else {
            statusText.setText(canDimNow() ? R.string.status_inactive : R.string.status_no_overlay);
        }

        boolean tooDark = state.enabled && state.effectiveDim() >= 88;
        warningText.setVisibility(tooDark ? View.VISIBLE : View.GONE);

        hwRow.toggle.setChecked(state.hwEnabled);
        hwRow.slider.setProgress(state.hw);
        hwRow.value.setText(state.hw + "%");

        extraRow.toggle.setChecked(state.extraEnabled);
        extraRow.slider.setProgress(state.extra);
        extraRow.value.setText(state.extra + "%");

        warmRow.toggle.setChecked(state.warmEnabled);
        warmRow.slider.setProgress(state.warmth);
        warmRow.value.setText(state.warmth + "%");

        updatePresetSelection(state.enabled);
        updateSleepUi();
        applyLayerAvailability();
        updating = false;

        refreshPermissionCard();
    }

    private void updatePresetSelection(boolean enabled) {
        String last = prefs.lastPreset();
        Preset[] presets = Preset.values();
        for (int i = 0; i < presetChips.size() && i < presets.length; i++) {
            presetChips.get(i).setSelected(enabled && presets[i].id.equals(last));
        }
    }

    private void updateScheduleUi() {
        startTime.setText(ScheduleController.format(prefs.scheduleStart()));
        endTime.setText(ScheduleController.format(prefs.scheduleEnd()));
        boolean enabled = prefs.scheduleEnabled();
        updating = true;
        switchSchedule.setChecked(enabled);
        updating = false;
        if (enabled) {
            scheduleHint.setText(getString(R.string.schedule_summary_on,
                    ScheduleController.format(prefs.scheduleStart()),
                    ScheduleController.format(prefs.scheduleEnd())));
            scheduleHint.setTextColor(getColor(R.color.text2));
        } else {
            scheduleHint.setText(R.string.schedule_summary_off);
            scheduleHint.setTextColor(getColor(R.color.text3));
        }
    }

    private void applyLayerAvailability() {
        boolean root = Boolean.TRUE.equals(rootAvailable);
        boolean hwOk = root || Settings.System.canWrite(this);
        boolean extraOk = root;

        hwRow.toggle.setEnabled(hwOk);
        hwRow.slider.setSliderEnabled(hwOk);
        hwRow.root.setAlpha(hwOk ? 1f : 0.45f);
        hwRow.hint.setText(hwOk ? R.string.layer_hw_hint : R.string.layer_hw_no_access);

        extraRow.toggle.setEnabled(extraOk);
        extraRow.slider.setSliderEnabled(extraOk);
        extraRow.root.setAlpha(extraOk ? 1f : 0.45f);
        extraRow.hint.setText(extraOk
                ? R.string.layer_extra_hint : R.string.layer_extra_root_hint);

        warmRow.toggle.setEnabled(true);
        warmRow.slider.setSliderEnabled(true);
        warmRow.root.setAlpha(1f);
        warmRow.hint.setText(R.string.layer_warm_hint);
    }

    private void refreshPermissionCard() {
        boolean ok = canDimNow();
        permissionCard.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (!ok) {
            permissionBody.setText(R.string.permission_overlay_body);
        }
    }

    // --------------------------------------------------------------- actions

    private void setEnabled(boolean enabled) {
        if (enabled && !canDimNow()) {
            updating = true;
            switchActive.setChecked(false);
            updating = false;
            refreshPermissionCard();
            Ui.toast(this, getString(R.string.toast_overlay_needed));
            return;
        }
        if (service != null) service.setEnabled(enabled);
        else DimService.startAction(this, enabled
                ? DimService.ACTION_ENABLE : DimService.ACTION_DISABLE);
        refreshAll();
    }

    private void changeHwEnabled(boolean checked) {
        if (checked && !Boolean.TRUE.equals(rootAvailable) && !Settings.System.canWrite(this)) {
            updating = true;
            hwRow.toggle.setChecked(false);
            updating = false;
            Ui.toast(this, getString(R.string.layer_hw_no_access));
            return;
        }
        if (service != null) service.setHwEnabled(checked);
        else prefs.setHwEnabled(checked);
        refreshAll();
    }

    private void changeExtraEnabled(boolean checked) {
        if (checked && !Boolean.TRUE.equals(rootAvailable)) {
            updating = true;
            extraRow.toggle.setChecked(false);
            updating = false;
            Ui.toast(this, getString(R.string.toast_no_root));
            return;
        }
        if (service != null) service.setExtraEnabled(checked);
        else prefs.setExtraEnabled(checked);
        refreshAll();
    }

    private void changeWarmEnabled(boolean checked) {
        if (service != null) service.setWarmEnabled(checked);
        else prefs.setWarmEnabled(checked);
        refreshAll();
    }

    private void changeHw(int value) {
        if (service != null) {
            service.setHw(value);
            if (!service.getState().hwEnabled) service.setHwEnabled(true);
        } else {
            prefs.setHw(value);
            prefs.setHwEnabled(true);
        }
        updating = true;
        hwRow.toggle.setChecked(true);
        hwRow.value.setText(value + "%");
        updating = false;
    }

    private void changeExtra(int value) {
        if (!Boolean.TRUE.equals(rootAvailable)) return;
        if (service != null) {
            service.setExtra(value);
            if (!service.getState().extraEnabled) service.setExtraEnabled(true);
        } else {
            prefs.setExtra(value);
            prefs.setExtraEnabled(true);
        }
        updating = true;
        extraRow.toggle.setChecked(true);
        extraRow.value.setText(value + "%");
        updating = false;
    }

    private void changeWarm(int value) {
        if (service != null) {
            service.setWarmth(value);
            if (value > 0 && !service.getState().warmEnabled) service.setWarmEnabled(true);
        } else {
            prefs.setWarmth(value);
            if (value > 0) prefs.setWarmEnabled(true);
        }
        boolean warmOn = service != null ? service.getState().warmEnabled : prefs.warmEnabled();
        updating = true;
        warmRow.toggle.setChecked(warmOn);
        warmRow.value.setText(value + "%");
        updating = false;
    }

    private boolean canDimNow() {
        if (prefs.cleanScreenshots()) return true;
        return Settings.canDrawOverlays(this) || Ui.accessibilityEnabled(this);
    }

    // ---------------------------------------------------------------- root

    private void refreshRoot(boolean force) {
        if (rootChecking) return;
        rootChecking = true;
        if (force) RootShell.resetCache();
        Thread thread = new Thread(() -> {
            boolean root = RootShell.isAvailable();
            runOnUiThread(() -> {
                rootChecking = false;
                rootAvailable = root;
                applyRootBadge();
                applyLayerAvailability();
                refreshPermissionCard();
                if (root && !canDimNow() && !autoGrantTried) {
                    autoGrantTried = true;
                    grantWithRoot(true);
                }
            });
        }, "deepdim-root-check");
        thread.setDaemon(true);
        thread.start();
    }

    private void applyRootBadge() {
        boolean root = Boolean.TRUE.equals(rootAvailable);
        rootBadge.setText(root ? R.string.root_badge : R.string.rootless_badge);
        rootBadge.setTextColor(getColor(root ? R.color.green : R.color.accent));
        rootBadge.setBackgroundResource(root
                ? R.drawable.bg_badge_root : R.drawable.bg_badge_amber);
    }

    private void grantWithRoot(boolean automatic) {
        btnGrantOverlay.setEnabled(false);
        btnGrantOverlay.setText(R.string.grant_running);
        RootSetup.run(this, status -> {
            btnGrantOverlay.setEnabled(true);
            btnGrantOverlay.setText(R.string.perm_overlay);
            rootAvailable = status.root;
            applyRootBadge();
            applyLayerAvailability();
            refreshPermissionCard();
            if (status.granted) {
                Ui.toast(this, getString(R.string.grant_done));
            } else if (!automatic) {
                Ui.toast(this, getString(R.string.grant_fail));
            }
        });
    }

    // ------------------------------------------------------------ shortcuts

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        switch (action) {
            case "com.deepdim.action.PRESET_NIGHT":
                DimService.startPreset(this, Preset.NIGHT.id);
                break;
            case "com.deepdim.action.PRESET_CINEMA":
                DimService.startPreset(this, Preset.CINEMA.id);
                break;
            case "com.deepdim.action.PRESET_OFF":
                DimService.startAction(this, DimService.ACTION_DISABLE);
                break;
            default:
                break;
        }
        intent.setAction(null);
    }

    // --------------------------------------------------------------- system

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        try {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        } catch (Throwable ignored) {
        }
    }

    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                StatusBarManager manager = getSystemService(StatusBarManager.class);
                if (manager != null) {
                    manager.requestAddTileService(
                            new ComponentName(this, DimTileService.class),
                            getString(R.string.tile_label),
                            Icon.createWithResource(this, R.drawable.ic_moon),
                            getMainExecutor(),
                            result -> {
                            });
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        Ui.toast(this, getString(R.string.tile_add_hint));
    }

    private void pinWidget() {
        if (Build.VERSION.SDK_INT < 26) {
            Ui.toast(this, getString(R.string.widget_pin_failed));
            return;
        }

        String[] titles = {
                getString(R.string.widget_variant_presets),
                getString(R.string.widget_variant_mini),
                getString(R.string.widget_variant_panel),
                getString(R.string.widget_variant_toggle)
        };
        String[] descriptions = {
                getString(R.string.widget_variant_presets_desc),
                getString(R.string.widget_variant_mini_desc),
                getString(R.string.widget_variant_panel_desc),
                getString(R.string.widget_variant_toggle_desc)
        };
        int[] icons = {
                R.drawable.ic_flame,
                R.drawable.ic_moon,
                R.drawable.ic_sun,
                R.drawable.ic_shield
        };
        Class<?>[] providers = {
                DimWidgetProvider.class,
                MiniWidgetProvider.class,
                PanelWidgetProvider.class,
                ToggleWidgetProvider.class
        };

        int side = Ui.dp(this, 8);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(side, Ui.dp(this, 4), side, 0);

        AlertDialog dialog = Ui.dialog(this)
                .setTitle(R.string.widget_pick_title)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .create();

        for (int i = 0; i < titles.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_row);
            int pad = Ui.dp(this, 12);
            row.setPadding(pad, pad, pad, pad);

            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            icon.setBackgroundResource(R.drawable.bg_widget_button);
            int iconPad = Ui.dp(this, 8);
            icon.setPadding(iconPad, iconPad, iconPad, iconPad);
            row.addView(icon, new LinearLayout.LayoutParams(
                    Ui.dp(this, 38), Ui.dp(this, 38)));

            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            textParams.leftMargin = Ui.dp(this, 12);
            row.addView(text, textParams);

            TextView title = new TextView(this);
            title.setText(titles[i]);
            title.setTextColor(getColor(R.color.text));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.addView(title);

            TextView description = new TextView(this);
            description.setText(descriptions[i]);
            description.setTextColor(getColor(R.color.text2));
            description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            descriptionParams.topMargin = Ui.dp(this, 2);
            text.addView(description, descriptionParams);

            ImageView chevron = new ImageView(this);
            chevron.setImageResource(R.drawable.ic_chevron);
            chevron.setImageTintList(ColorStateList.valueOf(getColor(R.color.text3)));
            chevron.setContentDescription(getString(R.string.widget_pin));
            row.addView(chevron, new LinearLayout.LayoutParams(
                    Ui.dp(this, 20), Ui.dp(this, 20)));

            final Class<?> provider = providers[i];
            row.setOnClickListener(v -> {
                dialog.dismiss();
                requestWidgetPin(provider);
            });

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = Ui.dp(this, 6);
            box.addView(row, rowParams);
        }

        dialog.show();
    }

    private void requestWidgetPin(Class<?> provider) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            if (manager != null && manager.isRequestPinAppWidgetSupported()) {
                manager.requestPinAppWidget(new ComponentName(this, provider), null, null);
            } else {
                Ui.toast(this, getString(R.string.widget_pin_failed));
            }
        } catch (Throwable t) {
            Ui.toast(this, getString(R.string.widget_pin_failed));
        }
    }

    private void fillVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionText.setText(getString(R.string.version_fmt,
                    info.versionName, Build.VERSION.RELEASE));
        } catch (Throwable t) {
            versionText.setText(getString(R.string.app_name));
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ holder

    private static final class LayerRow {
        View root;
        TextView title;
        TextView value;
        TextView hint;
        Switch toggle;
        ParamSlider slider;
    }
}
