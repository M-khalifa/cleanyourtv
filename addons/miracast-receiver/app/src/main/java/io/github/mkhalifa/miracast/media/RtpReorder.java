package io.github.mkhalifa.miracast.media;

import java.util.HashMap;
import java.util.Map;

/** Small RTP reorder window: emits payloads in sequence order and reports gaps it gives up on. */
public final class RtpReorder {
    public interface Sink {
        void onPayload(byte[] data, int off, int len);

        void onLoss(int packets);
    }

    private static final int RESET_DISTANCE = 3000;
    private final int window;
    private final Sink sink;
    private final Map<Integer, byte[]> held = new HashMap<>();
    private int expected = -1;
    private long lost;
    private long late;

    public RtpReorder(int window, Sink sink) {
        this.window = window;
        this.sink = sink;
    }

    public long lost() {
        return lost;
    }

    public long late() {
        return late;
    }

    /** True when nothing is held back waiting for a missing packet. */
    public boolean idle() {
        return held.isEmpty();
    }

    /** Signed distance a - b on the 16-bit sequence circle. */
    static int diff(int a, int b) {
        int d = (a - b) & 0xFFFF;
        return d >= 0x8000 ? d - 0x10000 : d;
    }

    public void push(int seq, byte[] data, int off, int len) {
        if (expected < 0) expected = seq;
        int d = diff(seq, expected);
        if (Math.abs(d) > RESET_DISTANCE) {
            held.clear();
            expected = seq;
            d = 0;
        }
        if (d < 0 || held.containsKey(seq)) {
            late++;
            return;
        }
        if (d == 0) {
            sink.onPayload(data, off, len);
            expected = (expected + 1) & 0xFFFF;
            drain();
            return;
        }
        byte[] copy = new byte[len];
        System.arraycopy(data, off, copy, 0, len);
        held.put(seq, copy);
        if (held.size() > window) {
            int gap = Integer.MAX_VALUE;
            for (int s : held.keySet()) gap = Math.min(gap, diff(s, expected));
            lost += gap;
            sink.onLoss(gap);
            expected = (expected + gap) & 0xFFFF;
            drain();
        }
    }

    /** Gives up on the current gap when nothing new arrived for a while; true when something was released. */
    public boolean flush() {
        if (held.isEmpty()) return false;
        int gap = Integer.MAX_VALUE;
        for (int s : held.keySet()) gap = Math.min(gap, diff(s, expected));
        lost += gap;
        sink.onLoss(gap);
        expected = (expected + gap) & 0xFFFF;
        drain();
        return true;
    }

    private void drain() {
        byte[] b;
        while ((b = held.remove(expected)) != null) {
            sink.onPayload(b, 0, b.length);
            expected = (expected + 1) & 0xFFFF;
        }
    }
}
