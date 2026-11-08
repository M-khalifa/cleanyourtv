package io.github.mkhalifa.miracast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WfdSessionWatchdogTest {
    private static final long T0 = 1_000_000;

    @Test
    public void silentSourceBeforePlayHitsTheNegotiationDeadline() {
        assertNull(WfdSession.timeoutReason(T0 + WfdSession.NEGOTIATE_MS, T0, T0, 0, false, false, false, 60));
        assertEquals("negotiation timeout",
                WfdSession.timeoutReason(T0 + WfdSession.NEGOTIATE_MS + 1, T0, T0, 0, false, false, false, 60));
        assertEquals("SETUP answered but no PLAY", "negotiation timeout",
                WfdSession.timeoutReason(T0 + WfdSession.NEGOTIATE_MS + 1, T0, T0 + 5_000, 0, true, false, false, 60));
    }

    @Test
    public void rtpStoppingWhilePlayingEndsTheSession() {
        long now = T0 + 100_000;
        assertNull(WfdSession.timeoutReason(now, T0, now, now - WfdSession.RTP_IDLE_MS, true, true, true, 60));
        assertEquals("no RTP for 30 s",
                WfdSession.timeoutReason(now, T0, now, now - WfdSession.RTP_IDLE_MS - 1, true, true, true, 60));
        assertNull("paused: no RTP expected",
                WfdSession.timeoutReason(now, T0, now, T0, true, true, false, 60));
    }

    @Test
    public void keepAliveTimeoutIsTwiceTheSessionTimeout() {
        long now = T0 + 200_000;
        assertNull(WfdSession.timeoutReason(now, T0, now - 60_000, now, true, true, true, 30));
        assertEquals("keep-alive timeout", WfdSession.timeoutReason(now, T0, now - 60_001, now, true, true, true, 30));
    }
}
