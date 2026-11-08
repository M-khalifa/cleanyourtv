package io.github.mkhalifa.miracast.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayOutputStream;

import org.junit.Test;

public class SpsParseTest {
    /** MSB-first bit writer with Exp-Golomb codes, for building test SPS NALs. */
    private static final class W {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int cur;
        private int n;

        W u(int bits, int v) {
            for (int i = bits - 1; i >= 0; i--) {
                cur = (cur << 1) | ((v >>> i) & 1);
                if (++n == 8) {
                    out.write(cur);
                    cur = 0;
                    n = 0;
                }
            }
            return this;
        }

        W ue(int v) {
            int x = v + 1;
            int len = 32 - Integer.numberOfLeadingZeros(x);
            u(len - 1, 0);
            return u(len, x);
        }

        byte[] nal() {
            u(1, 1);
            while (n != 0) u(1, 0);
            byte[] rbsp = out.toByteArray();
            ByteArrayOutputStream esc = new ByteArrayOutputStream();
            esc.write(0);
            esc.write(0);
            esc.write(0);
            esc.write(1);
            esc.write(0x67);
            int zeros = 0;
            for (byte b : rbsp) {
                if (zeros >= 2 && (b & 0xFF) <= 3) {
                    esc.write(3);
                    zeros = 0;
                }
                esc.write(b);
                zeros = b == 0 ? zeros + 1 : 0;
            }
            return esc.toByteArray();
        }
    }

    private static W high1080(W w) {
        w.u(8, 100).u(8, 0).u(8, 42).ue(0);
        w.ue(1).ue(0).ue(0).u(1, 0).u(1, 0);
        w.ue(0).ue(0).ue(0);
        w.ue(1).u(1, 0).ue(119).ue(67).u(1, 1).u(1, 1);
        w.u(1, 1).ue(0).ue(0).ue(0).ue(4);
        return w;
    }

    @Test
    public void readsColourAndReorderFromVui() {
        W w = high1080(new W());
        w.u(1, 1);
        w.u(1, 0).u(1, 0);
        w.u(1, 1).u(3, 5).u(1, 1).u(1, 1).u(8, 1).u(8, 1).u(8, 1);
        w.u(1, 0);
        w.u(1, 1).u(32, 1).u(32, 120).u(1, 1);
        w.u(1, 0).u(1, 0).u(1, 0);
        w.u(1, 1).u(1, 1).ue(2).ue(1).ue(16).ue(16).ue(0).ue(1);
        H264.SpsInfo s = H264.parseSps(w.nal());
        assertEquals(100, s.profile);
        assertEquals(42, s.level);
        assertEquals(1920, s.width);
        assertEquals(1080, s.height);
        assertEquals(1, s.fullRange);
        assertEquals(1, s.matrix);
        assertEquals(1, s.maxRefFrames);
        assertEquals(0, s.maxReorderFrames);
        assertEquals(1, s.maxDecFrameBuffering);
    }

    @Test
    public void noVuiLeavesFieldsUnknown() {
        H264.SpsInfo s = H264.parseSps(high1080(new W()).u(1, 0).nal());
        assertEquals(1920, s.width);
        assertEquals(-1, s.fullRange);
        assertEquals(-1, s.maxReorderFrames);
    }

    @Test
    public void rejectsNonSps() {
        assertNull(H264.parseSps(new byte[]{0, 0, 0, 1, 0x68, 1}));
    }
}
