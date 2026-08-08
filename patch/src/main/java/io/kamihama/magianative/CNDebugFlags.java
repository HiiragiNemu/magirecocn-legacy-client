package io.kamihama.magianative;

import java.io.File;

/**
 * 调试开关目录（Java 侧）。与 native 侧 {@code MagiaLegacy.cpp} 的
 * {@code DEBUG_DIR} <b>是同一个目录</b>：
 *
 * <pre>
 *     /data/data/io.kamihama.totentanz/files/madomagi/debug/&lt;开关名&gt;
 * </pre>
 *
 * 建一个同名空文件就是打开该开关，删掉就是关闭，<b>重启游戏生效</b>。
 * 开关名一律**小驼峰**，两侧同一风格。
 *
 * <h3>为什么做成这个形状</h3>
 *
 * 本仓库反复遇到同一类问题：某个改动疑似干扰引擎，表现是黑屏 / 卡死 / 闪退，
 * 而定位手段只有「改代码 → 重打包 → 找人真机走一遍」。{@code setURI}、
 * nghttp2 逐请求、web 端点、以及 2026-08-08 那次战斗崩溃，每次都烧掉整轮往返，
 * <b>一次 CI 还只能验一个假设</b>。
 *
 * <p>有了这个目录，<b>一次构建就能验多个假设</b>：装一次包，在设备上建/删文件、
 * 重启，逐个排除。排查可以交给手上有设备的人，不必每次都回到构建流程。
 *
 * <h3>两类开关</h3>
 *
 * <ul>
 *   <li><b>{@code skipXxx}</b> —— 启动链上每一步各一个，跳过该步。用来二分定位
 *       「是哪一步把游戏搞挂的」。</li>
 *   <li><b>{@code failXxx} / {@code slowXxx}</b> —— 故障注入。用来验证错误处理
 *       路径本身：退避重试、换线、事务回滚、慢网询问框……这些平时<b>只有在真的
 *       网络烂掉时才跑得到</b>，没有注入手段就等于从没测过。</li>
 * </ul>
 *
 * <h3>为什么放在 app 私有目录</h3>
 *
 * 这里在非 root 的正式包上<b>玩家碰不到</b>（{@code run-as} 只对 debuggable 包
 * 有效），所以不构成面向普通玩家的风险面；而有能力自查的人拿 root 或 debuggable
 * 包就能用。这正是想要的分界。
 *
 * <h3>🔴 边界：只关我们自己加的东西，只注入我们自己处理的故障</h3>
 *
 * {@code skipXxx} 一律只做一件事——<b>把客户端退回更接近原包的行为</b>。
 * {@code failXxx} 只在<b>我们自己的网络/事务代码</b>里造假失败，不去动引擎。
 *
 * <p><b>绝不设置任何削弱安全判定的开关</b>：外链白名单（{@link CNSafeLink}）、
 * https 强制、配置来源校验、解压膨胀比上限等一概不做成开关。否则这个目录就从
 * 排查工具变成了攻击面——一旦有人能写进这里，就能把防线一条条关掉。
 *
 * <p>加新开关前先问：它打开之后，客户端是「少一个我们加的功能 / 多走一条我们自己
 * 写的错误分支」，还是「少一道防线」？后者一律不做。
 *
 * <h3>用法</h3>
 *
 * <pre>
 *   adb shell "run-as io.kamihama.totentanz mkdir -p files/madomagi/debug"
 *   adb shell "run-as io.kamihama.totentanz touch files/madomagi/debug/skipHotUpdate"
 *   # 重启游戏；logcat 里 [DEBUG] 会把全表和当前生效的开关列出来
 * </pre>
 */
public final class CNDebugFlags {

    private static final String TAG = "CNDebugFlags";

    /** 与 native 侧 {@code DEBUG_DIR} 逐字一致。 */
    private static final String DEBUG_DIR =
        "/data/data/io.kamihama.totentanz/files/madomagi/debug";

    // ── skipXxx：启动链上每一步各一个（顺序即启动顺序）────────────────
    /** `CNWebProxy.install()` 不装 WebView 拦截层代理，一律透传直连。 */
    public static final String SKIP_WEB_PROXY      = "skipWebProxy";
    /** `CNDownloaderFix.runInstaller()` 不跑首次安装（资源缺失时会停在浮层）。 */
    public static final String SKIP_INSTALLER      = "skipInstaller";
    /** `CNCNDownloadUI.show()` 不显示浮层，连带不下发 native 的引擎闸门标记。 */
    public static final String SKIP_OVERLAY        = "skipOverlay";
    /** `CNVersionCheck` 不查客户端版本，不弹强制更新框。 */
    public static final String SKIP_VERSION_CHECK  = "skipVersionCheck";
    /** `CNMirrors` 不拉 config.json，全程用内置默认线路（代理配置也不下发）。 */
    public static final String SKIP_MIRROR_CONFIG  = "skipMirrorConfig";
    /** `CNHotUpdateCheck.start()` 跳过热更检查，直接进游戏。 */
    public static final String SKIP_HOT_UPDATE     = "skipHotUpdate";
    /** 不弹「是否播放序章」询问框。 */
    public static final String SKIP_TUTORIAL_PROMPT= "skipTutorialPrompt";
    /** 装完 / 序章后不自动重启（等价于旧的 NO_RESTART_FLAG）。 */
    public static final String SKIP_RESTART        = "skipRestart";
    /** 网络慢时不弹询问框，退回旧的静默 fail-open。 */
    public static final String SKIP_SLOW_ASK       = "skipSlowAsk";

