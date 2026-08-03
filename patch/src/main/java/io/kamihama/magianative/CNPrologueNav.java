package io.kamihama.magianative;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;

/**
 * 让前端从头播放<b>开场序章</b>（OP000 → OP010 → … → OP999）。
 *
 * <h3>此路不通：从 native 硬压场景</h3>
 *
 * 最早的做法是拦 {@code web::SceneCommand::pushSceneTop}、改调
 * {@code pushScenePrologue}。真机结果是序章直接从最后那场战斗开始，
 * <b>剧情文字一句都不出现</b>。
 *
 * <p>反汇编 {@code PrologueSceneLayer::notifyJs} 说明了原因：它调
 * {@code web::WebViewManager::evaluateJS}，把控制权**交回前端**，下发的语句
 * 形如 {@code <callback>("OP020");}。而 {@code callback} 正是
 * {@code PrologueSceneLayerInfo} 的第三个构造参数、也就是
 * {@code pushScenePrologue} 那个 JSON 里的 {@code callback} 字段——我们没给，
 * 引擎兜底成一个单字符的串，生成的 JS 是残的，前端从头到尾没收到任何通知。
 * 日志里「图层构造 → 0.26 秒后 notifyJs(OP020)」也印证了：那不是「OP020 播完
 * 了」，是「JS，去放 OP020」。序章是前端驱动的流程，native 只是它的播放器。
 *
 * <h3>此路也不通：跳 Scene0Top</h3>
 *
 * 路由表里有一整套 {@code Scene0*}，但 <b>Scene 0 是搭载在本作里的另一个衍生
 * 作品的名字，不是序章</b>。按名字望文生义会把玩家送进那个衍生作品的页面。
 *
 * <h3>现在的做法：走真实新号的那条路</h3>
 *
 * 前端 {@code js/top/TopPage.js} 里写得很清楚：
 *
 * <pre>
 *   if ("OP000" == a.storage.user.get("tutorialId"))
 *       var f = new a.PopupClass({title:"ゲーム開始", content:v}, null, d);
 *   else c();
 * </pre>
 *
 * 也就是说——标题页发现账号的 {@code tutorialId} 是 {@code OP000} 时，会弹
 * 「ゲーム開始」，确认后走 {@code d()}：挂上 {@code #commandDiv} 的
 * {@code nativeCallback} 监听，再由 native 逐段播序章，每段结束回调一次
 * （{@code "prologue"} 表示整套放完）。这正是新号第一次进游戏的路径。
 *
 * <p>{@code a.storage.user} 是前端自己的 Backbone 模型，可以直接改。所以：
 * <ol>
 *   <li>{@code require(["backboneCommon"], a => a.storage.user.set("tutorialId","OP000"))}</li>
 *   <li>导航到 {@code #/TopPage}，让它按 OP000 重新走一遍</li>
 * </ol>
 *
 * 不碰服务端：{@code tutorialIdRegist} 只在 id 前进时才 POST
 * {@code prologueRegister}，而 {@code "OP010".split("TU")[1]|0 == 0} 小于
 * {@code TU999} 的 999，条件不成立，本来也不会上报。
 *
 * <h3>时机</h3>
 *
 * 要等主页真的渲染稳定才动手。第一版只要 href 里出现 MyPage 就跳，结果白屏：
 * 那一刻前端正在加载主页自己的模块，两次切页叠在一起，前端 router 的
 * {@code a.interrupt} 分支让被打断的那次 {@code pageObj.init()} 不会跑，
 * 两个页面都没 init 完。
 */
public final class CNPrologueNav {

    private static final String TAG = "MagiaCNPrologue";

    /**
     * 第 0 章首页的路由名，取自前端 routes.js。
     *
     * <p>hash 带前导斜杠，与前端自己的形态一致（真机上看到的是
     * {@code index.html#/TopPage}）。不带斜杠 Backbone 也能路由——上一版就是
     * 这么跳成的——但没必要跟它自己的 history 记录长得不一样。
     */
    /** 目标页面：标题页。序章由它按 tutorialId 拉起。 */
    private static final String ROUTE      = "TopPage";
    private static final String ROUTE_HASH = "/TopPage";
    /** 触发序章的 tutorialId。见 TopPage.js 里那句 if ("OP000" == ...)。 */
    private static final String PROLOGUE_ID = "OP000";
    /** 出问题时退回这里，别把玩家留在白屏上。 */
    private static final String HOME_HASH  = "/MyPage";

    /** 等前端就绪的上限：180 × 500ms = 90 秒（要覆盖登录 + 资源校验）。 */
    private static final int  WAIT_TRIES   = 180;
    private static final long WAIT_STEP_MS = 500L;

    /**
     * 主页要连续这么多轮都「已渲染」才算稳定（6 × 500ms = 3 秒）。
     *
     * <p>上一版只要 href 里出现 MyPage 就立刻跳，结果白屏。日志显示那一刻前端
     * 正在加载主页自己的模块（MemoriaUtil / QuestUtil / puellaHistoria/CreateModel
     * …），我们的跳转和它的初始化叠在了一起。前端 router 里有个 {@code a.interrupt}
     * 分支专门处理「切页被打断」，被打断的那次 {@code pageObj.init()} 就不会跑
     * ——两个页面都没 init 完，屏幕自然是白的。
     */
    private static final int STABLE_ROUNDS = 6;
    /** 稳定之后再多等一会儿，给收尾动画和延迟请求留余量。 */
    private static final long SETTLE_MS = 2000L;

