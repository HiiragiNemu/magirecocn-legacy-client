package io.kamihama.magianative;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 所有 Java 下载器共享的网络连接闸门。
 *
 * <p>允许多个 ZIP 同时推进，但长连接总数始终不超过 8。等待许可不算线路停滞：
 * 调用方可传入 heartbeat，排队期间每秒刷新一次，避免另一个文件占满连接时被
 * 当前文件的停滞看门狗误杀。
 */
public final class CNDownloadConcurrency {
    private static final int MAX_CONNECTIONS = 8;
    private static final Semaphore PERMITS = new Semaphore(MAX_CONNECTIONS, true);
    private static final AtomicInteger ACTIVE = new AtomicInteger(0);
    private static final AtomicInteger PEAK = new AtomicInteger(0);
    private static final AtomicInteger WAITERS = new AtomicInteger(0);

    private CNDownloadConcurrency() {}

    public static Lease acquire(String label, AtomicLong heartbeat) throws IOException {
        long started = System.nanoTime();
        WAITERS.incrementAndGet();
        boolean acquired = false;
        try {
            while (!acquired) {
                try {
                    acquired = PERMITS.tryAcquire(1L, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待全局下载连接时被中断: " + label, e);
                }
                if (!acquired && heartbeat != null) heartbeat.set(System.nanoTime());
            }
        } finally {
            WAITERS.decrementAndGet();
        }
        int now = ACTIVE.incrementAndGet();
        updatePeak(now);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (waitedMs >= 1000L) {
            CNLog.i("MagiaCNChunk", "全局连接排队 " + waitedMs + "ms label=" + label
                    + " active=" + now + "/" + MAX_CONNECTIONS);
        }
        return new Lease(label);
    }

    public static Lease acquire(String label) throws IOException {
        return acquire(label, null);
    }

    private static void updatePeak(int value) {
        while (true) {
            int old = PEAK.get();
            if (value <= old || PEAK.compareAndSet(old, value)) return;
        }
    }

    public static boolean hasQueuedWaiters() {
        return WAITERS.get() > 0 || PERMITS.hasQueuedThreads();
    }

    public static int maxConnectionsForTest() { return MAX_CONNECTIONS; }
    public static int activeForTest() { return ACTIVE.get(); }
    public static int peakForTest() { return PEAK.get(); }
    public static int waitingForTest() { return WAITERS.get(); }
    public static void resetPeakForTest() { PEAK.set(ACTIVE.get()); }

    public static final class Lease implements java.io.Closeable {
        private final String label;
        private boolean closed;

        private Lease(String label) { this.label = label == null ? "" : label; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            int now = ACTIVE.decrementAndGet();
            if (now < 0) {
                ACTIVE.set(0);
                CNLog.w("MagiaCNChunk", "全局连接计数下溢 label=" + label);
            }
            PERMITS.release();
        }
    }
}
