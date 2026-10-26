package com.majnioui.tv.prayer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.util.Log;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Function;

/**
 * The recitations: every day at morning_quran_time (config.xml morning_quran), and on Fridays Al-Kahf
 * kahf_minutes_before_dhuhr before the Dhuhr Azan (kahf_friday). Each alarm opens QuranActivity straight away,
 * which wakes the TV, sets the volume and plays the surah. Re-armed each time QuranActivity starts; this receiver
 * re-arms them after boots, updates and clock changes.
 */
public class MorningQuran extends BroadcastReceiver {
    private static final String TAG = "MorningQuran";
    private static final int SLOT = 200;
    static final int TEST_SLOT = 201;
    private static final int KAHF_SLOT = 202;
    static final String EXTRA_AT = "at_millis";
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_VOLUME = "volume";
    static final String EXTRA_KIND = "kind";
    /** What a recitation alarm is for, so it can check its switch when it fires. */
    static final int KIND_MORNING = 0, KIND_KAHF = 1, KIND_TEST = 2;
    /** An alarm delivered later than this (the TV was in standby and woke later) is skipped. */
    static final long LATE_MS = 10 * 60_000L;

    /** The next occurrence of {@code time} strictly after {@code now}. */
    static ZonedDateTime nextAt(LocalTime time, ZonedDateTime now) {
        ZonedDateTime at = now.with(time).withSecond(0).withNano(0);
        return at.isAfter(now) ? at : at.plusDays(1);
    }

    /** The next Friday start of Al-Kahf strictly after {@code now}: {@code minutesBefore} that day's Dhuhr. */
    static ZonedDateTime nextKahf(Function<LocalDate, int[]> times, int minutesBefore, ZonedDateTime now) {
        LocalDate day = now.toLocalDate();
        for (int d = 0; d < 8; d++, day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            ZonedDateTime at = PrayerAlerts.wallClock(day, times.apply(day)[PrayerTimes.DHUHR] - minutesBefore,
                    now.getZone());
            if (at.isAfter(now)) return at;
        }
        throw new IllegalStateException("no Friday within eight days");
    }

    static synchronized void schedule(Context c) {
        Resources r = c.getResources();
        Options o = Options.load(c);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        cancel(c, SLOT);
        if (o.morning) {
            try {
                long at = nextAt(LocalTime.parse(o.morningTime), now).toInstant().toEpochMilli();
                arm(c, SLOT, at, null, null, -1, KIND_MORNING);
                Log.i(TAG, "armed for " + java.time.Instant.ofEpochMilli(at));
            } catch (RuntimeException e) {
                Log.e(TAG, "bad morning recitation time " + o.morningTime, e);
            }
        }
        cancel(c, KAHF_SLOT);
        if (o.kahf && o.hasLocation()) {
            try {
                long at = nextKahf(PrayerAlerts.times(c, now.getZone()), r.getInteger(R.integer.kahf_minutes_before_dhuhr),
                        now).toInstant().toEpochMilli();
                arm(c, KAHF_SLOT, at, r.getString(R.string.kahf_url), r.getString(R.string.kahf_title),
                        r.getInteger(R.integer.kahf_volume), KIND_KAHF);
                Log.i(TAG, "Al-Kahf armed for " + java.time.Instant.ofEpochMilli(at));
            } catch (RuntimeException e) {
                Log.e(TAG, "cannot work out Friday's Al-Kahf", e);
            }
        }
    }

    /** The recitation alarm in {@code slot} and its early wake. */
    private static void cancel(Context c, int slot) {
        PendingIntent pi = PendingIntent.getActivity(c, slot, new Intent(c, QuranActivity.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) c.getSystemService(AlarmManager.class).cancel(pi);
        PreWakeActivity.cancel(c, slot + PrayerAlerts.PREWAKE_OFFSET);
    }

    /** The adb test recitation and its early wake. */
    static void cancelTest(Context c) {
        cancel(c, TEST_SLOT);
    }

    /** When the next switched-on recitation starts, or 0 when none is on. */
    static long nextDue(Context c) {
        Options o = Options.load(c);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        long due = 0;
        try {
            if (o.morning) due = nextAt(LocalTime.parse(o.morningTime), now).toInstant().toEpochMilli();
            if (o.kahf && o.hasLocation()) {
                long k = nextKahf(PrayerAlerts.times(c, now.getZone()),
                        c.getResources().getInteger(R.integer.kahf_minutes_before_dhuhr), now).toInstant().toEpochMilli();
                due = due == 0 ? k : Math.min(due, k);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot work out the next recitation", e);
        }
        return due;
    }

    /** The switch for a recitation of {@code kind} is still on. */
    static boolean enabled(Options o, int kind) {
        return kind == KIND_TEST || kind == KIND_MORNING && o.morning || kind == KIND_KAHF && o.kahf;
    }

    /** {@code url}, {@code title} null and {@code volume} -1 take the morning recitation's from config.xml. */
    static void arm(Context c, int slot, long atMillis, String url, String title, int volume, int kind) {
        // The activity itself, not a broadcast: on this TCL a broadcast can wait minutes while the TV re-suspends.
        Intent open = new Intent(c, QuranActivity.class).putExtra(EXTRA_AT, atMillis).putExtra(EXTRA_VOLUME, volume)
                .putExtra(EXTRA_KIND, kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (url != null) open.putExtra(EXTRA_URL, url);
        if (title != null) open.putExtra(EXTRA_TITLE, title);
        PendingIntent fire = PendingIntent.getActivity(c, slot, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent show = PendingIntent.getActivity(c, slot + 1000, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(atMillis, show), fire);
            } else {
                Log.w(TAG, "exact alarms not allowed; the recitation may start a few minutes late");
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, fire);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "recitation not armed", e);
            return;
        }
        PreWakeActivity.arm(c, slot + PrayerAlerts.PREWAKE_OFFSET, atMillis, title != null ? "Al-Kahf" : "Morning Quran",
                PreWakeActivity.WHAT_RECITATION, kind);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        schedule(c);
    }
}
