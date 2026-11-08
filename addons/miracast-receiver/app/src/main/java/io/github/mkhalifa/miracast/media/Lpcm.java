package io.github.mkhalifa.miracast.media;

/** WFD LPCM in private stream 1: 4-byte header, then big-endian 16-bit samples. */
public final class Lpcm {
    public static final int HEADER = 4;
    public static final int SUB_STREAM_ID = 0xA0;

    public final int sampleRate;
    public final int channels;
    public final int frames;

    private Lpcm(int sampleRate, int channels, int frames) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.frames = frames;
    }

    /** Header: sub_stream_id 0xA0, AU count, reserved, quantization(2) frequency(3) channels(3); null if invalid. */
    public static Lpcm parse(byte[] pes, int len) {
        if (len < HEADER || (pes[0] & 0xFF) != SUB_STREAM_ID) return null;
        int b3 = pes[3] & 0xFF;
        int freq = (b3 >> 3) & 0x7;
        int ch = b3 & 0x7;
        int rate;
        if (freq == 1) rate = 44100;
        else if (freq == 2) rate = 48000;
        else return null;
        int channels = ch == 1 ? 2 : (ch == 0 ? 1 : ch + 1);
        int frames = (len - HEADER) / (2 * channels);
        return new Lpcm(rate, channels, frames);
    }

    /** Swaps 16-bit big-endian samples to little-endian in place over [off, off+len). */
    public static void swap16(byte[] b, int off, int len) {
        int end = off + (len & ~1);
        for (int i = off; i < end; i += 2) {
            byte t = b[i];
            b[i] = b[i + 1];
            b[i + 1] = t;
        }
    }
}
