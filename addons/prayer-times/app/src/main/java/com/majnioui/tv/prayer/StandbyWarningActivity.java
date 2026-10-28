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
 * An Azan playing by then puts the TV back at its own end instead.
 */
public class StandbyWarningActivity extends Activity {
    private static final String TAG = "MorningQuran";
    static final int SECONDS = 60;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            finish();
        }
    };
    private TextView count;
    private int left = SECONDS;
    private boolean registered;

    static void show(Context c) {
        c.startActivity(new Intent(c, StandbyWarningActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SleepService.ensureEnabled(this);
        Screen.turnOn(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        registered = true;

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
        keep.setOnClickListener(v -> keepOn());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (int) (56 * dp));
        blp.topMargin = (int) (36 * dp);
        root.addView(keep, blp);
        setContentView(root);
        keep.requestFocus();
        EventLog.i(this, TAG, "standby in " + SECONDS + " s unless a key is pressed");
        tick();
    }

    private void tick() {
        if (left <= 0) {
            EventLog.i(this, TAG, "no key in " + SECONDS + " s: standby");
            finish();
            if (Handover.STATE.standby(true)) SleepService.sleep(getApplicationContext());
            return;
        }
        count.setText("The TV turns off in " + left + " s");
        left--;
        main.postDelayed(this::tick, 1_000L);
    }

    private void keepOn() {
        EventLog.i(this, TAG, "standby cancelled: the TV stays on");
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any key, OK on the button included, keeps the TV on.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            keepOn();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        keepOn();
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
        super.onDestroy();
    }
}
