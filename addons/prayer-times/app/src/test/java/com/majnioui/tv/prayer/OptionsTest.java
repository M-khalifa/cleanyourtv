package com.majnioui.tv.prayer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class OptionsTest {
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final Function<LocalDate, int[]> TIMES = day -> new int[]{391, 460, 791, 981, 1125, 1193};

    @Test
    public void storedValuesOverrideDefaultsAndWrongTypesAreIgnored() {
        Options base = new Options();
        base.alerts = true;
        Map<String, Object> stored = new HashMap<>();
        stored.put(Options.AZAN, true);
        stored.put(Options.AZAN_PRAYER + PrayerTimes.FAJR, false);
        stored.put(Options.ALERTS, "yes");
        stored.put(Options.LAT, "41.8781");
        stored.put(Options.LON, "-87.6298");
        Options o = Options.apply(base, stored);
        assertTrue(o.alerts);
        assertTrue(o.azan);
        assertFalse(o.azanAt(PrayerTimes.FAJR));
        assertTrue(o.azanAt(PrayerTimes.DHUHR));
        assertFalse(o.azanAt(PrayerTimes.SUNRISE));
        assertTrue(o.hasLocation());
    }

    @Test
    public void noAzanWithoutASound() {
        Options o = new Options();
        o.azan = true;
        assertTrue(o.azanAt(PrayerTimes.DHUHR));
        assertTrue(o.azanAny());
        o.azanSound = false;
        assertFalse(o.azanAt(PrayerTimes.DHUHR));
        assertFalse(o.azanAny());
    }

    @Test
    public void azanAddressIsStored() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(Options.AZAN_URL, "https://example.org/azan.mp3");
        assertEquals("https://example.org/azan.mp3", Options.apply(new Options(), stored).azanUrl);
        assertEquals("", Options.apply(new Options(), new HashMap<>()).azanUrl);
    }

    @Test
    public void everythingOffAndNoLocationByDefault() {
        Options o = Options.apply(new Options(), new HashMap<>());
        assertFalse(o.hasLocation());
        assertFalse(o.alerts || o.azan || o.morning || o.kahf || o.ramadan || o.cardHijri);
        assertFalse(o.azanAt(PrayerTimes.DHUHR));
    }

    @Test
    public void storedTimesForAnotherCityOrMethodReadAsMissing() {
        Options o = new Options();
        o.lat = 41.8781;
        o.lon = -87.6298;
        String fp = o.fingerprint();
        int[] m = {391, 460, 791, 981, 1125, 1193};
        String value = fp + ";" + DailyTimes.encode(m);
        assertArrayEquals(m, DailyTimes.decodeFor(value, fp));
        o.method = 3;
        assertNull(DailyTimes.decodeFor(value, o.fingerprint()));
        assertNull(DailyTimes.decodeFor(DailyTimes.encode(m), fp));
    }

    @Test
    public void azanOnlyArmsJustThePrayersWithAnAzan() {
        Options o = new Options();
        o.azan = true;
        o.azanFor[PrayerTimes.FAJR] = false;
        Instant from = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, CHICAGO).toInstant();
        long[][] all = PrayerAlerts.upcoming(TIMES, 0, 0, from, CHICAGO, 6,
                (p, kind) -> kind != PrayerAlerts.AT || o.alerts || o.azanAt(p));
        assertEquals(6, all.length);
        for (long[] n : all) {
            assertEquals(PrayerAlerts.AT, n[2]);
            assertTrue(n[1] != PrayerTimes.FAJR);
        }
    }

    @Test
    public void methodAnglesAndUnknownMethodFallsBackToIsna() {
        Options o = new Options();
        o.method = 4;
        assertEquals(-90, o.angles()[1], 0);
        o.method = 999;
        assertEquals(15, o.angles()[0], 0);
        assertEquals("Muslim World League", Options.methodName(3));
    }

    @Test
    public void hanafiAsrIsLaterAndUmmAlQuraIshaIsNinetyMinutesAfterMaghrib() {
        int[] std = PrayerTimes.compute(2026, 10, 25, 41.8781, -87.6298, -5, 18.5, -90, 1);
        int[] hanafi = PrayerTimes.compute(2026, 10, 25, 41.8781, -87.6298, -5, 18.5, -90, 2);
        assertTrue(hanafi[PrayerTimes.ASR] - std[PrayerTimes.ASR] > 30);
        assertEquals(90, std[PrayerTimes.ISHA] - std[PrayerTimes.MAGHRIB], 1);
    }

    @Test
    public void prayerTimesAreWallClockOnDaylightSavingDays() {
        Instant after = ZonedDateTime.of(2026, 11, 1, 0, 30, 0, 0, CHICAGO).toInstant();
        long[] fajr = PrayerAlerts.nextPrayer(TIMES, after, CHICAGO);
        assertEquals("2026-11-01T06:31-06:00[America/Chicago]",
                Instant.ofEpochMilli(fajr[0]).atZone(CHICAGO).toString());
        Instant spring = ZonedDateTime.of(2027, 3, 14, 0, 30, 0, 0, CHICAGO).toInstant();
        assertEquals("2027-03-14T06:31-05:00[America/Chicago]",
                Instant.ofEpochMilli(PrayerAlerts.nextPrayer(TIMES, spring, CHICAGO)[0]).atZone(CHICAGO).toString());
    }

    @Test
    public void azanWithEveryPrayerOffCountsAsOff() {
        Options o = new Options();
        o.azan = true;
        for (int p = 0; p < o.azanFor.length; p++) o.azanFor[p] = false;
        assertFalse(o.azanAny());
        o.azanFor[PrayerTimes.ISHA] = true;
        assertTrue(o.azanAny());
    }
}
