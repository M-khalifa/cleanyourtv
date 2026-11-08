package io.github.mkhalifa.miracast.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class RtpTest {
    static byte[] rtp(int seq, byte[] payload, int csrc, boolean ext, int pad) {
        int hdr = 12 + csrc * 4 + (ext ? 8 : 0);
        byte[] b = new byte[hdr + payload.length + pad];
        b[0] = (byte) (0x80 | (pad > 0 ? 0x20 : 0) | (ext ? 0x10 : 0) | csrc);
        b[1] = (byte) 33;
        b[2] = (byte) (seq >> 8);
        b[3] = (byte) seq;
        b[7] = 1;
        b[11] = 9;
        if (ext) {
            int p = 12 + csrc * 4;
            b[p + 3] = 1;
        }
        System.arraycopy(payload, 0, b, hdr, payload.length);
        if (pad > 0) b[b.length - 1] = (byte) pad;
        return b;
    }

    @Test
    public void parsesHeaderWithCsrcExtensionAndPadding() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] b = rtp(0xBEEF, payload, 2, true, 3);
        RtpPacket p = new RtpPacket();
        assertTrue(p.parse(b, 0, b.length));
        assertEquals(33, p.payloadType);
        assertEquals(0xBEEF, p.sequence);
        assertEquals(1, p.timestamp);
        assertEquals(9, p.ssrc);
        assertEquals(12 + 8 + 8, p.payloadOffset);
        assertEquals(5, p.payloadLength);
        assertFalse(p.parse(new byte[]{0x40, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 0, 12));
    }

    private static final class Collect implements RtpReorder.Sink {
        final List<Integer> got = new ArrayList<>();
        final List<Integer> marked = new ArrayList<>();
        int lost;

        @Override
        public void onPayload(byte[] d, int off, int len, boolean marker) {
            got.add((int) d[off]);
            if (marker) marked.add((int) d[off]);
        }

        @Override
        public void onLoss(int n) {
            lost += n;
        }
    }

    private static void push(RtpReorder r, int seq, int marker) {
        r.push(seq & 0xFFFF, new byte[]{(byte) marker}, 0, 1, false);
    }

    @Test
    public void markerTravelsWithItsPacketAndLateDuplicateDropsIt() {
        Collect c = new Collect();
        RtpReorder r = new RtpReorder(8, c);
        r.push(10, new byte[]{0}, 0, 1, false);
        r.push(12, new byte[]{2}, 0, 1, true);
        r.push(11, new byte[]{1}, 0, 1, false);
        assertEquals(List.of(0, 1, 2), c.got);
        assertEquals("held packet keeps its marker", List.of(2), c.marked);
        r.push(12, new byte[]{2}, 0, 1, true);
        assertEquals("late duplicate is not delivered, nor its marker", List.of(2), c.marked);
        assertEquals(3, c.got.size());
    }

    @Test
    public void reordersWithinWindow() {
        Collect c = new Collect();
        RtpReorder r = new RtpReorder(4, c);
        push(r, 10, 0);
        push(r, 12, 2);
        push(r, 11, 1);
        push(r, 13, 3);
        assertEquals(List.of(0, 1, 2, 3), c.got);
        assertEquals(0, c.lost);
    }

    @Test
    public void reportsLossWhenWindowOverflows() {
        Collect c = new Collect();
        RtpReorder r = new RtpReorder(3, c);
        push(r, 100, 0);
        push(r, 102, 2);
        push(r, 103, 3);
        push(r, 104, 4);
        assertEquals(List.of(0), c.got);
        push(r, 105, 5);
        assertEquals(List.of(0, 2, 3, 4, 5), c.got);
        assertEquals(1, c.lost);
        push(r, 101, 1);
        assertEquals(1, r.late());
    }

    @Test
    public void handlesWraparoundAndFlush() {
        Collect c = new Collect();
        RtpReorder r = new RtpReorder(8, c);
        push(r, 65534, 0);
        push(r, 65535, 1);
        push(r, 1, 3);
        assertEquals(List.of(0, 1), c.got);
        assertTrue(r.flush());
        assertEquals(List.of(0, 1, 3), c.got);
        assertEquals(1, c.lost);
        assertFalse(r.flush());
        push(r, 2, 4);
        assertEquals(List.of(0, 1, 3, 4), c.got);
    }

    @Test
    public void duplicateIsDropped() {
        Collect c = new Collect();
        RtpReorder r = new RtpReorder(8, c);
        push(r, 5, 0);
        push(r, 5, 0);
        assertEquals(1, c.got.size());
        assertEquals(1, r.late());
    }

    @Test
    public void rtpFromAnotherHostOrStreamIsRejected() throws Exception {
        InetAddress source = InetAddress.getByName("192.168.49.20");
        InetAddress other = InetAddress.getByName("192.168.1.66");
        RtpSourceLock lock = new RtpSourceLock();
        assertEquals(false, lock.accept(source, null, 1));
        assertEquals(true, lock.accept(source, source, 1));
        assertEquals(false, lock.accept(other, source, 1));
        assertEquals(false, lock.accept(source, source, 2));
        assertEquals(true, lock.accept(source, source, 1));
        assertEquals(3, lock.rejected());
    }

    @Test
    public void rtpLockFollowsALastingSsrcChangeFromTheSource() throws Exception {
        InetAddress source = InetAddress.getByName("192.168.49.20");
        RtpSourceLock lock = new RtpSourceLock();
        lock.accept(source, source, 1);
        for (int i = 1; i < RtpSourceLock.RELOCK_AFTER; i++) assertEquals(false, lock.accept(source, source, 2));
        assertEquals(true, lock.accept(source, source, 2));
        assertEquals(false, lock.accept(source, source, 1));
    }
}
