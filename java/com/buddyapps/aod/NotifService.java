package com.buddyapps.aod;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Reads the notifications shown on the lock screen (needs the user's notification access). */
public class NotifService extends NotificationListenerService {

    public static class Item {
        public String app;
        public String title;
        public String text;
        public long time;
        public Drawable icon;
    }

    static volatile List<Item> items = new ArrayList<>();
    static volatile Runnable onChange;

    static boolean hasAccess(Context context) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    context.getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(context.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onListenerConnected() {
        refresh();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refresh();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refresh();
    }

    private void refresh() {
        List<Item> list = new ArrayList<>();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    Item item = toItem(sbn);
                    if (item != null) list.add(item);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(list, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.time, a.time);
            }
        });
        if (list.size() > 4) list = list.subList(0, 4);
        items = list;
        Runnable callback = onChange;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception ignored) {
            }
        }
    }

    private Item toItem(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            if (n == null) return null;
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName())) return null;
            if (pkg.equals("android")) return null;
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return null;
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
            if (n.visibility == Notification.VISIBILITY_SECRET) return null;
            NotificationListenerService.RankingMap rankings = getCurrentRanking();
            if (rankings != null) {
                NotificationListenerService.Ranking ranking = new NotificationListenerService.Ranking();
                if (rankings.getRanking(sbn.getKey(), ranking)
                        && (ranking.getImportance() <= NotificationManager.IMPORTANCE_MIN
                        || ranking.isAmbient()
                        || ranking.getLockscreenVisibilityOverride() == Notification.VISIBILITY_SECRET)) {
                    return null;
                }
            }
            Bundle extras = n.extras;
            if (extras == null) return null;
            String title = String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE, ""));
            String text = String.valueOf(extras.getCharSequence(Notification.EXTRA_TEXT, ""));
            if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return null;
            Item item = new Item();
            item.title = title;
            item.text = text;
            item.time = sbn.getPostTime();
            try {
                PackageManager pm = getPackageManager();
                android.content.pm.ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                item.app = String.valueOf(pm.getApplicationLabel(info));
                Drawable icon = pm.getApplicationIcon(info);
                item.icon = icon == null ? null : icon.mutate();
            } catch (Exception e) {
                item.app = pkg;
            }
            return item;
        } catch (Exception e) {
            return null;
        }
    }
}
