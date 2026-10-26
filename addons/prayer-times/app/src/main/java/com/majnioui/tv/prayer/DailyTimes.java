package com.majnioui.tv.prayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Each day's prayer times as Aladhan gives them (the same source and method the page shows), kept per date
 * so the alerts follow them. Each value carries the fingerprint of the location and method it was fetched for
 * ("fp;minutes"), so after a change of city or method the old times read as missing. Days not fetched yet fall
 * back to {@link PrayerTimes} with the method's angles.
 */
final class DailyTimes {
    private static final String TAG = "PrayerTimes";
    private static final String PREFS = "daily_times";
    private static final String[] KEYS = {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final DateTimeFormatter KEY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter URL_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    /** Days kept; older entries are dropped on each save. */
    private static final int KEEP_DAYS = 7;

    private DailyTimes() {
    }

    /** Minutes after midnight in Fajr..Isha order from an Aladhan timings answer, or null when one is missing. */
    static int[] parse(String json) {
        int[] m = new int[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            Matcher x = Pattern.compile("\"" + KEYS[i] + "\"\\s*:\\s*\"(\\d{1,2}):(\\d{2})").matcher(json);
            if (!x.find()) return null;
            m[i] = Integer.parseInt(x.group(1)) * 60 + Integer.parseInt(x.group(2));
        }
        return m;
    }

    static String encode(int[] m) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < m.length; i++) b.append(i == 0 ? "" : ",").append(m[i]);
        return b.toString();
    }

    static int[] decode(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != KEYS.length) return null;
        int[] m = new int[p.length];
        try {
            for (int i = 0; i < p.length; i++) m[i] = Integer.parseInt(p[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return m;
    }

    /** The day's minutes from a stored value, or null when it is for another fingerprint or unreadable. */
    static int[] decodeFor(String value, String fingerprint) {
        if (value == null) return null;
        int sep = value.indexOf(';');
        if (sep < 0 || !value.substring(0, sep).equals(fingerprint)) return null;
        return decode(value.substring(sep + 1));
    }

    static int[] stored(Context c, LocalDate day) {
        return stored(c, day, Options.load(c).fingerprint());
    }

    static int[] stored(Context c, LocalDate day, String fingerprint) {
        return decodeFor(c.getSharedPreferences(PREFS, 0).getString(day.format(KEY), null), fingerprint);
    }

    /** {@code fingerprint}: the settings the times were fetched with, not the settings now. */
    static void save(Context c, LocalDate day, String fingerprint, int[] minutes) {
        SharedPreferences p = c.getSharedPreferences(PREFS, 0);
        SharedPreferences.Editor e = p.edit().putString(day.format(KEY), fingerprint + ";" + encode(minutes));
        LocalDate oldest = LocalDate.now().minusDays(KEEP_DAYS);
        for (String k : p.getAll().keySet()) {
            try {
                if (LocalDate.parse(k, KEY).isBefore(oldest)) e.remove(k);
            } catch (RuntimeException ignored) {
                e.remove(k);
            }
        }
        e.apply();
    }

    /** Fetches and stores the days that are missing, today and tomorrow; returns how many were fetched. */
    static int refresh(Context c) {
        Options s = Options.load(c);
        if (!s.hasLocation()) return 0;
        String fp = s.fingerprint();
        int fetched = 0;
        for (int d = 0; d < 2; d++) {
            LocalDate day = LocalDate.now().plusDays(d);
            if (stored(c, day, fp) != null) continue;
            try {
                int[] m = parse(fetch(url(s, day)));
                if (m != null) {
                    save(c, day, fp, m);
                    fetched++;
                } else {
                    Log.w(TAG, "Aladhan answer without timings for " + day);
                }
            } catch (Exception e) {
                Log.w(TAG, "Aladhan fetch failed for " + day + ": " + e.getClass().getSimpleName());
            }
        }
        return fetched;
    }

    static String url(Options s, LocalDate day) {
        return String.format(java.util.Locale.ROOT, "https://api.aladhan.com/v1/timings/%s?latitude=%.4f&longitude=%.4f"
                + "&method=%d&school=%d", day.format(URL_DATE), s.lat, s.lon, s.method, s.school);
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
