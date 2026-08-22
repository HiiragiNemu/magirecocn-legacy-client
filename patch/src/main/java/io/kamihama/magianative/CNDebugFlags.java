package io.kamihama.magianative;

import java.io.File;

/**
 * 调试开关目录（Java 侧）。与 native 侧 {@code MagiaLegacy.cpp} 的
 * {@code DEBUG_DIR} <b>是同一个目录</b>：
 *
 * <pre>
 *     &lt;应用数据目录&gt;/debug/&lt;开关名&gt;
 * </pre>
 *
 * 数据目录<b>一律经 {@link CNPaths#privDir()} 解析</b>，与 native 侧同一套算法。
 * <b>不要照抄任何绝对路径</b>：它按 {@code /data/user/0/<包名>} →
 * {@code /data/data/<包名>} 的顺序探测，前者才是 4.2+ 上的真实目录，后者只是
 * 兼容软链——非标准容器 / 深度定制 ROM 上可能根本没有，为此翻过一次车
 * （详见 {@link CNPaths} 的类注释）。要看真实落点就读启动日志里那行
 * {@code [DEBUG] 调试开关目录: …}。
 *
 * <p>建一个同名空文件就是打开该开关，删掉就是关闭，<b>重启游戏生效</b>。
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
 * <b>现状：包里没有 {@code android:debuggable}，
 * {@code run-as} 用不了</b>，所以这个目录只有能直写<b>应用私有目录</b>的环境
 * （root/su、模拟器）碰得到。
 *
 * <p>公测期曾短暂打开过 debuggable（{@code f38ffea2}，为的是让人免 root 抓日志
 * 和改开关），两天后又收了回去——不是因为收紧，而是<b>这条路根本送不到人</b>：
 * 要用它得会 adb 或 Termux，而实际会用的人几乎没有。这件事直接决定了
 * {@link CNDebugBridge} 那个悬浮窗存在的理由，改这段之前先读它。
 *
 * <p>安全边界从来不靠目录的隐蔽性，而靠下面这条：开关只退功能，绝不退防线。
 * debuggable 开着还是关着，这条都不变——变的只是有多少人够得到。
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
 * 包不再 debuggable，{@code run-as} 会直接报
 * {@code package not debuggable}，只能走 root。<b>目录从日志里取，别硬敲</b>
 * ——理由见本类开头：{@code /data/data} 在某些设备上不存在。
 *
 * <pre>
 *   # 1) 先从启动日志拿到真实目录（两侧都会打这一行）
 *   adb logcat -d | grep -m1 '调试开关目录'
 *   #   I/CNDebugFlags: [DEBUG] 调试开关目录: /data/user/0/&lt;包名&gt;/debug
 *
 *   # 2) 用打出来的那个路径，别用这里的示例
 *   D=&lt;上一步打出来的路径&gt;
 *   adb shell "su -c 'mkdir -p $D &amp;&amp; touch $D/skipHotUpdate'"
 *   adb shell "su -c 'chown -R $(dirname $D | xargs stat -c %u):$(dirname $D | xargs stat -c %g) $D'"
 *
 *   # 3) 重启游戏；logcat 里 [DEBUG] 会把全表和当前生效的开关列出来
 * </pre>
 *
 * <p>⚠ 第二条命令里的 {@code chown} 不能省：用 su 建出来的文件<b>属主是
 * root</b>，应用改不动也删不掉。{@code log/.seq} 就这么卡死过一次
 * （启动序号永远不变），排查绕了大半天。
 *
 * <p>要么就干脆用 {@link CNDebugBridge} 的调试悬浮窗改——由应用自己写，
 * 属主天然是对的，这一整类问题不存在。
 */
public final class CNDebugFlags {

    private static final String TAG = "CNDebugFlags";

