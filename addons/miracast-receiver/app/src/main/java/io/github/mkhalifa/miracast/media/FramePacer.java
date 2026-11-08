package io.github.mkhalifa.miracast.media;

import java.util.ArrayDeque;

/**
 * Turns source timestamps into render times. The earliest frame seen sets the source-to-TV clock offset;
 * every frame then waits the playout delay, which jumps at once to cover a late frame and otherwise holds
 * the worst lateness of the last WINDOW frames, shrinking gently once that peak leaves the window. Jitter
 * from Windows and the network so becomes a steady delay instead of uneven frame spacing.
 */
public final class FramePacer {
    static final long MAX_DELAY_NS = 150_000_000L;
    /** About 10 s at 30 fps. */
    static final int WINDOW = 300;
    static final long MARGIN_NS = 4_000_000L;
    /** Largest shrink per frame, small enough that the speed-up is not visible. */
    static final long SHRINK_NS = 300_000L;
    /** Lets the offset follow a TV clock that runs slower than the source clock (about 0.15 ms/s at 30 fps). */
    static final long DRIFT_NS = 5_000L;
    static final long RESET_NS = 1_000_000_000L;

    private boolean started;
    private long offsetNs;
    private long delayNs;
    private long frameIndex;
    /** Monotonic queue of {frame index, lateness}: front is the window's peak. */
    private final ArrayDeque<long[]> peaks = new ArrayDeque<>();
    private long lastTargetNs;
    private long lastPtsNs;
    private long late;
    private long uneven;

    /** Render time (System.nanoTime base) for a frame with this PTS that is ready at nowNs. */
    public long target(long ptsUs, long nowNs) {
        long ptsNs = ptsUs * 1000L;
        long lag = nowNs - ptsNs;
        if (!started || lag < offsetNs || lag - offsetNs > RESET_NS) {
            if (started && lag - offsetNs > RESET_NS) {
                delayNs = 0;
                peaks.clear();
            }
            offsetNs = lag;
            started = true;
            lastTargetNs = 0;
        } else {
            offsetNs += DRIFT_NS;
        }
        long lateness = lag - offsetNs;
        frameIndex++;
        while (!peaks.isEmpty() && peaks.peekLast()[1] <= lateness) peaks.pollLast();
        peaks.addLast(new long[]{frameIndex, lateness});
        while (peaks.peekFirst()[0] <= frameIndex - WINDOW) peaks.pollFirst();
        long want = Math.min(peaks.peekFirst()[1] + MARGIN_NS, MAX_DELAY_NS);
        if (want > delayNs) delayNs = want;
        else delayNs = Math.max(want, delayNs - SHRINK_NS);
        long t = ptsNs + offsetNs + delayNs;
        if (t < nowNs) {
            t = nowNs;
            late++;
        }
        note(t, ptsNs);
        return t;
    }

    /** Counts a frame shown at tNs as uneven when it trails the previous one by over 1.5 frame times. */
    public void note(long tNs, long ptsNs) {
        long ptsGap = ptsNs - lastPtsNs;
        if (lastTargetNs != 0 && ptsGap > 0 && ptsGap < RESET_NS && tNs - lastTargetNs > ptsGap * 3 / 2 + 4_000_000L) {
            uneven++;
        }
        lastTargetNs = tNs;
        lastPtsNs = ptsNs;
    }

    public void reset() {
        started = false;
        delayNs = 0;
        peaks.clear();
        lastTargetNs = 0;
    }

    public long delayMs() {
        return delayNs / 1_000_000L;
    }

    public long late() {
        return late;
    }

    public long uneven() {
        return uneven;
    }
}
