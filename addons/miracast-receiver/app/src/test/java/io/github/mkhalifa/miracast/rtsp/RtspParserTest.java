package io.github.mkhalifa.miracast.rtsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class RtspParserTest {
    private static final String M1 = "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n";
    private static final String M3_BODY = "wfd_video_formats\r\nwfd_audio_codecs\r\n";
    private static final String M3 = "GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\n"
            + "Content-Type: text/parameters\r\nContent-Length: " + M3_BODY.length() + "\r\n\r\n" + M3_BODY;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void pipelinedMessagesInOneRead() throws Exception {
        byte[] all = b(M1 + M3);
        List<RtspMessage> got = new RtspParser().feed(all, 0, all.length);
        assertEquals(2, got.size());
        assertEquals("OPTIONS", got.get(0).method());
        assertEquals(1, got.get(0).cseq());
        assertEquals("GET_PARAMETER", got.get(1).method());
        assertEquals(M3_BODY, got.get(1).body);
        assertEquals(2, got.get(1).parameters().size());
        assertEquals("wfd_audio_codecs", got.get(1).parameters().get(1)[0]);
    }

    @Test
    public void byteByByteReads() throws Exception {
        byte[] all = b(M3 + M1);
        RtspParser p = new RtspParser();
        List<RtspMessage> got = new ArrayList<>();
        for (int i = 0; i < all.length; i++) got.addAll(p.feed(all, i, 1));
        assertEquals(2, got.size());
        assertEquals(M3_BODY, got.get(0).body);
        assertEquals("OPTIONS * RTSP/1.0", got.get(1).startLine);
    }

    @Test
    public void bodySplitAcrossReads() throws Exception {
        byte[] all = b(M3);
        RtspParser p = new RtspParser();
        int cut = all.length - 5;
        assertTrue(p.feed(all, 0, cut).isEmpty());
        List<RtspMessage> got = p.feed(all, cut, 5);
        assertEquals(1, got.size());
        assertEquals(M3_BODY, got.get(0).body);
    }

    @Test
    public void responseParsing() throws Exception {
        byte[] all = b("RTSP/1.0 200 OK\r\nCSeq: 3\r\nSession: 6B8B4567;timeout=30\r\n\r\n");
        RtspMessage m = new RtspParser().feed(all, 0, all.length).get(0);
        assertTrue(m.isResponse());
        assertEquals(200, m.statusCode());
        assertEquals("6B8B4567;timeout=30", m.get("session"));
    }

    @Test(expected = RtspParser.ProtocolException.class)
    public void badContentLength() throws Exception {
        byte[] all = b("SET_PARAMETER x RTSP/1.0\r\nCSeq: 1\r\nContent-Length: abc\r\n\r\n");
        new RtspParser().feed(all, 0, all.length);
    }

    @Test
    public void buildAddsContentLength() {
        RtspMessage m = RtspMessage.response(200, "OK", "7").body("a: b\r\n", "text/parameters");
        String s = new String(m.toBytes(), StandardCharsets.UTF_8);
        assertEquals("RTSP/1.0 200 OK\r\nCSeq: 7\r\nContent-Type: text/parameters\r\nContent-Length: 6\r\n\r\na: b\r\n", s);
        String empty = new String(RtspMessage.response(200, "OK", "8").toBytes(), StandardCharsets.UTF_8);
        assertEquals("RTSP/1.0 200 OK\r\nCSeq: 8\r\n\r\n", empty);
    }
}
