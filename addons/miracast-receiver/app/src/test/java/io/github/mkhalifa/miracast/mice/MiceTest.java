package io.github.mkhalifa.miracast.mice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class MiceTest {
    private static byte[] sourceReady(byte[] name) {
        ByteArrayOutputStream t = new ByteArrayOutputStream();
        t.write(0x00);
        t.write(name.length >> 8);
        t.write(name.length);
        t.write(name, 0, name.length);
        t.write(0x02);
        t.write(0);
        t.write(2);
        t.write(0x1C);
        t.write(0x44);
        t.write(0x03);
        t.write(0);
        t.write(16);
        for (int i = 0; i < 16; i++) t.write(i);
        byte[] tlvs = t.toByteArray();
        byte[] msg = new byte[4 + tlvs.length];
        msg[0] = (byte) (msg.length >> 8);
        msg[1] = (byte) msg.length;
        msg[2] = 1;
        msg[3] = 1;
        System.arraycopy(tlvs, 0, msg, 4, tlvs.length);
        return msg;
    }

    @Test
    public void parsesSourceReady() throws Exception {
        byte[] msg = sourceReady("LAPTOP-1".getBytes(StandardCharsets.UTF_16LE));
        MiceMessage m = MiceMessage.parse(msg, msg.length);
        assertEquals(MiceMessage.SOURCE_READY, m.command);
        assertEquals(1, m.version);
        assertEquals(7236, m.rtspPort());
        assertEquals("LAPTOP-1", m.friendlyName());
        assertEquals(16, m.value(MiceMessage.TLV_SOURCE_ID).length);
        assertArrayEquals(msg, m.toBytes());
    }

    @Test
    public void friendlyNameBigEndian() throws Exception {
        byte[] msg = sourceReady("PC".getBytes(StandardCharsets.UTF_16BE));
        assertEquals("PC", MiceMessage.parse(msg, msg.length).friendlyName());
    }

    @Test(expected = MiceMessage.FormatException.class)
    public void sizeMismatchRejected() throws Exception {
        byte[] msg = sourceReady(new byte[]{0x41, 0});
        MiceMessage.parse(msg, msg.length - 1);
    }

    @Test(expected = MiceMessage.FormatException.class)
    public void overrunningTlvRejected() throws Exception {
        byte[] msg = {0, 8, 1, 2, 0x00, 0, 9, 0x41};
        MiceMessage.parse(msg, msg.length);
    }

    @Test
    public void stopProjectionEchoesSourceValues() throws Exception {
        byte[] name = "PC".getBytes(StandardCharsets.UTF_16LE);
        byte[] id = new byte[16];
        id[15] = 7;
        byte[] b = MiceMessage.stopProjection(name, id).toBytes();
        assertEquals(4 + 3 + 4 + 3 + 16, b.length);
        assertEquals(0, b[0]);
        assertEquals(b.length, b[1]);
        assertEquals(1, b[2]);
        assertEquals(2, b[3]);
        MiceMessage m = MiceMessage.parse(b, b.length);
        assertEquals("PC", m.friendlyName());
        assertArrayEquals(id, m.value(MiceMessage.TLV_SOURCE_ID));
        assertNull(MiceMessage.stopProjection(null, null).value(MiceMessage.TLV_FRIENDLY_NAME));
    }

    @Test
    public void wscVendorElementBytes() {
        byte[] e = WscVendorElement.element("KhalifaTV", "192.168.1.240", 0x05);
        String hex = MiceMessage.hex(e);
        String ext = "000137" + "20010001" + "05"
                + "20020009" + MiceMessage.hex("KhalifaTV".getBytes(StandardCharsets.US_ASCII))
                + "2005000d" + MiceMessage.hex("192.168.1.240".getBytes(StandardCharsets.US_ASCII));
        int extLen = ext.length() / 2;
        String body = "0050f204" + "1049" + String.format("%04x", extLen) + ext;
        assertEquals("dd" + String.format("%02x", body.length() / 2) + body, hex);
        assertTrue(e.length < 256);
    }

    @Test(expected = IllegalArgumentException.class)
    public void hostNameWithDotRejected() {
        WscVendorElement.body("tv.local", null, 5);
    }
}
