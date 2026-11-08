package io.github.mkhalifa.miracast.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

import io.github.mkhalifa.miracast.SessionLog;

/** H.264 access units to a MediaCodec on a Surface, rendered paced by PTS (smooth) or as soon as decoded. */
public final class VideoDecoder {
    public interface Callback {
        void onNeedIdr(String why);

        void onVideoSize(int width, int height);

        void onFirstFrame();
    }

    private static final int MAX_PENDING = 30;
    /** Smooth: render at PTS plus an adaptive playout delay. Instant: render the moment a frame is decoded. */
    private static volatile boolean paced = true;
    private final Callback cb;
    private final FramePacer pacer = new FramePacer();
    private long arrivalMinNs = Long.MAX_VALUE;
    private long arrivalMaxNs = Long.MIN_VALUE;
    private long outputMinNs = Long.MAX_VALUE;
    private long outputMaxNs = Long.MIN_VALUE;
    private long lastArrivalPtsUs = -1;
    /** Typical PTS step between frames, to spot frames Windows never sent. */
    private long frameGapUs;
    private long sourceSkips;
    private final int maxWidth;
    private final int maxHeight;
    private final ArrayDeque<long[]> inFlight = new ArrayDeque<>();
    private long delaySumUs;
    private long delayMaxUs;
    private long delayCount;
    private String spsLog;
    private int spsWidth;
    private int spsHeight;
    private final HandlerThread thread = new HandlerThread("video-codec");
    private final Handler handler;
    private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();
    private final ArrayDeque<Object[]> pending = new ArrayDeque<>();
    private MediaCodec codec;
    private Surface surface;
    private byte[] sps;
    private byte[] pps;
    private boolean waitIdr = true;
    private boolean firstFrame;
    private long frames;
    private long dropped;
    private long corrupted;
    private long lastPtsUs;

    public VideoDecoder(Callback cb) {
        this(cb, false);
    }

