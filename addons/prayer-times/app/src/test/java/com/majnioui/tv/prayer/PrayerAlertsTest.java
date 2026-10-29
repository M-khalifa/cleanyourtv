package com.majnioui.tv.prayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.function.Function;

public class PrayerAlertsTest {
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    /** The ISNA calculation for Chicago, as the alerts use for days without Aladhan times. */
    private static final Function<LocalDate, int[]> ISNA = day -> {
        double tz = CHICAGO.getRules().getOffset(day.atTime(12, 0).atZone(CHICAGO).toInstant()).getTotalSeconds() / 3600.0;
        return PrayerTimes.compute(day.getYear(), day.getMonthValue(), day.getDayOfMonth(), 41.8781, -87.6298, tz, 15, 15);
    };

    private static Instant at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, CHICAGO).toInstant();
    }

    private static String clock(long millis) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(CHICAGO);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    @Test
    public void hourWarningComesFirst() {
        long[] n = PrayerAlerts.next(ISNA, 15, 60, at(24, 10, 0), CHICAGO);
        assertEquals(PrayerTimes.DHUHR, n[1]);
        assertEquals(PrayerAlerts.SOON, n[2]);
        assertEquals("11:35", clock(n[0]));
    }

    @Test
    public void thenFifteenMinutesBeforeThenAtThePrayer() {
        long[] before = PrayerAlerts.next(ISNA, 15, 60, at(24, 11, 36), CHICAGO);
        assertEquals(PrayerAlerts.BEFORE, before[2]);
        assertEquals("12:20", clock(before[0]));
        long[] onTime = PrayerAlerts.next(ISNA, 15, 60, at(24, 12, 21), CHICAGO);
        assertEquals(PrayerAlerts.AT, onTime[2]);
        assertEquals("12:35", clock(onTime[0]));
    }

    @Test
    public void afterIshaGoesToTomorrowsFajrHourWarning() {
        long[] n = PrayerAlerts.next(ISNA, 15, 60, at(24, 20, 0), CHICAGO);
        assertEquals(PrayerTimes.FAJR, n[1]);
        assertEquals(PrayerAlerts.SOON, n[2]);
        assertEquals(25, Instant.ofEpochMilli(n[0]).atZone(CHICAGO).getDayOfMonth());
    }

    @Test
    public void storedDailyTimesWinOverTheCalculation() {
        int[] aladhan = {5 * 60 + 57, 7 * 60 + 14, 12 * 60 + 36, 15 * 60 + 30, 17 * 60 + 56, 19 * 60 + 14};
        Function<LocalDate, int[]> times = day -> day.getDayOfMonth() == 24 ? aladhan : ISNA.apply(day);
        long[] n = PrayerAlerts.next(times, 15, 60, at(24, 17, 0), CHICAGO);
        assertEquals(PrayerTimes.MAGHRIB, n[1]);
        assertEquals("17:41", clock(n[0]));
        long[] p = PrayerAlerts.nextPrayer(times, at(24, 17, 0), CHICAGO);
        assertEquals("17:56", clock(p[0]));
    }

    @Test
    public void aDayOfAlertsInOrder() {
        long[][] all = PrayerAlerts.upcoming(ISNA, 15, 60, at(24, 15, 45), CHICAGO, PrayerAlerts.ALARM_SLOTS);
        assertEquals(PrayerTimes.MAGHRIB, all[0][1]);
        assertEquals(PrayerAlerts.SOON, all[0][2]);
        assertEquals("16:55", clock(all[0][0]));
        for (int i = 1; i < all.length; i++) assertTrue(all[i][0] > all[i - 1][0]);
        assertEquals(PrayerTimes.ASR, all[14][1]);
        assertEquals(PrayerAlerts.AT, all[14][2]);
    }

    @Test
    public void noHourWarningWhenItIsOff() {
        long[] n = PrayerAlerts.next(ISNA, 15, 0, at(24, 10, 0), CHICAGO);
        assertEquals(PrayerAlerts.BEFORE, n[2]);
        assertEquals("12:20", clock(n[0]));
    }

    @Test
    public void aladhanTimingsParse() {
        String json = "{\"code\":200,\"data\":{\"timings\":{\"Fajr\":\"06:31\",\"Sunrise\":\"07:40 (CDT)\","
                + "\"Dhuhr\":\"13:12\",\"Asr\":\"16:21\",\"Sunset\":\"18:45\",\"Maghrib\":\"18:45\","
                + "\"Isha\":\"19:53\",\"Imsak\":\"06:21\"}}}";
        assertArrayEquals(new int[]{391, 460, 792, 981, 1125, 1193}, DailyTimes.parse(json));
        assertNull(DailyTimes.parse("{\"data\":{\"timings\":{\"Fajr\":\"06:31\"}}}"));
    }

    @Test
    public void dailyTimesRoundTrip() {
        int[] m = {391, 460, 792, 981, 1125, 1193};
        assertArrayEquals(m, DailyTimes.decode(DailyTimes.encode(m)));
        assertNull(DailyTimes.decode("1,2,3"));
        assertNull(DailyTimes.decode("a,b,c,d,e,f"));
        assertNull(DailyTimes.decode(null));
    }

    @Test
    public void morningQuranNextDay() {
        java.time.LocalTime five = java.time.LocalTime.of(5, 0);
        assertEquals("2026-10-25T05:00-05:00[America/Chicago]",
                MorningQuran.nextAt(five, ZonedDateTime.of(2026, 10, 24, 22, 0, 0, 0, CHICAGO)).toString());
        assertEquals("2026-10-25T05:00-05:00[America/Chicago]",
                MorningQuran.nextAt(five, ZonedDateTime.of(2026, 10, 25, 4, 59, 0, 0, CHICAGO)).toString());
        assertEquals("2026-10-26T05:00-05:00[America/Chicago]",
                MorningQuran.nextAt(five, ZonedDateTime.of(2026, 10, 25, 5, 0, 0, 0, CHICAGO)).toString());
    }

    @Test
    public void countdownTicksJustAfterTheMinuteTurns() {
        assertEquals("1.5 min left shows 2 min for 30 s more", 30_050, CountdownService.nextTurn(90_000));
        assertEquals(60_050, CountdownService.nextTurn(60_000));
        assertEquals(51, CountdownService.nextTurn(1));
        assertEquals("in 1 min", CountdownService.countdown(90_000 - 30_050));
    }

    @Test
    public void countdownText() {
        assertEquals("in 42 min", CountdownService.countdown(42 * 60_000L));
        assertEquals("in 1 h 0 min", CountdownService.countdown(60 * 60_000L));
        assertEquals("now", CountdownService.countdown(0));
    }
}
