package io.github.mkhalifa.miracast.helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import io.github.mkhalifa.miracast.Prefs;

public class IpNeighTest {
    @Test
    public void keepsOnlyLiveIpv4() {
        String out = "192.168.49.91 lladdr 2a:7b:11:24:49:a6 REACHABLE\n"
                + "192.168.49.50 lladdr 2a:7b:11:24:49:a7 STALE\n"
                + "192.168.49.92 lladdr 2a:7b:11:24:49:a8 DELAY\n"
                + "fe80::287b:11ff:fe24:49a6 lladdr 2a:7b:11:24:49:a6 REACHABLE\n"
                + "192.168.49.93  FAILED\n"
                + "192.168.49.91 lladdr 2a:7b:11:24:49:a6 REACHABLE\n";
        assertEquals(List.of("192.168.49.91", "192.168.49.92"), IpNeigh.liveIpv4(out));
    }

    @Test
    public void withDevColumnAndEmpty() {
        assertEquals(List.of("10.0.0.2"), IpNeigh.liveIpv4("10.0.0.2 dev p2p-p2p0-0 lladdr aa:bb:cc:dd:ee:ff REACHABLE"));
        assertTrue(IpNeigh.liveIpv4("").isEmpty());
        assertTrue(IpNeigh.liveIpv4(null).isEmpty());
        assertTrue(IpNeigh.liveIpv4("999.1.1.1 lladdr x REACHABLE").isEmpty());
    }

    @Test
    public void hostLabelFromName() {
        assertEquals("KhalifaTV", Prefs.hostLabel("KhalifaTV"));
        assertEquals("Living-Room-TV", Prefs.hostLabel("Living Room TV."));
        assertEquals("KhalifaTV", Prefs.hostLabel("..."));
    }
}