    /** uhd: size decoder buffers for 3840x2160 so a mid-session switch to 4K needs no new codec. */
    public VideoDecoder(Callback cb, boolean uhd) {
        this.cb = cb;
        maxWidth = uhd ? 3840 : 1920;
        maxHeight = uhd ? 2176 : 1200;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** Switches between smooth and instant rendering; returns the new mode's name. */
    public synchronized String togglePacing() {
        paced = !paced;
        pacer.reset();
        String name = paced ? "smooth" : "instant";
        SessionLog.i("frame pacing: " + name);
        return name;
    }

    public synchronized void setSurface(Surface s) {
        if (s == surface) return;
        releaseCodec();
        surface = s;
        waitIdr = true;
        if (s != null) cb.onNeedIdr("surface ready");
    }

    /** One complete access unit from a PES packet. */
    public synchronized void onAccessUnit(byte[] au, int len, long ptsUs) {
        boolean idr = false;
        boolean hasSps = false;
        for (int[] n : H264.nals(au, len)) {
            int t = au[n[0]] & 0x1F;
            if (t == H264.NAL_IDR) idr = true;
            if (t == H264.NAL_SPS) hasSps = true;
            if (t == H264.NAL_PPS) pps = H264.extract(au, len, H264.NAL_PPS);
        }
        if (hasSps) {
            sps = H264.extract(au, len, H264.NAL_SPS);
            H264.SpsInfo info = sps == null ? null : H264.parseSps(sps);
            String s = info == null ? "unparsed" : info.toString();
            if (!s.equals(spsLog)) {
                spsLog = s;
                SessionLog.i("SPS " + s);
            }
            if (needsNewDecoder(info, spsWidth, spsHeight)) {
                if (codec != null) SessionLog.i("video size " + spsWidth + "x" + spsHeight + " -> "
                        + info.width + "x" + info.height + ": new decoder");
                spsWidth = info.width;
                spsHeight = info.height;
                releaseCodec();
            }
        }
        if (ptsUs >= 0) {
            long lag = System.nanoTime() - ptsUs * 1000L;
            arrivalMinNs = Math.min(arrivalMinNs, lag);
            arrivalMaxNs = Math.max(arrivalMaxNs, lag);
            long gap = ptsUs - lastArrivalPtsUs;
            if (lastArrivalPtsUs >= 0 && gap > 0 && gap < 1_000_000L) {
                if (frameGapUs == 0) frameGapUs = gap;
                else if (gap > frameGapUs * 8 / 5) sourceSkips++;
                else frameGapUs += (gap - frameGapUs) / 16;
            }
            lastArrivalPtsUs = ptsUs;
        }
        if (waitIdr) {
            if (!idr || sps == null || pps == null) {
                dropped++;
                cb.onNeedIdr("waiting for IDR");
                return;
            }
            waitIdr = false;
            pending.clear();
        }
        if (surface == null) return;
        if (codec == null && !createCodec()) {
            waitIdr = true;
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            SessionLog.i("video decoder backlog " + pending.size() + ", dropping until next IDR");
            pending.clear();
            waitIdr = true;
            cb.onNeedIdr("decoder backlog");
            return;
        }
        byte[] copy = new byte[len];
        System.arraycopy(au, 0, copy, 0, len);
        lastPtsUs = ptsUs >= 0 ? ptsUs : lastPtsUs + 16_666;
        pending.add(new Object[]{copy, lastPtsUs});
        feed();
    }

    /** True when a parsed SPS announces a picture size other than the one the decoder was set up for. */
    static boolean needsNewDecoder(H264.SpsInfo info, int width, int height) {
        return info != null && (info.width != width || info.height != height);
    }

    /** Playout delay video frames currently get; audio is held back by the same amount. */
    public synchronized long playoutDelayMs() {
        return paced ? pacer.delayMs() : 0;
    }

    /** Called after packet loss: keep decoding (the decoder conceals the damage) until the requested IDR heals it. */
    public synchronized void onCorruption() {
        corrupted++;
    }

    private boolean createCodec() {
        MediaCodec c = null;
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    spsWidth > 0 ? spsWidth : 1920, spsHeight > 0 ? spsHeight : 1080);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            f.setInteger(MediaFormat.KEY_MAX_WIDTH, maxWidth);
            f.setInteger(MediaFormat.KEY_MAX_HEIGHT, maxHeight);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            MediaCodecInfo.CodecCapabilities caps = c.getCodecInfo().getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC);
            boolean lowLatency = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency);
            if (lowLatency) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, 120);
            freeInputs.clear();
            c.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mc, int index) {
                    synchronized (VideoDecoder.this) {
                        if (mc != codec) return;
                        freeInputs.add(index);
                        feed();
                    }
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
                    synchronized (VideoDecoder.this) {
                        if (mc != codec) return;
                        try {
                            long now = System.nanoTime();
                            long lag = now - info.presentationTimeUs * 1000L;
                            outputMinNs = Math.min(outputMinNs, lag);
                            outputMaxNs = Math.max(outputMaxNs, lag);
                            if (info.size <= 0) {
                                mc.releaseOutputBuffer(index, false);
                            } else if (paced) {
                                mc.releaseOutputBuffer(index, pacer.target(info.presentationTimeUs, now));
                            } else {
                                pacer.note(now, info.presentationTimeUs * 1000L);
                                mc.releaseOutputBuffer(index, true);
                            }
                        } catch (IllegalStateException e) {
                            SessionLog.w("video release failed", e);
                            return;
                        }
                        frames++;
                        noteDecoded(info.presentationTimeUs);
                        if (!firstFrame && info.size > 0) {
                            firstFrame = true;
                            cb.onFirstFrame();
                        }
                    }
                }

                @Override
                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    SessionLog.w("video codec error", e);
                    synchronized (VideoDecoder.this) {
                        if (mc != codec) return;
                        releaseCodec();
                        waitIdr = true;
                    }
                    cb.onNeedIdr("codec error");
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                    int w = format.getInteger(MediaFormat.KEY_WIDTH);
                    int hgt = format.getInteger(MediaFormat.KEY_HEIGHT);
                    if (format.containsKey("crop-right") && format.containsKey("crop-left")) {
                        w = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
                    }
                    if (format.containsKey("crop-bottom") && format.containsKey("crop-top")) {
                        hgt = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
                    }
                    SessionLog.i("video format " + w + "x" + hgt + " " + format);
                    cb.onVideoSize(w, hgt);
                }
            }, handler);
            c.configure(f, surface, null, 0);
            c.start();
            codec = c;
            SessionLog.i("video decoder " + c.getName() + " lowLatency=" + lowLatency
                    + " max=" + maxWidth + "x" + maxHeight + " pacing=" + (paced ? "smooth" : "instant"));
            return true;
        } catch (Exception e) {
            SessionLog.w("video decoder setup failed", e);
            if (c != null && c != codec) {
                try {
                    c.release();
                } catch (RuntimeException re) {
                    SessionLog.w("video decoder release after failed setup failed", re);
                }
            }
            releaseCodec();
            return false;
        }
    }

    private void feed() {
        while (codec != null && !freeInputs.isEmpty() && !pending.isEmpty()) {
            int index = freeInputs.poll();
            Object[] au = pending.poll();
            byte[] data = (byte[]) au[0];
            try {
                ByteBuffer in = codec.getInputBuffer(index);
                if (in == null || in.capacity() < data.length) {
                    SessionLog.i("video input buffer too small for " + data.length + " bytes");
                    codec.queueInputBuffer(index, 0, 0, (long) au[1], 0);
                    waitIdr = true;
                    continue;
                }
                in.clear();
                in.put(data);
                codec.queueInputBuffer(index, 0, data.length, (long) au[1], 0);
                if (inFlight.size() >= 64) inFlight.poll();
                inFlight.add(new long[]{(long) au[1], System.nanoTime()});
            } catch (IllegalStateException e) {
                SessionLog.w("video queue failed", e);
                releaseCodec();
                waitIdr = true;
                return;
            }
        }
    }

    /** Time from queueInputBuffer to the decoded frame, matched by presentation time. */
    private void noteDecoded(long ptsUs) {
        while (!inFlight.isEmpty()) {
            long[] e = inFlight.poll();
            if (e[0] != ptsUs) continue;
            long us = (System.nanoTime() - e[1]) / 1000;
            delaySumUs += us;
            delayCount++;
            if (us > delayMaxUs) delayMaxUs = us;
            return;
        }
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        inFlight.clear();
        freeInputs.clear();
        pending.clear();
        pacer.reset();
        firstFrame = false;
        if (c == null) return;
        try {
            c.stop();
        } catch (IllegalStateException e) {
            SessionLog.w("video codec stop failed", e);
        }
        c.release();
    }

    public synchronized String stats() {
        String delay = delayCount == 0 ? "n/a"
                : (delaySumUs / delayCount / 1000) + "/" + (delayMaxUs / 1000) + "ms";
        delaySumUs = 0;
        delayMaxUs = 0;
        delayCount = 0;
        String s = "video frames=" + frames + " droppedBeforeIdr=" + dropped + " lossEvents=" + corrupted
                + " decodeDelay(avg/max)=" + delay + " pending=" + pending.size()
                + " jitter(arrival/decoded)=" + spreadMs(arrivalMinNs, arrivalMaxNs) + "/" + spreadMs(outputMinNs, outputMaxNs)
                + "ms pacing=" + (paced ? "smooth delay=" + pacer.delayMs() + "ms late=" + pacer.late() : "instant")
                + " uneven=" + pacer.uneven() + " sourceSkips=" + sourceSkips
                + " fps=" + (frameGapUs > 0 ? 1_000_000L / frameGapUs : 0);
        arrivalMinNs = outputMinNs = Long.MAX_VALUE;
        arrivalMaxNs = outputMaxNs = Long.MIN_VALUE;
        return s;
    }

    /** Spread of (arrival time - PTS) over the stats window: how unevenly frames reached this stage. */
    private static String spreadMs(long min, long max) {
        return min > max ? "n/a" : String.valueOf((max - min) / 1_000_000L);
    }

    public synchronized void release() {
        releaseCodec();
        surface = null;
        thread.quitSafely();
    }
}
