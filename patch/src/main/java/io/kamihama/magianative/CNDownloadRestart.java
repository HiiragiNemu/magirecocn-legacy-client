package io.kamihama.magianative;

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

    /**
     * generation 与 reason 必须作为一个不可变状态原子发布（F-087）。
     *
     * <p>旧实现分别写 KEEP_OFFLINE[index] 与 GENERATION[index]。两个不同原因的请求
     * 并发时可以让 generation 来自请求 A、reason 来自请求 B，消费侧无法把状态
     * 线性化到任何一次真实请求。AtomicReferenceArray 上的 CAS 是每槽位唯一发布点：
     * 谁赢得 CAS，谁的 generation/reason 就一起成为该槽位的最新请求。</p>
     */
    private static final AtomicReferenceArray<RequestState> REQUESTS =
            new AtomicReferenceArray<RequestState>(COUNT);
    private static final AtomicReferenceArray<Thread> ACTIVE =
            new AtomicReferenceArray<Thread>(COUNT);

    static {
        for (int i = 0; i < COUNT; i++) {
            REQUESTS.set(i, new RequestState(0, false));
        }
    }

    private static final class RequestState {
        final int generation;
        final boolean keepOffline;

        RequestState(int generation, boolean keepOffline) {
            this.generation = generation;
            this.keepOffline = keepOffline;
        }
    }

    private CNDownloadRestart() {}

    static int generation(int index) {
        return valid(index) ? REQUESTS.get(index).generation : 0;
    }

    /**
     * 把当前线程登记为 {@code index} 的「活动下载/解压线程」。
     *
     * <p>F-054 契约：对走 {@code ARCHIVE_LOCKS} 的路径，必须在**拿到锁之后**、
     * 做实际工作之前调用——ACTIVE[index] 恒为持锁者，等在锁外的第二个线程不会
     * 覆盖它，{@link #request(int)} 打断的才是真正持有文件/网络连接的线程。
     * 配合 {@link #unregister(int)}（同一临界区的 finally 里）即可。</p>
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
     * 上层的取消语义抹了。</p>
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
        return request(index, false);
    }

    /** installOfflineNow 专用变体：中止在传下载，但保留同名离线候选。 */
    static boolean request(int index, boolean keepOffline) {
        if (!valid(index)) return false;
        publish(index, keepOffline);
        Thread t = ACTIVE.get(index);
        if (t != null) {
            t.interrupt();
            return true;
        }
        return false;
    }

    /**
     * 当前最新重启请求是否要求保留同名离线候选。
     *
     * <p>返回值与 {@link #generation(int)} 来自同一个不可变 RequestState。并发请求按
     * CAS 顺序线性化，后一个请求完整覆盖前一个请求的 generation/reason，而不是只
     * 覆盖其中一个字段。</p>
     */
    static boolean keepOfflineRequested(int index) {
        return valid(index) && REQUESTS.get(index).keepOffline;
    }

    static boolean changed(int index, int token) {
        return valid(index) && REQUESTS.get(index).generation != token;
    }

    static boolean cancelled(int index, int token) {
        return Thread.currentThread().isInterrupted() || changed(index, token);
    }

    static void clearInterrupt() {
        Thread.interrupted();
    }

    private static RequestState publish(int index, boolean keepOffline) {
        while (true) {
            RequestState old = REQUESTS.get(index);
            RequestState next = new RequestState(old.generation + 1, keepOffline);
            if (REQUESTS.compareAndSet(index, old, next)) return next;
        }
    }

    // ---- JVM 合同测试入口 ----
    static int publishForTest(int index, boolean keepOffline) {
        if (!valid(index)) return 0;
        return publish(index, keepOffline).generation;
    }

    static void resetForTest(int index) {
        if (!valid(index)) return;
        ACTIVE.set(index, null);
        REQUESTS.set(index, new RequestState(0, false));
        Thread.interrupted();
    }

    private static boolean valid(int index) {
        return index >= 0 && index < COUNT;
    }
}
