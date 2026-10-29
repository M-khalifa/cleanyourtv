package com.majnioui.tv.prayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every overlap of the Azan and a recitation, run through the shipped Handover with a pretend TV: its media volume
 * starts at USER, and each step's volume and standby are applied the way AzanService and QuranActivity apply them.
 */
public class HandoverTest {
    private static final int USER = 20, FAJR = 2, AZAN = 5, RECITE = 2;
    private static final int K = WakePlan.KEEP;

    private final Handover h = new Handover();
    private int volume = USER;
    private int sleeps;

    private void set(int v) {
        if (v != K) volume = v;
    }

    private void apply(Handover.Step s) {
        set(s.volume);
        if (s.sleep) sleeps++;
    }

    private void azanStart(boolean woke, int v) {
        set(h.azanStart(woke, v, volume));
    }

    private void recitePlay() {
        assertTrue("a recitation never plays over the Azan", h.recitationMayPlay());
        set(h.recitationPlay(RECITE, volume));
    }

    @Test
    public void recitationAloneWokeTheTv() {
        h.recitationStart(true, false, -1);
        recitePlay();
        assertEquals(RECITE, volume);
        apply(h.recitationEnd());
        assertEquals(USER, volume);
        assertEquals(1, sleeps);
    }

    @Test
    public void recitationStoppedOrWatchedLeavesTheTvOnAndGivesTheVolumeBack() {
        h.recitationStart(true, false, -1);
        recitePlay();
        h.recitationKeyPressed();
        apply(h.recitationEnd());
        assertEquals(USER, volume);
        assertEquals(0, sleeps);

        h.recitationStart(false, false, -1);
        recitePlay();
        apply(h.recitationEnd());
        assertEquals(USER, volume);
        assertEquals(0, sleeps);
    }

    /** October: Al-Baqarah at 06:00 wakes the TV, Fajr at 06:32 comes during it. */
    @Test
    public void fajrDuringTheMorningRecitation() {
        h.recitationStart(true, false, -1);
        recitePlay();
        azanStart(false, FAJR);
        assertFalse(h.recitationMayPlay());
        apply(h.azanEnd(false));
        assertEquals(0, sleeps);
        recitePlay();
        assertEquals(RECITE, volume);
        apply(h.recitationEnd());
        assertEquals(USER, volume);
        assertEquals(1, sleeps);
    }

    /** Summer: the Fajr Azan wakes the TV at 05:15 and is still on when the 06:00... here, when the recitation opens. */
    @Test
    public void recitationOpensDuringAnAzanThatWokeTheTv() {
        azanStart(true, FAJR);
        h.recitationStart(false, false, -1);
        assertFalse(h.recitationMayPlay());
        apply(h.azanEnd(false));
        assertEquals("not to standby under a recitation", 0, sleeps);
        assertEquals(USER, volume);
        recitePlay();
        assertEquals(RECITE, volume);
        apply(h.recitationEnd());
        assertEquals(USER, volume);
        assertEquals("the recitation puts back the TV the Azan woke", 1, sleeps);
    }

    @Test
    public void recitationFailsWhileTheAzanPlays() {
        h.recitationStart(true, false, -1);
        recitePlay();
        azanStart(false, FAJR);
        apply(h.recitationEnd());
        assertEquals("not mid-Azan", 0, sleeps);
        apply(h.azanEnd(false));
        assertEquals(USER, volume);
        assertEquals(1, sleeps);
    }

    @Test
    public void recitationThatNeverPlayedEndsDuringAnAzanThatWokeTheTv() {
        azanStart(true, FAJR);
        h.recitationStart(false, false, -1);
        apply(h.recitationEnd());
        apply(h.azanEnd(false));
        assertEquals(USER, volume);
        assertEquals(1, sleeps);
    }

    @Test
    public void keyDuringTheAzanKeepsTheTvOnAfterTheRecitationToo() {
        h.recitationStart(true, false, -1);
        recitePlay();
        azanStart(false, FAJR);
        apply(h.azanEnd(true));
        recitePlay();
        apply(h.recitationEnd());
        assertEquals(USER, volume);
        assertEquals(0, sleeps);
    }

    @Test
    public void azanAloneFromStandby() {
        azanStart(true, AZAN);
        assertEquals(AZAN, volume);
        apply(h.azanEnd(false));
        assertEquals(USER, volume);
        assertEquals(1, sleeps);
        azanStart(true, AZAN);
        apply(h.azanEnd(true));
        assertEquals(USER, volume);
        assertEquals(1, sleeps);
    }

    @Test
    public void azanOverAnAppKeepsTheVolumeAndTheTvOn() {
        azanStart(false, K);
        assertEquals(USER, volume);
        apply(h.azanEnd(false));
        assertEquals(USER, volume);
        assertEquals(0, sleeps);
    }

    @Test
    public void takeoverCarriesTheVolumeToRestoreAndTheWake() {
        h.recitationStart(true, false, -1);
        recitePlay();
        Object[] carry = h.recitationHandover();
        h.recitationStart((Boolean) carry[0], (Boolean) carry[1], (Integer) carry[2]);
        assertEquals(K, h.recitationPlay(5, volume));
        apply(h.recitationEnd());
        assertEquals(USER, volume);
        assertEquals(1, sleeps);
    }

    @Test
    public void endsAreReportedOnce() {
        apply(h.azanEnd(false));
        apply(h.recitationEnd());
        assertEquals(USER, volume);
        assertEquals(0, sleeps);
    }

