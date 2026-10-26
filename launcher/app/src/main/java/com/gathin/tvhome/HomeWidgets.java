package com.gathin.tvhome;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The home widgets under the top buttons (config.xml home_widgets): the next prayer, read from the Prayer Times
 * add-on's provider only while it is installed and has a city (its own settings decide everything religious), and
 * the weather now, read from the Weather add-on.
 */
final class HomeWidgets {
    static final String[] NAMES = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final String[] ARABIC = {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"};
    private static final int[] PRAYERS = {PrayerTimes.FAJR, PrayerTimes.DHUHR, PrayerTimes.ASR,
            PrayerTimes.MAGHRIB, PrayerTimes.ISHA};
    private static final String WEATHER = "content://io.github.sumyabazar1.weather.now";
    static final String PRAYER_AUTHORITY = "com.majnioui.tv.prayer.times";
    private static final String PRAYER_TIMES = "content://" + PRAYER_AUTHORITY;
    private static final long WEATHER_MAX_AGE_MS = 3 * 60 * 60_000L;
    private static final String TAG = "TvHomeWidgets";

    private HomeWidgets() {
    }

    /** The prayer's time-of-day icon, as in the Prayer Times add-on. */
    static int prayerIcon(int prayer) {
        switch (prayer) {
            case PrayerTimes.FAJR: return R.drawable.widget_fajr;
            case PrayerTimes.SUNRISE: return R.drawable.widget_sunrise;
            case PrayerTimes.DHUHR: return R.drawable.widget_dhuhr;
            case PrayerTimes.ASR: return R.drawable.widget_asr;
            case PrayerTimes.MAGHRIB: return R.drawable.widget_maghrib;
            default: return R.drawable.widget_isha;
        }
    }

    /** An icon for a WMO weather code, by day or night (Open-Meteo's codes, as the Weather add-on reads them). */
    static int weatherIcon(int code, boolean day) {
        if (code == 0) return day ? R.drawable.widget_dhuhr : R.drawable.widget_isha;
        if (code <= 2) return day ? R.drawable.widget_partly : R.drawable.widget_cloud;
        if (code == 3) return R.drawable.widget_cloud;
        if (code == 45 || code == 48) return R.drawable.widget_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.widget_snow;
        if (code >= 95) return R.drawable.widget_storm;
        if (code >= 51) return R.drawable.widget_rain;
        return R.drawable.widget_cloud;
    }

    /** 0 normal, 1 under soonMinutes to the prayer, 2 under imminentMinutes (or already due). */
    /** The prayer's name on the card: in Ramadan, Fajr is when Suhoor ends and Maghrib is Iftar. */
    static String name(int prayer, boolean ramadan) {
        if (ramadan && prayer == PrayerTimes.FAJR) return "Suhoor ends";
        if (ramadan && prayer == PrayerTimes.MAGHRIB) return "Iftar";
        return NAMES[prayer];
    }

    static int urgency(long millisLeft, int soonMinutes, int imminentMinutes) {
        if (millisLeft < imminentMinutes * 60_000L) return 2;
        if (millisLeft < soonMinutes * 60_000L) return 1;
        return 0;
    }

    /** "in 2 h 35 min", "in 12 min", or "now" under a minute. */
    static String countdown(long millis) {
        long minutes = (millis + 59_999) / 60_000;
        if (minutes <= 0) return "now";
        if (minutes < 60) return "in " + minutes + " min";
        return "in " + minutes / 60 + " h " + minutes % 60 + " min";
    }

    /**
     * What the Prayer Times add-on gives the card: its daily times by ISO date and, for today, whether to use the
     * Ramadan names and the Hijri date text (null when the add-on has it off). Null when the add-on is missing or
     * has no times (no city chosen): the card then shows no prayer side at all.
     */
    static PrayerFeed prayerFeed(ContentResolver resolver) {
        java.util.Map<String, int[]> times = new java.util.HashMap<>();
        java.util.Set<String> ramadanDays = new java.util.HashSet<>();
        boolean alerts = false;
        String hijri = null;
        String today = LocalDate.now().toString();
        try (Cursor c = resolver.query(Uri.parse(PRAYER_TIMES), null, null, null, null)) {
            if (c == null) return null;
            String[] cols = {"fajr", "sunrise", "dhuhr", "asr", "maghrib", "isha"};
            int ramadanCol = c.getColumnIndex("ramadan"), hijriCol = c.getColumnIndex("hijri"),
                    alertsCol = c.getColumnIndex("alerts");
            while (c.moveToNext()) {
                int[] m = new int[cols.length];
                for (int i = 0; i < cols.length; i++) m[i] = c.getInt(c.getColumnIndexOrThrow(cols[i]));
                String date = c.getString(c.getColumnIndexOrThrow("date"));
                times.put(date, m);
                if (ramadanCol >= 0 && c.getInt(ramadanCol) == 1) ramadanDays.add(date);
                // An add-on without the column has its pill on: the headline steps aside as before.
                alerts = alertsCol < 0 || c.getInt(alertsCol) == 1;
                if (date.equals(today)) hijri = hijriCol >= 0 && !c.isNull(hijriCol) ? c.getString(hijriCol) : null;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "prayer times read failed", e);
            return null;
        }
        return times.isEmpty() ? null : new PrayerFeed(times, ramadanDays, hijri, alerts);
    }

    /** The next prayer from the add-on's times: {prayer index, epoch millis}, or null when they run out. */
    static long[] nextPrayer(java.util.Map<String, int[]> times, Instant now, ZoneId zone) {
        LocalDate day = now.atZone(zone).toLocalDate();
        for (int d = 0; d < 2; d++, day = day.plusDays(1)) {
            int[] t = times.get(day.toString());
            if (t == null) continue;
            for (int p : PRAYERS) {
                // Wall-clock minutes: on a daylight-saving day midnight plus real minutes is an hour off.
                ZonedDateTime at = day.atStartOfDay().plusMinutes(t[p]).atZone(zone);
                if (at.toInstant().isAfter(now)) return new long[]{p, at.toInstant().toEpochMilli()};
            }
        }
        return null;
    }

    static final class PrayerFeed {
        final java.util.Map<String, int[]> times;
        /** ISO dates in Ramadan with the add-on's Ramadan wording on: the next prayer's own date decides. */
        final java.util.Set<String> ramadanDays;
        final String hijri;
        /** The add-on's red countdown pill is on, so the Home headline can step aside under it. */
        final boolean alerts;

        PrayerFeed(java.util.Map<String, int[]> times, java.util.Set<String> ramadanDays, String hijri, boolean alerts) {
            this.times = times;
            this.ramadanDays = ramadanDays;
            this.hijri = hijri;
            this.alerts = alerts;
        }

        boolean ramadanOn(long epochMillis, ZoneId zone) {
            return ramadanDays.contains(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString());
        }

        static boolean same(PrayerFeed a, PrayerFeed b) {
            if (a == null || b == null) return a == b;
            if (!a.ramadanDays.equals(b.ramadanDays) || a.alerts != b.alerts
                    || !java.util.Objects.equals(a.hijri, b.hijri)) return false;
            if (!a.times.keySet().equals(b.times.keySet())) return false;
            for (String k : a.times.keySet()) if (!java.util.Arrays.equals(a.times.get(k), b.times.get(k))) return false;
            return true;
        }
    }

    /** The Weather add-on's latest reading, or null when it is missing, has no city yet, or failed. */
    static Weather weather(ContentResolver resolver) {
        try (Cursor c = resolver.query(Uri.parse(WEATHER), null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            int updated = c.getColumnIndex("updated_ms");
            // A reading hours old is not "now": the card shows no weather rather than an old one as current.
            if (updated >= 0 && System.currentTimeMillis() - c.getLong(updated) > WEATHER_MAX_AGE_MS) return null;
            return new Weather(c.getString(c.getColumnIndexOrThrow("place")),
                    c.getInt(c.getColumnIndexOrThrow("temp_c")), c.getInt(c.getColumnIndexOrThrow("feels_c")),
                    c.getString(c.getColumnIndexOrThrow("sky")), optInt(c, "code", 0), optInt(c, "day", 1) == 1);
        } catch (RuntimeException e) {
            Log.w(TAG, "weather read failed", e);
            return null;
        }
    }

    /** An older Weather add-on has no code or day column. */
    private static int optInt(Cursor c, String column, int fallback) {
        int i = c.getColumnIndex(column);
        return i < 0 ? fallback : c.getInt(i);
    }

    static final class Weather {
        final String place, sky;
        final int tempC, feelsC, code;
        final boolean day;

        Weather(String place, int tempC, int feelsC, String sky, int code, boolean day) {
            this.place = place;
            this.tempC = tempC;
            this.feelsC = feelsC;
            this.sky = sky;
            this.code = code;
            this.day = day;
        }
    }
}
