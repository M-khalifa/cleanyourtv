package com.majnioui.tv.prayer;

/**
 * Offline prayer times by angle (the PrayTimes.org method): Fajr and Isha at a sun depression angle (or Isha a
 * fixed time after Maghrib), Asr at shadow length 1 (standard) or 2 (Hanafi), sunrise and Maghrib at 0.833
 * degrees. ISNA is 15/15.
 */
final class PrayerTimes {
    static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;

    private PrayerTimes() {
    }

    /** Minutes after local midnight, rounded to the nearest minute, in FAJR..ISHA order. */
    static int[] compute(int year, int month, int day, double lat, double lng, double tzHours,
                         double fajrAngle, double ishaAngle) {
        return compute(year, month, day, lat, lng, tzHours, fajrAngle, ishaAngle, 1);
    }

    /** {@code isha} below zero is minutes after Maghrib (Umm al-Qura's 90); {@code asrShadow} 1 or 2 (Hanafi). */
    static int[] compute(int year, int month, int day, double lat, double lng, double tzHours,
                         double fajrAngle, double isha, int asrShadow) {
        double jDate = julian(year, month, day) - lng / (15.0 * 24.0);
        double[] t = {5, 6, 12, 13, 18, 18};
        for (int i = 0; i < t.length; i++) t[i] /= 24.0;

        double[] h = new double[6];
        h[FAJR] = sunAngleTime(jDate, lat, fajrAngle, t[FAJR], true);
        h[SUNRISE] = sunAngleTime(jDate, lat, 0.833, t[SUNRISE], true);
        h[DHUHR] = midDay(jDate, t[DHUHR]);
        h[ASR] = asrTime(jDate, lat, t[ASR], asrShadow);
        h[MAGHRIB] = sunAngleTime(jDate, lat, 0.833, t[MAGHRIB], false);
        h[ISHA] = isha < 0 ? h[MAGHRIB] - isha / 60.0 : sunAngleTime(jDate, lat, isha, t[ISHA], false);

        int[] minutes = new int[6];
        for (int i = 0; i < 6; i++) {
            double local = h[i] + tzHours - lng / 15.0;
            minutes[i] = (int) Math.round(fixHour(local) * 60.0) % (24 * 60);
        }
        return minutes;
    }

    private static double julian(int year, int month, int day) {
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4.0);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** {declination in degrees, equation of time in hours} at Julian date jd. */
    private static double[] sunPosition(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0;
        double eqt = q / 15.0 - fixHour(ra);
        double decl = darcsin(dsin(e) * dsin(l));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jDate, double portion) {
        return fixHour(12 - sunPosition(jDate + portion)[1]);
    }

    private static double sunAngleTime(double jDate, double lat, double angle, double portion, boolean beforeNoon) {
        double decl = sunPosition(jDate + portion)[0];
        double noon = midDay(jDate, portion);
        double cos = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat));
        double t = darccos(Math.max(-1, Math.min(1, cos))) / 15.0;
        return noon + (beforeNoon ? -t : t);
    }

    private static double asrTime(double jDate, double lat, double portion, int shadow) {
        double decl = sunPosition(jDate + portion)[0];
        double angle = -Math.toDegrees(Math.atan(1.0 / (shadow + dtan(Math.abs(lat - decl)))));
        return sunAngleTime(jDate, lat, angle, portion, false);
    }

    private static double fixAngle(double a) {
        a = a - 360.0 * Math.floor(a / 360.0);
        return a < 0 ? a + 360.0 : a;
    }

    private static double fixHour(double a) {
        a = a - 24.0 * Math.floor(a / 24.0);
        return a < 0 ? a + 24.0 : a;
    }

    private static double dsin(double d) {
        return Math.sin(Math.toRadians(d));
    }

    private static double dcos(double d) {
        return Math.cos(Math.toRadians(d));
    }

    private static double dtan(double d) {
        return Math.tan(Math.toRadians(d));
    }

    private static double darcsin(double x) {
        return Math.toDegrees(Math.asin(x));
    }

    private static double darccos(double x) {
        return Math.toDegrees(Math.acos(x));
    }

    private static double darctan2(double y, double x) {
        return Math.toDegrees(Math.atan2(y, x));
    }
}