    /** A key on the recitation, which ends during an Azan that woke the TV: someone is here, no standby. */
    @Test
    public void keyOnARecitationEndingDuringTheAzanKeepsTheTvOn() {
        azanStart(true, FAJR);
        h.recitationStart(false, false, -1);
        h.recitationKeyPressed();
        apply(h.recitationEnd());
        apply(h.azanEnd(false));
        assertEquals(USER, volume);
        assertEquals(0, sleeps);
    }

    @Test
    public void standbyRequestsWaitForWhateverIsPlaying() {
        assertFalse(h.standby(false));
        assertTrue(h.standby(true));
        h.recitationStart(false, false, -1);
        recitePlay();
        assertFalse("not under a playing recitation", h.standby(true));
        apply(h.recitationEnd());
        assertEquals("the recitation took over the early wake's standby", 1, sleeps);
        azanStart(false, AZAN);
        assertFalse("not mid-Azan", h.standby(true));
        apply(h.azanEnd(false));
        assertEquals(2, sleeps);
    }

    @Test
    public void theVolumeToGiveBackIsKeptOutsideTheProcessUntilTheLastEnd() {
        java.util.List<String> log = new java.util.ArrayList<>();
        h.store = new Handover.Store() {
            @Override
            public void save(int restore, int set) {
                log.add("save " + restore + " " + set);
            }

            @Override
            public void clear() {
                log.add("clear");
            }
        };
        h.recitationStart(true, false, -1);
        recitePlay();
        azanStart(false, FAJR);
        apply(h.azanEnd(false));
        recitePlay();
        apply(h.recitationEnd());
        assertEquals(java.util.Arrays.asList("save 20 2", "clear"), log);
    }

    /** The TV was already on and someone pressed a key: the recitation plays out, the warning runs out. */
    @Test
    public void playedOutWarningRunsOutToStandby() {
        h.recitationStart(false, false, -1);
        recitePlay();
        h.recitationKeyPressed();
        apply(h.recitationEnd());
        assertEquals(0, sleeps);
        assertTrue(h.playedOut());
        assertTrue(h.warningShowing());
        assertTrue(h.warningExpired());
        assertFalse(h.warningShowing());
    }

    @Test
    public void cancelledWarningNeverGoesToStandby() {
        assertTrue(h.playedOut());
        h.warningCancelled();
        assertFalse("a tick still queued after the cancel", h.warningExpired());
    }

    /** An Azan starting during the warning closes it and takes the standby, ignoring the recitation's old key. */
    @Test
    public void azanDuringTheWarningTakesTheStandbyOver() {
        int[] closed = {0};
        h.warningCloser = () -> closed[0]++;
        h.recitationStart(false, true, -1);
        recitePlay();
        apply(h.recitationEnd());
        assertTrue(h.playedOut());
        azanStart(false, AZAN);
        assertEquals(1, closed[0]);
        assertFalse(h.warningShowing());
        assertTrue(h.standbyAtAzanEnd());
        apply(h.azanEnd(false));
        assertEquals(1, sleeps);
    }

    /** Dismissing that Azan, or any key during it, means someone is watching. */
    @Test
    public void keyDuringTheAzanCancelsTheTakenOverStandby() {
        assertTrue(h.playedOut());
        azanStart(false, AZAN);
        apply(h.azanEnd(true));
        assertEquals(0, sleeps);
        assertFalse("the Azan's end leaves nothing pending", h.warningExpired());
    }

    /** A recitation that plays out while an Azan is on shows no warning under the Azan window. */
    @Test
    public void playedOutDuringAnAzanGoesToTheAzanEnd() {
        h.recitationStart(false, true, -1);
        recitePlay();
        azanStart(false, AZAN);
        apply(h.recitationEnd());
        assertFalse(h.playedOut());
        assertFalse(h.warningShowing());
        apply(h.azanEnd(false));
        assertEquals(1, sleeps);
    }

    @Test
    public void nextRecitationDuringTheWarningClosesIt() {
        int[] closed = {0};
        h.warningCloser = () -> closed[0]++;
        assertTrue(h.playedOut());
        h.recitationStart(false, false, -1);
        assertEquals(1, closed[0]);
        assertFalse(h.warningExpired());
    }

    @Test
    public void ownScreensAreCountedUntilTheyStop() {
        assertFalse(h.ownScreenShowing());
        h.ownScreenShown(true);
        h.ownScreenShown(true);
        h.ownScreenShown(false);
        assertTrue("an early wake still in front", h.ownScreenShowing());
        h.ownScreenShown(false);
        assertFalse(h.ownScreenShowing());
        h.ownScreenShown(false);
        h.ownScreenShown(true);
        assertTrue("an extra stop never hides a later start", h.ownScreenShowing());
    }

    /** Friday's Al-Kahf opens during the morning recitation's warning and then never loads. */
    @Test
    public void recitationDuringTheWarningOwesItsStandby() {
        assertTrue(h.playedOut());
        h.recitationStart(false, false, -1);
        assertFalse(h.warningShowing());
        apply(h.recitationEnd());
        assertEquals(1, sleeps);
    }

    @Test
    public void keyDuringTheNextRecitationStillKeepsTheTvOn() {
        assertTrue(h.playedOut());
        h.recitationStart(false, false, -1);
        recitePlay();
        h.recitationKeyPressed();
        apply(h.recitationEnd());
        assertEquals(0, sleeps);
    }
}
