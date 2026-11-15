package com.buddyapps.aod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AodService.isEnabled(context)) return;
        if (!android.provider.Settings.canDrawOverlays(context)) return;
        try {
            AodService.start(context, true);
        } catch (Exception ignored) {
        }
    }
}
