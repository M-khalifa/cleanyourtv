package io.github.mkhalifa.miracast.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.junit.Test;

public class AudioVideoFramingTest {
    @Test
    public void lpcmHeader48kStereo() {
        byte[] pes = {(byte) 0xA0, 0x06, 0x00, (byte) ((0 << 6) | (2 << 3) | 1), 0x12, 0x34, (byte) 0xAB, (byte) 0xCD};
        Lpcm h = Lpcm.parse(pes, pes.length);
        assertNotNull(h);
        assertEquals(48000, h.sampleRate);
        assertEquals(2, h.channels);
        assertEquals(1, h.frames);
        Lpcm.swap16(pes, Lpcm.HEADER, 4);
        assertArrayEquals(new byte[]{0x34, 0x12, (byte) 0xCD, (byte) 0xAB},
                new byte[]{pes[4], pes[5], pes[6], pes[7]});
    }

    @Test
    public void lpcmHeader441AndInvalid() {
        byte[] pes = {(byte) 0xA0, 1, 0, (byte) ((1 << 3) | 1)};
        assertEquals(44100, Lpcm.parse(pes, 4).sampleRate);
        assertNull(Lpcm.parse(new byte[]{(byte) 0xA1, 0, 0, 0x11}, 4));
        assertNull(Lpcm.parse(new byte[]{(byte) 0xA0, 0, 0, (byte) (5 << 3)}, 4));
    }

    static byte[] adts(int payloadLen, int rateIndex, int channels, boolean crc) {
        int header = crc ? 9 : 7;
        int len = header + payloadLen;
        byte[] f = new byte[len];
        f[0] = (byte) 0xFF;
        f[1] = (byte) (0xF0 | (crc ? 0 : 1));
        f[2] = (byte) ((1 << 6) | (rateIndex << 2) | (channels >> 2));
        f[3] = (byte) (((channels & 3) << 6) | (len >> 11));
        f[4] = (byte) (len >> 3);
        f[5] = (byte) (((len & 7) << 5) | 0x1F);
        f[6] = (byte) 0xFC;
        for (int i = header; i < len; i++) f[i] = (byte) i;
        return f;
    }

    @Test
    public void adtsSplitAndConfig() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] a = adts(100, 3, 2, false);
        byte[] b = adts(50, 3, 2, true);
        o.write(a, 0, a.length);
        o.write(b, 0, b.length);
        byte[] all = o.toByteArray();
        List<Adts.Frame> frames = Adts.split(all, all.length);
        assertEquals(2, frames.size());
        assertEquals(7, frames.get(0).offset);
        assertEquals(100, frames.get(0).length);
        assertEquals(a.length + 9, frames.get(1).offset);
        assertEquals(50, frames.get(1).length);
        assertEquals(48000, frames.get(0).sampleRate());
        assertArrayEquals(new byte[]{0x11, (byte) 0x90}, frames.get(0).audioSpecificConfig());
    }

    @Test
    public void adtsTruncatedFrameStops() {
        byte[] a = adts(100, 4, 2, false);
        List<Adts.Frame> frames = Adts.split(a, a.length - 1);
        assertTrue(frames.isEmpty());
    }

    @Test
    public void h264NalsAndTypes() {
        byte[] au = {0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5, 6};
        List<int[]> n = H264.nals(au, au.length);
        assertEquals(3, n.size());
        assertEquals(4, n.get(0)[0]);
        assertEquals(3, n.get(0)[1]);
        assertEquals(2, n.get(1)[1]);
        assertEquals(4, n.get(2)[1]);
        assertTrue(H264.contains(au, au.length, H264.NAL_IDR));
        assertFalse(H264.contains(au, au.length, 1));
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x68, 3}, H264.extract(au, au.length, H264.NAL_PPS));
    }
}
