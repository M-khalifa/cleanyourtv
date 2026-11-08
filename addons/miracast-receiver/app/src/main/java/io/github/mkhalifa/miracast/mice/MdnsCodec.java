package io.github.mkhalifa.miracast.mice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal mDNS (RFC 6762) query parser and answer builder for one host name and one _display._tcp service. */
public final class MdnsCodec {
    public static final int TYPE_A = 1;
    public static final int TYPE_PTR = 12;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_SRV = 33;
    public static final int TYPE_ANY = 255;
    public static final String SERVICE = "_display._tcp.local";
    public static final String SERVICE_ENUM = "_services._dns-sd._udp.local";
    private static final int CLASS_IN = 1;
    private static final int CACHE_FLUSH = 0x8000;
    public static final int TTL_HOST = 120;
    public static final int TTL_SERVICE = 4500;

    public static final class Question {
        public final String name;
        public final int type;
        public final boolean unicastResponse;

        Question(String name, int type, boolean unicastResponse) {
            this.name = name;
            this.type = type;
            this.unicastResponse = unicastResponse;
        }
    }

    public static final class Query {
        public final int id;
        public final List<Question> questions;

        Query(int id, List<Question> questions) {
            this.id = id;
            this.questions = questions;
        }

        public boolean wantsUnicast() {
            for (Question q : questions) if (q.unicastResponse) return true;
            return false;
        }
    }

    /** What this responder answers for. */
    public static final class Zone {
        public final String host;
        public final String instance;
        public final byte[] ipv4;
        public final int port;
        public final String containerId;

        public Zone(String host, String instance, byte[] ipv4, int port, String containerId) {
            this.host = host;
            this.instance = instance;
            this.ipv4 = ipv4;
            this.port = port;
            this.containerId = containerId;
        }

        String hostFqdn() {
            return host + ".local";
        }

        String instanceFqdn() {
            return instance + "." + SERVICE;
        }
    }

    private MdnsCodec() {
    }

    /** Parses questions of a query; null for responses or malformed packets. */
    public static Query parseQuery(byte[] b, int len) {
        if (len < 12) return null;
        int id = u16(b, 0);
        int flags = u16(b, 2);
        if ((flags & 0x8000) != 0) return null;
        int qd = u16(b, 4);
        List<Question> qs = new ArrayList<>();
        int[] pos = {12};
        for (int i = 0; i < qd; i++) {
            String name = readName(b, len, pos);
            if (name == null || pos[0] + 4 > len) return null;
            int type = u16(b, pos[0]);
            int cls = u16(b, pos[0] + 2);
            pos[0] += 4;
            qs.add(new Question(name.toLowerCase(Locale.ROOT), type, (cls & 0x8000) != 0));
        }
        return new Query(id, qs);
    }

