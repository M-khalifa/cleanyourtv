package io.github.mkhalifa.miracast.wfd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.mkhalifa.miracast.rtsp.RtspMessage;

/** WFD sink side of the RTSP exchange (M1..M8, M13, M16) without any I/O. */
public final class WfdSinkProtocol {
    public static final String DEFAULT_URL = "rtsp://localhost/wfd1.0";

    public interface Listener {
        void onLog(String line);

        void onUnknownParameter(String key);

        void onNegotiated(String videoFormat, String audioCodec);

        void onPlaying();

        void onPaused();

        void onTeardown(String reason);
    }

    private final WfdCapabilities caps;
    private final Listener listener;
    private final Map<Integer, String> pending = new HashMap<>();
    private int cseq = 0;
    private String presentationUrl = DEFAULT_URL;
    private String session;
    private int timeoutSeconds = 60;
    private String audioCodec;
    private String videoFormat;
    private boolean playing;
    private boolean tornDown;

    public WfdSinkProtocol(WfdCapabilities caps, Listener listener) {
        this.caps = caps;
        this.listener = listener;
    }

    public String session() {
        return session;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public String audioCodec() {
        return audioCodec;
    }

    public String videoFormat() {
        return videoFormat;
    }

    public String presentationUrl() {
        return presentationUrl;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** Handles one message from the source and returns what the sink sends back, in order. */
    public List<RtspMessage> handle(RtspMessage in) {
        List<RtspMessage> out = new ArrayList<>();
        if (in.isResponse()) {
            onResponse(in, out);
            return out;
        }
        String method = in.method() == null ? "" : in.method().toUpperCase(Locale.ROOT);
        String cs = in.get("CSeq");
        switch (method) {
            case "OPTIONS":
                out.add(RtspMessage.response(200, "OK", cs)
                        .header("Public", "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER"));
                out.add(request("OPTIONS", "*").header("Require", "org.wfa.wfd1.0"));
                break;
            case "GET_PARAMETER":
                out.add(answerGet(in, cs));
                break;
            case "SET_PARAMETER":
                out.add(RtspMessage.response(200, "OK", cs));
                onSetParameter(in, out);
                break;
            default:
                listener.onLog("unhandled request " + in.startLine);
                out.add(RtspMessage.response(200, "OK", cs));
                break;
        }
        return out;
    }

    private RtspMessage answerGet(RtspMessage in, String cs) {
        StringBuilder body = new StringBuilder();
        for (String[] p : in.parameters()) {
            String v = caps.answer(p[0]);
            if (v == null) listener.onUnknownParameter(p[0]);
            else body.append(p[0]).append(": ").append(v).append("\r\n");
        }
        RtspMessage r = RtspMessage.response(200, "OK", cs);
        if (body.length() > 0) r.body(body.toString(), "text/parameters");
        return r;
    }

    private void onSetParameter(RtspMessage in, List<RtspMessage> out) {
        String trigger = null;
        boolean negotiated = false;
        for (String[] p : in.parameters()) {
            switch (p[0]) {
                case "wfd_presentation_url": {
                    String url = p[1].split(" ")[0].trim();
                    if (!url.isEmpty() && !url.equalsIgnoreCase("none")) presentationUrl = url;
                    break;
                }
                case "wfd_audio_codecs":
                    audioCodec = p[1];
                    negotiated = true;
                    break;
                case "wfd_video_formats":
                    if (videoFormat == null || !videoFormat.startsWith("x:")) videoFormat = p[1];
                    negotiated = true;
                    break;
                case "wfdx_video_formats":
                    videoFormat = "x:" + p[1];
                    negotiated = true;
                    break;
                case "wfd_trigger_method":
                    trigger = p[1].trim().toUpperCase(Locale.ROOT);
                    break;
                default:
                    listener.onLog("set " + p[0] + ": " + p[1]);
                    break;
            }
        }
        if (negotiated) listener.onNegotiated(videoFormat, audioCodec);
        if (trigger == null) return;
        switch (trigger) {
            case "SETUP":
                out.add(request("SETUP", presentationUrl)
                        .header("Transport", "RTP/AVP/UDP;unicast;client_port=" + WfdCapabilities.RTP_PORT));
                break;
            case "PLAY":
                out.add(withSession(request("PLAY", presentationUrl)));
                break;
            case "PAUSE":
                out.add(withSession(request("PAUSE", presentationUrl)));
                break;
            case "TEARDOWN":
                out.add(withSession(request("TEARDOWN", presentationUrl)));
                break;
            default:
                listener.onLog("unknown trigger " + trigger);
                break;
        }
    }

    private void onResponse(RtspMessage in, List<RtspMessage> out) {
        String method = pending.remove(in.cseq());
        int code = in.statusCode();
        if (method == null) {
            listener.onLog("response to unknown CSeq " + in.cseq() + ": " + code);
            return;
        }
        if (code != 200) {
            listener.onLog(method + " failed: " + in.startLine);
            if (method.equals("SETUP") || method.equals("PLAY")) {
                tornDown = true;
                listener.onTeardown(method + " " + code);
            }
            return;
        }
        switch (method) {
            case "SETUP":
                parseSession(in.get("Session"));
                out.add(withSession(request("PLAY", presentationUrl)));
                break;
            case "PLAY":
                playing = true;
                listener.onPlaying();
                break;
            case "PAUSE":
                playing = false;
                listener.onPaused();
                break;
            case "TEARDOWN":
                playing = false;
                if (!tornDown) {
                    tornDown = true;
                    listener.onTeardown("TEARDOWN acknowledged");
                }
                break;
            default:
                break;
        }
    }

    private void parseSession(String s) {
        if (s == null) return;
        String[] parts = s.split(";");
        session = parts[0].trim();
        for (int i = 1; i < parts.length; i++) {
            String t = parts[i].trim().toLowerCase(Locale.ROOT);
            if (!t.startsWith("timeout=")) continue;
            try {
                timeoutSeconds = Integer.parseInt(t.substring(8).trim());
            } catch (NumberFormatException e) {
                listener.onLog("bad session timeout " + t);
            }
        }
    }

    /** M13: asks the source for an IDR picture. */
    public RtspMessage idrRequest() {
        return withSession(request("SET_PARAMETER", presentationUrl)).body("wfd_idr_request\r\n", "text/parameters");
    }

    /** M8 sent by the sink. */
    public RtspMessage teardown() {
        return withSession(request("TEARDOWN", presentationUrl));
    }

    private RtspMessage request(String method, String uri) {
        int c = ++cseq;
        pending.put(c, method);
        return RtspMessage.request(method, uri, c);
    }

    private RtspMessage withSession(RtspMessage m) {
        if (session != null) m.header("Session", session);
        return m;
    }
}
