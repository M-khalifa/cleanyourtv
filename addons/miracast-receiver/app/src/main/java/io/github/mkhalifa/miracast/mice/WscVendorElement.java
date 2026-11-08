package io.github.mkhalifa.miracast.mice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** WSC IE fragment (no Version attribute, so it joins the system WSC IE) carrying the MS-MICE Vendor Extension attribute (MS-MICE 2.2.8): capability, host name, IPv4. */
public final class WscVendorElement {
    public static final int ELEMENT_ID = 221;
    /** MiracastOverInfrastructureSupport (A, LSB) plus Version 1 in bits 2-4. */
    public static final int CAPABILITY_DEFAULT = 0x05;
    private static final byte[] WSC_OUI_TYPE = {0x00, 0x50, (byte) 0xF2, 0x04};
    private static final byte[] MS_OUI = {0x00, 0x01, 0x37};

    private WscVendorElement() {
    }

    /** Body of the vendor-specific element (everything after element ID and length). */
    public static byte[] body(String hostName, String ipv4, int capability) {
        if (hostName == null || hostName.isEmpty() || hostName.contains(".")) {
            throw new IllegalArgumentException("host name must be non-empty and contain no '.': " + hostName);
        }
        ByteArrayOutputStream ext = new ByteArrayOutputStream();
        ext.write(MS_OUI, 0, 3);
        attribute(ext, 0x2001, new byte[]{(byte) capability});
        attribute(ext, 0x2002, hostName.getBytes(StandardCharsets.US_ASCII));
        if (ipv4 != null && !ipv4.isEmpty()) attribute(ext, 0x2005, ipv4.getBytes(StandardCharsets.US_ASCII));

        ByteArrayOutputStream ie = new ByteArrayOutputStream();
        ie.write(WSC_OUI_TYPE, 0, 4);
        attribute(ie, 0x1049, ext.toByteArray());
        byte[] out = ie.toByteArray();
        if (out.length > 255) throw new IllegalArgumentException("element too long: " + out.length);
        return out;
    }

    /** Whole element as sent over the air: ID, length, body. */
    public static byte[] element(String hostName, String ipv4, int capability) {
        byte[] b = body(hostName, ipv4, capability);
        byte[] out = new byte[b.length + 2];
        out[0] = (byte) ELEMENT_ID;
        out[1] = (byte) b.length;
        System.arraycopy(b, 0, out, 2, b.length);
        return out;
    }

    private static void attribute(ByteArrayOutputStream out, int id, byte[] value) {
        out.write(id >> 8);
        out.write(id);
        out.write(value.length >> 8);
        out.write(value.length);
        out.write(value, 0, value.length);
    }
}