    // ── failXxx / slowXxx：故障注入 ──────────────────────────────────
    /** config.json 一律拉取失败。验退避重试与「再试一次 / 用内置线路」询问框。 */
    public static final String FAIL_CONFIG_FETCH   = "failConfigFetch";
    /** 版本 json 查询一律失败。验 fail-open 进游戏这条路。 */
    public static final String FAIL_VERSION_QUERY  = "failVersionQuery";
    /** 版本 json 查询人为拖慢到超过总闸。<b>验慢网询问框</b>，不必真去找烂网络。 */
    public static final String SLOW_VERSION_QUERY  = "slowVersionQuery";
    /** 资源/热更下载一律失败。验换线、冷却与重试上限。 */
    public static final String FAIL_DOWNLOAD       = "failDownload";
    /** 热更事务应用到一半失败。验 `CNHotUpdateTx` 的整体回滚与 journal 恢复。 */
    public static final String FAIL_HOTUPDATE_APPLY= "failHotUpdateApply";

    /** 注入延迟的时长。比 6 秒总闸长一截，保证一定触发询问框。 */
    public static final long SLOW_INJECT_MS = 9000L;

    private static final String[][] KNOWN = {
        { SKIP_WEB_PROXY,       "不装 WebView 拦截层代理，一律透传直连" },
        { SKIP_INSTALLER,       "不跑首次安装（资源缺失时会停在浮层）" },
        { SKIP_OVERLAY,         "不显示浮层（连带不下发 native 引擎闸门标记）" },
        { SKIP_VERSION_CHECK,   "不查客户端版本，不弹强制更新框" },
        { SKIP_MIRROR_CONFIG,   "不拉 config.json，全程用内置默认线路" },
        { SKIP_HOT_UPDATE,      "跳过热更检查，直接进游戏" },
        { SKIP_TUTORIAL_PROMPT, "不弹「是否播放序章」询问框" },
        { SKIP_RESTART,         "装完/序章后不自动重启" },
        { SKIP_SLOW_ASK,        "网络慢时不弹询问框，退回静默 fail-open" },
        { FAIL_CONFIG_FETCH,    "【注入】config.json 一律拉取失败" },
        { FAIL_VERSION_QUERY,   "【注入】版本 json 查询一律失败" },
        { SLOW_VERSION_QUERY,   "【注入】版本查询拖慢 " + SLOW_INJECT_MS + "ms（验慢网询问框）" },
        { FAIL_DOWNLOAD,        "【注入】资源/热更下载一律失败" },
        { FAIL_HOTUPDATE_APPLY, "【注入】热更事务应用到一半失败（验回滚）" },
    };

    /** 只在首次查询时扫一遍目录：这些开关会在热路径上被问到，不能每次都碰磁盘。 */
    private static volatile boolean loaded;
    private static volatile java.util.HashSet<String> on;

    private CNDebugFlags() {}

    /** 某个开关是否打开。任何异常一律当作「没开」——排查工具绝不能自己把游戏搞挂。 */
    public static boolean isOn(String name) {
        try {
            ensureLoaded();
            java.util.HashSet<String> s = on;
            return s != null && s.contains(name);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 注入用的睡眠。开关没开就立刻返回，开了就睡 {@link #SLOW_INJECT_MS}。
     *
     * <p>被中断时保留中断位并立即返回——注入延迟不该改变取消语义。
     */
    public static void injectSlow(String name, String what) {
        if (!isOn(name)) return;
        CNLog.w(TAG, "[DEBUG] 注入延迟 " + SLOW_INJECT_MS + "ms：" + what);
        try {
            Thread.sleep(SLOW_INJECT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        java.util.HashSet<String> found = new java.util.HashSet<String>();
        try {
            String[] names = new File(DEBUG_DIR).list();
            if (names != null) {
                for (int i = 0; i < names.length; i++) found.add(names[i]);
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "[DEBUG] 读调试开关目录失败（按全部关闭处理）: " + t);
        }
        on = found;
        report(found);
    }

    /** 把全表打进日志：有哪些开关、哪些开着、以及目录里不认识的文件。 */
    private static void report(java.util.HashSet<String> found) {
        try {
            CNLog.i(TAG, "[DEBUG] 调试开关目录: " + DEBUG_DIR);
            int count = 0;
            for (int i = 0; i < KNOWN.length; i++) {
                boolean isOn = found.contains(KNOWN[i][0]);
                if (isOn) count++;
                CNLog.i(TAG, "[DEBUG]   [" + (isOn ? "ON " : "   ") + "] "
                        + KNOWN[i][0] + "  " + KNOWN[i][1]);
            }
            // 名字打错时最容易的误判是「开关没用」，所以单独点名。
            // native 侧的开关也放在同一个目录，这里不认识它们是正常的，
            // 所以措辞是「Java 侧不认识」而不是「无效」。
            for (java.util.Iterator<String> it = found.iterator(); it.hasNext(); ) {
                String n = it.next();
                boolean known = false;
                for (int i = 0; i < KNOWN.length; i++) {
                    if (KNOWN[i][0].equals(n)) { known = true; break; }
                }
                if (!known) {
                    CNLog.w(TAG, "[DEBUG] Java 侧不认识的开关 " + n
                            + "（可能是 native 侧的，或者名字打错了——native 侧的"
                            + "全表见 logcat 里 MagiaCN_Legacy 的 [DEBUG] 行）");
                }
            }
            if (count > 0) {
                CNLog.w(TAG, "[DEBUG] ⚠ 共 " + count
                        + " 个 Java 侧开关生效——这是排查用的降级模式，不是正常配置");
            }
        } catch (Throwable ignore) {
        }
    }
}
