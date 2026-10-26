package com.majnioui.tv.prayer;

/**
 * What the Azan does with the TV. TV on: Fajr at the Fajr volume, the other prayers at the volume as it is, and
 * the TV stays on. TV in standby: wake it at the Fajr or Azan volume and put it back to standby when the Azan ends.
 */
final class WakePlan {
    /** {@link #volume} for leaving the media volume as it is. */
    static final int KEEP = -1;

    final boolean wake;
    final int volume;
    final boolean sleepAfter;

    private WakePlan(boolean wake, int volume, boolean sleepAfter) {
        this.wake = wake;
        this.volume = volume;
        this.sleepAfter = sleepAfter;
    }

    /** The plan for the Azan of {@code prayer}, or null to skip it (TV in standby and waking it is off). */
    static WakePlan azan(int prayer, boolean screenOn, boolean wakeTv, int fajrVolume, int volume) {
        boolean fajr = prayer == PrayerTimes.FAJR;
        if (screenOn) return new WakePlan(false, fajr ? fajrVolume : KEEP, false);
        if (!wakeTv) return null;
        return new WakePlan(true, fajr ? fajrVolume : volume, true);
    }

    /** Back to standby when a recitation or Azan ends: only a TV the app woke, and only if nobody pressed a key. */
    static boolean sleepAfter(boolean woke, boolean keyPressed) {
        return woke && !keyPressed;
    }
}
