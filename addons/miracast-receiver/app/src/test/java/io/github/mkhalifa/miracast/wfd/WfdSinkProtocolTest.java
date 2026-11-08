package io.github.mkhalifa.miracast.wfd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import io.github.mkhalifa.miracast.rtsp.RtspMessage;

public class WfdSinkProtocolTest {
    private final List<String> unknown = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private WfdSinkProtocol p;

    @Before
    public void setUp() {
        p = new WfdSinkProtocol(new WfdCapabilities(true), new WfdSinkProtocol.Listener() {
            @Override
            public void onLog(String line) {
            }

            @Override
            public void onUnknownParameter(String key) {
                unknown.add(key);
            }

            @Override
            public void onNegotiated(String v, String a) {
                events.add("negotiated " + a);
            }

            @Override
            public void onPlaying() {
                events.add("playing");
            }

            @Override
            public void onPaused() {
                events.add("paused");
            }

            @Override
            public void onTeardown(String reason) {
                events.add("teardown");
            }
        });
    }

    private static RtspMessage req(String method, int cseq, String body) {
        RtspMessage m = RtspMessage.request(method, "rtsp://localhost/wfd1.0", cseq);
        if (body != null) m.body(body, "text/parameters");
        return m;
    }

    @Test
    public void m1RepliesAndSendsM2() {
        List<RtspMessage> out = p.handle(req("OPTIONS", 1, null));
        assertEquals(2, out.size());
        assertEquals("RTSP/1.0 200 OK", out.get(0).startLine);
        assertEquals("1", out.get(0).get("CSeq"));
        assertEquals("org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER", out.get(0).get("Public"));
        assertEquals("OPTIONS * RTSP/1.0", out.get(1).startLine);
        assertEquals("org.wfa.wfd1.0", out.get(1).get("Require"));
    }

    @Test
    public void m3AnswersOnlyAskedKeysAndOmitsUnknown() {
        RtspMessage r = p.handle(req("GET_PARAMETER", 2,
                "wfd_audio_codecs\r\nwfd_client_rtp_ports\r\nmicrosoft_cursor\r\nwfd_content_protection\r\n"
                        + "wfdx_video_formats\r\nmicrosoft_latency_management_capability\r\n")).get(0);
        assertEquals("2", r.get("CSeq"));
        assertEquals("text/parameters", r.get("Content-Type"));
        assertEquals("wfd_audio_codecs: LPCM 00000003 00, AAC 00000001 00\r\n"
                + "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n"
                + "wfd_content_protection: none\r\n"
                + "wfdx_video_formats: " + WfdCapabilities.videoFormatsX() + "\r\n"
                + "microsoft_latency_management_capability: supported\r\n", r.body);
        assertEquals(1, unknown.size());
        assertEquals("microsoft_cursor", unknown.get(0));
        assertFalse(r.body.contains("wfd_video_formats"));
    }

    @Test
    public void videoFormatStrings() {
        assertEquals("40 00 02 10 0001bdeb 00000000 00000000 00 0000 0000 00 none none, "
                + "01 10 0001bdeb 00000000 00000000 00 0000 0000 00 none none", WfdCapabilities.videoFormats());
        assertEquals("0040 00 0002 0010 000001bdeb 0000000000 00000000 00 0000 0000 00 none none, "
                + "0001 0010 000001bdeb 0000000000 00000000 00 0000 0000 00 none none", WfdCapabilities.videoFormatsX());
        assertNull(new WfdCapabilities(false).answer("wfdx_video_formats"));
    }

