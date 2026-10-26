package com.majnioui.tv.prayer;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrayerTimesTest {
    private static final double LAT = 33.1507, LNG = -96.8236;

    private static void assertNear(String what, int expected, int actual) {
        assertTrue(what + ": expected " + expected + " got " + actual, Math.abs(expected - actual) <= 1);
    }

    private static int hm(int h, int m) {
        return h * 60 + m;
    }

    /** Aladhan ISNA for Frisco on 24 Oct 2026 (CDT, UTC-5), as the Prayer Times add-on showed it. */
    @Test
    public void matchesAladhanIsnaFrisco() {
        int[] t = PrayerTimes.compute(2026, 10, 24, LAT, LNG, -5, 15, 15);
        assertNear("Fajr", hm(6, 30), t[PrayerTimes.FAJR]);
        assertNear("Sunrise", hm(7, 39), t[PrayerTimes.SUNRISE]);
        assertNear("Dhuhr", hm(13, 11), t[PrayerTimes.DHUHR]);
        assertNear("Asr", hm(16, 20), t[PrayerTimes.ASR]);
        assertNear("Maghrib", hm(18, 44), t[PrayerTimes.MAGHRIB]);
        assertNear("Isha", hm(19, 52), t[PrayerTimes.ISHA]);
    }

    @Test
    public void timesAreInOrder() {
        for (int month = 1; month <= 12; month++) {
            double tz = (month >= 4 && month <= 10) ? -5 : -6;
            int[] t = PrayerTimes.compute(2026, month, 15, LAT, LNG, tz, 15, 15);
            for (int i = 1; i < t.length; i++) {
                assertTrue("month " + month + " index " + i, t[i] > t[i - 1]);
            }
        }
    }
}
