package io.kamihama.magianative;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebView 拦截层代理：给 {@code shouldInterceptRequest} 补一条「本地没有就走
 * {@code /stream/} 取」的路。
 *
 * <h3>为什么代理要落在这一层</h3>
 *
 * 端点级代理（{@code UrlConfig::api/chat} getter 钩子）在真机上<b>一次都没生效
 * 过</b>——0103/0104/0105/0107/0112 五份日志里，表示改写成功的
 * {@code [proxy] api[n]: 原址 -> 新址} 一行都没有。原因是两条独立的死路正好凑齐：
 *
 * <ul>
 *   <li>引擎自始至终只读 {@code api[0]}，而它的值是个<b>裸主机名</b>
 *       （{@code dorothy.magi-reco.com}，没有 scheme），native 的
 *       {@code tryRewriteUrl} 第一道 {@code "https://"} 判断就返回 false；</li>
 *   <li>{@code api[1..13]} 与 {@code chat[0..5]} 确实是完整 URL，但那些值只出现在
 *       {@code probeEndpointSlots} 的主动探测里——那条路走的是<b>原始</b> getter，
 *       只观测不改写。引擎自己压根没调过这些槽位。</li>
 * </ul>
 *
 * <p>而游戏真正的 API 流量走的是 WebView 的 {@code shouldInterceptRequest}
 * （日志里 {@code /magica/api/page/TopPage?…} 就是从那儿过的）。所以代理要真
 * 生效，就得落在这一层。
 *
 * <h3>为什么这一层没有跨域问题</h3>
 *
 * {@code UrlConfig::web} 的改写是<b>停用</b>的，原因是真机黑屏（45289988）：改了
 * web 端点等于换掉页面的 origin，前端所有相对请求与同源判断跟着一起变。
 *
 * <p>拦截层没有这个问题：我们把字节<b>交回</b>给 WebView，页面 origin 始终是
 * {@code dorothy.magi-reco.com}，浏览器根本不知道数据是从哪拿的，CORS 无从谈起。
 *
 * <h3>为什么这一层能失败回退，而端点级不能</h3>
 *
 * 端点级改写是「改完就交给引擎去连」——连没连上我们这边根本不知道，代理一挂玩家
 * 就永远进不去（这正是当年删掉代理配置磁盘缓存的理由）。拦截层相反：取不到就
 * {@code return null}，WebView 自己按原地址直连，代价只是这一个请求慢一点。
 *
 * <h3>硬限制：POST 代理不了</h3>
 *
 * Android 的 {@link WebResourceRequest} <b>不提供请求体</b>——没有
 * {@code getBody()}，任何版本都没有。所以只有 GET 能走代理，POST 一律透传直连。
 * 这不是偷懒，是平台层面就拿不到。
 *
 * <h3>三种模式，默认 off</h3>
 *
 * 由 config.json 的 {@code proxy.web_mode} 下发，改模式<b>不需要重新打 APK</b>：
 *
 * <ul>
 *   <li>{@code off}（默认）—— 纯透传。包装类装着，但一个请求都不改，
 *       行为与没有本类时完全一致。</li>
 *   <li>{@code measure} —— 仍然纯透传，但按节流在后台对<b>同一个 URL</b> 各拉一次
 *       直连与 {@code /stream/}，把两边耗时记进日志。这是回答「代理到底快不快」
 *       唯一靠谱的办法：只有玩家设备上的数字算数，开发机上量的没有参考价值。</li>
 *   <li>{@code on} —— GET 真走 {@code /stream/}，失败回退直连。</li>
 * </ul>
 *
 * <p>之所以默认 off 且做成配置可切：目的是<b>加速</b>，而加速这件事必须先证明。
 * 先发一版 {@code measure} 收数字，数字说得通再从 config.json 翻成 {@code on}。
 *
 * @see CNMirrors#proxyBase()
 */
public final class CNWebProxy {

    private static final String TAG = "MagiaCNWebProxy";