    @Test
    public void uhdOffersLevel51AndCustom2160p30() {
        WfdCapabilities uhd = new WfdCapabilities(true, true);
        assertEquals("40 00 02 40 0001bdeb 00000000 00000000 00 0000 0000 00 none none, "
                + "01 10 0001bdeb 00000000 00000000 00 0000 0000 00 none none", uhd.answer("wfd_video_formats"));
        assertEquals("0040 00 0002 0040 0006abbdeb 0000000000 00000000 00 0000 0000 00 none none, "
                + "0001 0010 000001bdeb 0000000000 00000000 00 0000 0000 00 none none", uhd.answer("wfdx_video_formats"));
        assertEquals("0f00 0870 001e, 0a00 05a0 003c", uhd.answer("microsoft_custom_video_formats"));
        assertEquals("none", new WfdCapabilities(true).answer("microsoft_custom_video_formats"));
        assertEquals("40000000", uhd.answer("microsoft_max_bitrate"));
    }

    @Test
    public void keepAliveGetParameterHasNoBody() {
        RtspMessage r = p.handle(req("GET_PARAMETER", 9, null)).get(0);
        assertEquals("RTSP/1.0 200 OK", r.startLine);
        assertEquals("", r.body);
        assertTrue(unknown.isEmpty());
    }

    @Test
    public void fullSetupPlayTeardown() {
        List<RtspMessage> out = p.handle(req("SET_PARAMETER", 3,
                "wfd_audio_codecs: AAC 00000001 00\r\nwfd_presentation_URL: rtsp://192.168.1.50/wfd1.0/streamid=0 none\r\n"
                        + "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n"));
        assertEquals(1, out.size());
        assertEquals("rtsp://192.168.1.50/wfd1.0/streamid=0", p.presentationUrl());
        assertEquals("negotiated AAC 00000001 00", events.get(0));

        out = p.handle(req("SET_PARAMETER", 4, "wfd_trigger_method: SETUP\r\n"));
        assertEquals(2, out.size());
        RtspMessage setup = out.get(1);
        assertEquals("SETUP rtsp://192.168.1.50/wfd1.0/streamid=0 RTSP/1.0", setup.startLine);
        assertEquals("RTP/AVP/UDP;unicast;client_port=19000", setup.get("Transport"));

        RtspMessage setupOk = RtspMessage.response(200, "OK", Integer.toString(setup.cseq()))
                .header("Session", "6B8B4567;timeout=30")
                .header("Transport", "RTP/AVP/UDP;unicast;client_port=19000;server_port=5000");
        out = p.handle(setupOk);
        assertEquals("6B8B4567", p.session());
        assertEquals(30, p.timeoutSeconds());
        RtspMessage play = out.get(0);
        assertEquals("PLAY rtsp://192.168.1.50/wfd1.0/streamid=0 RTSP/1.0", play.startLine);
        assertEquals("6B8B4567", play.get("Session"));

        p.handle(RtspMessage.response(200, "OK", Integer.toString(play.cseq())));
        assertTrue(p.isPlaying());
        assertTrue(events.contains("playing"));

        RtspMessage idr = p.idrRequest();
        assertEquals("wfd_idr_request\r\n", idr.body);
        assertEquals("6B8B4567", idr.get("Session"));

        out = p.handle(req("SET_PARAMETER", 5, "wfd_trigger_method: TEARDOWN\r\n"));
        RtspMessage td = out.get(1);
        assertEquals("TEARDOWN rtsp://192.168.1.50/wfd1.0/streamid=0 RTSP/1.0", td.startLine);
        p.handle(RtspMessage.response(200, "OK", Integer.toString(td.cseq())));
        assertTrue(events.contains("teardown"));
        assertFalse(p.isPlaying());
    }

    @Test
    public void pauseAndPlayTriggers() {
        assertEquals("PAUSE", p.handle(req("SET_PARAMETER", 6, "wfd_trigger_method: PAUSE\r\n")).get(1).method());
        assertEquals("PLAY", p.handle(req("SET_PARAMETER", 7, "wfd_trigger_method: PLAY\r\n")).get(1).method());
    }

    @Test
    public void failedSetupEndsSession() {
        RtspMessage setup = p.handle(req("SET_PARAMETER", 4, "wfd_trigger_method: SETUP\r\n")).get(1);
        p.handle(RtspMessage.response(454, "Session Not Found", Integer.toString(setup.cseq())));
        assertTrue(events.contains("teardown"));
    }
}
