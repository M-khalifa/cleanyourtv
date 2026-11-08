package io.github.mkhalifa.miracast.wfd;

/** Rate limit for IDR requests that keeps a request made too soon and releases it once the interval has passed. */
public final class IdrGate {
    private final long minIntervalMs;
    private long last = Long.MIN_VALUE / 2;
    private boolean pending;

    public IdrGate(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    /** True when the request may be sent now; otherwise it is kept for {@link #due}. */
    public synchronized boolean request(long nowMs) {
        if (nowMs - last < minIntervalMs) {
            pending = true;
            return false;
        }
        last = nowMs;
        pending = false;
        return true;
    }

    /** True once for a kept request whose interval has passed. */
    public synchronized boolean due(long nowMs) {
        if (!pending || nowMs - last < minIntervalMs) return false;
        last = nowMs;
        pending = false;
        return true;
    }
}
