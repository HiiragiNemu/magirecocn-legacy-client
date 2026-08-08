package io.kamihama.magianative;

import java.io.File;

/**
 * 调试开关目录（Java 侧）。与 native 侧
 * {@code MagiaLegacy.cpp} 的 {@code DEBUG_DIR} <b>是同一个目录</b>：
 *
 * <pre>
 *     /data/data/io.kamihama.totentanz/files/madomagi/debug/&lt;开关名&gt;
 * </pre>
 *
 * 建一个同名空文件就是打开该开关，删掉就是关闭，<b>重启游戏生效</b>。
 *
 * <h3>为什么做成这个形状</h3>
 *
 * 本仓库反复遇到同一类问题：某个改动疑似干扰引擎，表现是黑屏 / 卡死 / 闪退，
 * 而定位手段只有「改代码 → 重打包 → 找人真机走一遍」。setURI、nghttp2 逐请求、
 * web 端点、以及 2026-08-08 那次战斗崩溃，每一次都烧掉整轮往返，一次 CI 还只能
 * 验一个假设。
 *
 * <p>有了这个目录，<b>一次构建就能验多个假设</b>：装一次包，在设备上建/删文件、
 * 重启，逐个排除。排查可以交给手上有设备的人，不必每次都回到构建流程。
 *
 * <h3>为什么放在 app 私有目录</h3>
 *
 * 这里在非 root 的正式包上<b>玩家碰不到</b>（{@code run-as} 只对 debuggable 包
 * 有效），所以不构成面向普通玩家的风险面；而有能力自查的人拿 root 或 debuggable
 * 包就能用。这正是想要的分界。
 *
 * <h3>🔴 边界：只关我们自己加的东西</h3>
 *
 * 这些开关一律只做一件事——<b>把客户端退回更接近原包的行为</b>。
 * <b>绝不设置任何削弱安全判定的开关</b>：外链白名单（{@link CNSafeLink}）、
 * https 强制、配置来源校验、解压膨胀比上限等一概不做成开关。否则这个目录就从
 * 排查工具变成了攻击面——一旦有人能写进这里，就能把防线一条条关掉。
 *
 * <p>加新开关前先问：它关掉之后，客户端是「少一个我们加的功能」还是「少一道
 * 防线」？后者一律不做。
 *
 * <h3>用法</h3>
 *
 * <pre>
 *   adb shell "run-as io.kamihama.totentanz mkdir -p files/madomagi/debug"
 *   adb shell "run-as io.kamihama.totentanz touch files/madomagi/debug/no_webproxy"
 *   # 重启游戏；logcat 里 [DEBUG] 会把当前生效的开关列出来
 * </pre>
 *
 * 启动时无论开没开都会打印全表，所以「有哪些开关」看一眼日志就知道。
 */
public final class CNDebugFlags {

    private static final String TAG = "CNDebugFlags";

    /** 与 native 侧 {@code DEBUG_DIR} 逐字一致。 */
    private static final String DEBUG_DIR =
        "/data/data/io.kamihama.totentanz/files/madomagi/debug";

    // ── 开关名（与文件名逐字一致）─────────────────────────────
    /** 不安装 WebView 拦截层代理（{@link CNWebProxy}），一律透传直连。 */
    public static final String NO_WEBPROXY   = "no_webproxy";
    /** 跳过启动时的热更检查（{@link CNHotUpdateCheck}），直接进游戏。 */
    public static final String NO_HOTUPDATE  = "no_hotupdate";
    /** 网络慢时不弹询问框，退回旧的静默 fail-open 行为。 */
    public static final String NO_SLOW_ASK   = "no_slow_ask";
    /** 不显示安装/热更浮层（连带不下发 native 的引擎闸门标记）。 */
    public static final String NO_OVERLAY    = "no_overlay";

    private static final String[][] KNOWN = {
        { NO_WEBPROXY,  "不安装 WebView 拦截层代理，一律透传直连" },
        { NO_HOTUPDATE, "跳过启动时的热更检查，直接进游戏" },
        { NO_SLOW_ASK,  "网络慢时不弹询问框，退回静默 fail-open" },
        { NO_OVERLAY,   "不显示安装/热更浮层" },
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

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        java.util.HashSet<String> found = new java.util.HashSet<String>();
        try {
            File dir = new File(DEBUG_DIR);
            String[] names = dir.list();
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
                            + "（可能是 native 侧的，或者名字打错了——"
                            + "native 侧的全表见 logcat 里 MagiaCN_Legacy 的 [DEBUG] 行）");
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
