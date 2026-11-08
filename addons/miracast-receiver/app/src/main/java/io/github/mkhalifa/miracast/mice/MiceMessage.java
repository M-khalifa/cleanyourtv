package io.github.mkhalifa.miracast.mice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** MS-MICE message (section 2.2): Size(2) Version(1)=0x01 Command(1), then TLVs Type(1) Length(2) Value; big-endian. */
public final class MiceMessage {
    public static final int VERSION = 0x01;
    public static final int SOURCE_READY = 0x01;
    public static final int STOP_PROJECTION = 0x02;
    public static final int SECURITY_HANDSHAKE = 0x03;
    public static final int SESSION_REQUEST = 0x04;
    public static final int PIN_CHALLENGE = 0x05;
    public static final int PIN_RESPONSE = 0x06;

    public static final int TLV_FRIENDLY_NAME = 0x00;
    public static final int TLV_RTSP_PORT = 0x02;
    public static final int TLV_SOURCE_ID = 0x03;

    public static final class Tlv {
        public final int type;
        public final byte[] value;

        public Tlv(int type, byte[] value) {
            this.type = type;
            this.value = value;
        }
    }

    public static final class FormatException extends Exception {
        public FormatException(String msg) {
            super(msg);
        }
    }

    public final int version;
    public final int command;
    public final List<Tlv> tlvs;

    public MiceMessage(int version, int command, List<Tlv> tlvs) {
        this.version = version;
        this.command = command;
        this.tlvs = tlvs;
    }

    /** Parses one whole message (Size counts the 4-byte header too). */
    public static MiceMessage parse(byte[] b, int len) throws FormatException {
        if (len < 4) throw new FormatException("short message " + len);
        int size = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
        if (size != len) throw new FormatException("size field " + size + " != " + len);
        int version = b[2] & 0xFF;
        int command = b[3] & 0xFF;
        List<Tlv> tlvs = new ArrayList<>();
        int p = 4;
        while (p < len) {
            if (p + 3 > len) throw new FormatException("truncated TLV header at " + p);
            int type = b[p] & 0xFF;
            int l = ((b[p + 1] & 0xFF) << 8) | (b[p + 2] & 0xFF);
            if (p + 3 + l > len) throw new FormatException("TLV " + type + " length " + l + " overruns message");
            byte[] v = new byte[l];
            System.arraycopy(b, p + 3, v, 0, l);
            tlvs.add(new Tlv(type, v));
            p += 3 + l;
        }
        return new MiceMessage(version, command, tlvs);
    }

    public byte[] toBytes() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Tlv t : tlvs) {
            body.write(t.type);
            body.write(t.value.length >> 8);
            body.write(t.value.length);
            body.write(t.value, 0, t.value.length);
        }
        int size = 4 + body.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        out.write(size >> 8);
        out.write(size);
        out.write(version);
        out.write(command);
        byte[] b = body.toByteArray();
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    public byte[] value(int type) {
        for (Tlv t : tlvs) if (t.type == type) return t.value;
        return null;
    }

    /** RTSP_PORT TLV value, or -1. */
    public int rtspPort() {
        byte[] v = value(TLV_RTSP_PORT);
        if (v == null || v.length != 2) return -1;
        return ((v[0] & 0xFF) << 8) | (v[1] & 0xFF);
    }

    /** FRIENDLY_NAME is UTF-16; byte order is guessed from where the zero bytes of ASCII characters sit. */
    public String friendlyName() {
        byte[] v = value(TLV_FRIENDLY_NAME);
        if (v == null) return null;
        int evenZero = 0;
        int oddZero = 0;
        for (int i = 0; i + 1 < v.length; i += 2) {
            if (v[i] == 0) evenZero++;
            if (v[i + 1] == 0) oddZero++;
        }
        String s = new String(v, oddZero > evenZero ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE);
        int nul = s.indexOf('\0');
        return nul >= 0 ? s.substring(0, nul) : s;
    }

    /** Sink-initiated STOP_PROJECTION echoing the source's own Friendly Name and Source ID values. */
    public static MiceMessage stopProjection(byte[] friendlyNameRaw, byte[] sourceId) {
        List<Tlv> t = new ArrayList<>();
        if (friendlyNameRaw != null && friendlyNameRaw.length > 0) t.add(new Tlv(TLV_FRIENDLY_NAME, friendlyNameRaw));
        if (sourceId != null) t.add(new Tlv(TLV_SOURCE_ID, sourceId));
        return new MiceMessage(VERSION, STOP_PROJECTION, t);
    }

    public static String hex(byte[] b) {
        if (b == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }
}
