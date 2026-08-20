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

    /**
     * request / register / acknowledge / unregister 的每槽位线性化锁（F-086）。
     *
     * <p>只把极短的内存状态操作放进来；网络、文件、archive lock 都不在这里做。
     * request 在锁内先发布请求再读取 owner，unregister 在同一把锁里清 owner 并判断
     * 是否还有未确认代际，因此不存在“request 已返回 true，但 owner 刚好注销而没
     * 看见它”的缝。</p>
     */
    private static final Object[] SLOT_LOCKS = new Object[COUNT];
    private static final AtomicReferenceArray<OwnerState> ACTIVE =
            new AtomicReferenceArray<OwnerState>(COUNT);

    /**
     * 已退出 owner 留下、需要补排的普通重下代际。0 表示没有待补排请求。
     * 一个槽位最多一个守护线程；新代际覆盖旧代际，线程会循环到最新值清空。
     */
    private static final AtomicIntegerArray REQUEUE_GENERATION =
            new AtomicIntegerArray(COUNT);
    private static final AtomicIntegerArray REQUEUE_WORKER =
            new AtomicIntegerArray(COUNT);

    static {
        for (int i = 0; i < COUNT; i++) {
            REQUESTS.set(i, new RequestState(0, false));
            SLOT_LOCKS[i] = new Object();
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

    private static final class OwnerState {
        final Thread thread;
        int acknowledgedGeneration;

        OwnerState(Thread thread, int acknowledgedGeneration) {
            this.thread = thread;
            this.acknowledgedGeneration = acknowledgedGeneration;
        }
    }

    /** owner 注销时返回的尚未确认请求快照；包内测试可直接检查。 */
    static final class PendingRequest {
        final int generation;
        final boolean keepOffline;

        PendingRequest(int generation, boolean keepOffline) {
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
        if (!valid(index)) return;
        synchronized (SLOT_LOCKS[index]) {
            RequestState request = REQUESTS.get(index);
            ACTIVE.set(index, new OwnerState(
                    Thread.currentThread(), request.generation));
        }
    }

    /**
     * 注销当前线程的 owner 身份。
     *
     * <p>F-084：必须在<b>同一个 archive-lock 临界区内</b>调用（与 {@link #register}
     * 成对）。F-086 再加一层：注销与“有没有未确认请求”的判断在 slot lock 内原子
     * 完成。若普通重下命中了退避 sleep、工作函数收尾或其它来不及消费的窗口，owner
     * 退出后自动补排一个独立手动任务；离线接管不补排，因为发起它的
     * {@code installOfflineNow} 正在 archive lock 外等待接手。</p>
     */
    static void unregister(int index) {
        PendingRequest pending = finishOwner(index);
        if (pending != null && !pending.keepOffline) {
            schedulePendingRestart(index, pending.generation);
        }
    }

    static boolean request(int index) {
        return request(index, false);
    }

    /** installOfflineNow 专用变体：中止在传下载，但保留同名离线候选。 */
    static boolean request(int index, boolean keepOffline) {
        if (!valid(index)) return false;
        Thread target = null;
        synchronized (SLOT_LOCKS[index]) {
            publish(index, keepOffline);
            OwnerState owner = ACTIVE.get(index);
            if (owner != null) target = owner.thread;
        }
        if (target != null) {
            target.interrupt();
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

    /**
     * 当前 owner 已经识别并接管最新 restart generation。
     *
     * <p>现有下载状态机只在真正走“清断点并重来 / 让位离线安装”的分支调用本方法，
     * 所以这里顺便把 owner 的 acknowledgedGeneration 推进到最新请求。普通 shutdown
     * 中断不会调用它，owner 注销时仍按普通取消收尾。</p>
     */
    static void clearInterrupt() {
        Thread current = Thread.currentThread();
        for (int i = 0; i < COUNT; i++) {
            synchronized (SLOT_LOCKS[i]) {
                OwnerState owner = ACTIVE.get(i);
                if (owner != null && owner.thread == current) {
                    owner.acknowledgedGeneration = REQUESTS.get(i).generation;
                    break;
                }
            }
        }
        Thread.interrupted();
    }

    private static PendingRequest finishOwner(int index) {
        if (!valid(index)) return null;
        PendingRequest pending = null;
        boolean ownerMatched = false;
        Thread current = Thread.currentThread();
        synchronized (SLOT_LOCKS[index]) {
            OwnerState owner = ACTIVE.get(index);
            if (owner != null && owner.thread == current) {
                ownerMatched = true;
                RequestState latest = REQUESTS.get(index);
                if (latest.generation != owner.acknowledgedGeneration) {
                    pending = new PendingRequest(
                            latest.generation, latest.keepOffline);
                }
                ACTIVE.set(index, null);
            }
        }
        if (ownerMatched) {
            // restart 用 interrupt 唤醒 owner；无论已消费还是交给补排线程，都不能
            // 把中断位泄漏回线程池。
            Thread.interrupted();
        } else {
            CNLog.w(TAG, "unregister 时已不是登记 owner，保留中断位 index=" + index);
        }
        return pending;
    }

    private static void schedulePendingRestart(final int index, int generation) {
        REQUEUE_GENERATION.set(index, generation);
        if (!REQUEUE_WORKER.compareAndSet(index, 0, 1)) return;

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                runPendingRestartQueue(index);
            }
        }, "cnv-restart-requeue-" + index);
        t.setDaemon(true);
        try {
            t.start();
        } catch (Throwable startFailure) {
            REQUEUE_WORKER.set(index, 0);
            CNLog.e(TAG, "无法启动重下补排线程 index=" + index, startFailure);
        }
    }

    private static void runPendingRestartQueue(final int index) {
        try {
            while (true) {
                int target = REQUEUE_GENERATION.get(index);
                if (target == 0) return;

                // 当前 owner 若本来就是 ManualTask，RUNNING 要到其 finally 才归零；
                // 先等它完整收尾，否则 request() 只会命中同文件去重并把补排吃掉。
                while (CNManualRedownload.isRunning(index)) {
                    try {
                        Thread.sleep(25L);
                    } catch (InterruptedException e) {
                        // 补排线程没有取消语义；清位后继续等，不能把已确认的玩家请求丢掉。
                        Thread.interrupted();
                    }
                }

                CNLog.w(TAG, "owner 未消费重下请求，自动补排 index=" + index
                        + " generation=" + target);
                CNManualRedownload.request(null, index);
                REQUEUE_GENERATION.compareAndSet(index, target, 0);

                // 调用 request 期间若又有更新代际写入，CAS 不会清掉它，下一轮继续。
                if (REQUEUE_GENERATION.get(index) == 0) return;
            }
        } finally {
            REQUEUE_WORKER.set(index, 0);
            // 与退出并发的新请求可能在看到 worker=1 后只更新了 generation；双检并接棒。
            if (REQUEUE_GENERATION.get(index) != 0
                    && REQUEUE_WORKER.compareAndSet(index, 0, 1)) {
                Thread next = new Thread(new Runnable() {
                    @Override public void run() {
                        runPendingRestartQueue(index);
                    }
                }, "cnv-restart-requeue-" + index);
                next.setDaemon(true);
                try {
                    next.start();
                } catch (Throwable startFailure) {
                    REQUEUE_WORKER.set(index, 0);
                    CNLog.e(TAG, "无法接棒重下补排线程 index=" + index, startFailure);
                }
            }
        }
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
        synchronized (SLOT_LOCKS[index]) {
            return publish(index, keepOffline).generation;
        }
    }

    static PendingRequest unregisterForTest(int index) {
        return finishOwner(index);
    }

    static void resetForTest(int index) {
        if (!valid(index)) return;
        synchronized (SLOT_LOCKS[index]) {
            ACTIVE.set(index, null);
            REQUESTS.set(index, new RequestState(0, false));
        }
        REQUEUE_GENERATION.set(index, 0);
        REQUEUE_WORKER.set(index, 0);
        Thread.interrupted();
    }

    private static boolean valid(int index) {
        return index >= 0 && index < COUNT;
    }
}