    /** 纯透传，一个请求都不改。 */
    public static final int MODE_OFF     = 0;
    /** 透传 + 后台配对测速。 */
    public static final int MODE_MEASURE = 1;
    /** GET 真走代理，失败回退直连。 */
    public static final int MODE_ON      = 2;

    /** 装包装类需要 {@link WebView#getWebViewClient()}，它是 API 26 才有的。 */
    private static final int MIN_SDK_FOR_WRAP = 26;

    /**
     * 等 WebView 的轮询节奏。前 {@code POLL_DEADLINE_MS} 毫秒密集轮询（要赶在首屏
     * 之前接管），之后降到 {@code POLL_INTERVAL_IDLE_MS} 长期守着——WebView 会被
     * {@code removeWebView()} 销毁再重建，包过一次不等于永远包着。
     */
    private static final long POLL_INTERVAL_MS      = 1000L;
    private static final long POLL_INTERVAL_IDLE_MS = 5000L;
    private static final long POLL_DEADLINE_MS      = 180_000L;

    /** 代理取数的超时。比直连给得宽一点——代理慢是要被记下来的，不是要被判死的。 */
    private static final int PROXY_CONNECT_TIMEOUT_MS = 10_000;
    private static final int PROXY_READ_TIMEOUT_MS    = 20_000;

    /** measure 模式的节流：两次配对测速至少间隔这么久。 */
    private static final long MEASURE_INTERVAL_MS = 30_000L;
    /** 配对测速最多做这么多轮，之后不再产生任何流量。 */
    private static final int  MEASURE_MAX_ROUNDS  = 12;
    /** 配对测速只取前这么多字节，够算 TTFB 就行，不为了测速把流量打满。 */
    private static final int  MEASURE_SAMPLE_BYTES = 16 * 1024;

    private static volatile int      mode    = MODE_OFF;
    private static volatile String   base    = null;   // https://api.example.test/stream/
    private static volatile String[] domains = null;

    private static final AtomicBoolean INSTALLED     = new AtomicBoolean(false);
    private static final AtomicBoolean WRAPPED       = new AtomicBoolean(false);
    private static final AtomicLong    lastMeasureAt = new AtomicLong(0L);
    private static final AtomicLong    measureRounds = new AtomicLong(0L);

    private CNWebProxy() {}

    // ==================================================================
    // 配置
    // ==================================================================

    /**
     * 由 {@link CNMirrors} 解析完 config.json 的 {@code proxy} 段后调用。
     *
     * <p>与 native 侧一样<b>不做任何缓存</b>：没读到 config.json 就是 off，
     * 没有任何持久状态会让「服务器没了还照着旧配置走代理」这种事发生。
     *
     * @param b       代理入口前缀，须以 '/' 结尾；null/空表示未配置
     * @param d       域名后缀白名单
     * @param modeStr config.json 的 {@code proxy.web_mode}，无法识别一律当 off
     */
    public static void configure(String b, String[] d, String modeStr) {
        int m = parseMode(modeStr);
        if (b == null || b.isEmpty() || d == null || d.length == 0) {
            // 配置不全就没有代理可谈，无论 web_mode 写了什么
            base = null; domains = null; mode = MODE_OFF;
            CNLog.i(TAG, "未配置代理入口/白名单，拦截层保持透传");
            return;
        }
        base = b; domains = d; mode = m;
        CNLog.i(TAG, "拦截层代理配置：mode=" + modeName(m) + " base=" + b
                     + " domains=" + d.length);
    }

    private static int parseMode(String s) {
        if (s == null) return MODE_OFF;
        String v = s.trim().toLowerCase(Locale.US);
        if ("on".equals(v))      return MODE_ON;
        if ("measure".equals(v)) return MODE_MEASURE;
        return MODE_OFF;
    }

    private static String modeName(int m) {
        if (m == MODE_ON)      return "on";
        if (m == MODE_MEASURE) return "measure";
        return "off";
    }

    // ==================================================================
    // 安装
    // ==================================================================

