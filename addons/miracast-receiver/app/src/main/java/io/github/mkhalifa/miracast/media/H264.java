package io.github.mkhalifa.miracast.media;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Annex B helpers: NAL unit boundaries and types inside one access unit. */
public final class H264 {
    public static final int NAL_IDR = 5;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;

    private H264() {
    }

    /** NAL units as {offset of NAL header, length}, start codes excluded. */
    public static List<int[]> nals(byte[] d, int len) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        int i = 0;
        while (i + 2 < len) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                if (start >= 0) out.add(new int[]{start, trimZeros(d, start, i) - start});
                i += 3;
                start = i;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < len) out.add(new int[]{start, len - start});
        return out;
    }

    private static int trimZeros(byte[] d, int from, int end) {
        while (end > from && d[end - 1] == 0) end--;
        return end;
    }

    public static boolean contains(byte[] d, int len, int type) {
        for (int[] n : nals(d, len)) if ((d[n[0]] & 0x1F) == type) return true;
        return false;
    }

    /** Fields of an SPS that matter for colour and latency; -1 when the stream leaves a field out. */
    public static final class SpsInfo {
        public int profile;
        public int level;
        public int width;
        public int height;
        public int fullRange = -1;
        public int primaries = -1;
        public int transfer = -1;
        public int matrix = -1;
        public int maxReorderFrames = -1;
        public int maxDecFrameBuffering = -1;
        public int maxRefFrames;

        @Override
        public String toString() {
            return "profile=" + profile + " level=" + level + " " + width + "x" + height
                    + " fullRange=" + fullRange + " primaries=" + primaries + " transfer=" + transfer
                    + " matrix=" + matrix + " refFrames=" + maxRefFrames
                    + " reorder=" + maxReorderFrames + " dpb=" + maxDecFrameBuffering;
        }
    }

    /** Parses an SPS NAL (with or without start code) up to bitstream_restriction; null if malformed. */
    public static SpsInfo parseSps(byte[] nal) {
        int off = 0;
        while (off + 3 < nal.length && nal[off] == 0) off++;
        if (off >= 2 && nal[off] == 1) off++;
        else off = 0;
        if (off >= nal.length || (nal[off] & 0x1F) != NAL_SPS) return null;
        Bits b = new Bits(unescape(nal, off + 1, nal.length));
        try {
            SpsInfo s = new SpsInfo();
            s.profile = b.u(8);
            b.u(8);
            s.level = b.u(8);
            b.ue();
            int chromaFormat = 1;
            int p = s.profile;
            if (p == 100 || p == 110 || p == 122 || p == 244 || p == 44 || p == 83 || p == 86 || p == 118
                    || p == 128 || p == 138 || p == 139 || p == 134 || p == 135) {
                chromaFormat = b.ue();
                if (chromaFormat == 3) b.u(1);
                b.ue();
                b.ue();
                b.u(1);
                if (b.u(1) == 1) {
                    for (int i = 0; i < (chromaFormat != 3 ? 8 : 12); i++) {
                        if (b.u(1) == 1) skipScalingList(b, i < 6 ? 16 : 64);
                    }
                }
            }
            b.ue();
            int pocType = b.ue();
            if (pocType == 0) {
                b.ue();
            } else if (pocType == 1) {
                b.u(1);
                b.se();
                b.se();
                int n = b.ue();
                for (int i = 0; i < n; i++) b.se();
            }
            s.maxRefFrames = b.ue();
            b.u(1);
            int wMbs = b.ue() + 1;
            int hMapUnits = b.ue() + 1;
            int frameMbsOnly = b.u(1);
            if (frameMbsOnly == 0) b.u(1);
            b.u(1);
            int cl = 0, cr = 0, ct = 0, cb = 0;
            if (b.u(1) == 1) {
                cl = b.ue();
                cr = b.ue();
                ct = b.ue();
                cb = b.ue();
            }
            int cropX = chromaFormat == 0 || chromaFormat == 3 ? 1 : 2;
            int cropY = (chromaFormat == 1 ? 2 : 1) * (2 - frameMbsOnly);
            s.width = wMbs * 16 - cropX * (cl + cr);
            s.height = hMapUnits * 16 * (2 - frameMbsOnly) - cropY * (ct + cb);
            if (b.u(1) == 0) return s;
            if (b.u(1) == 1 && b.u(8) == 255) {
                b.u(16);
                b.u(16);
            }
            if (b.u(1) == 1) b.u(1);
            if (b.u(1) == 1) {
                b.u(3);
                s.fullRange = b.u(1);
                if (b.u(1) == 1) {
                    s.primaries = b.u(8);
                    s.transfer = b.u(8);
                    s.matrix = b.u(8);
                }
            }
            if (b.u(1) == 1) {
                b.ue();
                b.ue();
            }
            if (b.u(1) == 1) {
                b.u(32);
                b.u(32);
                b.u(1);
            }
            int nalHrd = b.u(1);
            if (nalHrd == 1) skipHrd(b);
            int vclHrd = b.u(1);
            if (vclHrd == 1) skipHrd(b);
            if (nalHrd == 1 || vclHrd == 1) b.u(1);
            b.u(1);
            if (b.u(1) == 1) {
                b.u(1);
                b.ue();
                b.ue();
                b.ue();
                b.ue();
                s.maxReorderFrames = b.ue();
                s.maxDecFrameBuffering = b.ue();
            }
            return s;
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static void skipScalingList(Bits b, int size) {
        int last = 8;
        int next = 8;
        for (int j = 0; j < size; j++) {
            if (next != 0) next = (last + b.se() + 256) % 256;
            if (next != 0) last = next;
        }
    }

    private static void skipHrd(Bits b) {
        int cpbCnt = b.ue() + 1;
        b.u(8);
        for (int i = 0; i < cpbCnt; i++) {
            b.ue();
            b.ue();
            b.u(1);
        }
        b.u(20);
    }

    /** Removes emulation prevention bytes (00 00 03). */
    static byte[] unescape(byte[] d, int from, int to) {
        byte[] out = new byte[to - from];
        int n = 0;
        int zeros = 0;
        for (int i = from; i < to; i++) {
            int v = d[i] & 0xFF;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            zeros = v == 0 ? zeros + 1 : 0;
            out[n++] = (byte) v;
        }
        return Arrays.copyOf(out, n);
    }

    private static final class Bits {
        private final byte[] d;
        private int pos;

        Bits(byte[] d) {
            this.d = d;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | ((d[pos >> 3] >> (7 - (pos & 7))) & 1);
                pos++;
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                if (++zeros > 31) throw new ArrayIndexOutOfBoundsException("bad exp-golomb");
            }
            return zeros == 0 ? 0 : (1 << zeros) - 1 + u(zeros);
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    /** The first NAL of the given type with a 4-byte start code prepended, or null. */
    public static byte[] extract(byte[] d, int len, int type) {
        for (int[] n : nals(d, len)) {
            if ((d[n[0]] & 0x1F) != type) continue;
            byte[] out = new byte[n[1] + 4];
            out[3] = 1;
            System.arraycopy(d, n[0], out, 4, n[1]);
            return out;
        }
        return null;
    }
}
