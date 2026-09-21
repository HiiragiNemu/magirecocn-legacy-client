package io.kamihama.magianative;

/** A low transfer rate is advice, never a cancellation or a request to discard bytes. */
public final class CNDownloadSlowNotice {
    private final long minimumBps;
    private final long waitNs;
    private long slowSinceNs = -1L;
    private boolean notified;

    public CNDownloadSlowNotice(long minimumBps, long waitNs) {
        this.minimumBps = Math.max(0L, minimumBps);
        this.waitNs = Math.max(0L, waitNs);
    }

    /** Once per transfer. Zero data is handled separately by the existing stall timeout. */
    public boolean observe(long bytes, long elapsedNs, long nowNs) {
        if (notified || minimumBps == 0 || bytes <= 0 || elapsedNs <= 0) return false;
        double bps = bytes * 1.0E9d / elapsedNs;
        if (bps >= minimumBps) {
            slowSinceNs = -1L;
            return false;
        }
        if (slowSinceNs == -1L) slowSinceNs = nowNs;
        if (nowNs - slowSinceNs < waitNs) return false;
        notified = true;
        return true;
    }
}
