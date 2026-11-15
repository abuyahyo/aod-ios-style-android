package com.buddyapps.aod;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Waits for the screen to turn off, then brings up the dim iOS-style clock / StandBy mode. */
public class AodService extends Service {

    static final String ACTION_START = "com.buddyapps.aod.START";
    static final String ACTION_STOP = "com.buddyapps.aod.STOP";
    private static final int NOTIF_ID = 101;
    private static final String CHANNEL = "aod_status";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver screenReceiver;
    static volatile boolean running = false;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("aod", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean("enabled", false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("enabled", enabled).apply();
    }

    static boolean isStandByEnabled(Context context) {
        return prefs(context).getBoolean("standby_enabled", true);
    }

    static void setStandByEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("standby_enabled", enabled).apply();
    }

    static boolean isShowWallpaper(Context context) {
        return prefs(context).getBoolean("show_wallpaper", true);
    }

    static void setShowWallpaper(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("show_wallpaper", enabled).apply();
    }

    static boolean isShowNotifications(Context context) {
        return prefs(context).getBoolean("show_notifs", true);
    }

    static void setShowNotifications(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("show_notifs", enabled).apply();
    }

    static boolean isNightModeEnabled(Context context) {
        return prefs(context).getBoolean("standby_night_mode", true);
    }

    static void setNightModeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("standby_night_mode", enabled).apply();
    }

    static void start(Context context, boolean enable) {
        Intent intent = new Intent(context, AodService.class);
        intent.setAction(enable ? ACTION_START : ACTION_STOP);
        if (enable) {
            setEnabled(context, true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } else {
            setEnabled(context, false);
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        Diag.log(this, "служба запущена");
        createChannel();
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    onScreenOff();
                } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                    try {
                        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                        if (pm != null && !pm.isInteractive() && isEnabled(context)) {
                            Diag.log(context, "зарядка подключена при погасшем экране");
                            onScreenOff();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
    }

    private void onScreenOff() {
        Diag.log(this, "экран погас");
        if (!isEnabled(this)) {
            Diag.log(this, "AOD выключен, не показываю");
            return;
        }
        if (AodActivity.showing) return;
        if (System.currentTimeMillis() - AodActivity.lastDismiss < 4000) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                launchAod();
            }
        }, 400);
    }

    private void launchAod() {
        launchAod(0);
    }

    private void launchAod(final int attempt) {
        if (AodActivity.showing) return;
        Intent intent = new Intent(this, AodActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
                startActivity(intent, options.toBundle());
            } else {
                startActivity(intent);
            }
            Diag.log(this, "открываю AOD (попытка " + (attempt + 1) + ")");
        } catch (Exception e) {
            Diag.log(this, "ошибка открытия: " + e);
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (AodActivity.showing) return;
                if (attempt < 2) {
                    Diag.log(AodService.this, "AOD не открылся, пробую снова");
                    launchAod(attempt + 1);
                } else {
                    Diag.log(AodService.this, "AOD не открылся (Android не дал)");
                }
            }
        }, 1500);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "AOD", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, AodService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("AOD включён")
                .setContentText("Часы и StandBy появятся, когда экран погаснет")
                .setContentIntent(openPi)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this,
                                android.R.drawable.ic_lock_idle_alarm),
                        "Выключить", stopPi).build());
        return builder.build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            setEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        setEnabled(this, true);
        try {
            startForeground(NOTIF_ID, buildNotification());
            Diag.log(this, "уведомление службы показано");
        } catch (Exception e) {
            Diag.log(this, "ошибка службы: " + e);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        Diag.log(this, "служба остановлена");
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
