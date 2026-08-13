package io.kamihama.magianative;

import java.io.File;

/**
 * 下载引擎的<b>单线程可靠模式</b>开关。
 *
 * <h3>它是什么</h3>
 *
 * 主引擎默认是「多线程分片 + 全局 8 连接」。这在正常网络上快得多，但在某些
 * 环境里恰恰是失败的原因：运营商对同一目的地的并发连接下手、老旧路由器 NAT
 * 表被打满、公共 Wi-Fi 限流、以及一部分 CDN 节点对多连接 Range 请求的行为
 * 与单连接不一致。这类环境下「重试」毫无用处——每次都以同样的方式失败。
 *
 * <p>单线程模式把四处并发全部压到 1：分片工作线程、字节分段数、全局连接闸门、
 * 并行文件数。慢，但**能下完**。
 *
 * <h3>三层来源，优先级从高到低</h3>
 *
 * <ol>
 *   <li><b>调试开关</b> {@link CNDebugFlags#USE_SINGLE_THREAD}——排查时强制打开，
 *       玩家在弹窗里关不掉。与 {@code useAria2} 同一类（{@code useXxx}：可选
 *       引擎），只换我们自己的下载路径，不碰任何安全判定。</li>
 *   <li><b>云端</b> {@code settings.force_single_thread}——某条 CDN 对所有人都
 *       炸了的时候，服务端一改所有人生效，不必等发版。与 {@code force_aria2}
 *       同一个位置、同一套语义。</li>
 *   <li><b>玩家自己选的</b>——下载失败弹窗里点「改用单线程下载」，落盘持久化。</li>
 * </ol>
 *
 * <p>前两层是<b>强制打开</b>，不是「默认值」：它们在时玩家关不掉，因为那两层
 * 存在的场合恰恰是「我们知道多线程在这里不行」。
 *
 * <h3>为什么用标记文件而不是 SharedPreferences</h3>
 *
 * 下载线程由 {@code Application.onCreate} 拉起，那时 Activity 未必已经建出来，
 * 而 {@code getSharedPreferences} 要 Context。{@link CNPaths} 的解析器本来就
 * 不依赖 Context（读 {@code /proc/self/cmdline}），与 {@code cn_base_done.flag}
 * 那批标记同一套做法，任何线程任何时机都能读。
 *
 * <h3>与单线程分支的关系</h3>
 *
 * {@code surgery/single-thread-reliable-*} 分支是把这四个常量直接改成 1 的
 * <b>独立发版</b>。本类把同一件事做成运行时可切换，目的是让主线版本自己就能
 * 覆盖那批用户——按 AGENTS.md 规则四，主线功能覆盖分支版之后，分支版就该关掉
 * {@code branch_versions.supported} 并退役。
 */
public final class CNDownloadMode {

    private static final String TAG = "CNDownloadMode";

    /** 玩家选择的落盘位置。存在 = 玩家选了单线程。 */
    private static final String FLAG_PATH = CNPaths.privDir() + "/cn_single_thread.flag";

    /** 玩家那一层的缓存；null = 还没读过盘。 */
    private static volatile Boolean playerChoice;

    private CNDownloadMode() {}

    /** 当前是否走单线程。三层任一成立即为真。 */
    public static boolean singleThread() {
        try {
            if (CNDebugFlags.isOn(CNDebugFlags.USE_SINGLE_THREAD)) return true;
            if (CNMirrors.forceSingleThread()) return true;
            return playerWants();
        } catch (Throwable t) {
            // 取不到一律按「不开」：单线程是降级路线，不该因为读取出错就把所有人
            // 拖进慢速模式。
            return false;
        }
    }

    /** 玩家是不是<b>自己</b>选了单线程（不含调试开关与云端强制）。 */
    public static boolean playerWants() {
        Boolean c = playerChoice;
        if (c != null) return c.booleanValue();
        boolean on = false;
        try {
            on = new File(FLAG_PATH).isFile();
        } catch (Throwable ignore) {}
        playerChoice = Boolean.valueOf(on);
        return on;
    }

