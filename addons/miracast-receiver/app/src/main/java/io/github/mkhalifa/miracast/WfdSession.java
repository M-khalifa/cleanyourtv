package io.github.mkhalifa.miracast;

import android.view.Surface;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Map;

import io.github.mkhalifa.miracast.media.AudioRenderer;
import io.github.mkhalifa.miracast.media.RtpPacket;
import io.github.mkhalifa.miracast.media.RtpReorder;
import io.github.mkhalifa.miracast.media.TsDemuxer;
import io.github.mkhalifa.miracast.media.VideoDecoder;
import io.github.mkhalifa.miracast.rtsp.RtspMessage;
import io.github.mkhalifa.miracast.rtsp.RtspParser;
import io.github.mkhalifa.miracast.wfd.WfdCapabilities;
import io.github.mkhalifa.miracast.wfd.WfdSinkProtocol;

/** One WFD sink session: RTSP client to the source, RTP/TS receive on UDP 19000, decoders. */
public final class WfdSession implements WfdSinkProtocol.Listener, VideoDecoder.Callback {
    public interface Callback {
        void onSessionState(WfdSession s, String state);

        void onVideoSize(WfdSession s, int width, int height);

        void onSessionEnded(WfdSession s, String reason);
    }

    private static final int RTP_WINDOW = 128;
    private static final long IDR_MIN_INTERVAL_MS = 1_000;

    public final String source;
    public final int port;
    public final String via;
    private final Callback cb;
    private final long connectDeadlineMs;
    private static final boolean UHD = true;
    private final WfdSinkProtocol protocol = new WfdSinkProtocol(new WfdCapabilities(true, UHD), this);
    private final VideoDecoder video = new VideoDecoder(this, UHD);
    private final AudioRenderer audio = new AudioRenderer();
    private final Object writeLock = new Object();
    private volatile Socket socket;
    private volatile DatagramSocket udp;
    private volatile boolean stopping;
    private volatile boolean ended;
    private volatile long lastRtspRx;
    private volatile long lastIdr;
    private volatile int videoPid = -1;

    public WfdSession(String source, int port, String via, long connectTimeoutMs, Callback cb) {
        this.source = source;
        this.port = port;
        this.via = via;
        this.cb = cb;
        this.connectDeadlineMs = connectTimeoutMs;
    }

    public void start() {
        try {
            DatagramSocket d = new DatagramSocket(null);
            d.setReuseAddress(true);
            d.setReceiveBufferSize(4 * 1024 * 1024);
            d.bind(new InetSocketAddress(WfdCapabilities.RTP_PORT));
            d.setSoTimeout(20);
            udp = d;
            SessionLog.i("RTP socket on " + WfdCapabilities.RTP_PORT + " rcvbuf=" + d.getReceiveBufferSize());
        } catch (SocketException e) {
            SessionLog.w("RTP socket bind failed", e);
        }
        new Thread(this::rtspLoop, "rtsp").start();
        if (udp != null) new Thread(this::rtpLoop, "rtp").start();
    }

    public void setSurface(Surface s) {
        video.setSurface(s);
    }

    public String togglePacing() {
        return video.togglePacing();
    }

