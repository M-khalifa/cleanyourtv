package io.github.mkhalifa.miracast.media;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;

import io.github.mkhalifa.miracast.SessionLog;

/** LPCM (byte-swapped) and AAC (MediaCodec) to a low-latency AudioTrack with at most ~100 ms queued. */
public final class AudioRenderer {
    private static final int MAX_LATENCY_MS = 100;
    private static final int MAX_AAC_PENDING = 20;

    private AudioTrack track;
    private int trackRate;
    private int trackChannels;
    private long framesWritten;
    private long droppedChunks;

    private MediaCodec aac;
    private String aacConfig;
    private final HandlerThread aacThread = new HandlerThread("aac-codec");
    private Handler aacHandler;
    private final ArrayDeque<Integer> aacInputs = new ArrayDeque<>();
    private final ArrayDeque<Object[]> aacPending = new ArrayDeque<>();

    public synchronized void onLpcm(byte[] pes, int len) {
        Lpcm h = Lpcm.parse(pes, len);
        if (h == null) {
            droppedChunks++;
            return;
        }
        int bytes = h.frames * 2 * h.channels;
        Lpcm.swap16(pes, Lpcm.HEADER, bytes);
        write(h.sampleRate, h.channels, pes, Lpcm.HEADER, bytes);
    }

    public synchronized void onAac(byte[] pes, int len, long ptsUs) {
        List<Adts.Frame> frames = Adts.split(pes, len);
        if (frames.isEmpty()) {
            droppedChunks++;
            return;
        }
        Adts.Frame first = frames.get(0);
        String config = first.profile + "/" + first.sampleRateIndex + "/" + first.channelConfig;
        if (aac == null || !config.equals(aacConfig)) startAac(first, config);
        if (aac == null) return;
        long t = ptsUs < 0 ? 0 : ptsUs;
        for (Adts.Frame f : frames) {
            if (aacPending.size() >= MAX_AAC_PENDING) {
                aacPending.poll();
                droppedChunks++;
            }
            byte[] raw = new byte[f.length];
            System.arraycopy(pes, f.offset, raw, 0, f.length);
            aacPending.add(new Object[]{raw, t});
            t += 1024L * 1_000_000L / Math.max(1, f.sampleRate());
        }
        feedAac();
    }

    private void startAac(Adts.Frame f, String config) {
        releaseAac();
        try {
            if (aacHandler == null) {
                aacThread.start();
                aacHandler = new Handler(aacThread.getLooper());
            }
            int channels = f.channelConfig == 0 ? 2 : f.channelConfig;
            MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, f.sampleRate(), channels);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(f.audioSpecificConfig()));
            fmt.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            MediaCodec c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            c.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec mc, int index) {
                    synchronized (AudioRenderer.this) {
                        if (mc != aac) return;
                        aacInputs.add(index);
                        feedAac();
                    }
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
                    synchronized (AudioRenderer.this) {
                        if (mc != aac) return;
                        try {
                            ByteBuffer out = mc.getOutputBuffer(index);
                            if (out != null && info.size > 0) {
                                byte[] pcm = new byte[info.size];
                                out.position(info.offset);
                                out.get(pcm, 0, info.size);
                                MediaFormat of = mc.getOutputFormat();
                                write(of.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                        of.getInteger(MediaFormat.KEY_CHANNEL_COUNT), pcm, 0, pcm.length);
                            }
                            mc.releaseOutputBuffer(index, false);
                        } catch (IllegalStateException e) {
                            SessionLog.w("aac output failed", e);
                        }
                    }
                }

                @Override
                public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                    SessionLog.w("aac codec error", e);
                    synchronized (AudioRenderer.this) {
                        if (mc == aac) releaseAac();
                    }
                }

                @Override
                public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                    SessionLog.i("aac output " + format);
                }
            }, aacHandler);
            c.configure(fmt, null, null, 0);
            c.start();
            aac = c;
            aacConfig = config;
            SessionLog.i("aac decoder " + c.getName() + " " + f.sampleRate() + " Hz ch=" + channels);
        } catch (Exception e) {
            SessionLog.w("aac decoder setup failed", e);
            releaseAac();
        }
    }

    private void feedAac() {
        while (aac != null && !aacInputs.isEmpty() && !aacPending.isEmpty()) {
            int index = aacInputs.poll();
            Object[] f = aacPending.poll();
            byte[] raw = (byte[]) f[0];
            try {
                ByteBuffer in = aac.getInputBuffer(index);
                if (in == null) continue;
                in.clear();
                in.put(raw);
                aac.queueInputBuffer(index, 0, raw.length, (long) f[1], 0);
            } catch (IllegalStateException | java.nio.BufferOverflowException e) {
                SessionLog.w("aac queue failed", e);
                releaseAac();
                return;
            }
        }
    }

    private void releaseAac() {
        MediaCodec c = aac;
        aac = null;
        aacConfig = null;
        aacInputs.clear();
        aacPending.clear();
        if (c == null) return;
        try {
            c.stop();
        } catch (IllegalStateException e) {
            SessionLog.w("aac stop failed", e);
        }
        c.release();
    }

    private void write(int rate, int channels, byte[] pcm, int off, int len) {
        if (track == null || rate != trackRate || channels != trackChannels) openTrack(rate, channels);
        if (track == null) return;
        long queued = framesWritten - (track.getPlaybackHeadPosition() & 0xFFFFFFFFL);
        if (queued * 1000L / rate > MAX_LATENCY_MS) {
            droppedChunks++;
            return;
        }
        int n = track.write(pcm, off, len, AudioTrack.WRITE_NON_BLOCKING);
        if (n > 0) framesWritten += n / (2L * channels);
        if (n < len) droppedChunks++;
    }

    private void openTrack(int rate, int channels) {
        releaseTrack();
        try {
            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min * 2, rate * channels * 2 / 5);
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(size)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            t.play();
            track = t;
            trackRate = rate;
            trackChannels = channels;
            framesWritten = 0;
            SessionLog.i("audio track " + rate + " Hz ch=" + channels + " buffer=" + size);
        } catch (RuntimeException e) {
            SessionLog.w("audio track setup failed", e);
            track = null;
        }
    }

    private void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try {
            t.stop();
        } catch (IllegalStateException e) {
            SessionLog.w("audio track stop failed", e);
        }
        t.release();
    }

    public synchronized String stats() {
        return "audio frames=" + framesWritten + " droppedChunks=" + droppedChunks;
    }

    public synchronized void release() {
        releaseAac();
        releaseTrack();
        if (aacHandler != null) aacThread.quitSafely();
    }
}
