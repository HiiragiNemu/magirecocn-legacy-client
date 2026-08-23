package io.kamihama.magianative;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地状态覆盖层的<b>装配方</b>：把 {@link CNWebStateBridge} 挂进 WebView，并在页面
 * 开始加载时注入 {@code assets/magia/localstate.js}。
 *
 * <h3>解决的是什么问题</h3>
 *
 * Totentanz 服务端无状态——无状态到打完一场战斗、结算界面的星数都不变。玩家改完
 * 编队点保存，请求发出去、罐头响应回来，<b>服务端不记</b>；下次进游戏编队就回到
 * 默认。本层的作用是把编队记在客户端，并在每个带 {@code userDeckList} 的响应到达
 * 前端之前把它换成记住的那份。
 *
 * <h3>🔴 时序是本类最难的一件事：桥必须赶在页面加载之前挂上</h3>
 *
 * {@code addJavascriptInterface} 的注入时机是<b>下一次页面加载</b>，不是调用当时。
 * 挂晚了，当前这个文档里 {@code window.CNLocalState} 就是 {@code undefined}；而游戏
 * 前端是 hash 路由（{@code location.href="#/TopPage"}），<b>整局都不会再触发一次
 * 文档级加载</b>——也就是说错过这一次，就是错过一整个会话。
 *
 * <p>所以本类<b>不搭 {@link CNWebProxy} 的便车</b>，自己起一条轮询：
 *
 * <ul>
 *   <li><b>轮询要快</b>：前 {@value #FAST_WINDOW_MS} 毫秒按 {@value #POLL_FAST_MS}
 *       毫秒一跳。引擎建完 WebView 到 {@code loadUrl} 之间只有很短一段，
 *       {@link CNWebProxy} 那条 1 秒一跳的轮询是为「包 WebViewClient」设计的，
 *       包晚一点只是少代理几个请求；这里晚一跳就是整局失效，两者要求不同；</li>
 *   <li><b>不受 {@code skipWebProxy} 影响</b>：那个开关关的是代理，不该把存档一起关掉；</li>
 *   <li><b>不受 API 26 门槛影响</b>：{@link CNWebProxy} 因为要
 *       {@code getWebViewClient()} 而在 API &lt; 26 上整个不装，但
 *       {@code addJavascriptInterface} 与 {@code evaluateJavascript} 分别是 API 17 /
 *       19 就有的，本层在 minSdk 21 上全程可用。</li>
 * </ul>
 *
 * <p>另外 {@link CNWebProxy} 在 {@code onPageStarted} 里也会调一次 {@link #inject}
 * ——那是 API 26+ 上更准的时机。两条路都调是有意的，脚本自己有重入保护
 * （{@code __MAGIACN_LOCAL_STATE__}），重复注入是廉价空操作。
 *
 * <h3>桥到底有没有挂上，不靠猜</h3>
 *
 * {@link #inject} 用 {@code evaluateJavascript} 的 {@link ValueCallback} 把
 * {@code !!window.CNLocalState} 的结果取回 Java 并记进日志。这一句是本层唯一的
 * 「它到底生效了没有」的判据——没有它，桥挂晚了的表现是「编队就是存不住」，
 * 与「脚本有 bug」「玩家没点保存」在日志里长得一模一样。
 *
 * <h3>为什么脚本是 asset 而不是 Java 里的字符串常量</h3>
 *
 * {@link CNWebProxy} 里那段 WS 计数脚本是拼在 Java 字符串里的，十几行还能忍。本层
 * 的脚本两百多行，继续拼字符串会有三个后果：JS 语法错误只有真机上才暴露、diff 全
 * 是转义看不出改了什么、没有任何工具能对它做检查。放进
 * {@code assets/magia/localstate.js} 之后它就是一个正常的 .js 文件。
 *
 * <h3>开关</h3>
 *
 * 默认<b>开</b>，逃生口 {@link CNDebugFlags#SKIP_LOCAL_STATE}。默认开是因为它修的是
 * 一个「不修就用不了」的功能缺失（编队根本存不住），与 {@link CNBootWatchdog} 同类；
 * 而不是 {@link CNWebProxy} 那种「为了更快」的优化——优化才需要先证明再默认打开。
 *
 * @see CNLocalStore 落盘
 * @see CNWebStateBridge JS 桥
 */
public final class CNDeckState {

    private static final String TAG = "MagiaCNDeckState";

    /** 前端脚本的 asset 路径。 */
    private static final String SCRIPT_ASSET = "magia/localstate.js";

    /**
     * 追加在脚本尾部的自检表达式。
     *
     * <p>{@code evaluateJavascript} 把「最后一个表达式的值」交给 ValueCallback，
     * 所以这一句必须是整段脚本的最后一个表达式。
     */
    private static final String PROBE = ";(!!window.CNLocalState)";

    /** 快轮询窗口内的间隔。 */
    private static final long POLL_FAST_MS = 100L;
    /** 快轮询持续多久。引擎建 WebView 与 loadUrl 之间的窗口远小于它。 */
    private static final long FAST_WINDOW_MS = 30_000L;
    /** 快窗口之后的间隔。WebView 会被销毁重建，得长期守着。 */
    private static final long POLL_IDLE_MS = 5_000L;
    /** 轮询总时长上限。超过之后不再产生任何活动。 */
    private static final long POLL_DEADLINE_MS = 300_000L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /** 脚本正文缓存。asset 不会变，读一次就够。 */
    private static volatile String script;
    /** 读 asset 失败过一次就不再重试——失败原因是打包问题，重试不会变好。 */
    private static volatile boolean scriptUnavailable;

    /** 已经挂过桥的那个 WebView。弱引用，理由同 {@link CNWebProxy} 的 handled。 */
    private static volatile java.lang.ref.WeakReference<WebView> bridged;

    /** 自检结果只在「变了」的时候记一次，别每次注入都刷屏。 */
    private static volatile int lastProbe = -1;   // -1 未知 / 0 没桥 / 1 有桥

    private CNDeckState() {}

    /** 本层是否启用。 */
    public static boolean enabled() {
        try {
            return !CNDebugFlags.isOn(CNDebugFlags.SKIP_LOCAL_STATE);
        } catch (Throwable t) {
            // 读不到开关目录时 isOn 本来就按「没开」处理；这里同样保持功能可用。
            return true;
        }
    }

    /**
     * 起轮询线程。与 {@link CNWebProxy#install} 一样在 WebView 创建<b>之前</b>调用。
     *
     * <p>内部 CAS 保证只生效一次。
     */
    public static void install() {
        try {
            if (!INSTALLED.compareAndSet(false, true)) return;
            if (!enabled()) {
                CNLog.i(TAG, "调试开关 " + CNDebugFlags.SKIP_LOCAL_STATE + " 生效，本地状态覆盖层不安装");
                return;
            }
            Thread t = new Thread(new Waiter(), "cnv-deckstate-install");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            CNLog.w(TAG, "本地状态覆盖层安装线程起不来（不影响游戏）: " + t);
        }
    }

    /** 轮询等 WebView 出现，出现（或被重建）就挂桥。 */
    private static final class Waiter implements Runnable {
        @Override public void run() {
            long start = System.currentTimeMillis();
            while (true) {
                try {
                    long elapsed = System.currentTimeMillis() - start;
                    if (elapsed > POLL_DEADLINE_MS) {
                        CNLog.i(TAG, "等 WebView 超过 " + (POLL_DEADLINE_MS / 1000) + "s，停止轮询");
                        return;
                    }
                    // 反射目标全仓库只有 CNWebProxy 那一处，这里走它的包内出口。
                    // 复制第二份的话，引擎哪天换了字段名就会漏改一个地方——
                    // tools/check-boot-watchdog-contract.py 钉着这条。
                    WebView wv = CNWebProxy.currentWebView();
                    if (wv != null && !alreadyBridged(wv)) {
                        new Handler(Looper.getMainLooper()).post(new Attach(wv));
                    }
                    Thread.sleep(elapsed < FAST_WINDOW_MS ? POLL_FAST_MS : POLL_IDLE_MS);
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable t) {
                    try { Thread.sleep(POLL_IDLE_MS); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    /** 在 UI 线程上挂桥。{@code addJavascriptInterface} 必须在 UI 线程调。 */
    private static final class Attach implements Runnable {
        private final WebView wv;
        Attach(WebView w) { this.wv = w; }

        @Override public void run() {
            if (alreadyBridged(wv)) return;
            try {
                wv.addJavascriptInterface(CNWebStateBridge.instance(), CNWebStateBridge.JS_NAME);
                markBridged(wv);
                CNLog.i(TAG, "已挂载本地状态桥 " + CNWebStateBridge.JS_NAME);
                // 挂桥之后立刻注一次：WebView 已经在加载页面时，这一次注入至少能
                // 让脚本的 XHR 钩子就位（覆盖仍取决于桥在不在，自检会说明）。
                inject(wv);
            } catch (Throwable t) {
                // 挂不上不是致命的：脚本取不到桥会自己退化成空操作。
                CNLog.w(TAG, "挂载本地状态桥失败，本地存档本次不生效: " + t);
            }
        }
    }

    /**
     * 注入前端脚本。{@link CNWebProxy} 在 {@code onPageStarted} 里也会调它。
     *
     * <p>脚本自己有重入保护，所以这里不记「注过没有」——页面重载会换一个新的 JS
     * 全局环境，标记跟着没，正好该重新注入；同一个文档里被调两次则被标记挡掉。
     */
    public static void inject(WebView wv) {
        if (wv == null || !enabled()) return;
        String js = loadScript(wv.getContext());
        if (js == null) return;
        try {
            wv.evaluateJavascript(js + PROBE, new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    reportProbe(value);
                }
            });
        } catch (Throwable t) {
            CNLog.w(TAG, "注入本地状态脚本失败: " + t);
        }
    }

    /**
     * 记录自检结果。
     *
     * <p>「没桥」这一条要说得足够重：它意味着本次会话编队存不住，而且因为前端是
     * hash 路由，不会再有第二次文档加载来补救——除非页面被重载
     * （{@link CNBootWatchdog} 那条路，或玩家重启）。
     */
    private static void reportProbe(String value) {
        int now = "true".equals(value) ? 1 : 0;
        if (now == lastProbe) return;
        lastProbe = now;
        if (now == 1) {
            CNLog.i(TAG, "自检通过：页面里 " + CNWebStateBridge.JS_NAME + " 可见，本地存档生效");
        } else {
            CNLog.w(TAG, "自检未通过：页面里取不到 " + CNWebStateBridge.JS_NAME
                         + "（桥挂在本次页面加载之后）。本次会话编队不会被记住，"
                         + "重载页面即可恢复。返回值=" + value);
        }
    }

    private static boolean alreadyBridged(WebView wv) {
        java.lang.ref.WeakReference<WebView> b = bridged;
        return b != null && b.get() == wv;
    }

    private static void markBridged(WebView wv) {
        bridged = new java.lang.ref.WeakReference<WebView>(wv);
    }

    /** 读 asset 里的脚本正文，失败返回 {@code null}。 */
    private static String loadScript(Context ctx) {
        String s = script;
        if (s != null) return s;
        if (scriptUnavailable || ctx == null) return null;

        InputStream in = null;
        try {
            in = ctx.getAssets().open(SCRIPT_ASSET);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            script = s;
            CNLog.i(TAG, "已载入本地状态脚本（" + s.length() + " 字符）");
            return s;
        } catch (Throwable t) {
            scriptUnavailable = true;
            CNLog.w(TAG, "读取 " + SCRIPT_ASSET + " 失败，本地存档不生效: " + t);
            return null;
        } finally {
            CNIo.closeQuietly(in);
        }
    }
}