    /** Sink-initiated end (Back key or STOP_PROJECTION): M8 then close. */
    public void stop(String reason) {
        if (stopping) return;
        stopping = true;
        SessionLog.i("stopping session: " + reason);
        new Thread(() -> {
            Socket s = socket;
            if (s != null && protocol.session() != null) {
                send(protocol.teardown());
                long until = System.currentTimeMillis() + 1_000;
                while (!ended && System.currentTimeMillis() < until) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        SessionLog.w("teardown wait interrupted", e);
                        break;
                    }
                }
            }
            end(reason);
        }, "rtsp-stop").start();
    }

    private void rtspLoop() {
        Socket s = connect();
        if (s == null) {
            end("could not connect to " + source + ":" + port);
            return;
        }
        socket = s;
        state("RTSP connected to " + source + ":" + port);
        lastRtspRx = System.currentTimeMillis();
        new Thread(this::watchdog, "rtsp-watchdog").start();
        RtspParser parser = new RtspParser();
        byte[] buf = new byte[16 * 1024];
        try {
            InputStream in = s.getInputStream();
            while (!ended) {
                int n = in.read(buf);
                if (n < 0) {
                    end("source closed RTSP");
                    return;
                }
                lastRtspRx = System.currentTimeMillis();
                for (RtspMessage m : parser.feed(buf, 0, n)) {
                    SessionLog.i("<<< " + m);
                    for (RtspMessage r : protocol.handle(m)) send(r);
                }
            }
        } catch (IOException | RtspParser.ProtocolException e) {
            if (!ended) SessionLog.w("RTSP read failed", e);
            end("RTSP error: " + e.getMessage());
        }
    }

    private Socket connect() {
        long deadline = System.currentTimeMillis() + connectDeadlineMs;
        int attempt = 0;
        while (!stopping && System.currentTimeMillis() < deadline) {
            attempt++;
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(source, port), 1_000);
                SessionLog.i("RTSP connect attempt " + attempt + " OK");
                return s;
            } catch (IOException e) {
                SessionLog.i("RTSP connect attempt " + attempt + ": " + e.getMessage());
                try {
                    s.close();
                } catch (IOException ce) {
                    SessionLog.w("socket close failed", ce);
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    SessionLog.w("connect wait interrupted", ie);
                    return null;
                }
            }
        }
        return null;
    }

    private void watchdog() {
        while (!ended) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                SessionLog.w("watchdog interrupted", e);
                return;
            }
            long limit = 2_000L * Math.max(10, protocol.timeoutSeconds());
            if (protocol.session() != null && System.currentTimeMillis() - lastRtspRx > limit) {
                SessionLog.i("no RTSP message for " + limit + " ms");
                stop("keep-alive timeout");
                return;
            }
        }
    }

    private void send(RtspMessage m) {
        Socket s = socket;
        if (s == null) return;
        SessionLog.i(">>> " + m);
        synchronized (writeLock) {
            try {
                OutputStream out = s.getOutputStream();
                out.write(m.toBytes());
                out.flush();
            } catch (IOException e) {
                SessionLog.w("RTSP write failed", e);
                end("RTSP write failed");
            }
        }
    }

    private void rtpLoop() {
        DatagramSocket d = udp;
        TsDemuxer demux = new TsDemuxer(new TsDemuxer.Listener() {
            @Override
            public void onProgram(Map<Integer, Integer> types) {
                SessionLog.i("TS program streams (pid=type) " + types);
                for (Map.Entry<Integer, Integer> e : types.entrySet()) {
                    if (e.getValue() == TsDemuxer.TYPE_H264) videoPid = e.getKey();
                }
            }

            @Override
            public void onPes(int pid, int type, int streamId, long ptsUs, byte[] data, int len) {
                if (type == TsDemuxer.TYPE_H264) video.onAccessUnit(data, len, ptsUs);
                else if (type == TsDemuxer.TYPE_LPCM) audio.onLpcm(data, len);
                else if (type == TsDemuxer.TYPE_AAC_ADTS) audio.onAac(data, len, ptsUs);
            }

            @Override
            public void onDiscontinuity(int pid) {
                if (pid == videoPid) {
                    video.onCorruption();
                    requestIdr("TS continuity error");
                }
            }

            @Override
            public void onPcr(long pcr27MHz) {
            }
        });
        RtpReorder reorder = new RtpReorder(RTP_WINDOW, new RtpReorder.Sink() {
            @Override
            public void onPayload(byte[] data, int off, int len) {
                demux.feed(data, off, len);
            }

            @Override
            public void onLoss(int packets) {
                video.onCorruption();
                requestIdr("RTP loss " + packets);
            }
        });
        byte[] buf = new byte[65536];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        RtpPacket rtp = new RtpPacket();
        long packets = 0;
        long bytes = 0;
        long lastStats = System.currentTimeMillis();
        long netMinNs = Long.MAX_VALUE;
        long netMaxNs = Long.MIN_VALUE;
        while (!ended) {
            try {
                p.setLength(buf.length);
                d.receive(p);
            } catch (SocketTimeoutException e) {
                reorder.flush();
                continue;
            } catch (IOException e) {
                if (!ended) SessionLog.w("RTP receive failed", e);
                break;
            }
            if (packets == 0) {
                SessionLog.i("first RTP packet from " + p.getAddress().getHostAddress() + " len=" + p.getLength());
                state("streaming");
            }
            packets++;
            bytes += p.getLength();
            if (!rtp.parse(buf, 0, p.getLength())) continue;
            reorder.push(rtp.sequence, buf, rtp.payloadOffset, rtp.payloadLength);
            if (rtp.marker) {
                // RTP timestamp is the sender's clock at send time: its spread against arrival is network jitter.
                long lag = System.nanoTime() - rtp.timestamp * 1_000_000L / 90;
                netMinNs = Math.min(netMinNs, lag);
                netMaxNs = Math.max(netMaxNs, lag);
                if (reorder.idle()) demux.onRtpMarker();
            }
            long now = System.currentTimeMillis();
            if (now - lastStats >= 10_000) {
                long net = netMinNs > netMaxNs ? -1 : (netMaxNs - netMinNs) / 1_000_000L;
                SessionLog.i("rtp packets=" + packets + " bytes=" + bytes + " lost=" + reorder.lost() + " late="
                        + reorder.late() + " netJitter=" + net + "ms " + demux.stats() + "; " + video.stats()
                        + "; " + audio.stats());
                netMinNs = Long.MAX_VALUE;
                netMaxNs = Long.MIN_VALUE;
                lastStats = now;
            }
        }
    }

    private void requestIdr(String why) {
        long now = System.currentTimeMillis();
        if (now - lastIdr < IDR_MIN_INTERVAL_MS || protocol.session() == null || ended) return;
        lastIdr = now;
        SessionLog.i("IDR request: " + why);
        new Thread(() -> send(protocol.idrRequest()), "idr").start();
    }

    private void state(String s) {
        SessionLog.i("session " + source + ": " + s);
        cb.onSessionState(this, s);
    }

    private synchronized void end(String reason) {
        if (ended) return;
        ended = true;
        SessionLog.i("session ended: " + reason);
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                SessionLog.w("RTSP close failed", e);
            }
        }
        DatagramSocket d = udp;
        if (d != null) d.close();
        video.release();
        audio.release();
        cb.onSessionEnded(this, reason);
    }

    public boolean isEnded() {
        return ended;
    }

    // ---- WfdSinkProtocol.Listener ----

    @Override
    public void onLog(String line) {
        SessionLog.i("wfd: " + line);
    }

    @Override
    public void onUnknownParameter(String key) {
        SessionLog.i("wfd: not answering unknown parameter " + key);
    }

    @Override
    public void onNegotiated(String videoFormat, String audioCodec) {
        SessionLog.i("wfd: negotiated video=" + videoFormat + " audio=" + audioCodec);
    }

    @Override
    public void onPlaying() {
        state("PLAY acknowledged");
    }

    @Override
    public void onPaused() {
        state("paused");
    }

    @Override
    public void onTeardown(String reason) {
        end(reason);
    }

    // ---- VideoDecoder.Callback ----

    @Override
    public void onNeedIdr(String why) {
        requestIdr(why);
    }

    @Override
    public void onVideoSize(int width, int height) {
        cb.onVideoSize(this, width, height);
    }

    @Override
    public void onFirstFrame() {
        state("first frame on screen");
    }
}
