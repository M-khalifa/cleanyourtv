package com.majnioui.tv.prayer;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * Puts the TV back to standby after a morning recitation or Azan that woke it. The "lock screen" global action
 * (Android 9 and later) sends the device to sleep, which an ordinary app cannot do otherwise. The service asks
 * for no events and reads no windows. Switched on over adb (README); Android drops it from the enabled list when
 * the app is force-stopped, so with WRITE_SECURE_SETTINGS granted the app puts it back itself.
 */
public class SleepService extends AccessibilityService {
    private static final String TAG = "PrayerAlerts";
    /** Waiting for Android to bind a service ensureEnabled() just put back. */
    private static final long BIND_WAIT_MS = 4_000L;
    private static SleepService instance;

    static boolean running() {
        return instance != null;
    }

    /** Sends the TV to standby, waiting briefly for the service to bind if it was just put back. */
    static void sleep(Context c) {
        if (instance != null) {
            sleepNow(c);
            return;
        }
        if (!ensureEnabled(c)) {
            EventLog.w(c, TAG, "SleepService is not switched on: the TV stays on");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (instance == null) EventLog.w(c, TAG, "SleepService did not bind: the TV stays on");
            else sleepNow(c);
        }, BIND_WAIT_MS);
    }

    private static void sleepNow(Context c) {
        SleepService service = instance;
        if (service == null) return;
        if (service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)) EventLog.i(c, TAG, "TV back to standby");
        else EventLog.w(c, TAG, "standby refused by Android: the TV stays on");
    }

    /** Puts the service back in the enabled list if it fell out; false when it cannot (permission not granted). */
    static boolean ensureEnabled(Context c) {
        String me = new ComponentName(c, SleepService.class).flattenToString();
        String shortMe = new ComponentName(c, SleepService.class).flattenToShortString();
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String padded = ":" + list + ":";
        if (list != null && (padded.contains(":" + me + ":") || padded.contains(":" + shortMe + ":"))) return true;
        if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        String updated = list == null || list.isEmpty() ? me : list + ":" + me;
        Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
        Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        Log.i(TAG, "SleepService switched back on");
        return true;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }
}