    /**
     * 上面那两层是不是<b>强制</b>状态。为真时弹窗不该再给「改回多线程」——
     * 给了也关不掉，只会让人以为按钮坏了。
     */
    public static boolean forcedOn() {
        try {
            return CNDebugFlags.isOn(CNDebugFlags.USE_SINGLE_THREAD)
                    || CNMirrors.forceSingleThread();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 玩家侧的切换：落盘 + <b>立刻</b>把全局连接闸门收到 1（或放回去）。
     *
     * <p>立刻生效是必须的——玩家是在「这个包刚下失败」的当口选的，如果要等
     * 重启才生效，他下一次重试还是会以同样的方式失败，然后得出「这个按钮没用」
     * 这个结论。四处并发里，分片数/分段数/并行文件数都是每次下载开始时现读的，
     * 只有连接闸门是长期存在的信号量，所以这里要显式调一次。
     *
     * @return 切换后的实际状态（强制打开时，传 false 也仍然是 true）
     */
    public static boolean setPlayerChoice(boolean on) {
        try {
            File f = new File(FLAG_PATH);
            if (on) {
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                if (!f.isFile() && !f.createNewFile() && !f.isFile()) {
                    CNLog.w(TAG, "写不出单线程标记，本次仅内存生效: " + FLAG_PATH);
                }
            } else if (f.isFile() && !f.delete() && f.isFile()) {
                CNLog.w(TAG, "删不掉单线程标记: " + FLAG_PATH);
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "切换单线程标记失败（本次仅内存生效）: " + t);
        }
        playerChoice = Boolean.valueOf(on);
        boolean effective = singleThread();
        applyNow();
        CNLog.w(TAG, "下载模式切换为" + (effective ? "【单线程可靠】" : "【多线程】")
                + "（玩家选择=" + on + "，强制层=" + forcedOn() + "）");
        return effective;
    }

    /**
     * 把当前模式下发到<b>长期存在</b>的那两处。启动时与切换时各调一次。
     *
     * <p>四处并发里，分片工作线程数与字节分段数是每次下载开始时现读 {@link #cap}
     * 的，不用下发；连接闸门（信号量）与并行文件池（线程池）是长期对象，必须
     * 显式改——漏掉任何一个，表现都是「选了单线程但并发没降下来」。
     */
    public static void applyNow() {
        try {
            CNDownloadConcurrency.setCap(cap(CNDownloadConcurrency.maxConnections()));
        } catch (Throwable t) {
            CNLog.w(TAG, "下发连接上限失败: " + t);
        }
        try {
            CNManualRedownload.applyMode();
        } catch (Throwable t) {
            CNLog.w(TAG, "下发并行文件数失败: " + t);
        }
    }

    /**
     * 并发相关的上限一律过这里：单线程模式下压到 1，否则原样返回。
     *
     * <p>四处调用点（分片工作线程、字节分段、连接闸门、并行文件数）共用这一个
     * 判据，是为了不让它们各自长出一套「要不要单线程」的判断——那种分裂正是
     * 「明明开了单线程，某一处还在开 8 个连接」这类 bug 的来源。
     */
    public static int cap(int normal) {
        return singleThread() ? 1 : normal;
    }

    /** 面板/日志用的一句话描述。 */
    public static String describe() {
        if (!singleThread()) return "多线程分片";
        if (CNDebugFlags.isOn(CNDebugFlags.USE_SINGLE_THREAD)) return "单线程（调试开关强制）";
        if (CNMirrors.forceSingleThread()) return "单线程（云端强制）";
        return "单线程（你选的）";
    }

    // ---- JVM 回归测试入口 ----
    public static String flagPathForTest() { return FLAG_PATH; }
    public static void resetCacheForTest() { playerChoice = null; }
}