    /**
     * 与 native 侧 {@code DEBUG_DIR} 逐字一致。
     *
     * <h3>为什么在 {@code PRIV_DIR} 下，而不是 {@code files/} 里</h3>
     *
     * 排查工具的落点统一放在应用私有目录根下，与 {@link CNLog} 的 {@code log/}
     * <b>平级</b>：{@code <priv>/log} 与 {@code <priv>/debug}。两条理由：
     *
     * <ul>
     *   <li><b>{@code files/} 是热更的解压根。</b>{@code CNHotUpdateTx} 会往那里
     *       重建，还会按前缀算孤儿并删除。目前 {@code cleanupPrefixes("scenario")}
     *       只清 {@code madomagi/resource/scenario/json/}，碰不到调试目录——但这是
     *       <b>巧合而非保证</b>：哪天有人把前缀放宽到 {@code madomagi/}，开关就会
     *       在某次热更后集体消失，而且查不出为什么。挪出来就不存在这个问题。</li>
     *   <li>排查工具应当自成一处，不和下发内容混在一起：找日志和找开关是同一件
     *       事，两个目录挨着放，说一次路径就够了。</li>
     * </ul>
     */
    private static final String DEBUG_DIR = CNPaths.privDir() + "/debug";

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
    /** {@link CNBootWatchdog} 不介入：浮层撤下后前端一直起不来也不自动重载页面。
     *  排查「到底是前端卡住还是看门狗把好好的一次加载掀了」时打开。 */
    public static final String SKIP_BOOT_WATCHDOG  = "skipBootWatchdog";

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

    // ── useXxx：可选引擎 ──────────────────────────────────────────────
    /** 资源下载改用 aria2c 备用引擎（默认关）。开=先走 aria2，失败回退主引擎。 */
    public static final String USE_ARIA2 = "useAria2";
    /**
     * 下载一律走<b>单线程可靠模式</b>（默认关）：分片工作线程、字节分段、全局
     * 连接闸门、并行文件数全部压到 1。
     *
     * <p>与 {@link #USE_ARIA2} 同一类——只换我们自己的下载路径，不碰任何安全
     * 判定。用来排查「多线程分片在这台设备/这条网上到底是不是失败原因」：开着
     * 重下一次，成了就说明是并发问题，不必再猜。
     *
     * <p>这是三层来源里<b>最高</b>的一层，玩家在弹窗里关不掉（见
     * {@link CNDownloadMode}）。
     */
    public static final String USE_SINGLE_THREAD = "useSingleThread";
    /** 开启 WebView 远程调试（默认关）。开=进程内 WebView 可被 chrome://inspect
     *  连接，看网络面板（每个请求的 method/URL、WebSocket 帧），排查网络问题用。
     *  仅影响调试：正式包不设此开关即完全无感。 */
    public static final String USE_WEBVIEW_DEBUG  = "useWebviewDebug";
    /** 记录 WebView 每个请求的 method+URL（去重，防页面子资源刷屏）。无电脑时替代
     *  chrome://inspect 的实证手段：日志面板/分享包直接看前端 API 走哪个 host。 */
    public static final String LOG_WEBVIEW_REQUESTS = "logWebviewRequests";
    /** 往游戏页面注入 JS 包装 window.WebSocket，经 CNWsCount 接口把每次连接
     *  （connect/open/error/close）打进 CNLog。零电脑数 WebSocket 连接数。 */
    public static final String COUNT_WEBSOCKET = "countWebSocket";

