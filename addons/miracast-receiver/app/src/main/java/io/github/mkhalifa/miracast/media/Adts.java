package io.github.mkhalifa.miracast.media;

import java.util.ArrayList;
import java.util.List;

/** ADTS framing: splits a PES payload into raw AAC frames and builds the AudioSpecificConfig. */
public final class Adts {
    static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    public static final class Frame {
        public final int profile;
        public final int sampleRateIndex;
        public final int channelConfig;
        public final int offset;
        public final int length;

        Frame(int profile, int sampleRateIndex, int channelConfig, int offset, int length) {
            this.profile = profile;
            this.sampleRateIndex = sampleRateIndex;
            this.channelConfig = channelConfig;
            this.offset = offset;
            this.length = length;
        }

        public int sampleRate() {
            return sampleRateIndex < RATES.length ? RATES[sampleRateIndex] : 0;
        }

        /** AudioSpecificConfig (csd-0) for MediaCodec. */
        public byte[] audioSpecificConfig() {
            int objectType = profile + 1;
            int v = (objectType << 11) | (sampleRateIndex << 7) | (channelConfig << 3);
            return new byte[]{(byte) (v >> 8), (byte) v};
        }
    }

    /** Raw AAC frames (without ADTS header) in d[0, len); resyncs on the sync word, stops at a truncated frame. */
    public static List<Frame> split(byte[] d, int len) {
        List<Frame> out = new ArrayList<>();
        int p = 0;
        while (p + 7 <= len) {
            if ((d[p] & 0xFF) != 0xFF || (d[p + 1] & 0xF0) != 0xF0) {
                p++;
                continue;
            }
            boolean noCrc = (d[p + 1] & 0x01) != 0;
            int profile = (d[p + 2] >> 6) & 0x3;
            int rateIndex = (d[p + 2] >> 2) & 0xF;
            int channel = ((d[p + 2] & 0x1) << 2) | ((d[p + 3] >> 6) & 0x3);
            int frameLength = ((d[p + 3] & 0x3) << 11) | ((d[p + 4] & 0xFF) << 3) | ((d[p + 5] >> 5) & 0x7);
            int header = noCrc ? 7 : 9;
            if (frameLength < header || p + frameLength > len || rateIndex >= RATES.length) break;
            out.add(new Frame(profile, rateIndex, channel, p + header, frameLength - header));
            p += frameLength;
        }
        return out;
    }
}
