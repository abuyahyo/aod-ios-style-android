package com.buddyapps.aod;

import android.service.dreams.DreamService;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** A system-managed charging screensaver for StandBy-compatible devices. */
public class AodDreamService extends DreamService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView clock;
    private TextView date;
    private TextView battery;
    private long lastShiftSlot = -1L;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (AodService.isSleepTime(AodDreamService.this)) {
                Diag.log(AodDreamService.this, "расписание сна — заставка остановлена");
                finish();
                return;
            }
            updateContent();
            shiftContent();
            long untilNextMinute = 60000L - (System.currentTimeMillis() % 60000L);
            handler.postDelayed(this, Math.max(1000L, untilNextMinute));
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));

        date = new TextView(this);
        date.setTextColor(0xBFFFFFFF);
        date.setTextSize(20);
        date.setGravity(Gravity.CENTER);
        content.addView(date, matchWrap());

        clock = new TextView(this);
        clock.setTextColor(0xF2FFFFFF);
        clock.setTextSize(92);
        clock.setGravity(Gravity.CENTER);
        clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", 0));
        content.addView(clock, matchWrap());

        battery = new TextView(this);
        battery.setTextColor(0xAFFFFFFF);
        battery.setTextSize(18);
        battery.setGravity(Gravity.CENTER);
        content.addView(battery, matchWrap());

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        updateContent();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(tick);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void updateContent() {
        Date now = Calendar.getInstance().getTime();
        boolean hour24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(hour24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int percent = level >= 0 && scale > 0 ? Math.round(level * 100f / scale) : -1;
            battery.setText(percent >= 0 ? percent + "%" : "");
        }
    }

    private void shiftContent() {
        long slot = System.currentTimeMillis() / 300000L;
        if (slot == lastShiftSlot) return;
        lastShiftSlot = slot;
        int step = (int) (slot % 9L);
        content.setTranslationX((step % 3 - 1) * 2f);
        content.setTranslationY((step / 3 - 1) * 2f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
