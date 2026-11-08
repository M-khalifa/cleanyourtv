package io.github.mkhalifa.miracast.media;

/** RTP fixed header (RFC 3550) parsed in place over a datagram buffer. */
public final class RtpPacket {
    public int payloadType;
    public int sequence;
    public long timestamp;
    public long ssrc;
    public boolean marker;
    public int payloadOffset;
    public int payloadLength;

    /** Parses data[off, off+len); false when it is not a valid RTP v2 packet. */
    public boolean parse(byte[] data, int off, int len) {
        if (len < 12) return false;
        int b0 = data[off] & 0xFF;
        if ((b0 >> 6) != 2) return false;
        boolean padding = (b0 & 0x20) != 0;
        boolean extension = (b0 & 0x10) != 0;
        int csrc = b0 & 0x0F;
        int b1 = data[off + 1] & 0xFF;
        marker = (b1 & 0x80) != 0;
        payloadType = b1 & 0x7F;
        sequence = ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
        timestamp = readU32(data, off + 4);
        ssrc = readU32(data, off + 8);
        int pos = 12 + csrc * 4;
        if (pos > len) return false;
        if (extension) {
            if (pos + 4 > len) return false;
            int words = ((data[off + pos + 2] & 0xFF) << 8) | (data[off + pos + 3] & 0xFF);
            pos += 4 + words * 4;
            if (pos > len) return false;
        }
        int end = len;
        if (padding) {
            int pad = data[off + len - 1] & 0xFF;
            if (pad == 0 || pad > end - pos) return false;
            end -= pad;
        }
        payloadOffset = off + pos;
        payloadLength = end - pos;
        return true;
    }

    private static long readU32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }
}
