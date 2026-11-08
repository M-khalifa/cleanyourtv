package io.github.mkhalifa.miracast.wfd;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.mkhalifa.miracast.rtsp.RtspMessage;

/** WFD sink side of the RTSP exchange (M1..M8, M13, M16) without any I/O; safe to call from several threads. */
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
    private boolean sourceTeardown;

    public WfdSinkProtocol(WfdCapabilities caps, Listener listener) {
        this.caps = caps;
        this.listener = listener;
    }

    public synchronized String session() {
        return session;
    }

    public synchronized int timeoutSeconds() {
        return timeoutSeconds;
    }

    public synchronized String audioCodec() {
        return audioCodec;
    }

    public synchronized String videoFormat() {
        return videoFormat;
    }

    public synchronized String presentationUrl() {
        return presentationUrl;
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    /** True after the source sent TEARDOWN itself: the caller ends the session once the reply is out. */
    public synchronized boolean sourceTornDown() {
        return sourceTeardown;
    }

    /** Handles one message from the source and returns what the sink sends back, in order. */
    public synchronized List<RtspMessage> handle(RtspMessage in) {
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
            case "TEARDOWN":
                listener.onLog("TEARDOWN from source");
                out.add(RtspMessage.response(200, "OK", cs));
                tornDown = true;
                sourceTeardown = true;
                break;
            default:
                listener.onLog("unsupported request " + in.startLine);
                out.add(RtspMessage.response(501, "Not Implemented", cs));
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
                if (!parseSession(in.get("Session"))) {
                    listener.onLog("SETUP answer without a usable Session: " + in.get("Session"));
                    tornDown = true;
                    listener.onTeardown("SETUP without session");
                    break;
                }
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

    /** False when the header is missing or has no session identifier. */
    private boolean parseSession(String s) {
        if (s == null) return false;
        String[] parts = s.split(";");
        if (parts.length == 0 || parts[0].trim().isEmpty()) return false;
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
        return true;
    }

    /** M13: asks the source for an IDR picture. */
    public synchronized RtspMessage idrRequest() {
        return withSession(request("SET_PARAMETER", presentationUrl)).body("wfd_idr_request\r\n", "text/parameters");
    }

    /** M8 sent by the sink. */
    public synchronized RtspMessage teardown() {
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
