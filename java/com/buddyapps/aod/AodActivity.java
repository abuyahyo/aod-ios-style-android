package com.buddyapps.aod;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Always-On Display Activity:
 * 1. Portrait: dimmed iOS lock screen (wallpaper, date, clock, widgets, notifications, flashlight, camera).
 * 2. StandBy: Activated when charging and horizontal (dual widgets: big stacked/digital clock + calendar & status),
 *    with automatic red night mode in the dark.
 */
public class AodActivity extends Activity implements SensorEventListener {

    public static volatile boolean showing = false;
    public static volatile long lastDismiss = 0L;

    private static final float DIM = 0.10f;
    private static final float BRIGHT = 0.85f;
    private static final long WAKE_TRANSITION_MS = 380L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout root;

    // Portrait UI
    private LinearLayout portraitContent;
    private View wallpaperScrim;
    private TextView portraitClock;
    private TextView portraitDate;
    private TextView portraitBatteryValue;
    private TextView portraitAlarmValue;
    private LinearLayout notifBox;

    // StandBy UI
    private LinearLayout standByContent;
    private LinearLayout clockWidgetBox;
    private TextView standByHour;
    private TextView standByMin;
    private TextView standByClockWide;
    private TextView standByClockWideDate;
    private TextView standByClockWideSec;
    private LinearLayout wideClockBox;
    private LinearLayout stackedClockBox;

    // StandBy right widgets
    private LinearLayout calCard;
    private TextView calMonth;
    private TextView calDay;
    private TextView calDayOfWeek;

    private LinearLayout statusCard;
    private TextView statusHeader;
    private TextView statusBattery;
    private TextView statusAlarm;
    private TextView statusNotifs;

    // State
    private boolean isStandBy = false;
    private boolean isCharging = false;
    private boolean isLandscape = false;
    private boolean isNightMode = false;
    private boolean forceStandByPreview = false;
    private int standByClockStyle = 0; // 0 = Stacked, 1 = Wide

    private boolean brightened = false;
    private boolean onScreen = false;
    private boolean covered = false;
    private long lastBurnInShiftMinute = -1L;
    private int batteryPct = -1;
    private boolean batteryIsCharging = false;

    private TextView portraitCalWeekday;
    private TextView portraitCalDay;
    private GradientDrawable flashlightBg;
    private Glyph flashlightGlyph;
    private Glyph batteryGlyph;
    private boolean torchOn;
    private String torchId;
    private CameraManager cameraManager;
    private GradientDrawable scrimDrawable;

    private static final int REQ_TORCH = 42;