    /** Reads a possibly compressed name at pos[0], advancing pos[0] past it; null when malformed. */
    static String readName(byte[] b, int len, int[] pos) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0];
        boolean jumped = false;
        int hops = 0;
        while (true) {
            if (p >= len) return null;
            int l = b[p] & 0xFF;
            if (l == 0) {
                if (!jumped) pos[0] = p + 1;
                break;
            }
            if ((l & 0xC0) == 0xC0) {
                if (p + 1 >= len || ++hops > 16) return null;
                int target = ((l & 0x3F) << 8) | (b[p + 1] & 0xFF);
                if (!jumped) pos[0] = p + 2;
                jumped = true;
                p = target;
                continue;
            }
            if ((l & 0xC0) != 0 || p + 1 + l > len) return null;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(b, p + 1, l, StandardCharsets.UTF_8));
            p += 1 + l;
        }
        return sb.toString();
    }

    /** Builds the response to a query, or null when nothing in it is ours. */
    public static byte[] answer(Query q, Zone z, boolean legacyUnicast) {
        List<byte[]> answers = new ArrayList<>();
        List<byte[]> extra = new ArrayList<>();
        String host = z.hostFqdn().toLowerCase(Locale.ROOT);
        String inst = z.instanceFqdn().toLowerCase(Locale.ROOT);
        int hostTtl = legacyUnicast ? 10 : TTL_HOST;
        int svcTtl = legacyUnicast ? 10 : TTL_SERVICE;
        for (Question qu : q.questions) {
            boolean any = qu.type == TYPE_ANY;
            if (qu.name.equals(host) && (any || qu.type == TYPE_A)) {
                answers.add(a(z, hostTtl, !legacyUnicast));
            } else if (qu.name.equals(SERVICE) && (any || qu.type == TYPE_PTR)) {
                answers.add(ptr(SERVICE, z.instanceFqdn(), svcTtl));
                extra.add(srv(z, hostTtl, !legacyUnicast));
                extra.add(txt(z, svcTtl, !legacyUnicast));
                extra.add(a(z, hostTtl, !legacyUnicast));
            } else if (qu.name.equals(inst) && (any || qu.type == TYPE_SRV || qu.type == TYPE_TXT)) {
                if (any || qu.type == TYPE_SRV) answers.add(srv(z, hostTtl, !legacyUnicast));
                if (any || qu.type == TYPE_TXT) answers.add(txt(z, svcTtl, !legacyUnicast));
                extra.add(a(z, hostTtl, !legacyUnicast));
            } else if (qu.name.equals(SERVICE_ENUM) && (any || qu.type == TYPE_PTR)) {
                answers.add(ptr(SERVICE_ENUM, SERVICE, svcTtl));
            }
        }
        if (answers.isEmpty()) return null;
        return packet(legacyUnicast ? q.id : 0, legacyUnicast ? q.questions : null, answers, extra);
    }

    /** Unsolicited announcement of all our records. */
    public static byte[] announce(Zone z) {
        List<byte[]> answers = new ArrayList<>();
        answers.add(a(z, TTL_HOST, true));
        answers.add(ptr(SERVICE, z.instanceFqdn(), TTL_SERVICE));
        answers.add(srv(z, TTL_HOST, true));
        answers.add(txt(z, TTL_SERVICE, true));
        return packet(0, null, answers, new ArrayList<>());
    }

    private static byte[] packet(int id, List<Question> echo, List<byte[]> answers, List<byte[]> extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        w16(out, id);
        w16(out, 0x8400);
        w16(out, echo == null ? 0 : echo.size());
        w16(out, answers.size());
        w16(out, 0);
        w16(out, extra.size());
        if (echo != null) {
            for (Question q : echo) {
                name(out, q.name);
                w16(out, q.type);
                w16(out, CLASS_IN);
            }
        }
        for (byte[] r : answers) out.write(r, 0, r.length);
        for (byte[] r : extra) out.write(r, 0, r.length);
        return out.toByteArray();
    }

    private static byte[] a(Zone z, int ttl, boolean flush) {
        return record(z.hostFqdn(), TYPE_A, ttl, flush, z.ipv4);
    }

    private static byte[] ptr(String name, String target, int ttl) {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        name(d, target);
        return record(name, TYPE_PTR, ttl, false, d.toByteArray());
    }

    private static byte[] srv(Zone z, int ttl, boolean flush) {
        ByteArrayOutputStream d = new ByteArrayOutputStream();
        w16(d, 0);
        w16(d, 0);
        w16(d, z.port);
        name(d, z.hostFqdn());
        return record(z.instanceFqdn(), TYPE_SRV, ttl, flush, d.toByteArray());
    }

    private static byte[] txt(Zone z, int ttl, boolean flush) {
        byte[] kv = ("container_id=" + z.containerId).getBytes(StandardCharsets.UTF_8);
        byte[] d = new byte[kv.length + 1];
        d[0] = (byte) kv.length;
        System.arraycopy(kv, 0, d, 1, kv.length);
        return record(z.instanceFqdn(), TYPE_TXT, ttl, flush, d);
    }

    private static byte[] record(String name, int type, int ttl, boolean flush, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        name(out, name);
        w16(out, type);
        w16(out, CLASS_IN | (flush ? CACHE_FLUSH : 0));
        out.write(ttl >>> 24);
        out.write(ttl >>> 16);
        out.write(ttl >>> 8);
        out.write(ttl);
        w16(out, data.length);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /** Writes a name as labels; an instance part before the service name stays one label (spaces allowed). */
    static void name(ByteArrayOutputStream out, String fqdn) {
        String rest = fqdn;
        String first = null;
        int svc = fqdn.toLowerCase(Locale.ROOT).indexOf("." + SERVICE);
        if (svc > 0) {
            first = fqdn.substring(0, svc);
            rest = fqdn.substring(svc + 1);
        }
        if (first != null) label(out, first);
        for (String l : rest.split("\\.")) if (!l.isEmpty()) label(out, l);
        out.write(0);
    }

    private static void label(ByteArrayOutputStream out, String l) {
        byte[] b = l.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(b.length, 63);
        out.write(n);
        out.write(b, 0, n);
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }

    private static void w16(ByteArrayOutputStream out, int v) {
        out.write(v >> 8);
        out.write(v);
    }
}