    /**
     * 起一个守护线程等 WebView 出现，出现后把它的 WebViewClient 包一层。
     *
     * <p>重复调用只有第一次生效；不抛异常，也不阻塞调用方。
     *
     * <p><b>为什么是包装而不是替换：</b>原来的 {@code WebViewClientImpl} 是
     * {@code WebViewImpl} 的私有内部类，我们既继承不了也 new 不出来。而它身上挂着
     * 六个不能丢的行为——本地文件拦截、{@code game:} 伪协议回调、GL 线程上的
     * {@code shouldOverrideUrlLoading}、以及三个页面生命周期回调。逐个重新实现等于
     * 把别人的代码抄一遍，抄错一处就是难查的怪毛病。包一层则是<b>结构上</b>保证
     * 原行为不变：每个方法都先/只交给原对象。
     */
    public static void install() {
        try {
            if (!INSTALLED.compareAndSet(false, true)) return;
            if (Build.VERSION.SDK_INT < MIN_SDK_FOR_WRAP) {
                // getWebViewClient() 是 API 26 才有的，拿不到原对象就没法包。
                // 这种设备直接不装——透传直连，与没有本类时一致。
                CNLog.i(TAG, "系统低于 API " + MIN_SDK_FOR_WRAP
                             + "，拿不到原 WebViewClient，拦截层代理不安装（直连）");
                return;
            }
            Thread t = new Thread(new Waiter(), "cnv-webproxy-install");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            CNLog.w(TAG, "拦截层代理安装线程起不来（不影响游戏）: " + t);
        }
    }

    /**
     * 轮询等 WebView 出现，出现（或被重建）就包一层。单独成类而不用匿名类，
     * 避开 d8 对嵌套匿名类的老毛病。
     *
     * <p><b>为什么一直轮下去而不是包一次就收工：</b>
     * {@code WebViewHelper.removeWebView()} 会 {@code destroy()} 掉当前 WebView
     * 并把 {@code sWebView} 置空，之后 {@code createWebView()} 建一个**新的**——
     * 新对象身上是引擎自己的 WebViewClient，我们那层跟着旧对象一起没了。
     * 所以这里比对实例身份，换了对象就重新包。
     *
     * <p>节流：前 {@value #POLL_DEADLINE_MS} 毫秒每 {@value #POLL_INTERVAL_MS} 毫秒
     * 一次（要赶在首屏之前接管），之后降到
     * {@value #POLL_INTERVAL_IDLE_MS} 毫秒一次。稳态下每次只是一个反射静态字段读
     * 加一次引用比较，开销可以忽略。
     */
    private static final class Waiter implements Runnable {
        @Override public void run() {
            long start = System.currentTimeMillis();
            int  quietRounds = 0;
            while (true) {
                try {
                    long elapsed = System.currentTimeMillis() - start;
                    boolean warmup = elapsed < POLL_DEADLINE_MS;

                    Object wv = findWebView();
                    if (wv != null) {
                        new Handler(Looper.getMainLooper()).post(new Wrapper((WebView) wv));
                    } else if (warmup) {
                        // 找不到时按 10 / 30 / 60 / 120 秒各记一次，别刷屏。
                        // 之前这里全程静默，真机上只看得到「等了 180s 没等到」，
                        // 分不清是「引擎还没建」还是「建了但我找错地方」——那次
                        // 恰恰是后者（tag 被 WebViewHelper 覆盖成 "WebView" 了）。
                        long s = elapsed / 1000;
                        if ((s >= 10 && quietRounds == 0) || (s >= 30 && quietRounds == 1)
                                || (s >= 60 && quietRounds == 2) || (s >= 120 && quietRounds == 3)) {
                            quietRounds++;
                            CNLog.i(TAG, "已等 " + s + "s，尚未取到 WebView（引擎还没建，继续等）");
                        }
                    }
                    Thread.sleep(warmup ? POLL_INTERVAL_MS : POLL_INTERVAL_IDLE_MS);
                } catch (InterruptedException ie) {
                    return;
                } catch (Throwable t) {
                    try { Thread.sleep(POLL_INTERVAL_IDLE_MS); } catch (InterruptedException ie) { return; }
                }
            }
        }
    }

