package io.github.mkhalifa.miracast.mice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class MdnsCodecTest {
    private static final MdnsCodec.Zone ZONE = new MdnsCodec.Zone("KhalifaTV", "Khalifa TV",
            new byte[]{(byte) 192, (byte) 168, 1, (byte) 240}, 7250, "1234-ABCD");

    private static byte[] query(int id, String name, int type, boolean qu) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(id >> 8);
        o.write(id);
        for (int i = 0; i < 2; i++) o.write(0);
        o.write(0);
        o.write(1);
        for (int i = 0; i < 6; i++) o.write(0);
        for (String l : name.split("\\.")) {
            byte[] b = l.getBytes(StandardCharsets.UTF_8);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
        o.write(0);
        o.write(type);
        o.write(qu ? 0x80 : 0);
        o.write(1);
        return o.toByteArray();
    }

    /** Record names and types in the answer and additional sections. */
    private static List<String> records(byte[] r) {
        List<String> out = new ArrayList<>();
        int qd = ((r[4] & 0xFF) << 8) | (r[5] & 0xFF);
        int an = ((r[6] & 0xFF) << 8) | (r[7] & 0xFF);
        int ar = ((r[10] & 0xFF) << 8) | (r[11] & 0xFF);
        int[] pos = {12};
        for (int i = 0; i < qd; i++) {
            MdnsCodec.readName(r, r.length, pos);
            pos[0] += 4;
        }
        for (int i = 0; i < an + ar; i++) {
            String n = MdnsCodec.readName(r, r.length, pos);
            int type = ((r[pos[0]] & 0xFF) << 8) | (r[pos[0] + 1] & 0xFF);
            int rdlen = ((r[pos[0] + 8] & 0xFF) << 8) | (r[pos[0] + 9] & 0xFF);
            out.add(type + " " + n);
            pos[0] += 10 + rdlen;
        }
        assertEquals(r.length, pos[0]);
        return out;
    }

    @Test
    public void answersAQueryCaseInsensitively() {
        byte[] q = query(0x1234, "khalifatv.LOCAL", MdnsCodec.TYPE_A, true);
        MdnsCodec.Query parsed = MdnsCodec.parseQuery(q, q.length);
        assertNotNull(parsed);
        assertTrue(parsed.wantsUnicast());
        byte[] r = MdnsCodec.answer(parsed, ZONE, false);
        assertNotNull(r);
        assertEquals(0, r[0]);
        assertEquals(0, r[1]);
        assertEquals((byte) 0x84, r[2]);
        assertEquals(List.of("1 KhalifaTV.local"), records(r));
        byte[] tail = new byte[4];
        System.arraycopy(r, r.length - 4, tail, 0, 4);
        assertArrayEquals(ZONE.ipv4, tail);
    }

    @Test
    public void legacyUnicastEchoesIdAndQuestion() {
        byte[] q = query(0xBEEF, "KhalifaTV.local", MdnsCodec.TYPE_A, false);
        byte[] r = MdnsCodec.answer(MdnsCodec.parseQuery(q, q.length), ZONE, true);
        assertEquals((byte) 0xBE, r[0]);
        assertEquals((byte) 0xEF, r[1]);
        assertEquals(1, r[5]);
        assertEquals(List.of("1 KhalifaTV.local"), records(r));
    }

    @Test
    public void ptrQueryGetsServiceRecords() {
        byte[] q = query(0, "_display._tcp.local", MdnsCodec.TYPE_PTR, false);
        byte[] r = MdnsCodec.answer(MdnsCodec.parseQuery(q, q.length), ZONE, false);
        assertEquals(List.of("12 _display._tcp.local", "33 Khalifa TV._display._tcp.local",
                "16 Khalifa TV._display._tcp.local", "1 KhalifaTV.local"), records(r));
        String s = new String(r, StandardCharsets.ISO_8859_1);
        assertTrue(s.contains("container_id=1234-ABCD"));
    }

    @Test
    public void compressedQuestionNames() {
        byte[] first = query(0, "KhalifaTV.local", MdnsCodec.TYPE_A, false);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(first, 0, first.length);
        o.write(0xC0);
        o.write(12);
        o.write(0);
        o.write(MdnsCodec.TYPE_ANY);
        o.write(0);
        o.write(1);
        byte[] q = o.toByteArray();
        q[5] = 2;
        MdnsCodec.Query parsed = MdnsCodec.parseQuery(q, q.length);
        assertEquals(2, parsed.questions.size());
        assertEquals("khalifatv.local", parsed.questions.get(1).name);
        assertEquals(MdnsCodec.TYPE_ANY, parsed.questions.get(1).type);
    }

    @Test
    public void ignoresOtherNamesAndResponses() {
        byte[] q = query(0, "other.local", MdnsCodec.TYPE_A, false);
        assertNull(MdnsCodec.answer(MdnsCodec.parseQuery(q, q.length), ZONE, false));
        q[2] = (byte) 0x84;
        assertNull(MdnsCodec.parseQuery(q, q.length));
        byte[] aaaa = query(0, "KhalifaTV.local", MdnsCodec.TYPE_AAAA, false);
        assertNull(MdnsCodec.answer(MdnsCodec.parseQuery(aaaa, aaaa.length), ZONE, false));
    }

    @Test
    public void announcementHasAllRecords() {
        assertEquals(List.of("1 KhalifaTV.local", "12 _display._tcp.local", "33 Khalifa TV._display._tcp.local",
                "16 Khalifa TV._display._tcp.local"), records(MdnsCodec.announce(ZONE)));
    }
}
