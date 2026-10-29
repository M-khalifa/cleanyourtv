package com.majnioui.tv.prayer;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * After a recitation that played to its end: the TV goes to standby in a minute unless someone presses a key.
 * An Azan or recitation starting meanwhile takes the decision over through Handover and closes this screen.
 */
public class StandbyWarningActivity extends Activity {
    private static final String TAG = "MorningQuran";
    static final int SECONDS = 60;
    /** How often a leaving is checked until it is known whether Home or an alarm screen of this app took the front. */
    private static final long LEAVE_CHECK_MS = 1_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (done) return;
            Handover.STATE.warningCancelled();
            close("TV turned off: warning closed");
        }
    };
    private final Runnable closer = () -> close("an Azan or recitation started: it decides the standby");
    private TextView count;
    private int left = SECONDS;
    private boolean registered;
    /** Settled one way or the other: no tick may request standby any more. */
    private boolean done;
    /** Left the front and not yet known why: the countdown may not end meanwhile. */
    private boolean leaving;
    private boolean inFront;

    static void show(Context c) {
        c.startActivity(new Intent(c, StandbyWarningActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Handover.STATE.warningShowing()) {
            // Recreated after its decision, or an event took over before it opened.
            done = true;
            finish();
            return;
        }
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        registered = true;
        if (!getSystemService(android.os.PowerManager.class).isInteractive()) {
            // Turned off since the recitation ended: it stays off.
            Handover.STATE.warningCancelled();
            close("TV turned off: no warning");
            return;
        }
        Handover.STATE.warningCloser = closer;
        SleepService.ensureEnabled(this);
        Screen.turnOn(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.rgb(6, 18, 14));
        root.addView(line("Recitation finished", 22, 0xB3FFFFFF));
        count = line("", 44, Color.WHITE);
        root.addView(count);
        Button keep = new Button(this);
        keep.setText("Keep the TV on");
        keep.setAllCaps(false);
        keep.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        keep.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(26 * dp);
        bg.setColor(0xFF2BB673);
        keep.setBackground(bg);
        keep.setPadding((int) (48 * dp), 0, (int) (48 * dp), 0);
        keep.setOnClickListener(v -> keepOn("OK"));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (56 * dp));
        blp.topMargin = (int) (36 * dp);
        root.addView(keep, blp);
        setContentView(root);
        keep.requestFocus();
        EventLog.i(this, TAG, "standby in " + SECONDS + " s unless a key is pressed");
        tick();
    }

    private void tick() {
        if (done) return;
        if (left <= 0 && leaving) {
            main.postDelayed(this::tick, 1_000L);
            return;
        }
        if (left <= 0) {
            done = true;
            finish();
            boolean now = Handover.STATE.warningExpired();
            EventLog.i(this, TAG, "no key in " + SECONDS + " s: " + (now ? "standby" : "an event took the standby over"));
            if (now) SleepService.sleep(getApplicationContext());
            return;
        }
        count.setText("The TV turns off in " + left + " s");
        left--;
        main.postDelayed(this::tick, 1_000L);
    }

    private void keepOn(String why) {
        if (done) return;
        Handover.STATE.warningCancelled();
        close(why + ": standby cancelled, the TV stays on");
    }

    private void close(String why) {
        if (done) return;
        done = true;
        main.removeCallbacksAndMessages(null);
        EventLog.i(this, TAG, why);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any key, OK on the button included, keeps the TV on.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) keepOn("key " + KeyEvent.keyCodeToString(event.getKeyCode()));
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
        leaving = false;
    }

    @Override
    protected void onPause() {
        inFront = false;
        super.onPause();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // An Azan, early-wake or recitation screen of this app opening also sends this hint: only Home is the user.
        leaving = true;
        main.postDelayed(this::checkLeaving, LEAVE_CHECK_MS);
    }

    private void checkLeaving() {
        if (done || !leaving || inFront) return;
        if (Handover.STATE.ownScreenShowing()) {
            main.postDelayed(this::checkLeaving, LEAVE_CHECK_MS);
            return;
        }
        keepOn("Home");
    }

    private TextView line(String s, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setPadding(0, 10, 0, 10);
        return v;
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(screenOff);
        if (Handover.STATE.warningCloser == closer) Handover.STATE.warningCloser = null;
        // Destroyed by the system before a decision: the TV stays on.
        if (!done) Handover.STATE.warningCancelled();
        super.onDestroy();
    }
}
