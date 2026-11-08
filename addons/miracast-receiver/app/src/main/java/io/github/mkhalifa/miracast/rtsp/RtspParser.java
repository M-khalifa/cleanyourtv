package io.github.mkhalifa.miracast.rtsp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Incremental RTSP framer: accepts arbitrary TCP chunks, returns complete messages by Content-Length. */
public final class RtspParser {
    private static final int MAX_HEAD = 64 * 1024;
    private static final int MAX_BODY = 1024 * 1024;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    public static final class ProtocolException extends Exception {
        public ProtocolException(String msg) {
            super(msg);
        }
    }

    public List<RtspMessage> feed(byte[] data, int off, int len) throws ProtocolException {
        buf.write(data, off, len);
        List<RtspMessage> out = new ArrayList<>();
        while (true) {
            byte[] b = buf.toByteArray();
            int start = 0;
            while (start < b.length && (b[start] == '\r' || b[start] == '\n')) start++;
            int end = indexOfHeadEnd(b, start);
            if (end < 0) {
                if (b.length - start > MAX_HEAD) throw new ProtocolException("header too long");
                compact(b, start);
                return out;
            }
            String head = new String(b, start, end - start, StandardCharsets.UTF_8);
            String[] lines = head.split("\r\n");
            RtspMessage m = new RtspMessage(lines[0].trim());
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c <= 0) continue;
                String name = lines[i].substring(0, c).trim();
                String value = lines[i].substring(c + 1).trim();
                m.header(name, value);
                if (name.equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new ProtocolException("bad Content-Length " + value);
                    }
                    if (contentLength < 0 || contentLength > MAX_BODY) throw new ProtocolException("bad Content-Length " + value);
                }
            }
            int bodyStart = end + 4;
            if (b.length - bodyStart < contentLength) {
                compact(b, start);
                return out;
            }
            m.body = new String(b, bodyStart, contentLength, StandardCharsets.UTF_8);
            out.add(m);
            compact(b, bodyStart + contentLength);
        }
    }

    private void compact(byte[] b, int from) {
        buf.reset();
        buf.write(b, from, b.length - from);
    }

    private static int indexOfHeadEnd(byte[] b, int from) {
        for (int i = from; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') return i;
        }
        return -1;
    }
}
