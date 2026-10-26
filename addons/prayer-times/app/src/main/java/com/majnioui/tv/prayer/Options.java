package com.majnioui.tv.prayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import java.util.Locale;
import java.util.Map;

/**
 * What the add-on does, set on the TV (SettingsActivity) over the defaults in config.xml. Only a value the user
 * changed is stored, so a default changed in config.xml still reaches everyone who never touched it. Everything
 * beyond the times page is off unless switched on.
 */
final class Options {
    static final String PREFS = "settings";
    static final String PLACE = "place", LAT = "lat", LON = "lon", METHOD = "method", SCHOOL = "school",
            ALERTS = "alerts", AZAN = "azan", AZAN_PRAYER = "azan_", AZAN_URL = "azan_url", MORNING = "morning",
            MORNING_TIME = "morning_time",
            KAHF = "kahf", RAMADAN = "ramadan", CARD_HIJRI = "card_hijri", HIJRI_OFFSET = "hijri_offset";

    /** Aladhan method ids offered on the TV, with the angles the offline fallback uses (isha < 0: minutes after Maghrib). */
    static final int[] METHOD_IDS = {2, 3, 5, 4, 1, 13, 21, 15};
    static final String[] METHOD_NAMES = {"ISNA (North America)", "Muslim World League", "Egyptian Authority",
            "Umm al-Qura, Makkah", "Karachi", "Diyanet, Turkey", "Morocco", "Moonsighting Committee"};
    private static final double[][] METHOD_ANGLES = {{15, 15}, {18, 17}, {19.5, 17.5}, {18.5, -90}, {18, 18},
            {18, 17}, {19, 17}, {18, 18}};

    String place = "";
    double lat = Double.NaN, lon = Double.NaN;
    int method = 2;
    /** 0 standard (shadow length 1), 1 Hanafi (2), as Aladhan's school parameter. */
    int school;
    boolean alerts, azan, morning, kahf, ramadan, cardHijri;
    final boolean[] azanFor = {true, false, true, true, true, true};
    /** An Azan recording is on the TV (AzanSound); load() checks the file. */
    boolean azanSound = true;
    /** Where the Azan recording was last downloaded from. */
    String azanUrl = "";
    String morningTime = "06:00";
    int hijriOffset;

    boolean hasLocation() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    /** The Azan is on for at least one prayer. */
    boolean azanAny() {
        if (!azan || !azanSound) return false;
        for (int p = 0; p < azanFor.length; p++) if (p != PrayerTimes.SUNRISE && azanFor[p]) return true;
        return false;
    }

    /** The Azan plays for this prayer. */
    boolean azanAt(int prayer) {
        return azan && azanSound && prayer >= 0 && prayer < azanFor.length && azanFor[prayer];
    }

    /** Identifies the times a location and method give; stored times with another fingerprint are stale. */
    String fingerprint() {
        return String.format(Locale.ROOT, "%.4f,%.4f,%d,%d", lat, lon, method, school);
    }

    /** {fajr angle, isha angle or minus minutes after Maghrib} for the method; ISNA for an unknown id. */
    double[] angles() {
        for (int i = 0; i < METHOD_IDS.length; i++) if (METHOD_IDS[i] == method) return METHOD_ANGLES[i];
        return METHOD_ANGLES[0];
    }

    static String methodName(int id) {
        for (int i = 0; i < METHOD_IDS.length; i++) if (METHOD_IDS[i] == id) return METHOD_NAMES[i];
        return "Method " + id;
    }

    /** Defaults from config.xml. */
    static Options defaults(Resources r) {
        Options s = new Options();
        s.place = r.getString(R.string.place);
        s.lat = parse(r.getString(R.string.latitude));
        s.lon = parse(r.getString(R.string.longitude));
        s.method = (int) parse(r.getString(R.string.method), 2);
        s.school = r.getInteger(R.integer.school);
        s.alerts = r.getBoolean(R.bool.alerts);
        s.azan = r.getBoolean(R.bool.azan_at_prayer);
        s.azanUrl = r.getString(R.string.azan_url);
        s.morning = r.getBoolean(R.bool.morning_quran);
        s.morningTime = r.getString(R.string.morning_quran_time);
        s.kahf = r.getBoolean(R.bool.kahf_friday);
        s.ramadan = r.getBoolean(R.bool.ramadan_wording);
        s.cardHijri = r.getBoolean(R.bool.card_hijri);
        s.hijriOffset = r.getInteger(R.integer.hijri_offset_days);
        return s;
    }

    static Options load(Context c) {
        Options o = apply(defaults(c.getResources()), c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll());
        o.azanSound = AzanSound.ready(c);
        return o;
    }

    /** {@code base} with the stored values in {@code stored} on top; values of the wrong type are ignored. */
    static Options apply(Options base, Map<String, ?> stored) {
        Options s = base;
        s.place = str(stored, PLACE, s.place);
        s.lat = parse(str(stored, LAT, Double.toString(s.lat)));
        s.lon = parse(str(stored, LON, Double.toString(s.lon)));
        s.method = integer(stored, METHOD, s.method);
        s.school = integer(stored, SCHOOL, s.school);
        s.alerts = bool(stored, ALERTS, s.alerts);
        s.azan = bool(stored, AZAN, s.azan);
        for (int p = 0; p < s.azanFor.length; p++) s.azanFor[p] = bool(stored, AZAN_PRAYER + p, s.azanFor[p]);
        s.azanUrl = str(stored, AZAN_URL, s.azanUrl);
        s.morning = bool(stored, MORNING, s.morning);
        s.morningTime = str(stored, MORNING_TIME, s.morningTime);
        s.kahf = bool(stored, KAHF, s.kahf);
        s.ramadan = bool(stored, RAMADAN, s.ramadan);
        s.cardHijri = bool(stored, CARD_HIJRI, s.cardHijri);
        s.hijriOffset = integer(stored, HIJRI_OFFSET, s.hijriOffset);
        return s;
    }

    /** Stores one changed value: Boolean, Integer or String. */
    static void put(Context c, String key, Object value) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) e.putInt(key, (Integer) value);
        else e.putString(key, String.valueOf(value));
        e.apply();
    }

    private static String str(Map<String, ?> m, String k, String d) {
        Object v = m.get(k);
        return v instanceof String ? (String) v : d;
    }

    private static int integer(Map<String, ?> m, String k, int d) {
        Object v = m.get(k);
        return v instanceof Integer ? (Integer) v : d;
    }

    private static boolean bool(Map<String, ?> m, String k, boolean d) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : d;
    }

    private static double parse(String s) {
        return parse(s, Double.NaN);
    }

    private static double parse(String s, double fallback) {
        try {
            return s == null || s.trim().isEmpty() ? fallback : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
