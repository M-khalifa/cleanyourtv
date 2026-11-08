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

/** H.264 access units to a MediaCodec on a Surface, rendered as soon as each frame is decoded. */
public final class VideoDecoder {
    public interface Callback {
        void onNeedIdr(String why);

        void onVideoSize(int width, int height);

        void onFirstFrame();
    }

    private static final int MAX_PENDING = 30;
    private final Callback cb;
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
        this.cb = cb;
        thread.start();
        handler = new Handler(thread.getLooper());
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
        for (int[] n : H264.nals(au, len)) {
            int t = au[n[0]] & 0x1F;
            if (t == H264.NAL_IDR) idr = true;
            if (t == H264.NAL_SPS) sps = H264.extract(au, len, H264.NAL_SPS);
            if (t == H264.NAL_PPS) pps = H264.extract(au, len, H264.NAL_PPS);
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

    /** Called after packet loss: keep decoding (the decoder conceals the damage) until the requested IDR heals it. */
    public synchronized void onCorruption() {
        corrupted++;
    }

    private boolean createCodec() {
        try {
            MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            f.setInteger(MediaFormat.KEY_MAX_WIDTH, 1920);
            f.setInteger(MediaFormat.KEY_MAX_HEIGHT, 1200);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            MediaCodec c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
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
                            mc.releaseOutputBuffer(index, info.size > 0);
                        } catch (IllegalStateException e) {
                            SessionLog.w("video release failed", e);
                            return;
                        }
                        frames++;
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
            SessionLog.i("video decoder " + c.getName() + " lowLatency=" + lowLatency);
            return true;
        } catch (Exception e) {
            SessionLog.w("video decoder setup failed", e);
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
            } catch (IllegalStateException e) {
                SessionLog.w("video queue failed", e);
                releaseCodec();
                waitIdr = true;
                return;
            }
        }
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        freeInputs.clear();
        pending.clear();
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
        return "video frames=" + frames + " droppedBeforeIdr=" + dropped + " lossEvents=" + corrupted;
    }

    public synchronized void release() {
        releaseCodec();
        surface = null;
        thread.quitSafely();
    }
}
