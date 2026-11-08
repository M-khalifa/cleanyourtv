package io.github.mkhalifa.miracast.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FramePacerTest {
    private static final long FRAME_US = 33_333;
    private static final long BASE_NS = 5_000_000_000L;

    @Test
    public void evenArrivalRendersAtArrival() {
        FramePacer p = new FramePacer();
        for (int i = 0; i < 10; i++) {
            long now = BASE_NS + i * FRAME_US * 1000;
            long t = p.target(i * FRAME_US, now);
            assertTrue(t >= now && t - now <= FramePacer.MARGIN_NS + FramePacer.DRIFT_NS);
        }
        assertEquals(FramePacer.MARGIN_NS / 1_000_000L, p.delayMs());
        assertEquals(0, p.uneven());
    }

    @Test
    public void lateFrameRaisesDelaySoLaterFramesStayEven() {
        FramePacer p = new FramePacer();
        long last = 0;
        for (int i = 0; i < 40; i++) {
            long jitter = i == 5 ? 40_000_000L : (i % 3) * 5_000_000L;
            long now = BASE_NS + i * FRAME_US * 1000 + jitter;
            long t = p.target(i * FRAME_US, now);
            assertTrue("never before the frame is ready", t >= now);
            if (i > 6) {
                long gap = t - last;
                assertTrue("even spacing after the delay settled: " + gap,
                        Math.abs(gap - FRAME_US * 1000) <= 2 * (FramePacer.SHRINK_NS + FramePacer.DRIFT_NS));
            }
            last = t;
        }
        assertTrue(p.delayMs() >= 30);
        assertEquals("the delay jumps to cover the late frame, so none render behind schedule", 0, p.late());
    }

    @Test
    public void delayIsCappedAndJumpsResetTheClock() {
        FramePacer p = new FramePacer();
        p.target(0, BASE_NS);
        p.target(FRAME_US, BASE_NS + FRAME_US * 1000 + 500_000_000L);
        assertEquals(FramePacer.MAX_DELAY_NS / 1_000_000L, p.delayMs());
        long now = BASE_NS + 10_000_000_000L;
        assertEquals(now + FramePacer.MARGIN_NS, p.target(2 * FRAME_US, now));
        assertEquals(FramePacer.MARGIN_NS / 1_000_000L, p.delayMs());
    }

    @Test
    public void delayHoldsThePeakThenShrinksGently() {
        FramePacer p = new FramePacer();
        long spike = 60_000_000L;
        long last = 0;
        for (int i = 0; i < FramePacer.WINDOW + 300; i++) {
            long now = BASE_NS + i * FRAME_US * 1000 + (i == 3 ? spike : 0);
            long t = p.target(i * FRAME_US, now);
            if (i > 3 && i < FramePacer.WINDOW) assertTrue(p.delayMs() >= 63 && p.delayMs() <= 64);
            if (i > 4) assertTrue("no visible speed-up while shrinking",
                    t - last >= FRAME_US * 1000 - FramePacer.SHRINK_NS - FramePacer.DRIFT_NS);
            last = t;
        }
        assertEquals(FramePacer.MARGIN_NS / 1_000_000L, p.delayMs());
    }

    @Test
    public void instantModeCountsUnevenGaps() {
        FramePacer p = new FramePacer();
        p.note(BASE_NS, 0);
        p.note(BASE_NS + 33_000_000L, 33_333_000L);
        p.note(BASE_NS + 120_000_000L, 66_666_000L);
        assertEquals(1, p.uneven());
    }
}
