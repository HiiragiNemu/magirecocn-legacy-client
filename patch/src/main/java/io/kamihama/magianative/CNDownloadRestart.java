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
    private static final String TAG = "CNDownloadRestart";
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

    /**
     * 把当前线程登记为 {@code index} 的「活动下载/解压线程」。
     *
     * <p>F-054 契约：对走 {@code ARCHIVE_LOCKS} 的路径，必须在**拿到锁之后**、
     * 做实际工作之前调用——ACTIVE[index] 恒为持锁者，等在锁外的第二个线程不会
     * 覆盖它，{@link #request(int)} 打断的才是真正持有文件/网络连接的线程。
     * 配合 {@link #unregister(int)}（finally 里，释放锁后）即可。
     */
    static void register(int index) {
        if (valid(index)) ACTIVE.set(index, Thread.currentThread());
    }

    /**
     * 注销当前线程的 owner 身份。
     *
     * <p>F-084：必须在<b>同一个 archive-lock 临界区内</b>调用（与 {@link #register}
     * 成对）。放在锁外的话会出现「锁已经放掉、ACTIVE 还指着旧 worker」的窗口：
     * 请求线程此时 generation++ 并 interrupt 那个已经收工的线程，返回 true 告诉
     * 玩家「已停止当前传输」，而真正接手的新 owner 一开始读到的就是<b>加过</b>的
     * generation，永远观察不到变化——那次重下请求就此蒸发。
     *
     * <p>只有 CAS 成功（确认自己确实是登记在案的 owner）才清中断位。中断是重启
     * 请求<b>特意</b>打上的，清掉它是 owner 的职责；非 owner 顺手清掉，等于把
     * 上层的取消语义抹了。
     */
    static void unregister(int index) {
        if (!valid(index)) return;
        Thread current = Thread.currentThread();
        if (ACTIVE.compareAndSet(index, current, null)) {
            // A restart deliberately interrupts the worker. Do not leak that flag into the pool.
            Thread.interrupted();
            return;
        }
        // 走到这里说明 ACTIVE 已经不是自己了。不清中断位，但要说出来——正常路径
        // 不该发生，发生了就是 register/unregister 又跑到临界区外面去了。
        CNLog.w(TAG, "unregister 时已不是登记 owner，保留中断位 index=" + index);
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
