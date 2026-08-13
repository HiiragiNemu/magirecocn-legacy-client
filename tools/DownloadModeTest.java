import io.kamihama.magianative.CNDownloadConcurrency;
import io.kamihama.magianative.CNDownloadMode;

import java.io.File;

/**
 * 单线程可靠模式的回归测试。
 *
 * <p>钉住两件最容易悄悄坏掉的事：
 *
 * <ol>
 *   <li><b>{@code cap()} 的语义</b>——四处并发（分片工作线程、字节分段、连接
 *       闸门、并行文件数）共用它。谁在自己那边另写一套「要不要单线程」的判断，
 *       就会出现「明明选了单线程，某一处还开着 8 条连接」，而这种事只有翻日志
 *       才发现得了。</li>
 *   <li><b>连接闸门能真的缩回去再放回来</b>——它是四处里唯一长期存在的对象，
 *       靠 {@code Semaphore.reducePermits} 缩容。缩了放不回来的话，玩家切回
 *       多线程之后速度再也上不来，而且不会有任何报错。</li>
 * </ol>
 *
 * <p>不测的：三层来源的优先级需要真实的调试目录与 config.json，玩家落盘那层
 * 会写应用私有目录——JVM 上这两样都不成立，留给真机验收。
 */
public class DownloadModeTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) {
        // ── [1] cap()：多线程时原样返回 ──────────────────────────────
        // JVM 上三层来源都不成立（没有调试目录、没拉过 config.json、没有落盘
        // 标记），所以默认必然是多线程。
        check("[1a] 默认不是单线程", !CNDownloadMode.singleThread());
        check("[1b] 多线程时 cap 原样返回 8", CNDownloadMode.cap(8) == 8);
        check("[1c] 多线程时 cap 原样返回 16", CNDownloadMode.cap(16) == 16);
        check("[1d] 多线程时 cap 原样返回 3", CNDownloadMode.cap(3) == 3);
        check("[1e] 强制层默认关", !CNDownloadMode.forcedOn());
        check("[1f] 玩家层默认关", !CNDownloadMode.playerWants());

        String desc = CNDownloadMode.describe();
        check("[1g] describe 非空且说的是多线程",
                desc != null && desc.contains("多线程"));

        // ── [2] 连接闸门可缩可放 ────────────────────────────────────
        int max = CNDownloadConcurrency.maxConnections();
        check("[2a] 名义上限 8", max == 8);
        check("[2b] 初始生效上限 = 名义上限",
                CNDownloadConcurrency.currentCap() == max);

        CNDownloadConcurrency.setCap(1);
        check("[2c] 能收到 1", CNDownloadConcurrency.currentCap() == 1);

        // 收到 1 之后真的只发得出一个许可——只验「第二个拿不到」，不能真去
        // 阻塞等待，那会把测试挂死。
        boolean onlyOne = false;
        try {
            CNDownloadConcurrency.Lease a = CNDownloadConcurrency.acquire("t1");
            Thread probe = new Thread(new Grab());
            grabbed = false;
            probe.setDaemon(true);
            probe.start();
            probe.join(600L);          // 拿不到就会一直等，join 超时即符合预期
            onlyOne = !grabbed;
            a.close();
            probe.interrupt();
        } catch (Throwable t) {
            System.out.println("      " + t);
        }
        check("[2d] 上限 1 时第二条连接拿不到许可", onlyOne);

        CNDownloadConcurrency.setCap(max);
        check("[2e] 能放回 8", CNDownloadConcurrency.currentCap() == max);

        // 放回去之后必须真的能同时拿到多个——只改计数不还许可的话，这里会挂
        boolean multiOk = true;
        CNDownloadConcurrency.Lease[] held = new CNDownloadConcurrency.Lease[max];
        try {
            for (int i = 0; i < max; i++) held[i] = CNDownloadConcurrency.acquire("m" + i);
        } catch (Throwable t) {
            multiOk = false;
            System.out.println("      " + t);
        } finally {
            for (int i = 0; i < max; i++) if (held[i] != null) held[i].close();
        }
        check("[2f] 放回后能同时拿满 8 个许可", multiOk);

        // 越界一律夹住，别让某处传个 0 把整条下载卡死
        CNDownloadConcurrency.setCap(0);
        check("[2g] 传 0 夹到 1", CNDownloadConcurrency.currentCap() == 1);
        CNDownloadConcurrency.setCap(999);
        check("[2h] 传超大夹到名义上限",
                CNDownloadConcurrency.currentCap() == max);
        CNDownloadConcurrency.setCap(max);   // 复位，别影响后面的套件

        // ── [3] 优先级与「不落盘」（2026-08-13 维护者要求）─────────────
        //
        // 玩家的选择压过云端。求或的写法在这里是错的：云端一开，玩家就再也关不掉，
        // 而坐在那台设备前面的是他。所以「没表态」与「选了多线程」必须分得开——
        // 合成一个 boolean 就没法表达前者。
        CNDownloadMode.resetCacheForTest();
        check("[3a] 新进程起手是「还没表态」", !CNDownloadMode.playerDecided()
                && !CNDownloadMode.playerWants());

        CNDownloadMode.setPlayerChoice(true);
        check("[3b] 选了单线程即生效", CNDownloadMode.playerDecided()
                && CNDownloadMode.playerWants() && CNDownloadMode.singleThread()
                && CNDownloadMode.cap(8) == 1);

        CNDownloadMode.setPlayerChoice(false);
        check("[3c] 选回多线程也算表过态（不是退回未表态）",
                CNDownloadMode.playerDecided() && !CNDownloadMode.playerWants());
        check("[3d] 选了多线程就不再单线程", !CNDownloadMode.singleThread()
                && CNDownloadMode.cap(8) == 8);

        // 云端那层在 JVM 上取不到（CNMirrors 未加载配置），恒为 false；这里能钉的是
        // 「玩家表过态时压根不去问云端」这条短路——它由 singleThread() 的顺序保证。
        check("[3e] 强制层只剩调试开关，云端不再算强制",
                !CNDownloadMode.forcedOn());

        // 不落盘：本次选择不该留到下次启动。resetCacheForTest 模拟的就是重启，
        // 若还有文件兜底，这里会读回 true——「一次慢次次慢」正是这么来的。
        CNDownloadMode.setPlayerChoice(true);
        CNDownloadMode.resetCacheForTest();
        check("[3f] 重启后回到未表态（玩家选择不落盘）",
                !CNDownloadMode.playerDecided() && !CNDownloadMode.playerWants());
        CNDownloadMode.applyNow();   // 复位闸门，别影响后面的套件

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static volatile boolean grabbed;

    /** static 嵌套类：匿名/非静态内部类带 this$0，d8 撞上直接 NPE。 */
    private static final class Grab implements Runnable {
        @Override public void run() {
            try {
                CNDownloadConcurrency.Lease l = CNDownloadConcurrency.acquire("t2");
                grabbed = true;
                l.close();
            } catch (Throwable ignore) {}
        }
    }
}
