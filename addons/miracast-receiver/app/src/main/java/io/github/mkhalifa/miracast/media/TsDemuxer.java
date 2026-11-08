package io.github.mkhalifa.miracast.media;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** MPEG-2 TS demuxer for one program: PAT, PMT, PCR and PES reassembly per elementary stream. */
public final class TsDemuxer {
    public static final int PACKET = 188;
    public static final int TYPE_H264 = 0x1B;
    public static final int TYPE_AAC_ADTS = 0x0F;
    public static final int TYPE_LPCM = 0x83;
    public static final int TYPE_AC3 = 0x81;

    public interface Listener {
        void onProgram(Map<Integer, Integer> streamTypeByPid);

        /** One complete PES payload; ptsUs is -1 when the header has no PTS. */
        void onPes(int pid, int streamType, int streamId, long ptsUs, byte[] data, int len);

        void onDiscontinuity(int pid);

        void onPcr(long pcr27MHz);
    }

    private final Listener listener;
    private int pmtPid = -1;
    private final Map<Integer, Integer> streams = new HashMap<>();
    private final Map<Integer, Pes> pes = new HashMap<>();
    private final Map<Integer, Integer> lastCc = new HashMap<>();
    private final ByteArrayOutputStream section = new ByteArrayOutputStream();
    private int sectionPid = -1;

    private static final class Pes {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        boolean active;
        boolean corrupt;
        int expectedLength;
    }

    public TsDemuxer(Listener listener) {
        this.listener = listener;
    }

    /** Feeds any number of whole 188-byte packets; stray bytes before a sync byte are skipped. */
    public void feed(byte[] d, int off, int len) {
        int p = off;
        int end = off + len;
        while (p + PACKET <= end) {
            if ((d[p] & 0xFF) != 0x47) {
                p++;
                continue;
            }
            packet(d, p);
            p += PACKET;
        }
    }

    private void packet(byte[] d, int p) {
        boolean tei = (d[p + 1] & 0x80) != 0;
        boolean pusi = (d[p + 1] & 0x40) != 0;
        int pid = ((d[p + 1] & 0x1F) << 8) | (d[p + 2] & 0xFF);
        int afc = (d[p + 3] >> 4) & 0x3;
        int cc = d[p + 3] & 0x0F;
        if (pid == 0x1FFF) return;
        int pos = p + 4;
        boolean discontinuityFlag = false;
        if (afc == 2 || afc == 3) {
            int afLen = d[pos] & 0xFF;
            if (afLen > 0) {
                int flags = d[pos + 1] & 0xFF;
                discontinuityFlag = (flags & 0x80) != 0;
                if ((flags & 0x10) != 0 && afLen >= 7) {
                    long base = ((long) (d[pos + 2] & 0xFF) << 25) | ((d[pos + 3] & 0xFF) << 17)
                            | ((d[pos + 4] & 0xFF) << 9) | ((d[pos + 5] & 0xFF) << 1) | ((d[pos + 6] & 0xFF) >> 7);
                    int ext = ((d[pos + 6] & 0x01) << 8) | (d[pos + 7] & 0xFF);
                    listener.onPcr(base * 300 + ext);
                }
            }
            pos += 1 + afLen;
        }
        boolean hasPayload = afc == 1 || afc == 3;
        if (!hasPayload) return;
        int payloadEnd = p + PACKET;
        if (pos >= payloadEnd) return;

        Integer prev = lastCc.get(pid);
        boolean ccError = false;
        if (prev != null && !discontinuityFlag) {
            if (cc == prev) return;
            ccError = cc != ((prev + 1) & 0xF);
        }
        lastCc.put(pid, cc);
        if (tei) ccError = true;

        if (pid == 0 || pid == pmtPid) {
            sectionPacket(pid, pusi, d, pos, payloadEnd);
            return;
        }
        Integer type = streams.get(pid);
        if (type == null) return;
        Pes s = pes.get(pid);
        if (s == null) {
            s = new Pes();
            pes.put(pid, s);
        }
        if (ccError) {
            s.corrupt = true;
            listener.onDiscontinuity(pid);
        }
        if (pusi) {
            if (s.active) emit(pid, type, s);
            s.buf.reset();
            s.active = true;
            s.corrupt = false;
            s.expectedLength = -1;
        } else if (!s.active) {
            return;
        }
        s.buf.write(d, pos, payloadEnd - pos);
        if (s.expectedLength < 0 && s.buf.size() >= 6) {
            byte[] h = s.buf.toByteArray();
            int len = ((h[4] & 0xFF) << 8) | (h[5] & 0xFF);
            s.expectedLength = len == 0 ? 0 : len + 6;
        }
        if (s.expectedLength > 0 && s.buf.size() >= s.expectedLength) {
            emit(pid, type, s);
            s.active = false;
            s.buf.reset();
        }
    }

