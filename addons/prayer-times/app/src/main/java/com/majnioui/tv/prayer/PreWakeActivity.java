package com.majnioui.tv.prayer;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Wakes a TV in standby prewake_minutes before an Azan or the morning recitation, on a dark clock. On this TCL an
 * alarm can start its activity up to about three minutes late while the screen is off, but at once while it is
 * on, so the event itself then starts on time. Remembers that it woke the TV, so the TV goes back to standby
 * afterwards; a key press means someone is watching and closes it. Does nothing when the TV is already on.
 */
public class PreWakeActivity extends Activity {
    private static final String TAG = "PrayerAlerts";
    private static final String EXTRA_AT = "at_millis";
    private static final String EXTRA_LABEL = "label";
    private static final String EXTRA_WHAT = "what";
    private static final String EXTRA_PRAYER = "prayer";
    /** What the early wake is for, rechecked when it fires: the Azan of a prayer, or a recitation of a kind. */
    static final int WHAT_AZAN = 0, WHAT_RECITATION = 1;
    private static final String PREFS = "prewake";
    private static final String KEY_WOKE_FOR = "woke_for";
    /** Past the event's time plus this with no event, the TV goes back to standby. */
    private static final long HOLD_AFTER_MS = 3 * 60_000L;
    private static PreWakeActivity showing;

    private final Handler main = new Handler(Looper.getMainLooper());
    private long at;

    /** Arms the wake for the event at {@code atMillis} in request-code {@code slot}; none if too close to now. */
    static void arm(Context c, int slot, long atMillis, String label, int what, int which) {
        PendingIntent pi = PendingIntent.getActivity(c, slot, new Intent(c, PreWakeActivity.class)
                        .putExtra(EXTRA_AT, atMillis).putExtra(EXTRA_LABEL, label)
                        .putExtra(EXTRA_WHAT, what).putExtra(EXTRA_PRAYER, which)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long when = atMillis - c.getResources().getInteger(R.integer.prewake_minutes) * 60_000L;
        boolean exact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        if (exact && Screen.canPutBack() && when <= atMillis - 60_000L && when > System.currentTimeMillis() + 5_000L) {
            PendingIntent show = PendingIntent.getActivity(c, slot, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE);
            try {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(when, show), pi);
            } catch (SecurityException e) {
                Log.w(TAG, "early wake not armed", e);
            }
        } else {
            am.cancel(pi);
        }
    }

    static void cancel(Context c, int slot) {
        PendingIntent pi = PendingIntent.getActivity(c, slot, new Intent(c, PreWakeActivity.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) c.getSystemService(AlarmManager.class).cancel(pi);
    }

    /** The TV is on now for the event at {@code atMillis}, kept on by another event: its end puts it back. */
    static void markWoke(Context c, long atMillis) {
        c.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_WOKE_FOR, atMillis).apply();
    }

    /** True, once, when a pre-wake turned the screen on for the event at {@code atMillis}. */
    static boolean consumeWoke(Context c, long atMillis) {
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        long wokeFor = p.getLong(KEY_WOKE_FOR, 0);
        if (wokeFor == 0 || Math.abs(wokeFor - atMillis) > 1_000L) return false;
        p.edit().remove(KEY_WOKE_FOR).apply();
        return true;
    }

    /** The event at {@code atMillis} has started: its clock steps aside (another event's clock stays). */
    static void close(long atMillis) {
        if (showing != null && Math.abs(showing.at - atMillis) <= 1_000L) showing.finish();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        at = getIntent().getLongExtra(EXTRA_AT, 0);
        if (getSystemService(PowerManager.class).isInteractive() || System.currentTimeMillis() > at || !stillWanted()) {
            finish();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_WOKE_FOR, at).apply();
        SleepService.ensureEnabled(this);
        Screen.turnOn(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        String time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US));
        EventLog.i(this, TAG, "TV woken early for " + label + " at " + time);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.addView(line(label == null ? "" : label.toUpperCase(Locale.US), 16, 0x992BB673));
        root.addView(line(time, 64, 0x99FFFFFF));
        setContentView(root);
        showing = this;
        main.postDelayed(this::timeout, Math.max(0, at + HOLD_AFTER_MS - System.currentTimeMillis()));
    }

    /** The event is still switched on and allowed to wake the TV: a setting may have changed since arming. */
    private boolean stillWanted() {
        Options o = Options.load(this);
        int which = getIntent().getIntExtra(EXTRA_PRAYER, -1);
        if (getIntent().getIntExtra(EXTRA_WHAT, WHAT_AZAN) == WHAT_RECITATION) return MorningQuran.enabled(o, which);
        return o.azanAt(which) && getResources().getBoolean(R.bool.azan_wake_tv);
    }

    /** Nothing started: put back the TV this woke. */
    private void timeout() {
        boolean sleep = consumeWoke(this, at);
        Log.w(TAG, "no event after the early wake" + (sleep ? ": TV back to standby" : ""));
        finish();
        // An Azan or recitation playing now puts the TV back at its own end instead.
        if (Handover.STATE.standby(sleep)) SleepService.sleep(getApplicationContext());
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            EventLog.i(this, TAG, "key pressed on the early-wake clock: the TV stays on");
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_WOKE_FOR).apply();
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private TextView line(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setLetterSpacing(sp < 20 ? 0.2f : 0f);
        return v;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (showing == this) showing = null;
        super.onDestroy();
    }
}