    /** 在 UI 线程上把这个 WebView 的 WebViewClient 包一层。 */
    private static final class Wrapper implements Runnable {
        private final WebView wv;
        Wrapper(WebView w) { this.wv = w; }

        @Override public void run() {
            try {
                WebViewClient orig = wv.getWebViewClient();
                if (orig == null) return;
                if (orig instanceof Delegating) return;   // 已经包过了，别套娃
                wv.setWebViewClient(new Delegating(orig));
                boolean first = WRAPPED.compareAndSet(false, true);
                CNLog.i(TAG, (first ? "已接管 WebViewClient（原对象 " : "WebView 被重建，重新接管（原对象 ")
                             + orig.getClass().getName() + "），当前 mode=" + modeName(mode));
            } catch (Throwable t) {
                CNLog.w(TAG, "接管 WebViewClient 失败，保持原样（直连）: " + t);
            }
        }
    }

    /**
     * 取当前的 WebView 实例。
     *
     * <p>直接读 {@code jp.f4samurai.web.WebViewHelper.sWebView} 这个私有静态字段——
     * 引擎自己就是靠它握着唯一那个 WebView 的（{@code createWebView} 赋值、
     * {@code removeWebView} 置空）。
     *
     * <p><b>不要再改回遍历 view 树找 tag。</b>第一版就是那么写的，照着
     * {@code WebViewImpl} 构造函数里的 {@code setTag("WebViewImpl")}
     * 去 {@code findViewWithTag("WebViewImpl")}，结果真机上等满 180 秒也找不到——
     * 因为 {@code WebViewHelper.createWebView()} 在构造之后<b>紧接着</b>就
     * {@code setTag("WebView")} 把它覆盖了。那个 tag 从来就不是构造函数里写的那个。
     * 读字段没有这个问题：它是引擎自己的事实来源，不会被别处改名。
     *
     * <p>反射失败或字段为空一律当作「还没到时候」，不报错。
     */
    private static Object findWebView() {
        try {
            Class<?> c = Class.forName("jp.f4samurai.web.WebViewHelper");
            Field f = c.getDeclaredField("sWebView");
            f.setAccessible(true);
            Object o = f.get(null);
            return (o instanceof WebView) ? o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================================================================
    // 包装类
    // ==================================================================

    /**
     * 把六个被原类覆盖的方法逐个转交给原对象，只在
     * {@link #shouldInterceptRequest(WebView, WebResourceRequest)} 里加一条后路。
     *
     * <p>注意 {@code shouldOverrideUrlLoading} 只覆盖 String 那个重载：原类也只覆盖
     * 了它，API 24+ 的 {@code WebResourceRequest} 重载默认实现会转调 String 版，
     * 保持不覆盖才能让链路和原来一模一样。
     */
    private static final class Delegating extends WebViewClient {
        private final WebViewClient orig;
        Delegating(WebViewClient o) { this.orig = o; }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            // 第一步永远是原对象：本地文件拦截的语义一个字节都不改
            WebResourceResponse local;
            try {
                local = orig.shouldInterceptRequest(view, req);
            } catch (Throwable t) {
                return null;      // 原对象炸了也不能把请求吃掉，交回给 WebView 直连
            }
            if (local != null) return local;
            try {
                return afterLocalMiss(req);
            } catch (Throwable t) {
                return null;      // 任何意外都回退直连
            }
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            try {
                return orig.shouldInterceptRequest(view, url);
            } catch (Throwable t) {
                return null;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return orig.shouldOverrideUrlLoading(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            orig.onPageStarted(view, url, favicon);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            orig.onPageFinished(view, url);
        }

        @Override
        public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
            orig.onReceivedError(view, code, desc, failingUrl);
        }
    }

    // ==================================================================
    // 本地未命中之后
    // ==================================================================

    /**
     * @return 代理取回的响应；{@code null} 表示「不接管，交回 WebView 直连」
     */
    private static WebResourceResponse afterLocalMiss(WebResourceRequest req) {
        int m = mode;
        if (m == MODE_OFF) return null;

        String url = (req.getUrl() == null) ? null : req.getUrl().toString();
        String rewritten = rewrite(url);
        if (rewritten == null) return null;          // 不是 https / 不在白名单 / 是我们自己

        // POST 拿不到 body（平台就没给），只能直连
        String method = req.getMethod();
        if (method != null && !"GET".equalsIgnoreCase(method)) return null;

        // Range 请求不接管。206 的语义要靠 Content-Range/Content-Length 一起表达，
        // 而我们下面为了避开分帧问题把 Content-Length 摘掉了，两者凑在一起容易出
        // 「读到一半就断」这种极难查的毛病。WebView 这层本来也几乎不发 Range
        // （视频走的是 CRI 原生播放器，不经这里），不值得为它冒险。
        Map<String, String> reqHeaders = req.getRequestHeaders();
        if (reqHeaders != null) {
            for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                if ("Range".equalsIgnoreCase(e.getKey())) return null;
            }
        }

        if (m == MODE_MEASURE) {
            maybeMeasure(url, rewritten);
            return null;                             // measure 模式绝不接管
        }
        return fetchViaProxy(url, rewritten, reqHeaders);
    }

    /**
     * 不能原样转交给上游的请求头。
     *
     * <p>{@code Accept-Encoding} 是这里面最要命的一个：{@link HttpURLConnection}
     * 只有在<b>它自己</b>加了 {@code Accept-Encoding: gzip} 时才会透明解压。我们
     * 一旦把 WebView 的 {@code Accept-Encoding: gzip, deflate} 原样转过去，它就
     * 认为「调用方自己要处理压缩」，交回来的是<b>压缩流</b>；而下面又把
     * {@code Content-Encoding} 从响应头里摘掉了——于是 WebView 拿到一坨没人告诉它
     * 是 gzip 的 gzip，页面直接是乱码。不转交，让 HttpURLConnection 自己管。
     *
     * <p>其余几个是逐跳头，转交会让连接层自相矛盾。
     */
    private static boolean isHopByHopRequestHeader(String k) {
        return "Accept-Encoding".equalsIgnoreCase(k)
            || "Host".equalsIgnoreCase(k)
            || "Connection".equalsIgnoreCase(k)
            || "Keep-Alive".equalsIgnoreCase(k)
            || "Transfer-Encoding".equalsIgnoreCase(k)
            || "TE".equalsIgnoreCase(k)
            || "Upgrade".equalsIgnoreCase(k)
            || "Content-Length".equalsIgnoreCase(k);
    }

    /**
     * 把 {@code https://host/path} 改成 {@code base + host + path}。
     *
     * <p>规则与 native 的 {@code tryRewriteUrl} 保持一致，包括「排除自身」——
     * {@code *.example.test} 是 config/线路表/资源所在，改写它会打成死循环。
     *
     * <p>是纯函数，没有任何状态；{@code tools/WebProxyTest.java} 直接拿它当测试面。
     *
     * @return 改写后的 URL；不该改写时返回 {@code null}
     */
    public static String rewrite(String url) {
        String b = base;
        String[] d = domains;
        if (url == null || b == null || d == null) return null;
        if (b.isEmpty() || b.charAt(b.length() - 1) != '/') return null;
        if (!url.regionMatches(true, 0, "https://", 0, 8)) return null;

        int hostStart = 8;
        int sep = -1;
        for (int i = hostStart; i < url.length(); i++) {
            char ch = url.charAt(i);
            if (ch == '/' || ch == '?' || ch == '#') { sep = i; break; }
        }
        String host = (sep < 0) ? url.substring(hostStart) : url.substring(hostStart, sep);
        String rest = (sep < 0) ? "" : url.substring(sep);
        if (host.isEmpty()) return null;

        String hostMatch = stripPort(host);
        if (hostMatch.isEmpty()) return null;
        if (isSelfHost(hostMatch)) return null;
        if (!hostMatches(hostMatch, d)) return null;

        return b + host + (rest.isEmpty() ? "/" : rest);
    }

    private static String stripPort(String host) {
        int pc = host.lastIndexOf(':');
        if (pc < 0) return host;
        for (int i = pc + 1; i < host.length(); i++) {
            char ch = host.charAt(i);
            if (ch < '0' || ch > '9') return host;   // 不是端口（IPv6 之类），原样用
        }
        return host.substring(0, pc);
    }

    private static boolean isSelfHost(String host) {
        return "example.test".equals(host) || host.endsWith(".example.test");
    }

    /** 后缀白名单："magi-reco.com" 匹配 "dorothy.magi-reco.com"，但不匹配 "evilmagi-reco.com"。 */
    private static boolean hostMatches(String host, String[] suffixes) {
        for (int i = 0; i < suffixes.length; i++) {
            String suf = suffixes[i];
            if (suf == null || suf.isEmpty()) continue;
            if (host.equals(suf)) return true;
            if (host.length() > suf.length()
                    && host.charAt(host.length() - suf.length() - 1) == '.'
                    && host.endsWith(suf)) return true;
        }
        return false;
    }

    // ==================================================================
    // 取数
    // ==================================================================

    private static WebResourceResponse fetchViaProxy(String origUrl, String proxyUrl,
                                                     Map<String, String> reqHeaders) {
        HttpURLConnection c = null;
        long t0 = System.currentTimeMillis();
        try {
            c = (HttpURLConnection) new URL(proxyUrl).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(PROXY_CONNECT_TIMEOUT_MS);
            c.setReadTimeout(PROXY_READ_TIMEOUT_MS);
            c.setRequestMethod("GET");
            // 带上 WebView 的请求头，但逐跳头与 Accept-Encoding 除外（见 isHopByHopRequestHeader）
            if (reqHeaders != null) {
                for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                    String k = e.getKey();
                    if (k == null || isHopByHopRequestHeader(k)) continue;
                    try { c.setRequestProperty(k, e.getValue()); } catch (Throwable ignore) {}
                }
            }

            int status = c.getResponseCode();
            if (status < 200 || status >= 400) {
                // 代理这边不正常就别硬撑，交回去让 WebView 直连
                CNLog.w(TAG, "代理取数 HTTP " + status + "，回退直连：" + origUrl);
                return null;
            }
            InputStream in = c.getInputStream();
            long ttfb = System.currentTimeMillis() - t0;

            String ctype = c.getContentType();
            String mime = mimeOf(ctype, origUrl);
            String enc  = charsetOf(ctype);

            Map<String, String> respHeaders = new HashMap<String, String>();
            Map<String, List<String>> hs = c.getHeaderFields();
            if (hs != null) {
                for (Map.Entry<String, List<String>> e : hs.entrySet()) {
                    String k = e.getKey();
                    List<String> v = e.getValue();
                    if (k == null || v == null || v.isEmpty()) continue;
                    // Transfer-Encoding / Content-Length / Connection：逐跳头，转交会让
                    //   WebView 按错误的分帧去读。
                    // Content-Encoding：HttpURLConnection 已经替我们解过压了，留着这行
                    //   等于告诉 WebView「这还是压缩的」，它会再解一次然后失败。
                    // Content-Type：已经拆成 mime + encoding 从构造函数传进去了，
                    //   再放一份进头里只会多一个可能对不上的真相。
                    if ("Transfer-Encoding".equalsIgnoreCase(k)
                            || "Content-Encoding".equalsIgnoreCase(k)
                            || "Content-Length".equalsIgnoreCase(k)
                            || "Content-Type".equalsIgnoreCase(k)
                            || "Connection".equalsIgnoreCase(k)) continue;
                    respHeaders.put(k, v.get(0));
                }
            }

            CNLog.i(TAG, "代理命中 " + ttfb + "ms " + origUrl);
            String reason = c.getResponseMessage();
            if (reason == null || reason.isEmpty()) reason = "OK";
            return new WebResourceResponse(mime, enc, status, reason, respHeaders, in);
        } catch (Throwable t) {
            CNLog.w(TAG, "代理取数失败，回退直连（" + t + "）：" + origUrl);
            if (c != null) { try { c.disconnect(); } catch (Throwable ignore) {} }
            return null;
        }
        // 成功时**不能** disconnect：InputStream 还要交给 WebView 继续读
    }

