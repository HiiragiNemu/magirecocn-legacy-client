package io.kamihama.magianative;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebView;

/**
 * 把前端导航到游戏自己的**第 0 章（序章）** 页面。
 *
 * <h3>为什么改成这条路</h3>
 *
 * 之前是从 native 硬压场景：拦 {@code web::SceneCommand::pushSceneTop}，命中标记
 * 就改调 {@code pushScenePrologue}。真机结果是——序章直接从最后那场战斗开始，
 * <b>剧情文字一句都不出现</b>。
 *
 * <p>反汇编 {@code PrologueSceneLayer::notifyJs} 说明了原因：它调的是
 * {@code web::WebViewManager::evaluateJS}，把控制权**交回前端 JS**，下发的语句
 * 形如 {@code <callback>("OP020");}。而那个 {@code callback} 正是
 * {@code PrologueSceneLayerInfo} 的第三个构造参数、也就是 {@code pushScenePrologue}
 * 那个 JSON 里的 {@code callback} 字段——我们没给，引擎兜底成一个单字符的串，
 * 生成的 JS 是残的，前端从头到尾没收到任何通知。日志里
 * 「图层构造 → 0.26 秒后 notifyJs(OP020)」也印证了：那不是「OP020 播完了」，
 * 是「JS，去放 OP020」。
 *
 * <p>换句话说：<b>序章是前端驱动的流程，native 只是它的播放器</b>。从 native
 * 单方面压场景，原理上就只能放出 native 自己能放的部分（战斗），放不出剧情。
 *
 * <h3>现在怎么做</h3>
 *
 * 走游戏自己的路由。前端是 Backbone Router + hash 路由（{@code js/_common/router.js}
 * 里 {@code Backbone.Router.extend}），路由表 {@code js/_common/routes.js} 中本来
 * 就有第 0 章的一整套页面：
 *
 * <pre>
 *   Scene0Top / Scene0StorySelectBeforeFilm1 / Scene0StorySelectAfterFilm1
 *   Scene0BattleSelect / Scene0SideStorySelect
 * </pre>
 *
 * 所以只要把 WebView 的 hash 设成 {@code Scene0Top}，前端就当作玩家自己点进来
 * 一样，剧情、演出、战斗全按正常流程走。
 *
 * <p>（顺带一提，{@code routes.js} 里那套 {@code Backdoor*} 调试路由要
 * {@code window.isDebug} 为真才注册；第 0 章不需要，它是正式路由。）
 *
 * <h3>时机</h3>
 *
 * 前端要先起来。这里等到 WebView 出现、且 {@code location.href} 已经进到主页
 * （{@code MyPage}）才导航——那时路由与登录态都就绪了。等不到就放弃，标记留着
 * 下次启动再试，不会把玩家卡住。
 */
public final class CNScene0Nav {

    private static final String TAG = "MagiaCNScene0";

    /** 第 0 章首页的路由名，取自前端 routes.js。 */
    private static final String ROUTE = "Scene0Top";

    /** 等前端就绪的上限：180 × 500ms = 90 秒（要覆盖登录 + 资源校验）。 */
    private static final int  WAIT_TRIES   = 180;
    private static final long WAIT_STEP_MS = 500L;

    /** 导航之后确认是否真的跳过去了。 */
    private static final int  CONFIRM_TRIES = 20;

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** evaluateJavascript 的返回值（异步）落在这里。 */
    private static volatile String lastJsResult;

    private CNScene0Nav() {}

    /**
     * 若「本次启动要去第 0 章」的标记还在，就起一个线程等前端就绪并导航。
     * 不抛异常，不阻塞调用方；重复调用只有第一次生效。
     */
    public static void startIfPending() {
        try {
            if (!CNTutorialPrompt.isArmed()) return;
            if (!STARTED.compareAndSet(false, true)) return;
            CNLog.i(TAG, "标记存在，本次启动将导航到第 0 章");
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
            catch (Throwable th) { CNLog.e(TAG, "导航第 0 章失败: " + th, th); }
        }
    }

    private static void run0() {
        WebView wv = awaitFrontEnd();
        if (wv == null) {
            // 标记不清：下次启动再试。玩家不会因此卡住，只是这次没跳成。
            CNLog.w(TAG, "等不到前端就绪，本次不导航（标记保留，下次启动再试）");
            return;
        }
        CNLog.i(TAG, "前端已就绪，导航到 " + ROUTE);
        // Backbone 的 hash 路由：设置 location.hash 即触发路由回调。
        // 用 try/catch 包住并把结果回传，免得 WebView 里抛异常我们这边一无所知。
        eval(wv, "(function(){try{location.hash='" + ROUTE + "';return 'OK';}"
                 + "catch(e){return 'ERR:'+e;}})()");

        for (int i = 0; i < CONFIRM_TRIES; i++) {
            sleep(500L);
            String href = evalSync(wv, "(function(){try{return String(location.href);}"
                                       + "catch(e){return 'ERR';}})()");
            if (href != null && href.contains(ROUTE)) {
                CNLog.i(TAG, "已进入第 0 章：" + href);
                CNTutorialPrompt.set(false);   // 一次性：消费掉标记
                return;
            }
        }
        CNLog.w(TAG, "导航后未确认到第 0 章，标记保留，下次启动再试");
    }

    /**
     * 等前端就绪：既要有 WebView，又要它已经进到主页。
     *
     * <p>只判断「WebView 存在」不够——刚建出来时还在加载标题画面，此时改 hash
     * 会被随后的正常跳转冲掉。等到 href 里出现 MyPage（主页）再动手。
     */
    private static WebView awaitFrontEnd() {
        for (int i = 0; i < WAIT_TRIES; i++) {
            WebView wv = findWebView();
            if (wv != null) {
                String href = evalSync(wv, "(function(){try{return String(location.href);}"
                                           + "catch(e){return 'ERR';}})()");
                if (href != null && href.contains("MyPage")) return wv;
                if (i % 20 == 0) CNLog.i(TAG, "等待前端进入主页…href=" + href);
            } else if (i % 20 == 0) {
                CNLog.i(TAG, "等待 WebView 出现…");
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
    //     Error in ...CNScene0Nav$3$1.class:
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
    //     Error in ...CNScene0Nav$ResultCallback.class:
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
