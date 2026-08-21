import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F-088 回归：同一热更槽位的提交必须单调。
 *
 * <p>真实的 {@code CNHotUpdateCheck.commitVerifiedPackage} 是私有的，而且依赖
 * SharedPreferences 与 CNHotUpdateTx，宿主 JVM 上跑不起来。所以这里建的是**结构
 * 模型**：把两种锁结构（旧的「只有全局锁」与新的「每槽位锁 + 窗口内重读」）都实现
 * 出来，用同一组时序去跑。
 *
 * <p>模型的价值全在于它<b>能把旧结构跑挂</b>——只证明新结构通过是套套逻辑，说明
 * 不了修复是否必要。源码与模型是否一致由
 * {@code tools/check-hotupdate-monotonic-contract.py} 静态核对。
 */
public class HotUpdateMonotonicTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✅ " + name); }
        else    { fail++; System.out.println("  ❌ " + name); }
    }

    /** 一个热更槽位：版本号 + 活动树里当前是哪个版本的内容。 */
    private static final class Slot {
        final Object lifecycleLock = new Object();
        volatile int version;          // 相当于 SharedPreferences 里的 localVersion
        volatile int treeContent;      // 相当于活动树里实际躺着的内容版本
        /** 记录有没有发生过「用更旧的内容盖掉更新的内容」。 */
        volatile boolean regressed;

        void applyTree(int candidate) {
            if (candidate < treeContent) regressed = true;
            // 模拟 CNHotUpdateTx.apply 的耗时窗口，把交错放大
            try { Thread.sleep(1); } catch (InterruptedException ignore) {}
            treeContent = candidate;
        }
    }

    /** 全局资源提交锁，对应 CNDownloaderFix.extractCommitLock()。 */
    private static final Object GLOBAL_COMMIT_LOCK = new Object();

    /** 旧结构：只有全局锁，进入窗口后不重读版本。这是 F-088 的故障形态。 */
    private static void commitOld(Slot slot, int candidate) {
        int current = slot.version;
        if (candidate < current) return;              // 窗口**外**读一次就完事
        synchronized (GLOBAL_COMMIT_LOCK) {
            slot.applyTree(candidate);
            slot.version = candidate;
        }
    }

    /** 新结构：每槽位锁 + 进入修改窗口后重读。锁序 lifecycle → global。 */
    private static void commitNew(Slot slot, int candidate) {
        synchronized (slot.lifecycleLock) {
            if (candidate < slot.version) return;
            synchronized (GLOBAL_COMMIT_LOCK) {
                if (candidate < slot.version) return;  // 窗口**内**再读一次
                slot.applyTree(candidate);
                slot.version = candidate;
            }
        }
    }

    private interface Committer { void commit(Slot slot, int candidate); }

    /** 确定性时序：手动链先提交 v3，随后旧的启动 continuation 带着 v2 到达。 */
    private static Slot deterministic(Committer c) {
        Slot slot = new Slot();
        slot.version = 1;
        slot.treeContent = 1;
        c.commit(slot, 3);
        c.commit(slot, 2);
        return slot;
    }

    /** 并发时序：两条链同时提交 v2 与 v3，重复多轮。 */
    private static Slot race(final Committer c, int rounds) throws Exception {
        Slot worst = null;
        for (int r = 0; r < rounds; r++) {
            final Slot slot = new Slot();
            slot.version = 1;
            slot.treeContent = 1;
            final CountDownLatch go = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(2);
            Runnable startup = new Runnable() {
                @Override public void run() {
                    try { go.await(); c.commit(slot, 2); }
                    catch (Throwable ignore) {}
                    finally { done.countDown(); }
                }
            };
            Runnable manual = new Runnable() {
                @Override public void run() {
                    try { go.await(); c.commit(slot, 3); }
                    catch (Throwable ignore) {}
                    finally { done.countDown(); }
                }
            };
            new Thread(startup, "startup-" + r).start();
            new Thread(manual, "manual-" + r).start();
            go.countDown();
            done.await(5, TimeUnit.SECONDS);
            boolean bad = slot.version != 3 || slot.treeContent != 3 || slot.regressed;
            if (bad) { worst = slot; break; }
            worst = slot;
        }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        final int ROUNDS = 400;

        System.out.println("[1] 确定性 v3 → 陈旧 v2");
        Slot oldDet = deterministic(new Committer() {
            @Override public void commit(Slot s, int v) { commitOld(s, v); }
        });
        Slot newDet = deterministic(new Committer() {
            @Override public void commit(Slot s, int v) { commitNew(s, v); }
        });
        // 这一条两种结构都挡得住：窗口外那次读就够了。它证明的是「确定性用例
        // 不足以发现 F-088」——真正的差别只在并发下才显出来。
        check("旧结构：确定性序列下 v2 被拒（说明确定性用例发现不了这个 bug）",
                oldDet.version == 3 && oldDet.treeContent == 3);
        check("新结构：确定性序列下 v2 被拒", newDet.version == 3 && newDet.treeContent == 3);

        System.out.println("[2] 并发 v2 / v3，" + ROUNDS + " 轮");
        Slot oldRace = race(new Committer() {
            @Override public void commit(Slot s, int v) { commitOld(s, v); }
        }, ROUNDS);
        boolean oldBroke = oldRace.version != 3 || oldRace.treeContent != 3 || oldRace.regressed;
        check("旧结构：并发下会被降级（模型确实能复现 F-088）", oldBroke);
        if (oldBroke) {
            System.out.println("       旧结构末态 version=" + oldRace.version
                    + " tree=" + oldRace.treeContent + " 发生过内容回退=" + oldRace.regressed);
        }

        Slot newRace = race(new Committer() {
            @Override public void commit(Slot s, int v) { commitNew(s, v); }
        }, ROUNDS);
        check("新结构：并发 " + ROUNDS + " 轮末态版本恒为 3", newRace.version == 3);
        check("新结构：并发 " + ROUNDS + " 轮末态内容恒为 3", newRace.treeContent == 3);
        check("新结构：从未用更旧的内容盖掉更新的内容", !newRace.regressed);

        System.out.println("[3] 锁序");
        // lifecycleLock → GLOBAL 的固定顺序意味着不存在反向等待环。
        // 反序获取（GLOBAL → lifecycle）会死锁，这里用超时把它证出来。
        final Slot slot = new Slot();
        final AtomicInteger finished = new AtomicInteger(0);
        final CountDownLatch bothIn = new CountDownLatch(2);
        Thread a = new Thread(new Runnable() {
            @Override public void run() {
                synchronized (slot.lifecycleLock) {
                    bothIn.countDown();
                    try { bothIn.await(1, TimeUnit.SECONDS); } catch (Throwable ignore) {}
                    synchronized (GLOBAL_COMMIT_LOCK) { finished.incrementAndGet(); }
                }
            }
        }, "order-a");
        Thread b = new Thread(new Runnable() {
            @Override public void run() {
                synchronized (GLOBAL_COMMIT_LOCK) {           // ← 反序，故意的
                    bothIn.countDown();
                    try { bothIn.await(1, TimeUnit.SECONDS); } catch (Throwable ignore) {}
                    synchronized (slot.lifecycleLock) { finished.incrementAndGet(); }
                }
            }
        }, "order-b");
        a.start(); b.start();
        a.join(2000); b.join(2000);
        check("反序获取确实会卡住（所以源码里的锁序必须由守卫钉死）", finished.get() < 2);
        // 卡住的线程是 daemon 之外的普通线程，显式打断免得挂住 JVM 退出
        a.interrupt(); b.interrupt();

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
        System.exit(0);
    }
}
