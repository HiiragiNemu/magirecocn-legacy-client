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

    /**
     * 可缩放的信号量。{@link Semaphore#reducePermits} 是 protected 的，只能这么拿。
     *
     * <p>缩容时若许可正被占用，permit 计数会变成负数——这是 {@code reducePermits}
     * 的既定行为，也正是这里要的：<b>在传的连接不会被掐断</b>，但它们释放之后
     * 不再发出去，于是并发是逐步收敛到新上限的，而不是把正在传的东西砍掉。
     */
    private static final class Gate extends Semaphore {
        Gate(int permits) { super(permits, true); }
        void shrink(int n) { reducePermits(n); }
    }

    private static final Gate PERMITS = new Gate(MAX_CONNECTIONS);
    /** 当前上限；单线程模式下由 {@link CNDownloadMode#applyNow()} 收到 1。 */
    private static volatile int cap = MAX_CONNECTIONS;
    private static final AtomicInteger ACTIVE = new AtomicInteger(0);
    private static final AtomicInteger PEAK = new AtomicInteger(0);
    private static final AtomicInteger WAITERS = new AtomicInteger(0);

    private CNDownloadConcurrency() {}

    /** 正常（非单线程）上限。 */
    public static int maxConnections() { return MAX_CONNECTIONS; }

    /** 当前生效的上限。 */
    public static int currentCap() { return cap; }

    /**
     * 改上限。夹到 {@code [1, MAX_CONNECTIONS]}；与当前一致时什么都不做。
     *
     * <p>由 {@link CNDownloadMode#applyNow()} 调用——这个信号量是四处并发里唯一
     * <b>长期存在</b>的那个（其余三处都是每次下载开始时现读），所以模式切换必须
     * 显式下发到这里，否则玩家点完「改用单线程」，连接数还是 8。
     */
    public static synchronized void setCap(int wanted) {
        int target = Math.max(1, Math.min(MAX_CONNECTIONS, wanted));
        if (target == cap) return;
        if (target < cap) PERMITS.shrink(cap - target);
        else PERMITS.release(target - cap);
        CNLog.i("MagiaCNChunk", "全局连接上限 " + cap + " → " + target
                + "（" + CNDownloadMode.describe() + "）");
        cap = target;
    }

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
                    + " active=" + now + "/" + cap);
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