    /**
     * MIME 只用来告诉 WebView 怎么解析，取不到就按扩展名兜底。
     *
     * <p>兜底表与原 {@code WebViewClientImpl} 的那张保持一致——同一个文件不该因为
     * 走了代理就被当成另一种类型。
     */
    private static String mimeOf(String contentType, String url) {
        if (contentType != null) {
            int semi = contentType.indexOf(';');
            String m = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
            if (!m.isEmpty()) return m;
        }
        String p = url.toLowerCase(Locale.US);
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".js"))   return "application/javascript";
        if (p.endsWith(".css"))  return "text/css";
        if (p.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }

    private static String charsetOf(String contentType) {
        if (contentType == null) return "utf-8";
        int i = contentType.toLowerCase(Locale.US).indexOf("charset=");
        if (i < 0) return "utf-8";
        String cs = contentType.substring(i + 8).trim();
        int semi = cs.indexOf(';');
        if (semi >= 0) cs = cs.substring(0, semi).trim();
        return cs.isEmpty() ? "utf-8" : cs;
    }

    // ==================================================================
    // 配对测速（measure 模式）
    // ==================================================================

    /**
     * 对同一个 URL 各拉一次直连与代理，把两边的 TTFB 记进日志。
     *
     * <p><b>为什么必须在真机上量：</b>开发机（境外容器、出口还套着一层 agent proxy）
     * 量出来 {@code /stream/} 每次都比直连慢 2～8 倍，但那个数字对国内玩家毫无参考
     * 价值——国内直连 {@code dorothy.magi-reco.com} 可能很糟，而
     * {@code api.example.test} 是国内加速入口，符号完全可能反过来。既然做代理的目的
     * 是加速，就只能拿玩家设备上的数字来判。
     *
     * <p>成对拉同一个 URL 而不是各测各的，是为了把「这个对象本来就慢」从对比里消掉。
     * 只取前 {@value #MEASURE_SAMPLE_BYTES} 字节，够算 TTFB，不为了测速把流量打满。
     */
    private static void maybeMeasure(String origUrl, String proxyUrl) {
        long now = System.currentTimeMillis();
        long last = lastMeasureAt.get();
        if (now - last < MEASURE_INTERVAL_MS) return;
        if (measureRounds.get() >= MEASURE_MAX_ROUNDS) return;
        if (!lastMeasureAt.compareAndSet(last, now)) return;   // 抢到名额才测
        measureRounds.incrementAndGet();
        try {
            Thread t = new Thread(new Measure(origUrl, proxyUrl), "cnv-webproxy-measure");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignore) {}
    }

    private static final class Measure implements Runnable {
        private final String direct;
        private final String viaProxy;
        Measure(String d, String p) { this.direct = d; this.viaProxy = p; }

        @Override public void run() {
            long a = probe(direct);
            long b = probe(viaProxy);
            String verdict;
            if (a < 0 || b < 0)      verdict = "有一边失败";
            else if (b < a)          verdict = "代理快 " + (a - b) + "ms";
            else                     verdict = "直连快 " + (b - a) + "ms";
            CNLog.i(TAG, "配对测速 直连=" + fmt(a) + " 代理=" + fmt(b)
                         + "（" + verdict + "） " + direct);
        }

        private static String fmt(long v) { return v < 0 ? "失败" : (v + "ms"); }
    }

    /** @return 首字节耗时（ms）；失败返回 -1 */
    private static long probe(String url) {
        HttpURLConnection c = null;
        try {
            long t0 = System.currentTimeMillis();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(PROXY_CONNECT_TIMEOUT_MS);
            c.setReadTimeout(PROXY_READ_TIMEOUT_MS);
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=0-" + (MEASURE_SAMPLE_BYTES - 1));
            int status = c.getResponseCode();
            if (status < 200 || status >= 400) return -1;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096];
            if (in.read(buf) < 0) return -1;
            long ttfb = System.currentTimeMillis() - t0;
            try { in.close(); } catch (Throwable ignore) {}
            return ttfb;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignore) {} }
        }
    }
}
