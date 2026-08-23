package io.kamihama.magianative;

import org.json.JSONArray;

/**
 * 页面 JS 访问 {@link CNLocalStore} 的<b>唯一</b>入口，经
 * {@code addJavascriptInterface} 以 {@value #JS_NAME} 之名挂进 WebView。
 *
 * <h3>它为什么存在</h3>
 *
 * 服务端无状态，编队之类的东西只能存在客户端；而「当前编队是什么」这件事只有前端
 * 知道（它在 {@code backboneCommon.storage.userDeckList} 里），Java 侧拿不到——
 * {@link CNWebProxy} 的类注释已经写死了这条硬限制：Android 的
 * {@code WebResourceRequest} 不提供请求体，编队保存恰好是 POST，拦截层看不见。
 *
 * <p>所以分工是固定的，没得选：<b>前端负责「知道存什么」，Java 负责「让它活过
 * 这次进程」</b>。本类就是这两侧之间那道门。
 *
 * <h3>为什么不用 localStorage</h3>
 *
 * 前端自己就有 localStorage（{@code base.js} 的 JS 错误重试标记就存在那儿），
 * 看起来存编队也够用。不用它的理由有三条：
 *
 * <ol>
 *   <li>{@code nativeCommand.js} 里有 {@code DATA_CLEAR_WEB_CACHE} 这条通往引擎的
 *       清理指令。它到底调 {@code clearCache()} 还是 {@code WebStorage.deleteAllData()}，
 *       在引擎侧、本仓库没有基线树、<b>没有核实过</b>。是后者的话玩家点一次
 *       清理，编队就全没了——而这类「偶尔全丢」的故障最难查；</li>
 *   <li>localStorage 跟着 WebView 的站点数据走，玩家在系统设置里清应用数据、或者
 *       某些 ROM 的「清理缓存」都可能带走它；</li>
 *   <li>存在私有目录里的文件可以被 {@link CNLogBundle} 那类工具打包带走，玩家换机
 *       或报障时能整份导出。localStorage 做不到。</li>
 * </ol>
 *
 * <h3>信任模型：这道门后面是什么</h3>
 *
 * {@code addJavascriptInterface} 是<b>按 WebView 挂的，不是按来源挂的</b>——挂上之后
 * 该 WebView 里跑的任何脚本都看得见 {@value #JS_NAME}，我们无法在这一层区分「游戏
 * 前端」和「不知道从哪来的脚本」。所以本类的写法一律按「调用者不可信」来：
 *
 * <ul>
 *   <li>命名空间走白名单（{@link CNLocalStore#isValidNamespace}），拼不出路径穿越；</li>
 *   <li>内容必须是合法 JSON 且有字节上限，填不满磁盘；</li>
 *   <li>写操作限速（{@value #MAX_WRITES_PER_WINDOW} 次 / {@value #WINDOW_MS} 毫秒），
 *       挡的是死循环里狂写——那会把 flash 写坏，不只是慢；</li>
 *   <li>能力面只有「读写我们自己那个目录里的 JSON」，没有任何通往文件系统其它位置、
 *       网络或 Activity 的路。</li>
 * </ul>
 *
 * <p>要说清楚的是：这道门<b>不防</b>「页面被攻破后篡改玩家自己的编队存档」。那不是
 * 这一层能解决的问题（页面本来就有权改编队），也不值得为它加密——玩家存档的价值
 * 对攻击者是零。它防的是「借这个桥读写存档以外的东西」，那才是真正会扩大伤害的方向。
 *
 * <h3>异常不外抛</h3>
 *
 * 每个 {@code @JavascriptInterface} 方法整体套 {@code catch (Throwable)}。异常逃进
 * JS 桥只会在页面侧变成一句没有上下文的错误，既查不动、又可能把前端的调用链打断，
 * 让玩家卡在一个没有编队的界面上。宁可返回失败值，让前端按「存不进去」处理。
 *
 * @see CNLocalStore 落盘实现
 * @see CNDeckState 注入与前端脚本
 */
public final class CNWebStateBridge {

    private static final String TAG = "MagiaCNStateBridge";

