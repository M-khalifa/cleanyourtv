package com.majnioui.tv.prayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;

/**
 * The Azan at the prayer time (WakePlan): takes the audio focus so whatever is playing pauses, plays
 * res/raw/azan.mp3 to the end as TV sound and keeps the prayer window on screen meanwhile. A TV in standby is
 * woken for it and put back to standby at the end unless someone pressed a key. Dismiss (OK or Back) stops it.
 */
public class AzanService extends Service {
    private static final String TAG = "PrayerAlerts";
    private static final String CHANNEL = "azan";
    private static final String EXTRA_PRAYER = "prayer";
    private static final String EXTRA_AT = "at_millis";
    private static final String EXTRA_WOKE = "woke";
    /** Past the audio's length plus this, the Azan ends whatever the player says. */
    private static final long GRACE_MS = 30_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private LinearLayout window;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private WakePlan plan;
    private boolean keyPressed;
    /** When the window appeared; keys in the KEY_GUARD_MS after it are ignored. */
    private long shownAt;
    private static final long KEY_GUARD_MS = 1_500L;
    /** Registered with Handover: its end is reported once, from finish(). */
    private boolean handedIn;
    private boolean started;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            // Turned off with the remote: the Azan stops with the TV.
            EventLog.i(c, TAG, "TV turned off during the Azan");
            keyPressed = true;
            finish();
        }
    };

    /** The prayer time the last Azan was for, so a second alarm for the same time plays nothing. */
    private static long lastAt;
    /** This Azan's TV was in standby (woken now or by the early wake): it goes back unless a key was pressed. */
    private boolean woke;

    /** {@code woke}: AzanActivity turned the screen on for this Azan. */
    static boolean start(Context c, int prayer, long atMillis, boolean woke) {
        try {
            c.startForegroundService(new Intent(c, AzanService.class).putExtra(EXTRA_PRAYER, prayer)
                    .putExtra(EXTRA_AT, atMillis).putExtra(EXTRA_WOKE, woke));
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "could not start the Azan", e);
            return false;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        if (started || intent == null) return START_NOT_STICKY;
        started = true;
        int prayer = Math.max(0, Math.min(PrayerAlerts.NAMES.length - 1, intent.getIntExtra(EXTRA_PRAYER, PrayerTimes.DHUHR)));
        long at = intent.getLongExtra(EXTRA_AT, System.currentTimeMillis());
        long late = System.currentTimeMillis() - at;
        woke = intent.getBooleanExtra(EXTRA_WOKE, false);
        VolumeGuard.reconcile(this);
        PrayerAlerts.rearmAfterAlarm(this);
        if (late > PrayerAlerts.LATE_MS || at == lastAt) {
            EventLog.i(this, TAG, "Azan " + PrayerAlerts.NAMES[prayer] + (at == lastAt ? ": already played"
                    : " " + late / 60_000 + " min late (the TV was asleep): skipped"));
            finish();
            return START_NOT_STICKY;
        }
        lastAt = at;
        EventLog.i(this, TAG, "Azan " + PrayerAlerts.NAMES[prayer] + " " + late / 1000 + " s after its time");
        Resources r = getResources();
        plan = WakePlan.azan(prayer, !woke, r.getBoolean(R.bool.azan_wake_tv),
                r.getInteger(R.integer.azan_volume_fajr), r.getInteger(R.integer.azan_volume));
        CountdownService.stop(this);
        if (plan == null) {
            EventLog.i(this, TAG, "TV in standby and azan_wake_tv is false: Azan skipped");
            finish();
            return START_NOT_STICKY;
        }
        audio = getSystemService(AudioManager.class);
        VolumeGuard.attach(this);
        int set = Handover.STATE.azanStart(woke, plan.volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        handedIn = true;
        if (set != WakePlan.KEEP) setVolume(set);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        showWindow(prayer);
        play();
        return START_NOT_STICKY;
    }

    private void setVolume(int volume) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.min(volume, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
        } catch (SecurityException e) {
            Log.w(TAG, "could not set the volume", e);
        }
    }

    private void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Azan", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("Azan")
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(2, n);
        }
    }

    private void play() {
        // Media, not alarm: it plays at the TV's own volume, the one the volume bar shows.
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(change -> { })
                .build();
        int granted = audio.requestAudioFocus(focus);
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) Log.w(TAG, "audio focus not granted: " + granted);
        player = MediaPlayer.create(this, android.net.Uri.fromFile(AzanSound.file(this)), null, attrs,
                audio.generateAudioSessionId());
        if (player == null) {
            EventLog.w(this, TAG, "could not open the Azan audio");
            finish();
            return;
        }
        player.setOnCompletionListener(mp -> {
            EventLog.i(this, TAG, "Azan played to its end");
            finish();
        });
        player.setOnErrorListener((mp, what, extra) -> {
            EventLog.w(this, TAG, "Azan playback error " + what + "/" + extra);
            finish();
            return true;
        });
        player.start();
        main.postDelayed(() -> {
            EventLog.w(this, TAG, "Azan still playing past its length: stopped");
            finish();
        }, player.getDuration() + GRACE_MS);
        EventLog.i(this, TAG, "Azan playing, " + player.getDuration() / 1000 + " s, volume "
                + audio.getStreamVolume(AudioManager.STREAM_MUSIC) + (plan.wake ? ", TV woken" : ""));
    }

    private void showWindow(int prayer) {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "no permission to draw over other apps: Azan window");
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        window = new LinearLayout(this);
        window.setOrientation(LinearLayout.VERTICAL);
        window.setGravity(Gravity.CENTER_HORIZONTAL);
        window.setPadding((int) (48 * dp), (int) (22 * dp), (int) (48 * dp), (int) (22 * dp));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0101814);
        bg.setStroke((int) (2 * dp), PrayerAlerts.COLOR_AT);
        bg.setCornerRadius(32 * dp);
        window.setBackground(bg);
        // Full-width single lines: wrapped to the text, the overlay measured them short and cut them at the dot.
        window.setMinimumWidth((int) (560 * dp));
        for (android.widget.TextView line : new android.widget.TextView[]{
                PrayerAlerts.text(this, (prayer == PrayerTimes.MAGHRIB && PrayerAlerts.ramadan(this) ? "IFTAR"
                        : "PRAYER TIME") + "  ·  AZAN", 14, PrayerAlerts.COLOR_AT),
                PrayerAlerts.text(this, PrayerAlerts.NAMES[prayer] + "  ·  " + PrayerAlerts.ARABIC[prayer], 34, 0xFFFFFFFF)}) {
            line.setSingleLine(true);
            line.setGravity(Gravity.CENTER);
            window.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        Button dismiss = new Button(this);
        dismiss.setText("Dismiss Azan");
        dismiss.setAllCaps(false);
        dismiss.setTextSize(18);
        dismiss.setTextColor(0xFFFFFFFF);
        GradientDrawable idle = new GradientDrawable();
        idle.setCornerRadius(24 * dp);
        idle.setColor(0x33FFFFFF);
        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(24 * dp);
        focused.setColor(PrayerAlerts.COLOR_AT);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, idle);
        dismiss.setBackground(states);
        dismiss.setPadding((int) (32 * dp), 0, (int) (32 * dp), 0);
        dismiss.setOnClickListener(v -> {
            EventLog.i(this, TAG, "Azan dismissed");
            finish();
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) (52 * dp));
        blp.topMargin = (int) (18 * dp);
        window.addView(dismiss, blp);
        // Any key means someone is watching, so a woken TV stays on; Back dismisses too.
        android.view.View.OnKeyListener keys = (v, code, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) keyPressed = true;
            // A press meant for the show as the window appears must not dismiss the Azan: it still counts as
            // someone watching, but does nothing for the first moments.
            if (android.os.SystemClock.uptimeMillis() - shownAt < KEY_GUARD_MS) return true;
            if (code == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                EventLog.i(this, TAG, "Back: Azan dismissed");
                finish();
                return true;
            }
            return false;
        };
        window.setFocusableInTouchMode(true);
        window.setOnKeyListener(keys);
        dismiss.setOnKeyListener(keys);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (48 * dp);
        wm = getSystemService(WindowManager.class);
        try {
            shownAt = android.os.SystemClock.uptimeMillis();
            wm.addView(window, lp);
            dismiss.requestFocus();
        } catch (RuntimeException e) {
            Log.e(TAG, "could not show the Azan window", e);
            window = null;
        }
    }

    private void finish() {
        main.removeCallbacksAndMessages(null);
        int playedMs = -1;
        if (player != null) {
            try {
                playedMs = player.getCurrentPosition();
                player.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "Azan already stopped", e);
            }
            player.release();
            player = null;
        }
        if (audio != null && focus != null) {
            audio.abandonAudioFocusRequest(focus);
            focus = null;
        }
        if (window != null && wm != null) {
            try {
                wm.removeView(window);
            } catch (RuntimeException e) {
                Log.w(TAG, "Azan window already gone", e);
            }
            window = null;
        }
        try {
            unregisterReceiver(screenOff);
        } catch (IllegalArgumentException e) {
            // Not registered: the Azan was skipped before it started.
        }
        // Every way out, skipped ones too: a TV this Azan's wake turned on goes back unless someone pressed a key;
        // with a recitation going on, Handover leaves that to the recitation's end.
        Handover.Step step = handedIn ? Handover.STATE.azanEnd(keyPressed)
                : new Handover.Step(WakePlan.KEEP, Handover.STATE.standby(WakePlan.sleepAfter(woke, keyPressed)));
        if (handedIn || playedMs >= 0) {
            EventLog.i(this, TAG, "Azan ended" + (playedMs >= 0 ? " after " + playedMs / 1000 + " s" : "")
                    + (keyPressed ? ", key pressed" : "") + (step.sleep ? ", standby" : ""));
        }
        woke = false;
        plan = null;
        if (step.volume != WakePlan.KEEP) setVolume(step.volume);
        if (handedIn) {
            handedIn = false;
            QuranActivity.azanFinished();
        }
        boolean sleep = step.sleep;
        if (sleep) {
            // A recitation due within its own early wake: stay on for it instead of a standby it would undo.
            long due = MorningQuran.nextDue(this);
            long lead = (getResources().getInteger(R.integer.prewake_minutes) + 1) * 60_000L;
            if (due > 0 && due - System.currentTimeMillis() <= lead) {
                PreWakeActivity.markWoke(this, due);
                sleep = false;
                EventLog.i(this, TAG, "a recitation is due in a few minutes: the TV stays on for it");
            }
        }
        if (sleep) SleepService.sleep(getApplicationContext());
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (player != null || window != null) finish();
        super.onDestroy();
    }
}
