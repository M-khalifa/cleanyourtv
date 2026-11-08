package io.github.mkhalifa.miracast.media;

import java.net.InetAddress;

/** Accepts RTP only from the RTSP peer and only from the first SSRC it sends, re-locking after a long run of another. */
public final class RtpSourceLock {
    /** Consecutive packets from the peer with a new SSRC before that SSRC is taken as the source's own. */
    static final int RELOCK_AFTER = 200;
    private long ssrc = -1;
    private int mismatches;
    private long rejected;

    public boolean accept(InetAddress from, InetAddress peer, long packetSsrc) {
        if (peer == null || !peer.equals(from)) {
            rejected++;
            return false;
        }
        if (ssrc < 0 || packetSsrc == ssrc) {
            ssrc = packetSsrc;
            mismatches = 0;
            return true;
        }
        if (++mismatches >= RELOCK_AFTER) {
            ssrc = packetSsrc;
            mismatches = 0;
            return true;
        }
        rejected++;
        return false;
    }

    public long rejected() {
        return rejected;
    }
}