    /** 跑一次 TLS 探针：本机起 TLS1.2 自签名服务端，再用**引擎自带的 OpenSSL**
     *  去连它，把握手结果打进日志。验的是「自建服务端这条路通不通」，
     *  与游戏本身的任何流程无关，跑完即止。见 {@link CNTlsProbe}。 */
    public static final String TLS_PROBE = "tlsProbe";

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
        { SKIP_BOOT_WATCHDOG,   "启动看门狗不介入（前端卡住也不自动重载页面）" },
        { USE_WEBVIEW_DEBUG,    "开启 WebView 远程调试（chrome://inspect 看网络流量）" },
        { LOG_WEBVIEW_REQUESTS, "记录 WebView 每个请求的 method+URL（去重）" },
        { COUNT_WEBSOCKET,      "注入 JS 计数 WebSocket 连接（零电脑）" },
        { FAIL_CONFIG_FETCH,    "【注入】config.json 一律拉取失败" },
        { FAIL_VERSION_QUERY,   "【注入】版本 json 查询一律失败" },
        { SLOW_VERSION_QUERY,   "【注入】版本查询拖慢 " + SLOW_INJECT_MS + "ms（验慢网询问框）" },
        { FAIL_DOWNLOAD,        "【注入】资源/热更下载一律失败" },
        { FAIL_HOTUPDATE_APPLY, "【注入】热更事务应用到一半失败（验回滚）" },
        { USE_ARIA2,            "资源下载改用 aria2c 备用引擎（默认关）" },
        { USE_SINGLE_THREAD,    "下载一律单线程可靠模式（并发全部压到 1，默认关）" },
        { TLS_PROBE,            "跑一次 TLS 探针（本机自签名端点 + 引擎自带 OpenSSL）" },
    };

    /** 开关名的合法形状：小驼峰，纯 ASCII 字母数字。见 {@link #writeState}。 */
    private static final java.util.regex.Pattern NAME_OK =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    /** 只在首次查询时扫一遍目录：这些开关会在热路径上被问到，不能每次都碰磁盘。 */
    private static volatile boolean loaded;
    private static volatile java.util.HashSet<String> on;

    private CNDebugFlags() {}

    /**
     * 在后台线程上把目录先读掉。
     *
     * <p>{@link #isOn} 是懒加载的：**第一次**调用要读一次目录、再打十几行日志
     * （{@code CNLog} 每行都 flush）。这点开销本身无所谓，但它落在哪个线程上是
     * 随机的——{@code show()} 里那次调用就可能发生在 UI 线程上。
     *
     * <p>所以由 {@code triggerInstaller} 在自己的后台线程上先调一次，把这次读盘
     * 钉死在那儿。调不调都不影响正确性，只是不让它有机会落到 UI 线程。
     */
    public static void preload() {
        try { ensureLoaded(); } catch (Throwable ignore) {}
    }

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

    // ══ 以下三个给 CNDebugBridge（调试悬浮窗）用 ═══════════════════════
    //
    // 悬浮窗只是这些开关的**另一个写入口**，不改变开关本身能做什么，所以它
    // 不碰上面那条「只退功能、不退防线」的边界。CI 的
    // tools/check-debug-flag-boundary.py 另有一条规则禁止 CNDebugBridge /
    // CNDebugOverlay 出现在任何安全判据里。

    /** 全表的只读副本：{名字, 说明}。纯静态数据，<b>不触发读盘</b>。 */
    public static String[][] knownTable() {
        String[][] copy = new String[KNOWN.length][];
        for (int i = 0; i < KNOWN.length; i++) {
            copy[i] = new String[] { KNOWN[i][0], KNOWN[i][1] };
        }
        return copy;
    }

    /**
     * <b>现在磁盘上</b>有哪些开关文件——每次调用都真去读一遍目录。
     *
     * <p>与 {@link #isOn} 是两回事，这个区别必须留着：{@code isOn} 报的是
     * <b>本次进程启动时</b>的状态（缓存，也正是当前真正在生效的那份），而这里
     * 报的是<b>下次启动会生效</b>的状态。悬浮窗里勾了、保存了但还没重启时，
     * 两者就会不一样——把它们混成一个，界面上就会出现「明明勾上了却说没生效」
     * 或者反过来的错觉，而这套开关存在的意义恰恰是不让人误判。
     *
     * <p>读不到目录一律返回空集合（与 {@code isOn} 同样的「异常按关处理」）。
     */
    public static java.util.HashSet<String> onDisk() {
        java.util.HashSet<String> found = new java.util.HashSet<String>();
        try {
            String[] names = new File(DEBUG_DIR).list();
            if (names != null) {
                for (int i = 0; i < names.length; i++) found.add(names[i]);
            }
        } catch (Throwable ignore) {}
        return found;
    }

    /**
     * 按 {@code desired} 把 {@code universe} 里每个开关的文件建出来 / 删掉。
     * 返回实际改动的条数，失败返回 -1（并已打日志）。
     *
     * <h3>为什么写入口在这里，而不是在悬浮窗里</h3>
     *
     * 三条限制必须和目录常量待在同一处，否则迟早会有第二个写入口绕开它们：
     *
     * <ul>
     *   <li><b>要求包里的调试总闸是开的</b>（{@link CNDebugBridge#overlayAllowed}，
     *       烧在 native 里的一个布尔）。分界写在代码里，而不是只写在界面上——
     *       不然任何一处调用都能凭空打开开关。</li>
     *   <li><b>只认 {@code universe} 里的名字，且名字必须是小驼峰 ASCII。</b>
     *       这个目录不是通用文件柜：没有这条，界面上的一个 bug 就能在应用私有
     *       目录里建出任意路径的文件（{@code ../} 之类）。</li>
     *   <li><b>不碰缓存。</b>开关只在进程启动时读一次，两侧都是——写完当场
     *       「生效」是做不到的（native 那几个 {@code noXxxHook} 决定的是钩子装
     *       不装，{@code JNI_OnLoad} 跑完就定死了）。假装生效比不生效更坏。</li>
     * </ul>
     *
     * <p>顺带解决一个实打实踩过的坑：用 su 建出来的文件属主是 root，应用写不动
     * （{@code log/.seq} 卡在同一个值那次绕了大半天）。由应用自己写，属主天然
     * 正确，这一整类问题直接消失。
     */
    public static int writeState(java.util.Set<String> desired,
                                 java.util.Collection<String> universe) {
        if (!CNDebugBridge.overlayAllowed()) {
            CNLog.w(TAG, "[DEBUG] 拒绝写开关：本包的调试总闸是关的");
            return -1;
        }
        if (universe == null) return -1;
        int changed = 0;
        try {
            File dir = new File(DEBUG_DIR);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                CNLog.w(TAG, "[DEBUG] 建不出开关目录: " + DEBUG_DIR);
                return -1;
            }
            for (java.util.Iterator<String> it = universe.iterator(); it.hasNext(); ) {
                String name = it.next();
                if (name == null || !NAME_OK.matcher(name).matches()) {
                    CNLog.w(TAG, "[DEBUG] 跳过形状不合法的开关名: " + name);
                    continue;
                }
                File f = new File(dir, name);
                boolean want = desired != null && desired.contains(name);
                boolean has = f.exists();
                if (want == has) continue;
                boolean ok = want ? createEmpty(f) : f.delete();
                if (ok) changed++;
                else CNLog.w(TAG, "[DEBUG] " + (want ? "建不出 " : "删不掉 ") + f);
            }
            CNLog.i(TAG, "[DEBUG] 已写入开关状态，改动 " + changed + " 项（重启后生效）");
            return changed;
        } catch (Throwable t) {
            CNLog.w(TAG, "[DEBUG] 写开关状态失败: " + t);
            return -1;
        }
    }

    private static boolean createEmpty(File f) {
        try {
            return f.createNewFile() || f.exists();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 反面用例用：只验名字形状这一条。 */
    public static boolean nameShapeOkForTest(String name) {
        return name != null && NAME_OK.matcher(name).matches();
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
            } else {
                // list() 返回 null 有两种原因，**结论完全不同**，必须分开说：
                // 目录不存在是正常状态（没人在排查），而目录在却读不出来是故障
                // ——最常见的是拿 su/root 建的目录，属主是 root、模式 700，
                // 应用（uid 10xxx）连遍历都进不去，于是**每个开关都读成「关」**。
                //
                // 不分开的话，这两种情况在日志里长得一模一样，人会得出
                // 「开关坏了」这个错结论——而这套开关存在的意义恰恰是不让人误判。
                if (!dir.exists()) {
                    CNLog.i(TAG, "[DEBUG] 目录不存在，全部开关按关闭处理（正常状态）");
                } else {
                    CNLog.w(TAG, "[DEBUG] ⚠ 目录在，但列不出内容——应用没有读权限，"
                            + "所有开关都会读成「关」。多半是用 su/root 建的（属主不是"
                            + "应用）。请改用 run-as 重建：\n"
                            + "  adb shell \"run-as io.kamihama.totentanz mkdir -p debug\"");
                }
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