    /** 导航之后确认是否真的跳过去了。 */
    private static final int  CONFIRM_TRIES = 20;

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** evaluateJavascript 的返回值（异步）落在这里。 */
    private static volatile String lastJsResult;

    private CNPrologueNav() {}

    /**
     * 若「本次启动要去第 0 章」的标记还在，就起一个线程等前端就绪并导航。
     * 不抛异常，不阻塞调用方；重复调用只有第一次生效。
     */
    public static void startIfPending() {
        try {
            if (!CNTutorialPrompt.isArmed()) return;
            if (!STARTED.compareAndSet(false, true)) return;
            CNLog.i(TAG, "标记存在，本次启动将从头播放开场序章");
            Thread t = new Thread(new NavTask(), "cnv-scene0-nav");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            try { android.util.Log.e(TAG, "startIfPending 失败", t); } catch (Throwable ignore) {}
        }
    }

    private static final class NavTask implements Runnable {
        @Override public void run() {
            try { run0(); }
            catch (Throwable th) { CNLog.e(TAG, "序章导航失败: " + th, th); }
        }
    }

    private static void run0() {
        WebView wv = awaitFrontEnd();
        if (wv == null) {
            // 标记不清：下次启动再试。玩家不会因此卡住，只是这次没跳成。
            CNLog.w(TAG, "等不到主页稳定，本次不动作（标记保留，下次启动再试）");
            return;
        }
        CNLog.i(TAG, "主页已稳定，" + SETTLE_MS + "ms 后开始");
        sleep(SETTLE_MS);

        // 设 tutorialId 与跳转**必须在同一次执行里**，而且要用同步形式取模块。
        //
        // 上一版分成两次 evaluateJavascript，并且用了 require([...], cb) 的
        // 异步形式——RequireJS 即使模块已加载也会异步回调。于是两件事的先后
        // 完全不保证，读回的值也必然是 null。真机表现正是「跳回了标题页，
        // 但没进序章」：hash 先变了，tutorialId 还没写上，TopPage 一 init
        // 读到的仍是旧值。
        //
        // require('name') 的 CommonJS 同步形式对**已加载**的模块直接返回，
        // backboneCommon 是全站基础模块，此刻必定已加载。
        String r = evalSync(wv,
            "(function(){try{"
            + "var a=require('backboneCommon');"
            + "var b=a.storage.user.get('tutorialId');"
            + "a.storage.user.set('tutorialId','" + PROLOGUE_ID + "');"
            + "var c=a.storage.user.get('tutorialId');"
            + "location.hash='" + ROUTE_HASH + "';"
            + "return 'before='+b+' after='+c;"
            + "}catch(e){return 'ERR:'+e;}})()");
        CNLog.i(TAG, "设 tutorialId 并跳转：" + r);

        for (int i = 0; i < CONFIRM_TRIES; i++) {
            sleep(500L);
            String st = probe(wv);
            if (st != null && st.contains(ROUTE) && rendered(st)) {
                CNLog.i(TAG, "已回到标题页并渲染：" + st);
                CNTutorialPrompt.set(false);   // 一次性：消费掉标记
                return;
            }
        }
        // 没回到标题页就别留个烂摊子：退回主页，标记保留下次再试。
        CNLog.e(TAG, "未确认回到标题页，退回主页（标记保留）最后一次探测=" + probe(wv));
        eval(wv, "(function(){try{location.hash='" + HOME_HASH + "';return 'OK';}"
                 + "catch(e){return 'ERR:'+e;}})()");
    }

    /**
     * 探一次前端状态，返回 {@code <href>|<#mainContent 的子元素个数>}。
     *
     * <p>光看 href 不够：URL 改了不代表页面画出来了，上一版白屏时 URL 就是对的。
     * {@code #mainContent} 是前端的主内容容器（TutorialUtil.js 里也是按这个 id
     * 取的），它有子元素才说明当前页真的渲染过了。
     */
    private static String probe(WebView wv) {
        return evalSync(wv,
            "(function(){try{var m=document.getElementById('mainContent');"
            // 同步形式：异步回调拿不到值，探测串里永远是 '?'
            + "var t='?';try{t=require('backboneCommon').storage.user.get('tutorialId');}"
            + "catch(e2){}"
            + "return String(location.href)+'|'+(m?m.childElementCount:-1)+'|tu='+t;}"
            + "catch(e){return 'ERR|-1|tu=?';}})()");
    }

    /**
     * 探测结果是否表示「当前页已渲染」。
     *
     * <p>探测串形如 {@code <href>|<childElementCount>|tu=<tutorialId>}，
     * 这里取中间那段。
     */
    private static boolean rendered(String st) {
        if (st == null) return false;
        String[] parts = st.split("\\|");
        if (parts.length < 2) return false;
        try { return Integer.parseInt(parts[1].trim()) > 0; }
        catch (NumberFormatException e) { return false; }
    }

