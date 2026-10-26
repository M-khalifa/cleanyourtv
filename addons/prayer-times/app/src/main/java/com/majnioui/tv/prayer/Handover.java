package com.majnioui.tv.prayer;

/**
 * Who gives the volume back and who puts the TV back to standby when the Azan and a recitation overlap. Both run
 * on the main thread of one process and report their events here; each event returns what to do now. The rule:
 * whichever ends last restores the volume found before the first one changed it, and puts back a TV either of them
 * (or an early wake) woke, unless someone pressed a key during any of them. Every standby request goes through here.
 */
final class Handover {
    /** What to do now: the media volume to set ({@link WakePlan#KEEP} for none) and whether to go to standby. */
    static final class Step {
        final int volume;
        final boolean sleep;

        Step(int volume, boolean sleep) {
            this.volume = volume;
            this.sleep = sleep;
        }
    }

    /** Keeps the volume to give back across a process death (VolumeGuard); absent in tests. */
    interface Store {
        /** {@code restore}: the user's volume; {@code set}: the level the event put the TV at. */
        void save(int restore, int set);

        void clear();
    }

    static final Handover STATE = new Handover();

    Store store;
    private boolean saved;
    private boolean azan, azanWoke, azanKey;
    private int azanRestore = -1;
    private boolean recitation, recitationWoke, recitationKey, recitationApplied;
    private int recitationRestore = -1;

    boolean azanPlaying() {
        return azan;
    }

    boolean recitationActive() {
        return recitation;
    }

    /**
     * A standby request from outside the two events (a skipped event, an early wake nobody used): true to go to
     * standby now; while an Azan or recitation plays the TV goes back at its end instead, so false.
     */
    boolean standby(boolean woke) {
        if (!woke) return false;
        if (azan) {
            azanWoke = true;
            return false;
        }
        if (recitation) {
            recitationWoke = true;
            return false;
        }
        return true;
    }

    /** The Azan starts at {@code volume} (or KEEP) with the TV at {@code current}; returns the volume to set. */
    int azanStart(boolean woke, int volume, int current) {
        azan = true;
        azanWoke = woke;
        azanKey = false;
        azanRestore = volume == WakePlan.KEEP ? -1 : current;
        if (volume != WakePlan.KEEP) remember(current, volume);
        return volume;
    }

    /** The Azan has ended (played out, dismissed or the TV turned off: {@code keyPressed}). */
    Step azanEnd(boolean keyPressed) {
        if (!azan) return new Step(WakePlan.KEEP, false);
        azan = false;
        boolean key = keyPressed || azanKey;
        boolean sleep = azanWoke && !key;
        int volume = azanRestore < 0 ? WakePlan.KEEP : azanRestore;
        azanWoke = false;
        azanKey = false;
        azanRestore = -1;
        // A key during the Azan means someone is here for the recitation after it too.
        if (key && recitation) recitationKey = true;
        if (sleep && recitation) {
            // The recitation goes on: it puts the TV back at its own end.
            recitationWoke = true;
            sleep = false;
        }
        forgetIfIdle();
        return new Step(volume, sleep);
    }

    /** A recitation opens; {@code restore} is a volume carried from the recitation it takes over, else -1. */
    void recitationStart(boolean woke, boolean keyPressed, int restore) {
        recitation = true;
        recitationWoke = woke;
        recitationKey = keyPressed;
        recitationRestore = restore;
        recitationApplied = restore >= 0;
    }

    /** It may start or resume only while no Azan plays. */
    boolean recitationMayPlay() {
        return recitation && !azan;
    }

    /** Its first real start sets its volume; later ones keep what the user chose. Returns the volume to set. */
    int recitationPlay(int volume, int current) {
        if (recitationApplied) return WakePlan.KEEP;
        recitationApplied = true;
        recitationRestore = current;
        remember(current, volume);
        return volume;
    }

    void recitationKeyPressed() {
        recitationKey = true;
    }

    /** Carried to the next instance on a takeover: {woke, key pressed, volume to restore}. */
    Object[] recitationHandover() {
        recitation = false;
        return new Object[]{recitationWoke, recitationKey, recitationRestore};
    }

    /** The recitation has ended, however: finished, stopped, failed or the TV turned off. */
    Step recitationEnd() {
        if (!recitation) return new Step(WakePlan.KEEP, false);
        recitation = false;
        boolean sleep = recitationWoke && !recitationKey;
        int restore = recitationRestore;
        recitationRestore = -1;
        if (azan) {
            // The Azan is on: it gives back the volume the recitation found and puts the TV back when it ends,
            // unless someone pressed a key during the recitation.
            if (recitationKey) azanKey = true;
            azanWoke |= sleep;
            if (restore >= 0) azanRestore = restore;
            return new Step(WakePlan.KEEP, false);
        }
        forgetIfIdle();
        return new Step(restore < 0 ? WakePlan.KEEP : restore, sleep);
    }

    /** The first volume change of an overlap is kept outside the process, in case it dies before the end. */
    private void remember(int restore, int set) {
        if (saved || store == null) return;
        store.save(restore, set);
        saved = true;
    }

    private void forgetIfIdle() {
        if (azan || recitation || !saved) return;
        saved = false;
        if (store != null) store.clear();
    }
}
