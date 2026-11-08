package io.github.mkhalifa.miracast.wfd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdrGateTest {
    @Test
    public void requestTooSoonIsSentOnceTheIntervalPasses() {
        IdrGate g = new IdrGate(1_000);
        assertTrue(g.request(10_000));
        assertFalse("nothing held yet", g.due(11_500));
        assertFalse("rate limited", g.request(10_400));
        assertFalse("still inside the interval", g.due(10_900));
        assertTrue("held request goes out", g.due(11_400));
        assertFalse("only once", g.due(13_000));
    }

    @Test
    public void sentRequestRestartsTheInterval() {
        IdrGate g = new IdrGate(1_000);
        assertTrue(g.request(0));
        assertFalse(g.request(500));
        assertTrue(g.due(1_000));
        assertFalse(g.request(1_500));
        assertTrue(g.request(2_000));
        assertFalse("a later direct send clears the held one", g.due(5_000));
    }
}
