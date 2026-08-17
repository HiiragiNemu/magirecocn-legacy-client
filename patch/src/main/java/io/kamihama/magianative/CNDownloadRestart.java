package io.kamihama.magianative;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Per-file restart generation and active-thread registry.
 *
 * <p>A restart request never starts a second writer for the same archive. It advances the
 * generation and interrupts the current worker. The existing installer/manual task observes
 * the stale generation, discards only that file's download state, and starts the same file
 * again from byte zero. Other files keep running.</p>
 */
final class CNDownloadRestart {
    private static final int COUNT = 15;
    private static final AtomicIntegerArray GENERATION = new AtomicIntegerArray(COUNT);
    private static final AtomicReferenceArray<Thread> ACTIVE =
            new AtomicReferenceArray<Thread>(COUNT);
    /**
     * 与 GENERATION 平行的「本次重启请保留同名离线候选」标志（object-storage-01/02）。
     *
     * <p>manual-restart 分支有两个触发源，清理语义相反：紫色「重下」
     * （CNManualRedownload）要删掉离线候选—— FORCE_REDOWNLOAD 之外的最后
     * 一道「不许捡离线包」保障；installOfflineNow（离线即时安装）必须保留
     * ——它就是奔着这个包来的，删了等锁后必然报「离线包已消失」、导入
     * 作废且白下一遍。请求时覆盖写（含复位 false），消费时只读，无残留。
     */
    private static final AtomicIntegerArray KEEP_OFFLINE = new AtomicIntegerArray(COUNT);

    private CNDownloadRestart() {}

    static int generation(int index) {
        return valid(index) ? GENERATION.get(index) : 0;
    }

    static void register(int index) {
        if (valid(index)) ACTIVE.set(index, Thread.currentThread());
    }

    static void unregister(int index) {
        if (!valid(index)) return;
        Thread current = Thread.currentThread();
        ACTIVE.compareAndSet(index, current, null);
        // A restart deliberately interrupts the worker. Do not leak that flag into the pool.
        Thread.interrupted();
    }

    static boolean request(int index) {
        if (!valid(index)) return false;
        KEEP_OFFLINE.set(index, 0);   // 默认「重下」语义：不保留离线候选
        GENERATION.incrementAndGet(index);
        Thread t = ACTIVE.get(index);
        if (t != null) {
            t.interrupt();
            return true;
        }
        return false;
    }

    /** installOfflineNow 专用变体：中止在传下载，但保留同名离线候选。 */
    static boolean request(int index, boolean keepOffline) {
        if (!valid(index)) return false;
        KEEP_OFFLINE.set(index, keepOffline ? 1 : 0);
        GENERATION.incrementAndGet(index);
        Thread t = ACTIVE.get(index);
        if (t != null) {
            t.interrupt();
            return true;
        }
        return false;
    }

    /** 当前登记的重启请求是否要求保留离线候选（消费侧只读）。 */
    static boolean keepOfflineRequested(int index) {
        return valid(index) && KEEP_OFFLINE.get(index) != 0;
    }

    static boolean changed(int index, int token) {
        return valid(index) && GENERATION.get(index) != token;
    }

    static boolean cancelled(int index, int token) {
        return Thread.currentThread().isInterrupted() || changed(index, token);
    }

    static void clearInterrupt() {
        Thread.interrupted();
    }

    private static boolean valid(int index) {
        return index >= 0 && index < COUNT;
    }
}
