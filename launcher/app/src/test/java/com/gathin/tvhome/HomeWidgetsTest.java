package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public class HomeWidgetsTest {
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    /** The add-on's times for two days, as its provider gives them. */
    private static final java.util.Map<String, int[]> TIMES = new java.util.HashMap<>();

    static {
        TIMES.put("2026-10-24", new int[]{391, 460, 791, 981, 1125, 1193});
        TIMES.put("2026-10-25", new int[]{392, 461, 791, 980, 1124, 1192});
    }

    private static long[] at(int hour, int minute) {
        Instant now = ZonedDateTime.of(2026, 10, 24, hour, minute, 0, 0, CHICAGO).toInstant();
        return HomeWidgets.nextPrayer(TIMES, now, CHICAGO);
    }

    private static String clock(long millis) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(CHICAGO);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    @Test
    public void morningPointsAtDhuhr() {
        long[] next = at(10, 0);
        assertEquals(PrayerTimes.DHUHR, next[0]);
        assertEquals("13:11", clock(next[1]));
    }

    @Test
    public void eveningPointsAtMaghrib() {
        long[] next = at(18, 0);
        assertEquals(PrayerTimes.MAGHRIB, next[0]);
        assertEquals("18:45", clock(next[1]));
    }

    @Test
    public void sunriseIsSkipped() {
        long[] next = at(7, 0);
        assertEquals(PrayerTimes.DHUHR, next[0]);
    }

    @Test
    public void afterIshaPointsAtTomorrowsFajr() {
        long[] next = at(21, 0);
        assertEquals(PrayerTimes.FAJR, next[0]);
        assertEquals(25, Instant.ofEpochMilli(next[1]).atZone(CHICAGO).getDayOfMonth());
    }

    @Test
    public void weatherIcons() {
        assertEquals(R.drawable.widget_dhuhr, HomeWidgets.weatherIcon(0, true));
        assertEquals(R.drawable.widget_isha, HomeWidgets.weatherIcon(0, false));
        assertEquals(R.drawable.widget_partly, HomeWidgets.weatherIcon(2, true));
        assertEquals(R.drawable.widget_cloud, HomeWidgets.weatherIcon(3, true));
        assertEquals(R.drawable.widget_fog, HomeWidgets.weatherIcon(45, true));
        assertEquals(R.drawable.widget_rain, HomeWidgets.weatherIcon(61, true));
        assertEquals(R.drawable.widget_rain, HomeWidgets.weatherIcon(81, true));
        assertEquals(R.drawable.widget_snow, HomeWidgets.weatherIcon(73, true));
        assertEquals(R.drawable.widget_storm, HomeWidgets.weatherIcon(95, true));
    }

    @Test
    public void prayerIcons() {
        assertEquals(R.drawable.widget_fajr, HomeWidgets.prayerIcon(PrayerTimes.FAJR));
        assertEquals(R.drawable.widget_maghrib, HomeWidgets.prayerIcon(PrayerTimes.MAGHRIB));
        assertEquals(R.drawable.widget_isha, HomeWidgets.prayerIcon(PrayerTimes.ISHA));
    }

    @Test
    public void urgencyLevels() {
        assertEquals(0, HomeWidgets.urgency(60 * 60_000L, 60, 15));
        assertEquals(1, HomeWidgets.urgency(60 * 60_000L - 1_000, 60, 15));
        assertEquals(1, HomeWidgets.urgency(15 * 60_000L, 60, 15));
        assertEquals(2, HomeWidgets.urgency(15 * 60_000L - 1_000, 60, 15));
        assertEquals(2, HomeWidgets.urgency(0, 60, 15));
        assertEquals(2, HomeWidgets.urgency(-5_000, 60, 15));
    }

    @Test
    public void countdownText() {
        assertEquals("now", HomeWidgets.countdown(0));
        assertEquals("in 1 min", HomeWidgets.countdown(30_000));
        assertEquals("in 12 min", HomeWidgets.countdown(12 * 60_000));
        assertEquals("in 2 h 35 min", HomeWidgets.countdown((2 * 60 + 35) * 60_000L));
    }

    @Test
    public void hijriDateAndRamadanNames() {
        org.junit.Assert.assertEquals("Iftar", HomeWidgets.name(PrayerTimes.MAGHRIB, true));
        org.junit.Assert.assertEquals("Suhoor ends", HomeWidgets.name(PrayerTimes.FAJR, true));
        org.junit.Assert.assertEquals("Maghrib", HomeWidgets.name(PrayerTimes.MAGHRIB, false));
        org.junit.Assert.assertEquals("Dhuhr", HomeWidgets.name(PrayerTimes.DHUHR, true));
    }

    @Test
    public void nextPrayerOnlyFromTheAddOnsTimes() {
        java.time.ZoneId z = java.time.ZoneId.of("America/Chicago");
        java.util.Map<String, int[]> times = new java.util.HashMap<>();
        java.time.Instant noon = java.time.ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, z).toInstant();
        org.junit.Assert.assertNull(HomeWidgets.nextPrayer(times, noon, z));
        times.put("2026-10-25", new int[]{391, 460, 791, 981, 1125, 1193});
        long[] n = HomeWidgets.nextPrayer(times, noon, z);
        org.junit.Assert.assertEquals(PrayerTimes.DHUHR, n[0]);
        java.time.Instant late = java.time.ZonedDateTime.of(2026, 10, 25, 23, 0, 0, 0, z).toInstant();
        org.junit.Assert.assertNull(HomeWidgets.nextPrayer(times, late, z));
    }

    @Test
    public void feedsCompareByTimesAndFlags() {
        java.util.Map<String, int[]> a = new java.util.HashMap<>();
        a.put("2026-10-25", new int[]{1, 2, 3, 4, 5, 6});
        java.util.Map<String, int[]> b = new java.util.HashMap<>();
        b.put("2026-10-25", new int[]{1, 2, 3, 4, 5, 6});
        org.junit.Assert.assertTrue(HomeWidgets.PrayerFeed.same(new HomeWidgets.PrayerFeed(a, java.util.Collections.emptySet(), "x", true),
                new HomeWidgets.PrayerFeed(b, java.util.Collections.emptySet(), "x", true)));
        org.junit.Assert.assertFalse(HomeWidgets.PrayerFeed.same(new HomeWidgets.PrayerFeed(a, java.util.Collections.emptySet(), "x", true),
                new HomeWidgets.PrayerFeed(b, java.util.Collections.singleton("2026-10-25"), "x", true)));
        org.junit.Assert.assertFalse(HomeWidgets.PrayerFeed.same(null, new HomeWidgets.PrayerFeed(b, java.util.Collections.emptySet(), null, true)));
        org.junit.Assert.assertTrue(HomeWidgets.PrayerFeed.same(null, null));
    }

    @Test
    public void cardPrayerTimeIsWallClockOnDaylightSavingDay() {
        java.time.ZoneId z = java.time.ZoneId.of("America/Chicago");
        java.util.Map<String, int[]> times = new java.util.HashMap<>();
        times.put("2026-11-01", new int[]{391, 460, 791, 981, 1125, 1193});
        long[] n = HomeWidgets.nextPrayer(times, java.time.ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, z).toInstant(), z);
        org.junit.Assert.assertEquals("2026-11-01T06:31-06:00[America/Chicago]",
                java.time.Instant.ofEpochMilli(n[1]).atZone(z).toString());
    }

    @Test
    public void ramadanNameFollowsTheNextPrayersOwnDate() {
        java.time.ZoneId z = java.time.ZoneId.of("America/Chicago");
        HomeWidgets.PrayerFeed f = new HomeWidgets.PrayerFeed(new java.util.HashMap<>(),
                java.util.Collections.singleton("2027-02-08"), null, true);
        long tomorrowFajr = java.time.ZonedDateTime.of(2027, 2, 8, 6, 20, 0, 0, z).toInstant().toEpochMilli();
        long tonight = java.time.ZonedDateTime.of(2027, 2, 7, 20, 0, 0, 0, z).toInstant().toEpochMilli();
        org.junit.Assert.assertTrue(f.ramadanOn(tomorrowFajr, z));
        org.junit.Assert.assertFalse(f.ramadanOn(tonight, z));
    }
}