    private final Runnable dimAgain = new Runnable() {
        @Override
        public void run() {
            brightened = false;
            if (!isFinishing() && !covered) {
                animateBrightness(DIM, WAKE_TRANSITION_MS);
                applyWallpaperMood(false);
            }
        }
    };

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (torchId != null && torchId.equals(cameraId)) {
                torchOn = enabled;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshFlashlight();
                    }
                });
            }
        }
    };

    private SensorManager sensorManager;
    private Sensor proximity;
    private Sensor lightSensor;
    private Sensor accelerometer;
    private int pickupMotionSamples;
    private long lastPickupWake;
    private boolean pickupWakePending;
    private OrientationEventListener orientationListener;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (action == null) return;
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                dismiss();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                if (onScreen) dismiss();
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                updateBatteryFromIntent(intent);
                evaluateMode();
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                isCharging = true;
                batteryIsCharging = true;
                evaluateMode();
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                isCharging = false;
                batteryIsCharging = false;
                evaluateMode();
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!onScreen) return;
            updateClocksAndWidgets();
            burnInShift();
            checkBatterySaver();
            handler.postDelayed(this, nextTickDelay());
        }
    };

    private long nextTickDelay() {
        // Only the optional wide StandBy clock shows seconds. The lock screen and
        // other clock styles need minute updates, which cuts needless wakeups.
        if (isStandBy && standByClockWideSec != null
                && standByClockWideSec.getVisibility() == View.VISIBLE) {
            return 1000L;
        }
        long untilNextMinute = 60000L - (System.currentTimeMillis() % 60000L);
        return Math.max(1000L, untilNextMinute);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        forceStandByPreview = getIntent().getBooleanExtra("preview_standby", false);
        Diag.log(this, "AOD показан (режим " + (forceStandByPreview ? "StandBy предпросмотр" : "стандарт") + ")");

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        getWindow().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        hideSystemBars();
        setBrightness(DIM);

        checkInitialBattery();
        buildUi();

        initOrientationListener();
    }

    private void checkInitialBattery() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                updateBatteryFromIntent(batteryStatus);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateBatteryFromIntent(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        if (level >= 0 && scale > 0) {
            batteryPct = Math.round(level * 100f / scale);
        }
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        batteryIsCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        isCharging = batteryIsCharging;
        updateBatteryDisplays();
    }

    private void initOrientationListener() {
        orientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                boolean landscape = (orientation >= 60 && orientation <= 120)
                        || (orientation >= 240 && orientation <= 300);
                if (landscape != isLandscape) {
                    isLandscape = landscape;
                    evaluateMode();
                }
            }
        };
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean landscape = (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
        if (landscape != isLandscape) {
            isLandscape = landscape;
            evaluateMode();
        }
    }

    private void evaluateMode() {
        boolean standByAllowed = AodService.isStandByEnabled(this);
        boolean shouldBeStandBy = forceStandByPreview || (standByAllowed && isCharging && isLandscape);
        if (shouldBeStandBy != isStandBy) {
            isStandBy = shouldBeStandBy;
            applyModeSwitch();
        }
    }

    private void applyModeSwitch() {
        View incoming = isStandBy ? standByContent : portraitContent;
        View outgoing = isStandBy ? portraitContent : standByContent;
        if (isStandBy) {
            if (wallpaperScrim != null) wallpaperScrim.setVisibility(View.GONE);
            root.setBackgroundColor(Color.BLACK);
            portraitContent.setVisibility(View.VISIBLE);
            standByContent.setVisibility(View.VISIBLE);
            applyNightColors(isNightMode);
            Diag.log(this, "режим StandBy активирован");
        } else {
            boolean showWall = AodService.isShowWallpaper(this);
            if (showWall) {
                if (wallpaperScrim != null) wallpaperScrim.setVisibility(View.VISIBLE);
                root.setBackgroundColor(Color.TRANSPARENT);
            } else {
                if (wallpaperScrim != null) wallpaperScrim.setVisibility(View.GONE);
                root.setBackgroundColor(Color.BLACK);
            }
            portraitContent.setVisibility(View.VISIBLE);
            standByContent.setVisibility(View.VISIBLE);
            Diag.log(this, "режим Portrait AOD активирован");
        }
        updateClocksAndWidgets();
        if (covered) {
            incoming.setAlpha(0f);
            outgoing.setAlpha(0f);
        } else {
            incoming.animate().cancel();
            incoming.setAlpha(0f);
            incoming.setTranslationX(isStandBy ? dp(8) : -dp(8));
            incoming.setScaleX(0.99f);
            incoming.setScaleY(0.99f);
            incoming.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(WAKE_TRANSITION_MS).start();
            outgoing.animate().cancel();
            outgoing.animate().alpha(0f).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(WAKE_TRANSITION_MS).withEndAction(() -> {
                outgoing.setVisibility(View.GONE);
                outgoing.setAlpha(1f);
            }).start();
        }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void addWallpaper(FrameLayout parent) {
        // FLAG_SHOW_WALLPAPER lets Android draw its own wallpaper behind our
        // transparent window. Reading the image directly is denied on Android 14+.
        Diag.log(this, "фон рабочего стола берётся напрямую из системы");
        wallpaperScrim = new View(this);
        scrimDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x80000000, 0x9A000000, 0xC4000000});
        wallpaperScrim.setBackground(scrimDrawable);
        wallpaperScrim.setAlpha(1f);
        parent.addView(wallpaperScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void applyWallpaperMood(boolean bright) {
        if (wallpaperScrim != null) {
            wallpaperScrim.animate().cancel();
            wallpaperScrim.animate().alpha(bright ? 0.28f : 1f)
                    .setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(WAKE_TRANSITION_MS).start();
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        addWallpaper(root);

        buildPortraitUi();
        buildStandByUi();

        setContentView(root);
        root.setAlpha(0f);
        root.post(() -> root.animate().alpha(1f).setDuration(420L).start());

        final GestureDetector gestures = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        brighten();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        dismiss();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (e1 != null && Math.abs(e1.getY() - e2.getY()) > dp(60)) {
                            dismiss();
                            return true;
                        }
                        return false;
                    }
                });

        root.setOnTouchListener((v, event) -> gestures.onTouchEvent(event));
    }

    private void buildPortraitUi() {
        portraitContent = new LinearLayout(this);
        portraitContent.setOrientation(LinearLayout.VERTICAL);
        portraitContent.setGravity(Gravity.CENTER_HORIZONTAL);
        portraitContent.setPadding(dp(26), dp(30), dp(26), dp(30));
        portraitContent.addView(new Space(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.22f));

        LockIcon lock = new LockIcon(this);
        portraitContent.addView(lock, new LinearLayout.LayoutParams(dp(17), dp(24)));

        portraitDate = new TextView(this);
        portraitDate.setTextColor(0xE6FFFFFF);
        portraitDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        portraitDate.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        portraitDate.setLetterSpacing(0.01f);
        portraitDate.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp(12);
        portraitContent.addView(portraitDate, dateLp);

        portraitClock = new TextView(this);
        portraitClock.setTextColor(0xF2FFFFFF);
        portraitClock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 92);
        portraitClock.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        portraitClock.setLetterSpacing(-0.03f);
        portraitClock.setIncludeFontPadding(false);
        portraitClock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clockLp.topMargin = dp(2);
        portraitContent.addView(portraitClock, clockLp);

        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams cardsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardsLp.topMargin = dp(22);
        cardsLp.leftMargin = dp(4);
        cardsLp.rightMargin = dp(4);
        calendarWidget(cards);
        statusWidget(cards);
        portraitContent.addView(cards, cardsLp);

        notifBox = new LinearLayout(this);
        notifBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams notifLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        notifLp.topMargin = dp(14);
        notifLp.leftMargin = dp(6);
        notifLp.rightMargin = dp(6);
        portraitContent.addView(notifBox, notifLp);

        portraitContent.addView(new Space(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        shortcuts.setGravity(Gravity.CENTER_VERTICAL);
        shortcuts.addView(circleButton(true));
        shortcuts.addView(new Space(this), new LinearLayout.LayoutParams(0, 0, 1f));
        shortcuts.addView(circleButton(false));
        LinearLayout.LayoutParams shortLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shortLp.leftMargin = dp(18);
        shortLp.rightMargin = dp(18);
        shortLp.bottomMargin = dp(8);
        portraitContent.addView(shortcuts, shortLp);

        root.addView(portraitContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private GradientDrawable widgetBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x2EFFFFFF);
        bg.setCornerRadius(dp(22));
        return bg;
    }

    private void calendarWidget(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setMinimumHeight(dp(88));
        box.setBackground(widgetBackground());

        portraitCalWeekday = new TextView(this);
        portraitCalWeekday.setTextColor(0xFFFF453A);
        portraitCalWeekday.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        portraitCalWeekday.setLetterSpacing(0.06f);
        portraitCalWeekday.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        portraitCalWeekday.setSingleLine(true);
        box.addView(portraitCalWeekday);

        portraitCalDay = new TextView(this);
        portraitCalDay.setText("—");
        portraitCalDay.setTextColor(0xF2FFFFFF);
        portraitCalDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36);
        portraitCalDay.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        portraitCalDay.setIncludeFontPadding(false);
        box.addView(portraitCalDay);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(7);
        parent.addView(box, lp);
    }

    private void statusWidget(LinearLayout parent) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(16), dp(12), dp(14), dp(12));
        box.setMinimumHeight(dp(88));
        box.setBackground(widgetBackground());

        box.addView(iconValueRow(Glyph.BELL, true));
        box.addView(iconValueRow(Glyph.BATTERY, false));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(7);
        parent.addView(box, lp);
    }

    private LinearLayout iconValueRow(int kind, boolean alarm) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Glyph glyph = new Glyph(this, kind);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView val = new TextView(this);
        val.setText("—");
        val.setTextColor(0xF2FFFFFF);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        val.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        val.setSingleLine(true);
        LinearLayout.LayoutParams valLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valLp.leftMargin = dp(8);
        row.addView(val, valLp);

        if (alarm) {
            portraitAlarmValue = val;
        } else {
            portraitBatteryValue = val;
            batteryGlyph = glyph;
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.topMargin = dp(6);
            row.setLayoutParams(rowLp);
        }
        return row;
    }

    private FrameLayout circleButton(boolean flash) {
        FrameLayout wrap = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x38FFFFFF);
        wrap.setBackground(bg);
        Glyph glyph = new Glyph(this, flash ? Glyph.FLASH : Glyph.CAMERA);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(26), dp(26));
        glp.gravity = Gravity.CENTER;
        wrap.addView(glyph, glp);
        wrap.setOnClickListener(v -> {
            brighten();
            if (flash) onFlashlight();
            else openCamera();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
        wrap.setLayoutParams(lp);
        if (flash) {
            flashlightBg = bg;
            flashlightGlyph = glyph;
        }
        return wrap;
    }

    /** iOS 17 StandBy layout: Split into 2 halves (Clock widget + Calendar/Status widgets). */
    private void buildStandByUi() {
        standByContent = new LinearLayout(this);
        standByContent.setOrientation(LinearLayout.HORIZONTAL);
        standByContent.setGravity(Gravity.CENTER);
        standByContent.setPadding(dp(28), dp(20), dp(28), dp(20));
        standByContent.setWeightSum(2f);
        standByContent.setVisibility(View.GONE);

        // LEFT HALF: StandBy Clock (Tap switches Stacked vs Wide)
        clockWidgetBox = new LinearLayout(this);
        clockWidgetBox.setOrientation(LinearLayout.VERTICAL);
        clockWidgetBox.setGravity(Gravity.CENTER);
        clockWidgetBox.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Style 0: Stacked iOS StandBy clock (Hour on top, Minute on bottom)
        stackedClockBox = new LinearLayout(this);
        stackedClockBox.setOrientation(LinearLayout.VERTICAL);
        stackedClockBox.setGravity(Gravity.CENTER);

        standByHour = new TextView(this);
        standByHour.setTextColor(0xF2FFFFFF);
        standByHour.setTextSize(TypedValue.COMPLEX_UNIT_SP, 84);
        standByHour.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        standByHour.setLetterSpacing(-0.04f);
        standByHour.setIncludeFontPadding(false);
        standByHour.setGravity(Gravity.CENTER);
        stackedClockBox.addView(standByHour);

        standByMin = new TextView(this);
        standByMin.setTextColor(0xF2FFFFFF);
        standByMin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 84);
        standByMin.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        standByMin.setLetterSpacing(-0.04f);
        standByMin.setIncludeFontPadding(false);
        standByMin.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams minLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        minLp.topMargin = -dp(12);
        stackedClockBox.addView(standByMin, minLp);

        clockWidgetBox.addView(stackedClockBox);

        // Style 1: Wide digital clock
        wideClockBox = new LinearLayout(this);
        wideClockBox.setOrientation(LinearLayout.VERTICAL);
        wideClockBox.setGravity(Gravity.CENTER);
        wideClockBox.setVisibility(View.GONE);

        standByClockWideDate = new TextView(this);
        standByClockWideDate.setTextColor(0xB3FFFFFF);
        standByClockWideDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        standByClockWideDate.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        standByClockWideDate.setLetterSpacing(0.06f);
        standByClockWideDate.setGravity(Gravity.CENTER);
        wideClockBox.addView(standByClockWideDate);

        standByClockWide = new TextView(this);
        standByClockWide.setTextColor(0xF2FFFFFF);
        standByClockWide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 58);
        standByClockWide.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        standByClockWide.setLetterSpacing(-0.03f);
        standByClockWide.setIncludeFontPadding(false);
        standByClockWide.setGravity(Gravity.CENTER);
        wideClockBox.addView(standByClockWide);

        standByClockWideSec = new TextView(this);
        standByClockWideSec.setTextColor(0x8CFFFFFF);
        standByClockWideSec.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        standByClockWideSec.setGravity(Gravity.CENTER);
        wideClockBox.addView(standByClockWideSec);

        clockWidgetBox.addView(wideClockBox);

        // Switch clock style on tap
        clockWidgetBox.setOnClickListener(v -> {
            standByClockStyle = (standByClockStyle == 0) ? 1 : 0;
            stackedClockBox.setVisibility(standByClockStyle == 0 ? View.VISIBLE : View.GONE);
            wideClockBox.setVisibility(standByClockStyle == 1 ? View.VISIBLE : View.GONE);
            brighten();
        });

        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftLp.rightMargin = dp(14);
        standByContent.addView(clockWidgetBox, leftLp);

        // RIGHT HALF: 2 Stacked Cards (Calendar + Status/Battery/Alarm)
        LinearLayout rightColumn = new LinearLayout(this);
        rightColumn.setOrientation(LinearLayout.VERTICAL);
        rightColumn.setGravity(Gravity.CENTER);

        // Card 1: Calendar Widget
        calCard = new LinearLayout(this);
        calCard.setOrientation(LinearLayout.VERTICAL);
        calCard.setGravity(Gravity.CENTER_VERTICAL);
        calCard.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable calBg = new GradientDrawable();
        calBg.setColor(0x22FFFFFF);
        calBg.setCornerRadius(dp(22));
        calCard.setBackground(calBg);

        calMonth = new TextView(this);
        calMonth.setTextColor(0xFFFF453A); // iOS Red accent
        calMonth.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        calMonth.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        calMonth.setLetterSpacing(0.08f);
        calCard.addView(calMonth);

        calDay = new TextView(this);
        calDay.setTextColor(0xF2FFFFFF);
        calDay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        calDay.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        calDay.setIncludeFontPadding(false);
        LinearLayout.LayoutParams dayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dayLp.topMargin = -dp(2);
        calCard.addView(calDay, dayLp);

        calDayOfWeek = new TextView(this);
        calDayOfWeek.setTextColor(0xB3FFFFFF);
        calDayOfWeek.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        calDayOfWeek.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        calCard.addView(calDayOfWeek);

        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        calLp.bottomMargin = dp(10);
        rightColumn.addView(calCard, calLp);

        // Card 2: Status & Battery & Alarm
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable stBg = new GradientDrawable();
        stBg.setColor(0x22FFFFFF);
        stBg.setCornerRadius(dp(22));
        statusCard.setBackground(stBg);

        statusHeader = new TextView(this);
        statusHeader.setText("СТАТУС УСТРОЙСТВА");
        statusHeader.setTextColor(0x8CFFFFFF);
        statusHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        statusHeader.setLetterSpacing(0.08f);
        statusHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusCard.addView(statusHeader);

        statusBattery = new TextView(this);
        statusBattery.setText("⚡ 100%");
        statusBattery.setTextColor(0xF2FFFFFF);
        statusBattery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        statusBattery.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        LinearLayout.LayoutParams batLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        batLp.topMargin = dp(2);
        statusCard.addView(statusBattery, batLp);

        statusAlarm = new TextView(this);
        statusAlarm.setText("⏰ Будильник: —");
        statusAlarm.setTextColor(0xB3FFFFFF);
        statusAlarm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams almLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        almLp.topMargin = dp(2);
        statusCard.addView(statusAlarm, almLp);

        statusNotifs = new TextView(this);
        statusNotifs.setText("");
        statusNotifs.setTextColor(0x8CFFFFFF);
        statusNotifs.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        statusNotifs.setVisibility(View.GONE);
        statusCard.addView(statusNotifs);

        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rightColumn.addView(statusCard, stLp);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.leftMargin = dp(14);
        standByContent.addView(rightColumn, rightLp);

        root.addView(standByContent, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Night Mode in StandBy: Shifts all typography into soft glowing iOS Night Red (0xFFFF3B30). */
    private void applyNightColors(boolean night) {
        if (!AodService.isNightModeEnabled(this)) {
            night = false;
        }
        int mainColor = night ? 0xFFFF3B30 : 0xF2FFFFFF;
        int subColor = night ? 0xCCFF3B30 : 0xB3FFFFFF;
        int dimColor = night ? 0x99FF3B30 : 0x8CFFFFFF;
        int cardColor = night ? 0x24FF3B30 : 0x22FFFFFF;

        if (standByHour != null) standByHour.setTextColor(mainColor);
        if (standByMin != null) standByMin.setTextColor(mainColor);
        if (standByClockWide != null) standByClockWide.setTextColor(mainColor);
        if (standByClockWideDate != null) standByClockWideDate.setTextColor(subColor);
        if (standByClockWideSec != null) standByClockWideSec.setTextColor(dimColor);

        if (calMonth != null) calMonth.setTextColor(night ? 0xFFFF3B30 : 0xFFFF453A);
        if (calDay != null) calDay.setTextColor(mainColor);
        if (calDayOfWeek != null) calDayOfWeek.setTextColor(subColor);

        if (statusHeader != null) statusHeader.setTextColor(dimColor);
        if (statusBattery != null) statusBattery.setTextColor(mainColor);
        if (statusAlarm != null) statusAlarm.setTextColor(subColor);
        if (statusNotifs != null) statusNotifs.setTextColor(dimColor);

        if (calCard != null) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(cardColor);
            d.setCornerRadius(dp(22));
            if (night) d.setStroke(dp(1), 0x33FF3B30);
            calCard.setBackground(d);
        }
        if (statusCard != null) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(cardColor);
            d.setCornerRadius(dp(22));
            if (night) d.setStroke(dp(1), 0x33FF3B30);
            statusCard.setBackground(d);
        }
    }

    private void renderNotifications() {
        if (notifBox == null) return;
        notifBox.removeAllViews();

        boolean showNotifs = AodService.isShowNotifications(this);
        if (!showNotifs) {
            notifBox.setVisibility(View.GONE);
            if (statusNotifs != null) statusNotifs.setVisibility(View.GONE);
            return;
        }

        List<NotifService.Item> list = NotifService.items;
        if (list == null || list.isEmpty()) {
            notifBox.setVisibility(View.GONE);
            if (statusNotifs != null) statusNotifs.setVisibility(View.GONE);
            return;
        }

        if (statusNotifs != null) {
            statusNotifs.setVisibility(View.VISIBLE);
            statusNotifs.setText("Уведомлений: " + list.size());
        }

        notifBox.setVisibility(View.VISIBLE);
        int shown = Math.min(3, list.size());
        for (int i = 0; i < shown; i++) {
            notifBox.addView(notificationCard(list.get(i)), notificationCardLp());
        }
        if (list.size() > shown) {
            TextView more = new TextView(this);
            more.setText("ещё " + (list.size() - shown));
            more.setTextColor(0x99FFFFFF);
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            more.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            moreLp.topMargin = dp(6);
            notifBox.addView(more, moreLp);
        }
    }

    private LinearLayout.LayoutParams notificationCardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        return lp;
    }

    private LinearLayout notificationCard(NotifService.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x30FFFFFF);
        bg.setCornerRadius(dp(22));
        row.setBackground(bg);

        ImageView icon = new ImageView(this);
        int size = dp(36);
        if (item.icon != null) icon.setImageDrawable(item.icon);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setClipToOutline(true);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        if (item.icon == null) {
            GradientDrawable ph = new GradientDrawable();
            ph.setCornerRadius(dp(10));
            ph.setColor(0x55FFFFFF);
            icon.setBackground(ph);
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(size, size);
        iconLp.rightMargin = dp(12);
        row.addView(icon, iconLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        String title = item.title == null ? "" : item.title;
        String body = item.text == null ? "" : item.text;
        String app = item.app == null ? "" : item.app;

        TextView titleView = new TextView(this);
        titleView.setText(title.isEmpty() ? app : title);
        titleView.setTextColor(0xF2FFFFFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textCol.addView(titleView);

        String sub = title.isEmpty() ? body : (body.isEmpty() ? app : body);
        if (!sub.isEmpty()) {
            TextView bodyView = new TextView(this);
            bodyView.setText(sub);
            bodyView.setTextColor(0xB3FFFFFF);
            bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            bodyView.setMaxLines(1);
            bodyView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(bodyView);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void burnInShift() {
        View target = isStandBy ? standByContent : portraitContent;
        if (target == null) return;
        long minute = System.currentTimeMillis() / 60000L;
        if (minute == lastBurnInShiftMinute) return;
        lastBurnInShiftMinute = minute;
        // Walk a small 3x3 grid, including a wider offset than the previous
        // 4dp random nudge, so bright pixels do not sit in one place all night.
        int slot = (int) (minute % 9L);
        int dx = (slot % 3 - 1) * dp(8);
        int dy = (slot / 3 - 1) * dp(8);
        target.setTranslationX(dx);
        target.setTranslationY(dy);
    }

    private void checkBatterySaver() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) {
                Diag.log(this, "энергосбережение — AOD выключаю");
                dismiss();
            }
        } catch (Exception ignored) {
        }
    }

    private void brighten() {
        if (covered) return;
        boolean enteringBrightMode = !brightened;
        brightened = true;
        animateBrightness(BRIGHT, WAKE_TRANSITION_MS);
        applyWallpaperMood(true);
        if (enteringBrightMode) animateWakeTransition();
        handler.removeCallbacks(dimAgain);
        handler.postDelayed(dimAgain, 7000);
    }

    private void animateWakeTransition() {
        if (!isStandBy) {
            animateClockView(portraitClock);
        } else if (wideClockBox != null && wideClockBox.getVisibility() == View.VISIBLE) {
            animateClockView(standByClockWide);
            animateClockView(standByClockWideSec);
        } else {
            animateClockView(standByHour);
            animateClockView(standByMin);
        }
    }

    private void animateClockView(View clock) {
        if (clock == null || clock.getVisibility() != View.VISIBLE) return;
        float restY = clock.getTranslationY();
        clock.animate().cancel();
        clock.setAlpha(0.84f);
        clock.setScaleX(0.975f);
        clock.setScaleY(0.975f);
        clock.setTranslationY(restY + dp(5));
        clock.animate().alpha(1f).translationY(restY).scaleX(1f).scaleY(1f)
                .setInterpolator(new DecelerateInterpolator(2f))
                .setDuration(WAKE_TRANSITION_MS).start();
    }

    private void animateBrightness(float target, long duration) {
        float start = getWindow().getAttributes().screenBrightness;
        if (start < 0f) start = DIM;
        ValueAnimator animator = ValueAnimator.ofFloat(start, target);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(2f));
        animator.addUpdateListener(a -> setBrightness((Float) a.getAnimatedValue()));
        animator.start();
    }

    private void updateClocksAndWidgets() {
        Locale locale = Locale.getDefault();
        Date now = Calendar.getInstance().getTime();
        boolean hour24 = android.text.format.DateFormat.is24HourFormat(this);

        // Portrait Clock & Date
        if (portraitClock != null) {
            String timePattern = hour24 ? "HH:mm" : "h:mm";
            portraitClock.setText(new SimpleDateFormat(timePattern, locale).format(now));
        }
        if (portraitDate != null) {
            String d = new SimpleDateFormat("EEEE, d MMMM", locale).format(now);
            if (d.length() > 1) d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
            portraitDate.setText(d);
        }
        if (portraitCalWeekday != null) {
            portraitCalWeekday.setText(new SimpleDateFormat("EEEE", locale).format(now).toUpperCase(locale));
        }
        if (portraitCalDay != null) {
            portraitCalDay.setText(new SimpleDateFormat("d", locale).format(now));
        }

        // StandBy Clock
        if (standByHour != null) {
            String hourPattern = hour24 ? "HH" : "h";
            standByHour.setText(new SimpleDateFormat(hourPattern, locale).format(now));
        }
        if (standByMin != null) {
            standByMin.setText(new SimpleDateFormat("mm", locale).format(now));
        }
        if (standByClockWide != null) {
            String timePattern = hour24 ? "HH:mm" : "h:mm";
            standByClockWide.setText(new SimpleDateFormat(timePattern, locale).format(now));
        }
        if (standByClockWideDate != null) {
            standByClockWideDate.setText(new SimpleDateFormat("EEEE, d MMMM", locale).format(now).toUpperCase(locale));
        }
        if (standByClockWideSec != null) {
            standByClockWideSec.setText(new SimpleDateFormat(":ss", locale).format(now));
        }

        // StandBy Calendar
        if (calMonth != null) {
            calMonth.setText(new SimpleDateFormat("LLLL", locale).format(now).toUpperCase(locale));
        }
        if (calDay != null) {
            calDay.setText(new SimpleDateFormat("d", locale).format(now));
        }
        if (calDayOfWeek != null) {
            String dow = new SimpleDateFormat("EEEE", locale).format(now);
            if (dow.length() > 1) dow = Character.toUpperCase(dow.charAt(0)) + dow.substring(1);
            calDayOfWeek.setText(dow);
        }

        updateAlarmDisplays();
        updateBatteryDisplays();
    }

    private void updateBatteryDisplays() {
        String pctStr = (batteryPct >= 0) ? (batteryPct + "%") : "—";
        String statusStr = (batteryIsCharging ? "⚡ " : "") + pctStr + (batteryIsCharging ? " · Зарядка" : "");

        if (portraitBatteryValue != null) {
            portraitBatteryValue.setText(pctStr);
            portraitBatteryValue.setTextColor(batteryIsCharging ? 0xFF30D158 : 0xF2FFFFFF);
        }
        if (batteryGlyph != null) {
            batteryGlyph.setBattery(batteryPct, batteryIsCharging);
        }
        if (statusBattery != null) {
            statusBattery.setText(statusStr);
        }
    }

    private void updateAlarmDisplays() {
        String alarmText = "—";
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            android.app.AlarmManager.AlarmClockInfo info = am == null ? null : am.getNextAlarmClock();
            if (info != null) {
                boolean hour24 = android.text.format.DateFormat.is24HourFormat(this);
                String pattern = hour24 ? "HH:mm" : "h:mm";
                alarmText = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date(info.getTriggerTime()));
            }
        } catch (Exception ignored) {
        }

        if (portraitAlarmValue != null) portraitAlarmValue.setText(alarmText);
        if (statusAlarm != null) {
            statusAlarm.setText("⏰ Будильник: " + alarmText);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            float max = Math.max(1f, event.sensor.getMaximumRange());
            boolean near = event.values[0] < max;
            if (near != covered) {
                covered = near;
                applyCover();
            }
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float magnitude = (float) Math.sqrt(event.values[0] * event.values[0]
                    + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
            if (Math.abs(magnitude - SensorManager.GRAVITY_EARTH) > 1.2f) {
                pickupMotionSamples++;
            } else {
                pickupMotionSamples = 0;
            }
            long now = System.currentTimeMillis();
            if (pickupMotionSamples >= 2 && now - lastPickupWake > 12000L) {
                pickupMotionSamples = 0;
                lastPickupWake = now;
                if (covered) {
                    pickupWakePending = true;
                } else {
                    wakeOnPickup();
                }
            }
        } else if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lux = event.values[0];
            boolean night = (lux < 6.0f);
            if (night != isNightMode) {
                isNightMode = night;
                if (isStandBy) {
                    applyNightColors(isNightMode);
                    Diag.log(this, "освещение: " + lux + " лк -> ночной режим StandBy " + (night ? "ВКЛ" : "ВЫКЛ"));
                }
            }
        }
    }

    private void wakeOnPickup() {
        if (!onScreen || isFinishing()) return;
        Diag.log(this, "телефон поднят — ярко показываю экран блокировки");
        brighten();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void applyCover() {
        if (covered) {
            brightened = false;
            if (torchOn) setTorch(false);
            if (portraitContent != null) portraitContent.setAlpha(0f);
            if (standByContent != null) standByContent.setAlpha(0f);
            if (root != null) root.setBackgroundColor(Color.BLACK);
            setBrightness(0f);
            handler.removeCallbacks(tick);
            Diag.log(this, "датчик приближения — экран погашен");
        } else {
            if (portraitContent != null) portraitContent.setAlpha(1f);
            if (standByContent != null) standByContent.setAlpha(1f);
            if (!isStandBy && AodService.isShowWallpaper(this)) {
                if (root != null) root.setBackgroundColor(Color.TRANSPARENT);
            } else {
                if (root != null) root.setBackgroundColor(Color.BLACK);
            }
            setBrightness(DIM);
            updateClocksAndWidgets();
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, nextTickDelay());
            if (pickupWakePending) {
                pickupWakePending = false;
                wakeOnPickup();
            }
            Diag.log(this, "датчик приближения — экран снова горит");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        onScreen = true;
        showing = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(receiver, filter);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (proximity != null) {
                sensorManager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL);
            }
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (lightSensor != null) {
                sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
            }
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        if (orientationListener != null && orientationListener.canDetectOrientation()) {
            orientationListener.enable();
        }

        findTorch();
        if (cameraManager != null) {
            try {
                cameraManager.registerTorchCallback(torchCallback, handler);
            } catch (Exception ignored) {
            }
        }

        evaluateMode();
        updateClocksAndWidgets();
        renderNotifications();

        NotifService.onChange = () -> handler.post(this::renderNotifications);

        handler.removeCallbacks(tick);
        handler.postDelayed(tick, nextTickDelay());
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        super.onPause();
        onScreen = false;
        showing = false;
        covered = false;
        pickupWakePending = false;
        pickupMotionSamples = 0;
        handler.removeCallbacks(tick);
        NotifService.onChange = null;
        if (orientationListener != null) {
            orientationListener.disable();
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (cameraManager != null) {
            try {
                cameraManager.unregisterTorchCallback(torchCallback);
            } catch (Exception ignored) {
            }
        }
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        lastDismiss = System.currentTimeMillis();
        showing = false;
        super.onDestroy();
    }

    private void findTorch() {
        try {
            if (cameraManager == null) {
                cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
            }
            if (cameraManager == null || torchId != null) return;
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Boolean flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash)
                        && (facing == null || facing == CameraCharacteristics.LENS_FACING_BACK)) {
                    torchId = id;
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void onFlashlight() {
        findTorch();
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_TORCH);
            return;
        }
        if (cameraManager == null || torchId == null) {
            Toast.makeText(this, "Фонарик недоступен", Toast.LENGTH_SHORT).show();
            return;
        }
        setTorch(!torchOn);
    }

    private void setTorch(boolean on) {
        if (cameraManager == null || torchId == null) return;
        try {
            cameraManager.setTorchMode(torchId, on);
        } catch (SecurityException se) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_TORCH);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось включить фонарик", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshFlashlight() {
        if (flashlightBg != null) {
            flashlightBg.setColor(torchOn ? 0xFFF2F2F7 : 0x38FFFFFF);
        }
        if (flashlightGlyph != null) {
            flashlightGlyph.setColor(torchOn ? 0xFF1C1C1E : 0xFFFFFFFF);
        }
    }

    private void openCamera() {
        try {
            Intent still = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
            if (still.resolveActivity(getPackageManager()) == null) {
                still = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (still.resolveActivity(getPackageManager()) == null) {
                    Toast.makeText(this, "Камера не открылась", Toast.LENGTH_SHORT).show();
                    return;
                }
            }
            startActivity(still);
            dismiss();
        } catch (Exception e) {
            Toast.makeText(this, "Камера не открылась", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_TORCH && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            findTorch();
            setTorch(true);
        }
    }

    private void dismiss() {
        lastDismiss = System.currentTimeMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        dismiss();
    }

    static class LockIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LockIcon(Context context) {
            super(context);
            paint.setColor(0x8CFFFFFF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.6f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float bodyTop = h * 0.42f;
            RectF body = new RectF(0, bodyTop, w, h);
            float r = w * 0.24f;
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(body, r, r, paint);
            paint.setStyle(Paint.Style.STROKE);
            RectF shackle = new RectF(w * 0.2f, h * 0.06f, w * 0.8f, bodyTop + h * 0.16f);
            canvas.drawArc(shackle, 180, 180, false, paint);
            canvas.drawLine(w * 0.2f, (shackle.top + shackle.bottom) / 2f, w * 0.2f, bodyTop, paint);
            canvas.drawLine(w * 0.8f, (shackle.top + shackle.bottom) / 2f, w * 0.8f, bodyTop, paint);
        }
    }

    static class Glyph extends View {
        static final int FLASH = 1;
        static final int CAMERA = 2;
        static final int BELL = 3;
        static final int BATTERY = 4;

        private final int kind;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pct = -1;
        private boolean charging;

        Glyph(Context context, int kind) {
            super(context);
            this.kind = kind;
            paint.setColor(0xFFFFFFFF);
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidate();
        }

        void setBattery(int level, boolean charging) {
            this.pct = level;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            if (kind == FLASH) drawFlash(canvas, w, h);
            else if (kind == CAMERA) drawCamera(canvas, w, h);
            else if (kind == BELL) drawBell(canvas, w, h);
            else if (kind == BATTERY) drawBattery(canvas, w, h);
        }

        private void drawFlash(Canvas canvas, float w, float h) {
            canvas.save();
            canvas.rotate(-38, w / 2f, h / 2f);
            paint.setStyle(Paint.Style.FILL);
            float bw = w * 0.32f;
            float left = (w - bw) / 2f;
            canvas.drawRoundRect(new RectF(left, h * 0.34f, left + bw, h * 0.96f), bw / 2f, bw / 2f, paint);
            canvas.drawRoundRect(new RectF(w * 0.16f, h * 0.16f, w * 0.84f, h * 0.42f), w * 0.08f, w * 0.08f, paint);
            canvas.restore();
        }

        private void drawCamera(Canvas canvas, float w, float h) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.6f, w * 0.08f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawRoundRect(new RectF(w * 0.08f, h * 0.24f, w * 0.92f, h * 0.86f), w * 0.16f, w * 0.16f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(w * 0.52f, h * 0.56f, w * 0.12f, paint);
            canvas.drawRoundRect(new RectF(w * 0.22f, h * 0.10f, w * 0.46f, h * 0.28f), w * 0.06f, w * 0.06f, paint);
        }

        private void drawBell(Canvas canvas, float w, float h) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawArc(new RectF(w * 0.18f, h * 0.08f, w * 0.82f, h * 0.78f), 180, 180, true, paint);
            canvas.drawRect(w * 0.18f, h * 0.40f, w * 0.82f, h * 0.64f, paint);
            canvas.drawRoundRect(new RectF(w * 0.12f, h * 0.60f, w * 0.88f, h * 0.74f), h * 0.06f, h * 0.06f, paint);
            canvas.drawCircle(w * 0.5f, h * 0.86f, w * 0.09f, paint);
        }

        private void drawBattery(Canvas canvas, float w, float h) {
            int base = paint.getColor();
            float sw = Math.max(1.4f, w * 0.08f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sw);
            RectF body = new RectF(w * 0.04f, h * 0.22f, w * 0.78f, h * 0.78f);
            canvas.drawRoundRect(body, w * 0.1f, w * 0.1f, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(w * 0.80f, h * 0.38f, w * 0.96f, h * 0.62f), 2, 2, paint);
            if (pct >= 0) {
                float pad = sw + w * 0.05f;
                float l = body.left + pad;
                float t = body.top + pad;
                float r = body.right - pad;
                float b = body.bottom - pad;
                float fillR = l + (r - l) * Math.max(0f, Math.min(100, pct)) / 100f;
                int fill = charging ? 0xFF30D158 : (pct <= 15 ? 0xFFFFD60A : base);
                paint.setColor(fill);
                if (fillR > l + 1f) {
                    canvas.drawRoundRect(new RectF(l, t, fillR, b), 1.5f, 1.5f, paint);
                }
                paint.setColor(base);
            }
        }
    }
}
