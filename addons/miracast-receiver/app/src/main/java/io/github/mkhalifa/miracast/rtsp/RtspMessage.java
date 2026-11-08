package io.github.mkhalifa.miracast.rtsp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One RTSP request or response: start line, ordered headers and a text body. */
public final class RtspMessage {
    public final String startLine;
    private final List<String[]> headers = new ArrayList<>();
    public String body = "";

    public RtspMessage(String startLine) {
        this.startLine = startLine;
    }

    public static RtspMessage request(String method, String uri, int cseq) {
        RtspMessage m = new RtspMessage(method + " " + uri + " RTSP/1.0");
        m.header("CSeq", Integer.toString(cseq));
        return m;
    }

    public static RtspMessage response(int code, String reason, String cseq) {
        RtspMessage m = new RtspMessage("RTSP/1.0 " + code + " " + reason);
        if (cseq != null) m.header("CSeq", cseq);
        return m;
    }

    public RtspMessage header(String name, String value) {
        headers.add(new String[]{name, value});
        return this;
    }

    public RtspMessage body(String text, String contentType) {
        body = text == null ? "" : text;
        if (!body.isEmpty() && contentType != null) header("Content-Type", contentType);
        return this;
    }

    /** Case-insensitive header lookup; null when absent. */
    public String get(String name) {
        for (String[] h : headers) if (h[0].equalsIgnoreCase(name)) return h[1];
        return null;
    }

    public boolean isResponse() {
        return startLine.startsWith("RTSP/");
    }

    public String method() {
        if (isResponse()) return null;
        int sp = startLine.indexOf(' ');
        return sp < 0 ? startLine : startLine.substring(0, sp);
    }

    public String uri() {
        String[] p = startLine.split(" ");
        return !isResponse() && p.length > 1 ? p[1] : null;
    }

    public int statusCode() {
        if (!isResponse()) return -1;
        String[] p = startLine.split(" ");
        try {
            return p.length > 1 ? Integer.parseInt(p[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public int cseq() {
        String v = get("CSeq");
        try {
            return v == null ? -1 : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Body lines as "name: value" pairs, names lower-cased; bare names map to "". */
    public List<String[]> parameters() {
        List<String[]> out = new ArrayList<>();
        for (String line : body.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int c = t.indexOf(':');
            if (c < 0) out.add(new String[]{t.toLowerCase(Locale.ROOT), ""});
            else out.add(new String[]{t.substring(0, c).trim().toLowerCase(Locale.ROOT), t.substring(c + 1).trim()});
        }
        return out;
    }

    public byte[] toBytes() {
        StringBuilder sb = new StringBuilder(startLine).append("\r\n");
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase("Content-Length")) continue;
            sb.append(h[0]).append(": ").append(h[1]).append("\r\n");
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (b.length > 0) sb.append("Content-Length: ").append(b.length).append("\r\n");
        sb.append("\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + b.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(b, 0, all, head.length, b.length);
        return all;
    }

    /** Single-line form for logs. */
    @Override
    public String toString() {
        String s = new String(toBytes(), StandardCharsets.UTF_8);
        return s.replace("\r\n", " | ").trim();
    }
}