    /**
     * 等主页真的稳定：href 是 MyPage、主内容区已渲染，且连续
     * {@link #STABLE_ROUNDS} 轮都如此。
     *
     * <p>只判断「WebView 存在」不够——刚建出来时还在标题画面。只判断「href 里
     * 有 MyPage」也不够——那一刻主页往往才刚开始加载自己的模块，这时跳走会把
     * 它的初始化打断，两个页面都 init 不完，白屏。
     */
    private static WebView awaitFrontEnd() {
        int stable = 0;
        for (int i = 0; i < WAIT_TRIES; i++) {
            WebView wv = findWebView();
            if (wv != null) {
                String st = probe(wv);
                if (st != null && st.contains("MyPage") && rendered(st)) {
                    if (++stable >= STABLE_ROUNDS) return wv;
                } else {
                    stable = 0;
                    if (i % 20 == 0) CNLog.i(TAG, "等待主页渲染…" + st);
                }
            } else {
                stable = 0;
                if (i % 20 == 0) CNLog.i(TAG, "等待 WebView 出现…");
            }
            sleep(WAIT_STEP_MS);
        }
        return null;
    }

    // ==================================================================
    // WebView 存取
    // ==================================================================

    /**
     * 在当前 Activity 的视图树里找 WebView。
     *
     * <p>认 {@code android.webkit.WebView} 基类而不是具体类名：游戏主界面用的是
     * {@code jp.f4samurai.web.WebViewImpl}，包里另有一套 cocos 的
     * {@code Cocos2dxWebView}，认基类两者通吃。
     */
    private static WebView findWebView() {
        try {
            Activity act = RestClient.getCurrentActivity();
            if (act == null || act.getWindow() == null) return null;
            View root = act.getWindow().peekDecorView();
            return root == null ? null : search(root);
        } catch (Throwable t) {
            return null;
        }
    }

    private static WebView search(View v) {
        if (v instanceof WebView) return (WebView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView r = search(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    // ⚠ 下面这几个都写成**具名**内部类，不用匿名类。
    // 匿名类套匿名类（Runnable 里再放一个 ValueCallback）会让 d8 8.2.2 直接崩：
    //     Error in ...CNPrologueNav$3$1.class:
    //     java.lang.NullPointerException: Cannot invoke "String.length()"
    // 这个工程别处也一律用具名内部类，照做。

    /** 在 UI 线程上跑一段 JS，不等结果。 */
    private static final class EvalTask implements Runnable {
        private final WebView wv;
        private final String  js;
        EvalTask(WebView wv, String js) { this.wv = wv; this.js = js; }
        @Override public void run() {
            try { wv.evaluateJavascript(js, null); }
            catch (Throwable t) { CNLog.w(TAG, "evaluateJavascript 失败: " + t); }
        }
    }

    /** 跑一段 JS 并把结果交给闩。 */
    private static final class EvalSyncTask implements Runnable {
        private final WebView wv;
        private final String  js;
        private final java.util.concurrent.CountDownLatch latch;
        EvalSyncTask(WebView wv, String js, java.util.concurrent.CountDownLatch latch) {
            this.wv = wv; this.js = js; this.latch = latch;
        }
        @Override public void run() {
            try { wv.evaluateJavascript(js, new ResultCallback(latch)); }
            catch (Throwable t) { latch.countDown(); }
        }
    }

    // ⚠ 实现的是**原始类型** ValueCallback，不是 ValueCallback<String>。
    // 带泛型时 javac 会生成 Signature 属性，而 d8 8.2.2 处理它时直接崩：
    //     Error in ...CNPrologueNav$ResultCallback.class:
    //     java.lang.NullPointerException: Cannot invoke "String.length()"
    // 去掉泛型即绕开；运行期传进来的本来就是 String，转型是安全的。
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static final class ResultCallback implements ValueCallback {
        private final java.util.concurrent.CountDownLatch latch;
        ResultCallback(java.util.concurrent.CountDownLatch latch) { this.latch = latch; }
        @Override public void onReceiveValue(Object value) {
            lastJsResult = (value == null) ? null : String.valueOf(value);
            latch.countDown();
        }
    }

    private static void eval(WebView wv, String js) {
        try { wv.post(new EvalTask(wv, js)); }
        catch (Throwable t) { CNLog.w(TAG, "post 到 WebView 失败: " + t); }
    }

    /**
     * 跑一段 JS 并等它的返回值。{@code evaluateJavascript} 的回调在 UI 线程上，
     * 而本类跑在工作线程，所以拿个闩等；超时返回 null，绝不把工作线程卡死。
     */
    private static String evalSync(WebView wv, String js) {
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        lastJsResult = null;
        try {
            wv.post(new EvalSyncTask(wv, js, latch));
            if (!latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            return null;
        }
        String v = lastJsResult;
        // evaluateJavascript 返回的是 JSON 字面量，字符串会带引号
        if (v != null && v.length() >= 2 && v.charAt(0) == '"' && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