    /** 挂进 WebView 的全局名。前端脚本按这个名字找它。 */
    public static final String JS_NAME = "CNLocalState";

    /** 写限速窗口。 */
    private static final long WINDOW_MS = 10_000L;
    /** 一个窗口内允许的写次数。正常玩家改编队一次一写，个位数就够。 */
    private static final int MAX_WRITES_PER_WINDOW = 40;

    /** 只给测试用：限速阈值。 */
    public static int maxWritesPerWindowForTest() { return MAX_WRITES_PER_WINDOW; }

    private static long windowStart;
    private static int  windowWrites;

    /** 单例：{@code addJavascriptInterface} 要的是对象，不是类。 */
    private static final CNWebStateBridge INSTANCE = new CNWebStateBridge();

    private CNWebStateBridge() {}

    /** 挂进 WebView 时用的那个对象。 */
    public static CNWebStateBridge instance() { return INSTANCE; }

    // ── 页面侧可见的方法 ────────────────────────────────────────────

    /**
     * 读一个命名空间。
     *
     * @return JSON 文本；不存在或被拒时返回 {@code null}（JS 侧看到的是 {@code null}）
     */
    @android.webkit.JavascriptInterface
    public String get(String ns) {
        try {
            return CNLocalStore.read(ns);
        } catch (Throwable t) {
            CNLog.w(TAG, "get 失败: " + t);
            return null;
        }
    }

    /**
     * 写一个命名空间。
     *
     * @return 是否写成功。返回 {@code false} 时盘上旧内容保持原样
     */
    @android.webkit.JavascriptInterface
    public boolean set(String ns, String json) {
        try {
            if (!allowWrite()) {
                CNLog.w(TAG, "set 被限速丢弃 ns 长度=" + (ns == null ? -1 : ns.length()));
                return false;
            }
            return CNLocalStore.write(ns, json);
        } catch (Throwable t) {
            CNLog.w(TAG, "set 失败: " + t);
            return false;
        }
    }

    /**
     * 删掉一个命名空间。
     *
     * @return 是否删成功（本来就不存在也算成功）
     */
    @android.webkit.JavascriptInterface
    public boolean remove(String ns) {
        try {
            if (!allowWrite()) {
                CNLog.w(TAG, "remove 被限速丢弃");
                return false;
            }
            return CNLocalStore.clear(ns);
        } catch (Throwable t) {
            CNLog.w(TAG, "remove 失败: " + t);
            return false;
        }
    }

    /**
     * 列出已存在的命名空间。
     *
     * @return JSON 数组文本，如 {@code ["deck","settings"]}；失败时返回 {@code "[]"}
     */
    @android.webkit.JavascriptInterface
    public String list() {
        try {
            JSONArray arr = new JSONArray();
            String[] all = CNLocalStore.namespaces();
            for (int i = 0; i < all.length; i++) arr.put(all[i]);
            return arr.toString();
        } catch (Throwable t) {
            CNLog.w(TAG, "list 失败: " + t);
            return "[]";
        }
    }

    // ── 限速 ────────────────────────────────────────────────────────

    /**
     * 滑动窗口限速。
     *
     * <p>只拦写（{@link #set}/{@link #remove}），不拦读——读不产生磁盘写入，而且
     * 页面每次加载都要读，限它只会把正常流程卡住。
     */
    private static synchronized boolean allowWrite() {
        long now = System.currentTimeMillis();
        // 用「间隔超过窗口」判断而不是 now > windowStart + WINDOW_MS，是为了
        // 系统时间被往回调时也能自愈：差值为负同样落进「开新窗口」这一支。
        if (windowStart == 0L || now - windowStart < 0L || now - windowStart > WINDOW_MS) {
            windowStart = now;
            windowWrites = 0;
        }
        if (windowWrites >= MAX_WRITES_PER_WINDOW) return false;
        windowWrites++;
        return true;
    }

    /** 只给测试用：清掉限速窗口。 */
    public static synchronized void resetThrottleForTest() {
        windowStart = 0L;
        windowWrites = 0;
    }
}
