package com.majnioui.tv.prayer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Function;

/**
 * Prayer reminders over whatever is on screen (config.xml alerts): from alert_soon_minutes before each prayer a
 * countdown pill (CountdownService), alert_lead_minutes before it a 15 s banner, at it the Azan (AzanService).
 * Times are each day's Aladhan times (DailyTimes), worked out on the TV for days not fetched yet. A day of alerts stays armed, each
 * its own alarm, and every alert, boot, update and clock change refreshes the times and re-arms them.
 */
public class PrayerAlerts extends BroadcastReceiver {
    static final String ACTION_ALERT = "com.majnioui.tv.prayer.ALERT";
    static final String EXTRA_PRAYER = "prayer";
    static final String EXTRA_KIND = "kind";
    static final String EXTRA_AT = "at_millis";
    /** An alert delivered later than this (the TV was in standby and woke later) shows nothing. */
    static final long LATE_MS = 2 * 60_000L;
    /** Alert kinds: the lead banner, the banner at the prayer, and the start of the countdown pill. */
    static final int BEFORE = 0, AT = 1, SOON = 2;
    private static final String TAG = "PrayerAlerts";
    private static final int[] PRAYERS = {PrayerTimes.FAJR, PrayerTimes.DHUHR, PrayerTimes.ASR,
            PrayerTimes.MAGHRIB, PrayerTimes.ISHA};
    static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final String[] ARABIC = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final long SHOW_MS = 15_000L;
    /** Alerts kept armed ahead: three per prayer, so a day. */
    static final int ALARM_SLOTS = 15;
    /** Request-code slots for the test alerts MainActivity arms with the test_alert_seconds extra. */
    static final int TEST_SLOT = 99;
    /** Request-code offset of each slot's early wake (PreWakeActivity). */
    static final int PREWAKE_OFFSET = 500;
    private static final long AFTER_ALARM_MS = 60_000L;
    static final int COLOR_SOON = 0xFFFF8A1F, COLOR_IMMINENT = 0xFFFF453A, COLOR_AT = 0xFF2BB673;

    /**
     * The next alert strictly after {@code after}: {epoch millis, prayer index, kind}. {@code times} gives a day's
     * minutes after midnight in Fajr..Isha order. Kinds with a zero or negative lead are skipped.
     */
    static long[] next(Function<LocalDate, int[]> times, int leadMinutes, int soonMinutes, Instant after, ZoneId zone) {
        LocalDate day = after.atZone(zone).toLocalDate();
        for (int d = 0; d < 3; d++, day = day.plusDays(1)) {
            int[] t = times.apply(day);
            for (int p : PRAYERS) {
                Instant at = wallClock(day, t[p], zone).toInstant();
                if (soonMinutes > leadMinutes) {
                    Instant soon = at.minusSeconds(soonMinutes * 60L);
                    if (soon.isAfter(after)) return new long[]{soon.toEpochMilli(), p, SOON};
                }
                if (leadMinutes > 0) {
                    Instant before = at.minusSeconds(leadMinutes * 60L);
                    if (before.isAfter(after)) return new long[]{before.toEpochMilli(), p, BEFORE};
                }
                if (at.isAfter(after)) return new long[]{at.toEpochMilli(), p, AT};
            }
        }
        throw new IllegalStateException("no prayer within three days");
    }

    /**
     * The moment {@code minutes} after midnight on the wall clock: on a daylight-saving day that is not
     * midnight plus that many minutes of real time.
     */
    static java.time.ZonedDateTime wallClock(LocalDate day, int minutes, ZoneId zone) {
        return day.atStartOfDay().plusMinutes(minutes).atZone(zone);
    }

    /** Which alerts are worth an alarm: (prayer index, kind). */
    interface Wanted {
        boolean test(int prayer, int kind);
    }

    /** The next {@code count} alerts after {@code after}, in time order. */
    static long[][] upcoming(Function<LocalDate, int[]> times, int leadMinutes, int soonMinutes, Instant after,
                             ZoneId zone, int count) {
        return upcoming(times, leadMinutes, soonMinutes, after, zone, count, (p, k) -> true);
    }

