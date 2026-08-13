package io.kamihama.magianative;

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
 *       玩家关不掉。它要的就是「无论如何按单线程跑一遍」。</li>
 *   <li><b>玩家自己选的</b>——下载失败弹窗里的选择。<b>压过云端</b>：坐在那台设备
 *       前面的是他，云端那条是给「所有人都炸了」准备的粗判断，不该反过来把一个
 *       明确表过态的人按住。他选多线程就是多线程。</li>
 *   <li><b>云端</b> {@code settings.force_single_thread}——只在玩家<b>没表过态</b>
 *       时兜底。某条 CDN 对所有人都炸了的时候，服务端一改即刻生效，不必等发版。</li>
 * </ol>
 *
 * <p>所以「玩家没表态」与「玩家选了多线程」必须分得开——用 {@code Boolean} 的
 * null / FALSE 两态表示。合成一个 boolean 的话，云端一开，玩家就再也关不掉了。
 *
 * <h3>玩家那一层<b>不落盘</b></h3>
 *
 * 只活在本次进程里，下次启动回到「没表态」。玩家是在「这个包刚下失败」的当口
 * 选的单线程——那多半是当时那条网络的一次性状况。把它持久化下去，就是<b>一次慢、
 * 次次慢</b>：网络早好了，他却要一直用着为最差情况准备的降级路线，而且没有任何
 * 地方提示他「你还开着这个」。要长期生效的场合有云端开关和调试开关，那两层本来
 * 就是干这个的。
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

    /**
     * 玩家这一层：{@code null} = 本次启动还没表过态，{@code TRUE/FALSE} = 明确选了。
     * <b>只在内存里</b>，不落盘（理由见类注释「玩家那一层不落盘」）。
     */
    private static volatile Boolean playerChoice;

    private CNDownloadMode() {}

    /**
     * 当前是否走单线程。<b>按优先级短路</b>，不是三层求或——求或的话玩家永远
     * 关不掉云端那条。
     */
    public static boolean singleThread() {
        try {
            if (CNDebugFlags.isOn(CNDebugFlags.USE_SINGLE_THREAD)) return true;
            Boolean p = playerChoice;
            if (p != null) return p.booleanValue();   // 玩家表过态：以他为准
            return CNMirrors.forceSingleThread();     // 没表态才轮到云端
        } catch (Throwable t) {
            // 取不到一律按「不开」：单线程是降级路线，不该因为读取出错就把所有人
            // 拖进慢速模式。
            return false;
        }
    }

    /** 玩家是不是<b>自己</b>选了单线程（没表过态算否）。 */
    public static boolean playerWants() {
        Boolean c = playerChoice;
        return c != null && c.booleanValue();
    }

    /** 玩家本次启动有没有表过态。云端那层只在这个为 false 时才轮得到。 */
    public static boolean playerDecided() {
        return playerChoice != null;
    }

    /**
     * 是不是<b>玩家关不掉</b>的强制状态。为真时弹窗不该再给「改回多线程」——
     * 给了也关不掉，只会让人以为按钮坏了。
     *
     * <p>只剩调试开关这一层。云端那条<b>不再</b>算强制：玩家的选择压过它，
     * 所以按钮给了就是有用的。
     */
    public static boolean forcedOn() {
        try {
            return CNDebugFlags.isOn(CNDebugFlags.USE_SINGLE_THREAD);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 玩家侧的切换：记在内存里 + <b>立刻</b>把全局连接闸门收到 1（或放回去）。
     *
     * <p>立刻生效是必须的——玩家是在「这个包刚下失败」的当口选的，如果要等
     * 重启才生效，他下一次重试还是会以同样的方式失败，然后得出「这个按钮没用」
     * 这个结论。四处并发里，分片数/分段数/并行文件数都是每次下载开始时现读的，
     * 只有连接闸门是长期存在的信号量，所以这里要显式调一次。
     *
     * @return 切换后的实际状态（强制打开时，传 false 也仍然是 true）
     */
    public static boolean setPlayerChoice(boolean on) {
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
        if (playerWants()) return "单线程（你选的，仅本次启动）";
        return "单线程（云端）";
    }

    // ---- JVM 回归测试入口 ----
    /** 回到「本次启动还没表过态」。真机上每次启动本来就是这个状态。 */
    public static void resetCacheForTest() { playerChoice = null; }
}
