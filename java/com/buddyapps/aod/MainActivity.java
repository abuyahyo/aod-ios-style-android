package com.buddyapps.aod;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private Button toggle;
    private Button preview;
    private Button standbyPreview;

    private Button standbyToggle;
    private Button wallpaperToggle;
    private Button notifToggle;
    private Button notifContentToggle;
    private Button nightModeToggle;

    private Button overlayPerm;
    private Button batteryPerm;
    private Button notifPerm;
    private TextView status;
    private TextView diag;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        buildUi();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0B0B0D);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), dp(44), dp(24), dp(40));
        scroll.addView(column);

        TextView title = new TextView(this);
        title.setText("AOD & StandBy");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(-0.02f);
        column.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Экран блокировки и режим ожидания как на iOS");
        subtitle.setTextColor(0xFF8E8E93);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.bottomMargin = dp(24);
        column.addView(subtitle, subLp);

        status = new TextView(this);
        status.setTextColor(0xFF8E8E93);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.bottomMargin = dp(16);
        column.addView(status, statusLp);

        toggle = button("Включить AOD", 0xFF0A84FF, 0xFFFFFFFF);
        toggle.setOnClickListener(v -> onToggle());
        add(column, toggle, 10);

        preview = button("Показать экран AOD (Портрет)", 0xFF1C1C1E, 0xFFFFFFFF);
        preview.setOnClickListener(v -> startActivity(new Intent(this, AodActivity.class)));
        add(column, preview, 10);

        standbyPreview = button("Показать режим StandBy (Ожидание)", 0xFF1C1C1E, 0xFF0A84FF);
        standbyPreview.setOnClickListener(v -> {
            Intent intent = new Intent(this, AodActivity.class);
            intent.putExtra("preview_standby", true);
            startActivity(intent);
        });
        add(column, standbyPreview, 10);

        TextView settingsTitle = new TextView(this);
        settingsTitle.setText("НАСТРОЙКИ РЕЖИМОВ");
        settingsTitle.setTextColor(0xFF8E8E93);
        settingsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        settingsTitle.setLetterSpacing(0.08f);
        settingsTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(24);
        stLp.bottomMargin = dp(6);
        column.addView(settingsTitle, stLp);

        standbyToggle = button("StandBy при зарядке: ВКЛ", 0xFF1C1C1E, 0xFFFFFFFF);
        standbyToggle.setOnClickListener(v -> {
            boolean cur = AodService.isStandByEnabled(this);
            AodService.setStandByEnabled(this, !cur);
            refresh();
        });
        add(column, standbyToggle, 8);

        wallpaperToggle = button("Обои на AOD: ВКЛ", 0xFF1C1C1E, 0xFFFFFFFF);
        wallpaperToggle.setOnClickListener(v -> {
            boolean cur = AodService.isShowWallpaper(this);
            AodService.setShowWallpaper(this, !cur);
            refresh();
        });
        add(column, wallpaperToggle, 8);

        notifToggle = button("Уведомления на AOD: ВКЛ", 0xFF1C1C1E, 0xFFFFFFFF);
        notifToggle.setOnClickListener(v -> {
            boolean cur = AodService.isShowNotifications(this);
            AodService.setShowNotifications(this, !cur);
            refresh();
        });
        add(column, notifToggle, 8);

        notifContentToggle = button("Содержание уведомлений скрыто", 0xFF1C1C1E, 0xFFFFFFFF);
        notifContentToggle.setOnClickListener(v -> {
            boolean cur = AodService.isNotificationContentVisible(this);
            AodService.setNotificationContentVisible(this, !cur);
            refresh();
        });
        add(column, notifContentToggle, 8);

        nightModeToggle = button("Красный ночной режим StandBy: ВКЛ", 0xFF1C1C1E, 0xFFFFFFFF);
        nightModeToggle.setOnClickListener(v -> {
            boolean cur = AodService.isNightModeEnabled(this);
            AodService.setNightModeEnabled(this, !cur);
            refresh();
        });
        add(column, nightModeToggle, 8);

        overlayPerm = button("Разрешить показ поверх окон", 0xFF1C1C1E, 0xFFFF453A);
        overlayPerm.setOnClickListener(v -> openOverlaySettings());
        add(column, overlayPerm, 14);

        batteryPerm = button("Не выключать в фоне", 0xFF1C1C1E, 0xFFFF9F0A);
        batteryPerm.setOnClickListener(v -> openBatterySettings());
        add(column, batteryPerm, 10);

        notifPerm = button("Разрешить доступ к уведомлениям", 0xFF1C1C1E, 0xFF0A84FF);
        notifPerm.setOnClickListener(v -> openNotificationSettings());
        add(column, notifPerm, 10);

        TextView help = new TextView(this);
        help.setText("Как работают AOD и StandBy:\n\n"
                + "• Always-On Display (AOD):\n"
                + "При выключении экрана показывает приглушённый экран блокировки как на iPhone: "
                + "затемнённые обои, крупные часы, дату, виджеты и уведомления с иконками. "
                + "Снизу фонарик и камера. Поднятие телефона или касание на несколько секунд ярко показывает экран блокировки.\n\n"
                + "• Режим StandBy («Ожидание»):\n"
                + "Активируется, когда смартфон стоит на зарядке и повернут горизонтально. "
                + "Экран превращается в прикроватную умную станцию: слева большие часы (тап по ним меняет стиль), "
                + "справа виджеты календаря, заряда батареи и будильника.\n\n"
                + "• Ночной режим StandBy:\n"
                + "В темноте датчик освещенности автоматически переключает StandBy на мягкий красный свет без бликов.\n\n"
                + "• Датчики и защита:\n"
                + "Экран гаснет полностью в кармане или экраном вниз (датчик приближения), "
                + "а также при включении режима энергосбережения.\n\n"
                + "• Приватность уведомлений:\n"
                + "По умолчанию на AOD видны только названия приложений. В настройках можно разрешить текст уведомлений.");
        help.setTextColor(0xFF6E6E73);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        help.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        helpLp.topMargin = dp(26);
        column.addView(help, helpLp);

        diag = new TextView(this);
        diag.setTextColor(0xFF6E6E73);
        diag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        diag.setTypeface(android.graphics.Typeface.MONOSPACE);
        diag.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams diagLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        diagLp.topMargin = dp(22);
        column.addView(diag, diagLp);

        setContentView(scroll);
    }

    private void add(LinearLayout parent, View view, float topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        parent.addView(view, lp);
    }

    private void setColor(Button b, int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
    }

    private Button button(String text, int color, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        b.setBackground(bg);
        b.setPadding(dp(16), dp(13), dp(16), dp(13));
        b.setStateListAnimator(null);
        b.setElevation(0);
        return b;
    }

    private void onToggle() {
        if (AodService.isEnabled(this)) {
            AodService.start(this, false);
        } else {
            if (!Settings.canDrawOverlays(this)) {
                openOverlaySettings();
                return;
            }
            AodService.start(this, true);
        }
        refresh();
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void openBatterySettings() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception ignored) {
        }
    }

    private void openNotificationSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean enabled = AodService.isEnabled(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean batteryOk = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());

        if (enabled && overlay) {
            toggle.setText("Выключить AOD");
            setColor(toggle, 0xFF2C2C2E);
        } else {
            toggle.setText("Включить AOD");
            setColor(toggle, 0xFF0A84FF);
        }

        boolean sb = AodService.isStandByEnabled(this);
        standbyToggle.setText("StandBy при зарядке: " + (sb ? "ВКЛ" : "ВЫКЛ"));
        standbyToggle.setTextColor(sb ? 0xFF30D158 : 0xFF8E8E93);

        boolean wall = AodService.isShowWallpaper(this);
        wallpaperToggle.setText("Обои на AOD: " + (wall ? "ВКЛ (обои)" : "ВЫКЛ (черный фон)"));
        wallpaperToggle.setTextColor(wall ? 0xFF30D158 : 0xFF8E8E93);

        boolean notif = AodService.isShowNotifications(this);
        notifToggle.setText("Уведомления на экране: " + (notif ? "ВКЛ" : "ВЫКЛ"));
        notifToggle.setTextColor(notif ? 0xFF30D158 : 0xFF8E8E93);

        boolean showNotifContent = AodService.isNotificationContentVisible(this);
        notifContentToggle.setText(showNotifContent
                ? "Текст уведомлений: ПОКАЗЫВАТЬ"
                : "Текст уведомлений: СКРЫТЬ");
        notifContentToggle.setTextColor(showNotifContent ? 0xFFFF9F0A : 0xFF30D158);

        boolean night = AodService.isNightModeEnabled(this);
        nightModeToggle.setText("Красный ночной режим StandBy: " + (night ? "ВКЛ" : "ВЫКЛ"));
        nightModeToggle.setTextColor(night ? 0xFFFF453A : 0xFF8E8E93);

        overlayPerm.setVisibility(overlay ? View.GONE : View.VISIBLE);
        batteryPerm.setVisibility(batteryOk ? View.GONE : View.VISIBLE);
        boolean notifAccess = NotifService.hasAccess(this);
        if (notifPerm != null) {
            notifPerm.setVisibility(notifAccess ? View.GONE : View.VISIBLE);
        }
        status.setText((enabled ? "AOD включён" : "AOD выключен")
                + (overlay ? " · разрешение есть" : " · нужно разрешение")
                + (AodService.running ? " · служба работает" : " · служба не работает")
                + (AodService.getLastError(this).isEmpty() ? "" : "\nОшибка: " + AodService.getLastError(this)));
        if (diag != null) {
            diag.setText("Журнал:\n" + Diag.tail(this, 5));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (AodService.isEnabled(this) && Settings.canDrawOverlays(this)
                && !AodService.running) {
            Diag.log(this, "перезапускаю службу AOD при открытии приложения");
            AodService.start(this, true);
            getWindow().getDecorView().postDelayed(() -> {
                if (!isFinishing()) refresh();
            }, 800L);
        }
        refresh();
    }
}