    /** The next {@code count} alerts after {@code after} that {@code wanted} keeps, in time order; fewer if none. */
    static long[][] upcoming(Function<LocalDate, int[]> times, int leadMinutes, int soonMinutes, Instant after,
                             ZoneId zone, int count, Wanted wanted) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        Instant from = after;
        // Three kinds per prayer and five prayers a day: twenty days of skipped alerts is never reached.
        for (int guard = 0; out.size() < count && guard < count * 100; guard++) {
            long[] n = next(times, leadMinutes, soonMinutes, from, zone);
            from = Instant.ofEpochMilli(n[0]);
            if (wanted.test((int) n[1], (int) n[2])) out.add(n);
        }
        return out.toArray(new long[0][]);
    }

    /** The next prayer itself after {@code after}: {epoch millis, prayer index}. */
    static long[] nextPrayer(Function<LocalDate, int[]> times, Instant after, ZoneId zone) {
        long[] n = next(times, 0, 0, after, zone);
        return new long[]{n[0], n[1]};
    }

    /**
     * Stored Aladhan times for the day, else the calculation with the chosen method's angles and Asr school.
     * Throws IllegalStateException when no location is set.
     */
    static Function<LocalDate, int[]> times(Context c, ZoneId zone) {
        Options o = Options.load(c);
        if (!o.hasLocation()) throw new IllegalStateException("no location set");
        String fp = o.fingerprint();
        double[] angles = o.angles();
        return day -> {
            int[] s = DailyTimes.stored(c, day, fp);
            if (s != null) return s;
            double tz = zone.getRules().getOffset(day.atTime(12, 0).atZone(zone).toInstant()).getTotalSeconds() / 3600.0;
            return PrayerTimes.compute(day.getYear(), day.getMonthValue(), day.getDayOfMonth(), o.lat, o.lon, tz,
                    angles[0], angles[1], o.school == 1 ? 2 : 1);
        };
    }

    /**
     * Arms the next ALARM_SLOTS alerts after {@code afterMillis}, one alarm each, so a delivery the firmware
     * blocks (TCL's app-start control) cannot stop the ones after it, and starts the countdown pill when a prayer
     * is already near. Only what Options switches on is armed; everything else is cancelled.
     */
    static synchronized void schedule(Context c, long afterMillis) {
        Options o = Options.load(c);
        if (!o.hasLocation() || !o.alerts && !o.azanAny()) {
            cancelAll(c);
            Log.i(TAG, o.hasLocation() ? "alerts and Azan off: nothing armed" : "no location: nothing armed");
            return;
        }
        Resources r = c.getResources();
        ZoneId zone = ZoneId.systemDefault();
        int lead = o.alerts ? r.getInteger(R.integer.alert_lead_minutes) : 0;
        int soon = o.alerts ? r.getInteger(R.integer.alert_soon_minutes) : 0;
        long[][] all;
        long[] prayer;
        try {
            Function<LocalDate, int[]> times = times(c, zone);
            all = upcoming(times, lead, soon, Instant.ofEpochMilli(afterMillis), zone, ALARM_SLOTS,
                    (p, kind) -> kind != AT || o.alerts || o.azanAt(p));
            prayer = nextPrayer(times, Instant.ofEpochMilli(System.currentTimeMillis()), zone);
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot work out the next alerts", e);
            return;
        }
        cancelAlarms(c, false);
        for (int slot = 0; slot < all.length; slot++) {
            try {
                arm(c, slot, all[slot], o);
            } catch (SecurityException e) {
                Log.e(TAG, "alert " + slot + " not armed", e);
            }
        }
        SleepService.ensureEnabled(c);
        if (all.length > 0) {
            Log.i(TAG, "armed " + all.length + " alerts; next: " + NAMES[(int) all[0][1]] + " kind " + all[0][2]
                    + " at " + Instant.ofEpochMilli(all[0][0]));
        }
        if (soon > 0 && prayer[0] - System.currentTimeMillis() <= soon * 60_000L) {
            CountdownService.start(c, (int) prayer[1], prayer[0]);
        } else {
            // Alerts off, or the city or method changed and the next prayer is no longer near.
            CountdownService.stop(c);
        }
    }

    /** Cancels every alert, Azan and early-wake alarm this add-on may hold, and the countdown pill. */
    static void cancelAll(Context c) {
        cancelAlarms(c, true);
        MorningQuran.cancelTest(c);
        CountdownService.stop(c);
    }

    /** {@code tests}: also the adb test alerts, which a re-arm from the first of them must leave armed. */
    private static void cancelAlarms(Context c, boolean tests) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        int probe = PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE;
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        for (int s = 0; s < ALARM_SLOTS; s++) slots.add(s);
        if (tests) for (int s = TEST_SLOT; s < TEST_SLOT + 3; s++) slots.add(s);
        for (int slot : slots) {
            for (PendingIntent pi : new PendingIntent[]{
                    PendingIntent.getBroadcast(c, slot, new Intent(c, AlertReceiver.class).setAction(ACTION_ALERT), probe),
                    // Armed by builds before AlertReceiver.
                    PendingIntent.getBroadcast(c, slot, new Intent(c, PrayerAlerts.class).setAction(ACTION_ALERT), probe),
                    PendingIntent.getActivity(c, slot, new Intent(c, AzanActivity.class), probe),
                    PendingIntent.getForegroundService(c, slot, new Intent(c, AzanService.class), probe)}) {
                if (pi != null) am.cancel(pi);
            }
            PreWakeActivity.cancel(c, slot + PREWAKE_OFFSET);
        }
    }

    /** One alert alarm in request-code slot {@code slot}; re-arming a slot replaces what it held. */
    static void arm(Context c, int slot, long[] n) {
        arm(c, slot, n, Options.load(c));
    }

    static void arm(Context c, int slot, long[] n, Options o) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent fire = new Intent(c, AlertReceiver.class).setAction(ACTION_ALERT)
                .putExtra(EXTRA_PRAYER, (int) n[1]).putExtra(EXTRA_KIND, (int) n[2]).putExtra(EXTRA_AT, n[0]);
        Intent azan = AzanActivity.intent(c, (int) n[1], n[0]);
        boolean direct = n[2] == AT && o.azanAt((int) n[1]);
        // A slot holds one alarm: drop the other kinds it may still hold from an earlier arming.
        int probe = PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE;
        for (PendingIntent other : new PendingIntent[]{
                direct ? PendingIntent.getBroadcast(c, slot, fire, probe) : PendingIntent.getActivity(c, slot, azan, probe),
                PendingIntent.getForegroundService(c, slot, new Intent(c, AzanService.class), probe)}) {
            if (other != null) am.cancel(other);
        }
        // The Azan straight from the alarm as an activity: it starts at once and can wake the screen, while on this
        // TCL a broadcast or service start can wait minutes as the TV re-suspends.
        PendingIntent pi = direct
                ? PendingIntent.getActivity(c, slot, azan, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
                : PendingIntent.getBroadcast(c, slot, fire, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (direct && c.getResources().getBoolean(R.bool.azan_wake_tv)) {
            PreWakeActivity.arm(c, slot + PREWAKE_OFFSET, n[0], NAMES[(int) n[1]] + " Azan",
                    PreWakeActivity.WHAT_AZAN, (int) n[1]);
        }
        else PreWakeActivity.cancel(c, slot + PREWAKE_OFFSET);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
            // Alarm-clock alarms are never deferred or batched, unlike the other exact kinds on this firmware.
            PendingIntent show = PendingIntent.getActivity(c, slot, new Intent(c, MainActivity.class),
                    PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(n[0], show), pi);
        } else {
            Log.w(TAG, "exact alarms not allowed; the alert may be a few minutes late");
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n[0], pi);
        }
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        PendingResult pending = goAsync();
        long late = System.currentTimeMillis() - intent.getLongExtra(EXTRA_AT, System.currentTimeMillis());
        boolean onTime = ACTION_ALERT.equals(intent.getAction()) && late <= LATE_MS;
        if (ACTION_ALERT.equals(intent.getAction()) && !onTime) {
            Log.i(TAG, "alert " + late / 60_000 + " min late (the TV was asleep): skipped");
        }
        int prayer = intent.getIntExtra(EXTRA_PRAYER, PrayerTimes.DHUHR);
        int kind = intent.getIntExtra(EXTRA_KIND, AT);
        if (ACTION_ALERT.equals(intent.getAction()) && !(this instanceof AlertReceiver)
                || prayer < 0 || prayer >= NAMES.length) {
            // Alerts come only through the private AlertReceiver: one sent to this exported receiver is ignored.
            onTime = false;
            prayer = PrayerTimes.DHUHR;
        }
        Options o = Options.load(c);
        boolean azan = kind == AT && o.azanAt(prayer);
        // Checked again here: an alarm armed before a setting changed may still fire.
        boolean alert = onTime && (azan || o.alerts);
        if (alert && kind != AT && !c.getSystemService(PowerManager.class).isInteractive()) {
            // The pill and the banner never wake the TV; only the Azan does.
            Log.i(TAG, "alert " + NAMES[prayer] + " kind " + kind + ": TV in standby, skipped");
            alert = false;
        }
        if (alert) Log.i(TAG, "alert fired: " + NAMES[prayer] + " kind " + kind);
        int lead = c.getResources().getInteger(R.integer.alert_lead_minutes);
        int soon = c.getResources().getInteger(R.integer.alert_soon_minutes);
        if (alert && o.hasLocation() && (kind == SOON || kind == BEFORE && soon > 0 && soon <= lead)) {
            long[] p = nextPrayer(times(c, ZoneId.systemDefault()), Instant.now(), ZoneId.systemDefault());
            CountdownService.start(c, prayer, p[1] == prayer ? p[0] : System.currentTimeMillis() + 60 * 60_000L);
        }
        if (alert && azan) {
            CountdownService.stop(c);
            c.startActivity(AzanActivity.intent(c, prayer, intent.getLongExtra(EXTRA_AT, System.currentTimeMillis())));
        } else if (alert && kind != SOON) {
            String kicker = kind == AT ? "Prayer time" : "In " + lead + " minutes";
            if (kind == BEFORE && ramadan(c) && (prayer == PrayerTimes.FAJR || prayer == PrayerTimes.MAGHRIB)) {
                kicker = (prayer == PrayerTimes.FAJR ? "Suhoor ends in " : "Iftar in ") + lead + " minutes";
            }
            show(c, kicker, NAMES[prayer] + "  ·  " + ARABIC[prayer], kind == AT ? COLOR_AT : COLOR_IMMINENT);
        }
        boolean shown = alert;
        Context app = c.getApplicationContext();
        // The day's Aladhan times off the main thread, then a day of alarms re-armed from them. A few seconds past
        // now, so a late alarm never re-arms the alert it is showing.
        boolean fromAlarm = ACTION_ALERT.equals(intent.getAction());
        if (!fromAlarm) VolumeGuard.reconcile(app);
        new Thread(() -> {
            try {
                DailyTimes.refresh(app);
                schedule(app, System.currentTimeMillis() + (fromAlarm ? AFTER_ALARM_MS : 0));
            } finally {
                if (!shown || kind == SOON || azan) {
                    pending.finish();
                }
                else new Handler(Looper.getMainLooper()).postDelayed(pending::finish, SHOW_MS);
            }
        }, "prayer-alerts").start();
    }

    /** Ramadan wording is on and today is in Ramadan (Hijri, moved by the Hijri offset). */
    static boolean ramadan(Context c) {
        Options o = Options.load(c);
        return o.ramadan && Hijri.ramadan(LocalDate.now(), o.hijriOffset);
    }

    /**
     * Refreshes the day's Aladhan times off the main thread, then re-arms the alerts from now and the recitations,
     * so a change in Options takes effect.
     */
    static void rearm(Context c) {
        rearm(c, 0);
    }

    /**
     * After an alert or Azan alarm: from a minute past now, so an alarm delivered a few seconds early never arms
     * its own alert again (alerts are minutes apart).
     */
    static void rearmAfterAlarm(Context c) {
        rearm(c, AFTER_ALARM_MS);
    }

    private static void rearm(Context c, long offset) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            // Arm first, so a switch takes effect at once even while Aladhan is slow; again once times arrive.
            schedule(app, System.currentTimeMillis() + offset);
            MorningQuran.schedule(app);
            if (DailyTimes.refresh(app) > 0) {
                schedule(app, System.currentTimeMillis() + offset);
                MorningQuran.schedule(app);
            }
        }, "prayer-alerts").start();
    }

    /** The banner, top centre, for SHOW_MS. */
    private static void show(Context c, String kicker, String title, int color) {
        Context app = c.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            Log.w(TAG, "no permission to draw over other apps: " + title);
            return;
        }
        float dp = app.getResources().getDisplayMetrics().density;
        LinearLayout banner = new LinearLayout(app);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setGravity(Gravity.CENTER_HORIZONTAL);
        banner.setPadding((int) (36 * dp), (int) (14 * dp), (int) (36 * dp), (int) (16 * dp));
        banner.setBackground(Glass.dark(dp, color, Glass.RADIUS));
        banner.addView(text(app, kicker.toUpperCase(), 13, color));
        banner.addView(text(app, title, 26, 0xFFFFFFFF));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (36 * dp);
        WindowManager wm = app.getSystemService(WindowManager.class);
        try {
            wm.addView(banner, lp);
        } catch (RuntimeException e) {
            Log.e(TAG, "could not show the banner", e);
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                wm.removeView(banner);
            } catch (RuntimeException e) {
                Log.w(TAG, "banner already gone", e);
            }
        }, SHOW_MS);
    }

    static TextView text(Context c, String s, int sp, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setLetterSpacing(sp < 16 ? 0.18f : 0f);
        v.setTypeface(Glass.font(c, sp < 16 ? 500 : sp >= 26 ? 300 : 400));
        return v;
    }
}