    private void emit(int pid, int type, Pes s) {
        if (s.corrupt) {
            s.corrupt = false;
            return;
        }
        byte[] b = s.buf.toByteArray();
        int len = s.expectedLength > 0 ? Math.min(s.expectedLength, b.length) : b.length;
        if (len < 9 || b[0] != 0 || b[1] != 0 || b[2] != 1) return;
        int streamId = b[3] & 0xFF;
        int headerLen = b[8] & 0xFF;
        int payload = 9 + headerLen;
        if (payload > len) return;
        long ptsUs = -1;
        if ((b[7] & 0x80) != 0 && headerLen >= 5) ptsUs = readTimestamp(b, 9) * 100 / 9;
        byte[] out = new byte[len - payload];
        System.arraycopy(b, payload, out, 0, out.length);
        listener.onPes(pid, type, streamId, ptsUs, out, out.length);
    }

    static long readTimestamp(byte[] b, int p) {
        return ((long) ((b[p] >> 1) & 0x07) << 30) | ((long) (b[p + 1] & 0xFF) << 22)
                | ((long) ((b[p + 2] >> 1) & 0x7F) << 15) | ((long) (b[p + 3] & 0xFF) << 7) | ((b[p + 4] >> 1) & 0x7F);
    }

    private void sectionPacket(int pid, boolean pusi, byte[] d, int pos, int end) {
        if (pusi) {
            int pointer = d[pos] & 0xFF;
            pos += 1 + pointer;
            if (pos >= end) return;
            section.reset();
            sectionPid = pid;
        } else if (sectionPid != pid) {
            return;
        }
        section.write(d, pos, end - pos);
        byte[] s = section.toByteArray();
        if (s.length < 3) return;
        int sectionLength = ((s[1] & 0x0F) << 8) | (s[2] & 0xFF);
        if (s.length < 3 + sectionLength) return;
        sectionPid = -1;
        if (pid == 0 && (s[0] & 0xFF) == 0x00) parsePat(s, 3 + sectionLength);
        else if (pid == pmtPid && (s[0] & 0xFF) == 0x02) parsePmt(s, 3 + sectionLength);
    }

    private void parsePat(byte[] s, int total) {
        for (int i = 8; i + 4 <= total - 4; i += 4) {
            int program = ((s[i] & 0xFF) << 8) | (s[i + 1] & 0xFF);
            int pid = ((s[i + 2] & 0x1F) << 8) | (s[i + 3] & 0xFF);
            if (program != 0) {
                pmtPid = pid;
                return;
            }
        }
    }

    private void parsePmt(byte[] s, int total) {
        int programInfoLength = ((s[10] & 0x0F) << 8) | (s[11] & 0xFF);
        int i = 12 + programInfoLength;
        Map<Integer, Integer> found = new HashMap<>();
        while (i + 5 <= total - 4) {
            int type = s[i] & 0xFF;
            int pid = ((s[i + 1] & 0x1F) << 8) | (s[i + 2] & 0xFF);
            int esInfo = ((s[i + 3] & 0x0F) << 8) | (s[i + 4] & 0xFF);
            found.put(pid, type);
            i += 5 + esInfo;
        }
        if (!found.equals(streams)) {
            streams.clear();
            streams.putAll(found);
            List<Integer> gone = new ArrayList<>(pes.keySet());
            for (int pid : gone) if (!found.containsKey(pid)) pes.remove(pid);
            listener.onProgram(new HashMap<>(found));
        }
    }
}
