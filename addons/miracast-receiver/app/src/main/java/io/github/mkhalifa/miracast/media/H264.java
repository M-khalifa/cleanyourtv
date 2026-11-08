package io.github.mkhalifa.miracast.media;

import java.util.ArrayList;
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
