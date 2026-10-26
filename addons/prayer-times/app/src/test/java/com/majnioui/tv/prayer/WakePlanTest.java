package com.majnioui.tv.prayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WakePlanTest {
    @Test
    public void tvOnKeepsTheVolumeAndStaysOn() {
        for (int p : new int[]{PrayerTimes.DHUHR, PrayerTimes.ASR, PrayerTimes.MAGHRIB, PrayerTimes.ISHA}) {
            WakePlan plan = WakePlan.azan(p, true, true, 2, 5);
            assertFalse(plan.wake);
            assertEquals(WakePlan.KEEP, plan.volume);
            assertFalse(plan.sleepAfter);
        }
    }

    @Test
    public void tvOnFajrIsQuiet() {
        WakePlan plan = WakePlan.azan(PrayerTimes.FAJR, true, true, 2, 5);
        assertFalse(plan.wake);
        assertEquals(2, plan.volume);
        assertFalse(plan.sleepAfter);
    }

    @Test
    public void tvOffWakesAtTheAzanVolumeAndSleepsAfter() {
        WakePlan plan = WakePlan.azan(PrayerTimes.ASR, false, true, 2, 5);
        assertTrue(plan.wake);
        assertEquals(5, plan.volume);
        assertTrue(plan.sleepAfter);
        WakePlan fajr = WakePlan.azan(PrayerTimes.FAJR, false, true, 2, 5);
        assertTrue(fajr.wake);
        assertEquals(2, fajr.volume);
        assertTrue(fajr.sleepAfter);
    }

    @Test
    public void tvOffWithWakingOffSkipsTheAzan() {
        assertNull(WakePlan.azan(PrayerTimes.ISHA, false, false, 2, 5));
    }

    @Test
    public void onlyAWokenUntouchedTvGoesBackToStandby() {
        assertTrue(WakePlan.sleepAfter(true, false));
        assertFalse(WakePlan.sleepAfter(true, true));
        assertFalse(WakePlan.sleepAfter(false, false));
        assertFalse(WakePlan.sleepAfter(false, true));
    }
}
