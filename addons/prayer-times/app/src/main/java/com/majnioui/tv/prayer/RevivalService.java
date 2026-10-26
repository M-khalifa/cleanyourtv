package com.majnioui.tv.prayer;

import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * Brings the alarms back after the firmware force-stops the app, which cancels every alarm it set. A notification
 * listener keeps its approval across a force-stop and the system binds it again afterwards; that bind re-arms the
 * alerts, the morning recitation and Friday's Al-Kahf. No notification is read. Approved over adb (README).
 */
public class RevivalService extends NotificationListenerService {
    private static final String TAG = "PrayerAlerts";

    @Override
    public void onListenerConnected() {
        Log.i(TAG, "revival: re-arming the alarms");
        VolumeGuard.reconcile(this);
        PrayerAlerts.rearm(this);
        SleepService.ensureEnabled(this);
    }
}
