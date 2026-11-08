package io.github.mkhalifa.miracast.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class TsDemuxerTest {
    private static final int PMT_PID = 0x100;
    private static final int VIDEO_PID = 0x1011;
    private static final int AUDIO_PID = 0x1100;
    private final int[] cc = new int[0x2000];

    /** One TS packet; payload is padded with an adaptation field of stuffing, optional PCR. */
    private byte[] packet(int pid, boolean pusi, byte[] payload, int off, int len, Long pcrBase) {
        byte[] p = new byte[188];
        p[0] = 0x47;
        p[1] = (byte) ((pusi ? 0x40 : 0) | (pid >> 8));
        p[2] = (byte) pid;
        int room = 184;
        int afNeeded = pcrBase != null ? 8 : 0;
        boolean af = pcrBase != null || len < room;
        int pos = 4;
        if (af) {
            int afLen = Math.max(afNeeded == 0 ? 1 : afNeeded, room - len) - 1;
            if (afNeeded > 0 && afLen < 7) afLen = 7;
            p[3] = (byte) (0x30 | (cc[pid] & 0xF));
            p[4] = (byte) afLen;
            if (afLen > 0) {
                p[5] = (byte) (pcrBase != null ? 0x10 : 0);
                if (pcrBase != null) {
                    long b = pcrBase;
                    p[6] = (byte) (b >> 25);
                    p[7] = (byte) (b >> 17);
                    p[8] = (byte) (b >> 9);
                    p[9] = (byte) (b >> 1);
                    p[10] = (byte) (((b & 1) << 7) | 0x7E);
                    p[11] = 0;
                }
                for (int i = pcrBase != null ? 12 : 6; i < 5 + afLen; i++) p[i] = (byte) 0xFF;
            }
            pos = 5 + afLen;
        } else {
            p[3] = (byte) (0x10 | (cc[pid] & 0xF));
        }
        cc[pid]++;
        System.arraycopy(payload, off, p, pos, 188 - pos);
        return p;
    }

    private List<byte[]> packetize(int pid, byte[] pes, Long pcr) {
        List<byte[]> out = new ArrayList<>();
        int off = 0;
        boolean first = true;
        while (off < pes.length) {
            int room = 184 - (first && pcr != null ? 8 : 0);
            int n = Math.min(room, pes.length - off);
            byte[] chunk = Arrays.copyOfRange(pes, off, off + n);
            out.add(packet(pid, first, chunk, 0, n, first ? pcr : null));
            off += n;
            first = false;
        }
        return out;
    }

    private static byte[] psi(int tableId, byte[] body) {
        int sectionLength = body.length + 5 + 4;
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(0);
        s.write(tableId);
        s.write(0xB0 | (sectionLength >> 8));
        s.write(sectionLength);
        s.write(0);
        s.write(1);
        s.write(0xC1);
        s.write(0);
        s.write(0);
        s.write(body, 0, body.length);
        s.write(0);
        s.write(0);
        s.write(0);
        s.write(0);
        return s.toByteArray();
    }

    private byte[] pat() {
        byte[] body = {0, 1, (byte) (0xE0 | (PMT_PID >> 8)), (byte) PMT_PID};
        byte[] s = psi(0, body);
        return packet(0, true, s, 0, s.length, null);
    }

    private byte[] pmt() {
        byte[] body = {(byte) (0xE0 | (VIDEO_PID >> 8)), (byte) VIDEO_PID, (byte) 0xF0, 0,
                0x1B, (byte) (0xE0 | (VIDEO_PID >> 8)), (byte) VIDEO_PID, (byte) 0xF0, 0,
                (byte) 0x83, (byte) (0xE0 | (AUDIO_PID >> 8)), (byte) AUDIO_PID, (byte) 0xF0, 0};
        byte[] s = psi(2, body);
        return packet(PMT_PID, true, s, 0, s.length, null);
    }

    static byte[] pes(int streamId, long pts90k, byte[] payload, boolean withLength) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        o.write(0);
        o.write(1);
        o.write(streamId);
        int len = withLength ? payload.length + 8 : 0;
        o.write(len >> 8);
        o.write(len);
        o.write(0x80);
        o.write(0x80);
        o.write(5);
        o.write((int) (0x21 | ((pts90k >> 29) & 0x0E)));
        o.write((int) (pts90k >> 22));
        o.write((int) (((pts90k >> 14) & 0xFE) | 1));
        o.write((int) (pts90k >> 7));
        o.write((int) (((pts90k << 1) & 0xFE) | 1));
        o.write(payload, 0, payload.length);
        return o.toByteArray();
    }

    private static final class Got implements TsDemuxer.Listener {
        Map<Integer, Integer> program;
        final List<Object[]> pes = new ArrayList<>();
        final List<Long> pcr = new ArrayList<>();
        int discontinuities;

        @Override
        public void onProgram(Map<Integer, Integer> m) {
            program = m;
        }

        @Override
        public void onPes(int pid, int type, int sid, long ptsUs, byte[] data, int len) {
            pes.add(new Object[]{pid, type, ptsUs, Arrays.copyOf(data, len)});
        }

        @Override
        public void onDiscontinuity(int pid) {
            discontinuities++;
        }

        @Override
        public void onPcr(long pcr27) {
            pcr.add(pcr27);
        }
    }

    private static byte[] concat(List<byte[]> l) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] b : l) o.write(b, 0, b.length);
        return o.toByteArray();
    }

    @Test
    public void videoPesAcrossPacketsWithAdaptationField() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] psi = concat(List.of(pat(), pmt()));
        d.feed(psi, 0, psi.length);
        assertEquals(Integer.valueOf(0x1B), g.program.get(VIDEO_PID));
        assertEquals(Integer.valueOf(0x83), g.program.get(AUDIO_PID));

        byte[] au = new byte[500];
        for (int i = 0; i < au.length; i++) au[i] = (byte) i;
        au[0] = 0;
        au[1] = 0;
        au[2] = 0;
        au[3] = 1;
        au[4] = 0x65;
        List<byte[]> pk = packetize(VIDEO_PID, pes(0xE0, 90_000, au, false), 1234L);
        assertEquals(3, pk.size());
        byte[] ts = concat(pk);
        d.feed(ts, 0, ts.length);
        assertTrue(g.pes.isEmpty());
        assertEquals(1, g.pcr.size());
        assertEquals(1234L * 300, (long) g.pcr.get(0));

        byte[] next = concat(packetize(VIDEO_PID, pes(0xE0, 93_000, new byte[]{0, 0, 0, 1, 0x41}, false), null));
        d.feed(next, 0, next.length);
        assertEquals(1, g.pes.size());
        Object[] e = g.pes.get(0);
        assertEquals(VIDEO_PID, e[0]);
        assertEquals(1_000_000L, e[2]);
        assertArrayEquals(au, (byte[]) e[3]);
    }

    private void sendFrame(TsDemuxer d, int i, boolean marker) {
        byte[] ts = concat(packetize(VIDEO_PID, pes(0xE0, 3_000L * i, new byte[]{0, 0, 0, 1, 0x41, (byte) i}, false), null));
        d.feed(ts, 0, ts.length);
        if (marker) d.onRtpMarker();
    }

    @Test
    public void rtpMarkerEndsFramesOnceLearned() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] psi = concat(List.of(pat(), pmt()));
        d.feed(psi, 0, psi.length);
        for (int i = 0; i <= TsDemuxer.ALIGN_FRAMES; i++) sendFrame(d, i, true);
        int before = g.pes.size();
        assertEquals("the frame that completed learning already ended at its marker", TsDemuxer.ALIGN_FRAMES + 1, before);
        sendFrame(d, 100, true);
        assertEquals("frame emitted at its marker", before + 1, g.pes.size());
        assertEquals((byte) 100, ((byte[]) g.pes.get(g.pes.size() - 1)[3])[5]);
        assertTrue(d.stats().contains("frameEndByMarker=on"));
    }

    @Test
    public void markerInsideAFrameTurnsEarlyEmitOff() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] psi = concat(List.of(pat(), pmt()));
        d.feed(psi, 0, psi.length);
        for (int i = 0; i <= TsDemuxer.ALIGN_FRAMES; i++) sendFrame(d, i, true);
        sendFrame(d, 200, false);
        sendFrame(d, 201, false);
        assertTrue(d.stats().contains("frameEndByMarker=off"));
        int n = g.pes.size();
        sendFrame(d, 202, true);
        assertEquals("waits for the next frame start again", n + 1, g.pes.size());
        assertEquals((byte) 201, ((byte[]) g.pes.get(g.pes.size() - 1)[3])[5]);
    }

    @Test
    public void boundedPesIsEmittedWithoutWaiting() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] psi = concat(List.of(pat(), pmt()));
        d.feed(psi, 0, psi.length);
        byte[] pcm = new byte[4 + 320];
        pcm[0] = (byte) 0xA0;
        byte[] ts = concat(packetize(AUDIO_PID, pes(0xBD, 45_000, pcm, true), null));
        d.feed(ts, 0, ts.length);
        assertEquals(1, g.pes.size());
        assertEquals(0x83, g.pes.get(0)[1]);
        assertEquals(500_000L, g.pes.get(0)[2]);
        assertArrayEquals(pcm, (byte[]) g.pes.get(0)[3]);
    }

    @Test
    public void continityErrorDropsPes() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] psi = concat(List.of(pat(), pmt()));
        d.feed(psi, 0, psi.length);
        List<byte[]> pk = packetize(VIDEO_PID, pes(0xE0, 0, new byte[600], false), null);
        pk.remove(1);
        byte[] ts = concat(pk);
        d.feed(ts, 0, ts.length);
        byte[] next = concat(packetize(VIDEO_PID, pes(0xE0, 3000, new byte[]{0, 0, 1, 9}, false), null));
        d.feed(next, 0, next.length);
        assertEquals(1, g.discontinuities);
        assertTrue(g.pes.isEmpty());
    }

    @Test
    public void framesEndAtNextFrameStartUntilMarkersAreLearned() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] psi = concat(List.of(pat(), pmt()));
        d.feed(psi, 0, psi.length);
        for (int i = 0; i < TsDemuxer.ALIGN_FRAMES; i++) sendFrame(d, i, true);
        assertEquals("each frame waits for the next frame start", TsDemuxer.ALIGN_FRAMES - 1, g.pes.size());
        assertTrue(d.stats().contains("frameEndByMarker=off"));
    }

    @Test
    public void frameContinuingAfterItsMarkerIsReportedAsDamage() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] psi = concat(List.of(pat(), pmt()));
        d.feed(psi, 0, psi.length);
        for (int i = 0; i <= TsDemuxer.ALIGN_FRAMES; i++) sendFrame(d, i, true);
        List<byte[]> pk = packetize(VIDEO_PID, pes(0xE0, 300_000, new byte[500], false), null);
        assertEquals(3, pk.size());
        d.feed(pk.get(0), 0, 188);
        d.onRtpMarker();
        assertEquals(0, g.discontinuities);
        d.feed(pk.get(1), 0, 188);
        d.feed(pk.get(2), 0, 188);
        assertEquals("rest of the frame flags the damage once", 1, g.discontinuities);
        assertTrue(d.stats().contains("frameEndByMarker=off"));
    }

    @Test
    public void shortPmtSectionIsIgnored() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] p = pat();
        d.feed(p, 0, p.length);
        byte[] section = {0, 0x02, (byte) 0xB0, 0x05, 0, 1, (byte) 0xC1, 0, 0};
        byte[] pmt = packet(PMT_PID, true, section, 0, section.length, null);
        d.feed(pmt, 0, pmt.length);
        assertEquals(null, g.program);
    }

    @Test
    public void oversizedVideoPesIsDroppedAndReported() {
        Got g = new Got();
        TsDemuxer d = new TsDemuxer(g);
        byte[] psi = concat(List.of(pat(), pmt()));
        d.feed(psi, 0, psi.length);
        byte[] start = concat(packetize(VIDEO_PID, pes(0xE0, 0, new byte[100], false), null));
        d.feed(start, 0, start.length);
        byte[] filler = new byte[184];
        int packets = TsDemuxer.MAX_PES / 184 + 2;
        for (int i = 0; i < packets; i++) {
            byte[] pk = packet(VIDEO_PID, false, filler, 0, filler.length, null);
            d.feed(pk, 0, pk.length);
        }
        assertEquals(1, g.discontinuities);
        sendFrame(d, 1, false);
        sendFrame(d, 2, false);
        assertEquals("the oversized frame is not emitted, the next one is", 1, g.pes.size());
        assertEquals((byte) 1, ((byte[]) g.pes.get(0)[3])[5]);
    }

    @Test
    public void ptsDecoding() {
        byte[] b = pes(0xE0, 0x1FFFFFFFFL, new byte[0], false);
        assertEquals(0x1FFFFFFFFL, TsDemuxer.readTimestamp(b, 9));
    }
}
