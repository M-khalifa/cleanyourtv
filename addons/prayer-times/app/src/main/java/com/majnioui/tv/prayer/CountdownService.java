package com.majnioui.tv.prayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The countdown pill at the top right over whatever is on screen, from alert_soon_minutes before a prayer until
 * it: orange, then red from alert_lead_minutes before. Updated every 20 s; gone at the prayer.
 */
public class CountdownService extends Service {
    private static final String TAG = "PrayerAlerts";
    private static final String CHANNEL = "countdown";
    private static final String EXTRA_PRAYER = "prayer";
    private static final String EXTRA_AT = "at_millis";
    private static final long TICK_MS = 20_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout pill;
    private TextView line;
    private GradientDrawable bg;
    private int prayer;
    private long at;

    static void start(Context c, int prayer, long atMillis) {
        Intent i = new Intent(c, CountdownService.class).putExtra(EXTRA_PRAYER, prayer).putExtra(EXTRA_AT, atMillis);
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            Log.e(TAG, "could not start the countdown", e);
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, CountdownService.class));
    }

    /** "in 42 min", "in 1 h 0 min", or "now". */
    static String countdown(long millisLeft) {
        long minutes = (millisLeft + 59_999) / 60_000;
        if (minutes <= 0) return "now";
        if (minutes < 60) return "in " + minutes + " min";
        return "in " + minutes / 60 + " h " + minutes % 60 + " min";
    }

    /** Milliseconds until "in N min" changes, a little past it: the minute turns with the clock. */
    static long nextTurn(long millisLeft) {
        return (millisLeft - 1) % 60_000 + 1 + 50;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        prayer = intent.getIntExtra(EXTRA_PRAYER, PrayerTimes.DHUHR);
        at = intent.getLongExtra(EXTRA_AT, 0);
        if (at <= System.currentTimeMillis() || !Settings.canDrawOverlays(this)) {
            if (at > System.currentTimeMillis()) Log.w(TAG, "no permission to draw over other apps: countdown");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (pill == null) addPill();
        main.removeCallbacksAndMessages(null);
        tick();
        return START_NOT_STICKY;
    }

    private void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Prayer countdown",
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("Next prayer")
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void addPill() {
        float dp = getResources().getDisplayMetrics().density;
        pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding((int) (20 * dp), (int) (8 * dp), (int) (22 * dp), (int) (8 * dp));
        bg = Glass.dark(dp, 0, 22);
        pill.setBackground(bg);
        line = PrayerAlerts.text(this, "", 15, 0xFFFFFFFF);
        line.setLetterSpacing(0.12f);
        pill.addView(line);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = (int) (40 * dp);
        lp.y = (int) (24 * dp);
        wm = getSystemService(WindowManager.class);
        try {
            wm.addView(pill, lp);
        } catch (RuntimeException e) {
            Log.e(TAG, "could not show the countdown", e);
            pill = null;
        }
    }

    private void tick() {
        long left = at - System.currentTimeMillis();
        if (left <= 0 || pill == null) {
            stopSelf();
            return;
        }
        int lead = getResources().getInteger(R.integer.alert_lead_minutes);
        int color = left <= lead * 60_000L ? PrayerAlerts.COLOR_IMMINENT : PrayerAlerts.COLOR_SOON;
        float dp = getResources().getDisplayMetrics().density;
        bg.setStroke((int) (2 * dp), color);
        line.setTextColor(color);
        line.setText("●  " + PrayerAlerts.NAMES[prayer].toUpperCase() + " " + countdown(left).toUpperCase()
                + "  ·  " + PrayerAlerts.ARABIC[prayer]);
        main.postDelayed(this::tick, Math.min(TICK_MS, nextTurn(left)));
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (pill != null && wm != null) {
            try {
                wm.removeView(pill);
            } catch (RuntimeException e) {
                Log.w(TAG, "countdown already gone", e);
            }
            pill = null;
        }
        super.onDestroy();
    }
}
