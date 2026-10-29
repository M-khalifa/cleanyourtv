package com.majnioui.tv.prayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Invisible first step of the Azan, opened straight from the alarm: an activity is the only thing that both starts
 * at once and can turn the screen on, so a TV in standby wakes for it. Hands over to AzanService and closes.
 */
public class AzanActivity extends Activity {
    private static final String EXTRA_PRAYER = "prayer";
    private static final String EXTRA_AT = "at_millis";
    private static final String EXTRA_TEST = "test";
    /** How long a waking activity stays, so the screen is on before it closes. */
    private static final long HOLD_MS = 1_500L;

    static Intent intent(Context c, int prayer, long atMillis) {
        return new Intent(c, AzanActivity.class).putExtra(EXTRA_PRAYER, prayer).putExtra(EXTRA_AT, atMillis)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    /** The Azan now, whatever the switches say: Options' "Test Azan" row. */
    static Intent test(Context c, int prayer) {
        return intent(c, prayer, System.currentTimeMillis()).putExtra(EXTRA_TEST, true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Handover.STATE.ownScreenShown(true);
    }

    @Override
    protected void onStop() {
        Handover.STATE.ownScreenShown(false);
        super.onStop();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        long at = getIntent().getLongExtra(EXTRA_AT, System.currentTimeMillis());
        int prayer = getIntent().getIntExtra(EXTRA_PRAYER, PrayerTimes.DHUHR);
        boolean test = getIntent().getBooleanExtra(EXTRA_TEST, false);
        boolean screenOff = !getSystemService(PowerManager.class).isInteractive();
        // Woken a few minutes ago by the early wake (the screen is on now), or about to be woken here.
        boolean earlyWoke = PreWakeActivity.consumeWoke(this, at);
        PreWakeActivity.close(at);
        long late = System.currentTimeMillis() - at;
        String skip = prayer < 0 || prayer >= PrayerAlerts.NAMES.length ? "unknown prayer"
                : !test && !Options.load(this).azanAt(prayer) ? "switched off"
                : !test && late > PrayerAlerts.LATE_MS ? late / 60_000 + " min late"
                : screenOff && (!getResources().getBoolean(R.bool.azan_wake_tv) || !Screen.canPutBack())
                ? "TV in standby, waking it is off"
                : null;
        if (skip != null) {
            // Checked before the screen turns on; a TV the early wake turned on goes back to standby.
            android.util.Log.i("PrayerAlerts", "Azan skipped: " + skip);
            finish();
            if (Handover.STATE.standby(earlyWoke && !screenOff)) SleepService.sleep(this);
            return;
        }
        SleepService.ensureEnabled(this);
        boolean woke = earlyWoke || screenOff;
        if (!AzanService.start(this, prayer, at, woke)) {
            finish();
            if (Handover.STATE.standby(earlyWoke && !screenOff)) SleepService.sleep(this);
            return;
        }
        if (!screenOff) {
            // TV on: close before it is ever drawn, so the app being watched is not interrupted.
            finish();
            return;
        }
        Screen.turnOn(this);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, HOLD_MS);
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
