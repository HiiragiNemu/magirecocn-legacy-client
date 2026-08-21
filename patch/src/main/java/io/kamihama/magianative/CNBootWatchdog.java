package io.kamihama.magianative;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 开机看门狗：浮层撤下之后，前端界面迟迟不出现就自动重载一次页面。
 *
 * <h3>它在救什么</h3>
 *
 * 2026-08-21 的玩家日志（0097 / 0099 / 0100）是同一个形状：
 *
 * <pre>
 *   21:29:21  本地模块全部供给完毕、画出 connecting 转圈
 *   21:29:21  POST /magica/api/page/TopPage 发出（透传直连）
 *   ……        20 秒里一条日志都没有
 *   21:29:41  错误弹窗的贴图被取走 —— 前端超时了
 *   （之后 DSL::onEnter 再也不触发，引擎停在原地）
 * </pre>
 *
 * 对照正常的 0085 / 0092 / 0098：转圈之后 3~10 秒就调进 native
 * （{@code DSL::onEnter} → {@code pushSceneTop 放行}），标题页正常出来。
 *
 * <p>黑屏的直接原因在前端自己：{@code TopPage.js} 的 {@code fetch()} 头一句就是
 * {@code b.setWebView(!1)}——它<b>主动把 WebView 藏了</b>再去发请求。请求不回来，
 * 就没有人再把它显示回去，于是玩家看到的是一块纯黑。玩家当时是靠游戏自带的
 * 「重新加载」走出来的：WebView 被重建、页面重跑一遍，一次就成。
 *
 * <p>前端侧的修法（错误分支补 {@code d.setWebView()}）已经在 前端那一侧
 * 里做了。这个看门狗是<b>兜底</b>：前端要是压根没走到错误分支——JS 抛了、
 * 请求发都没发出去、WebView 自己死了——那边的修法一句也帮不上，只有从 Java
 * 这侧看「屏幕上到底有没有东西」才发现得了。
 *
 * <h3>为什么判据是「WebView 可见」</h3>
 *
 * 因为它就是症状本身。玩家抱怨的是黑屏，而浮层撤下之后本该接管屏幕的正是
 * 标题页——那是一张 HTML 页面，只可能由 WebView 画。WebView 可见 = 玩家看得见
 * 东西 = 没有黑屏，不必再去猜是哪一环卡住的。
 *
 * <p>已知的漏判：如果 WebView 可见但内容是空白，这里会判成「起来了」。真出现
 * 那种情况得另配判据，但目前所有实证日志里的失败都是「WebView 一直没被显示」。
 *
 * <h3>为什么等这么久（{@value #DEADLINE_MS} ms）</h3>
 *
 * 前端自己对 TopPage 那条请求的上限是 {@value #FRONTEND_TOPPAGE_TIMEOUT_MS} ms
 * （前端那一侧 的 ajaxPrefilter 给 {@code /magica/api/page/TopPage} 单独
 * 设的 {@code options.timeout=6E4}）。看门狗必须排在它<b>后面</b>：抢在前面重载，
 * 等于把一次本来还有希望回来的请求掀掉，而且掀完还会再等一遍——越救越慢。
 *
 * <p>代价是明摆着的：真卡住时玩家要黑屏一分半才等到自动重载。这个数字仍不好看，
 * 但它由前端那个 6E4 决定，不由这里决定。**要再缩短就得先把 6E4 调下来**，
 * 两边一起改；单独把这里改小只会打断前端自己的等待。
 *
 * <h3>只开一枪</h3>
 *
 * 整个进程生命周期里最多重载一次。重载之后如果还是起不来，前端那边的错误弹窗
 * （现在会先调 {@code d.setWebView()}，看得见了）会接手；再自动重载下去就成了
 * 无限刷新，玩家连报错都看不到。
 */
public final class CNBootWatchdog {

    private static final String TAG = "启动看门狗";

    /**
     * 前端给 TopPage 那条请求的超时上限，单位 ms。
     *
     * <p>这个值<b>不在本仓库</b>——它在 前端那一侧 的
     * {@code magica/js/libs/jquery-3.7.1.min.js} 里，形如
     * {@code options.timeout=6E4}。这里写死一份只是为了让
     * {@link #DEADLINE_MS} 的推导能被读懂；两边不同步时，以那边为准，
     * 并把这里一起改。
     */
    static final long FRONTEND_TOPPAGE_TIMEOUT_MS = 60000L;

    /** 浮层撤下之后等这么久，前端还没起来就重载。必须 &gt; 前端自己的超时。 */
    static final long DEADLINE_MS = FRONTEND_TOPPAGE_TIMEOUT_MS + 30000L;

    /** 轮询间隔。走主线程 Handler，不额外起线程。 */
    static final long TICK_MS = 2000L;

    /**
     * 连续观测到几次「前端界面在」才算它真起来了。
     *
     * <p>为什么不能只看一次快照：浮层撤下与 WebView 建好几乎同时发生（0092 的日志里
     * 「下载浮层关闭」和「已接管 WebViewClient」在同一秒，TopPage.html 是下一秒），
     * 而<b>前端要跑到 TopPage.js 的 fetch() 才会调 setWebView(false) 把自己藏起来</b>。
     * 也就是说武装后的头一两秒，WebView 可能还停在「引擎刚建好、尚未被前端藏起」的
     * 可见状态——那不是「前端起来了」，只是还没轮到它藏。只看一次就会在这里误判成功、
     * 当场解除看门狗，而失败会话（0100：浮层 21:29:19、TopPage 21:29:21）撞的正是
     * 同一个窗口，等于该救的时候恰好不救。
     *
     * <p>取 3 次（{@value #TICK_MS} ms × 3 = 6 秒连续可见）：瞬间可见撑不了这么久，
     * 而真进了标题页之后 WebView 会一直在，很快攒满。
     */
    static final int VISIBLE_STREAK = 3;

    /**
     * 到点时判断「要不要开枪」的门槛：武装以来<b>最长</b>连续可见次数低于它，才重载。
     *
     * <p>这个数必须在两种错法之间取中间值，两边都掉不得：
     *
     * <ul>
     *   <li>取 1（等价于「哪怕见过一次就不重载」）——上面那段瞬间可见会把它闩上，
     *       于是该救的黑屏永远不救，看门狗变成摆设；
     *   <li>取 0（等价于「到点必开枪」）——玩家要是在标题页露面后 6 秒内就点进了战斗
     *       （WebView 被藏），攒不满 {@value #VISIBLE_STREAK} 次早退，一分半后就会在
     *       战斗中途重载页面，把结算流程打断。
     * </ul>
     *
     * <p>取 2（{@value #TICK_MS} ms × 2 = 4 秒连续可见）：瞬间可见只够一个 tick，
     * 攒不到 2；而真渲染出来的标题页轻松超过。
     *
     * <p>⚠ 前提是「瞬间可见撑不过一个 tick」，只有真机能最终确认。要复现就开
     * skipBootWatchdog 对照跑一次，看日志里报的最长连续次数。
     */
    static final int SAFE_STREAK = 2;

    /** 一个进程只武装一次，也只重载一次。 */
    private static final AtomicBoolean ARMED = new AtomicBoolean(false);

    private CNBootWatchdog() {}

    /**
     * 浮层撤下时调一次。之后由主线程 Handler 自己续期，调用方不必管。
     *
     * <p>本身吞掉所有异常：它挂在浮层收尾路径上，而那条路径出任何事都会让引擎
     * 停在半路——看门狗自己把启动搞挂，比它要救的毛病还严重。
     */
    public static void arm() {
        try {
            if (CNDebugFlags.isOn(CNDebugFlags.SKIP_BOOT_WATCHDOG)) {
                CNLog.i(TAG, "skipBootWatchdog 开着，不武装");
                return;
            }
            // 强制序章期间 native 看门狗每 250ms 把前端界面按回隐藏（见
            // MagiaLegacy.cpp 的 uiWatchdogMain），一藏就是整段序章。那种“看不见
            // WebView”是有意为之，不是故障，这里必须让开。
            if (CNTutorialPrompt.isArmed()) {
                CNLog.i(TAG, "序章标记在，本次不武装（序章全程本来就压着前端界面）");
                return;
            }
            if (!ARMED.compareAndSet(false, true)) {
                return;                       // 已经武装过，不重复
            }
            Handler h = new Handler(Looper.getMainLooper());
            long deadline = SystemClock.elapsedRealtime() + DEADLINE_MS;
            CNLog.i(TAG, "已武装：" + (DEADLINE_MS / 1000) + " 秒内前端界面还没出来就自动重载一次页面"
                    + "（前端自己对 TopPage 的超时是 " + (FRONTEND_TOPPAGE_TIMEOUT_MS / 1000) + " 秒）");
            h.postDelayed(new Tick(h, deadline, SystemClock.elapsedRealtime()), TICK_MS);
        } catch (Throwable t) {
            CNLog.w(TAG, "武装失败，本次不设看门狗: " + t);
        }
    }

    /**
     * 前端界面起来了没有。<b>只在主线程调</b>——读 View 状态本来就该在主线程。
     */
    static boolean frontEndUp() {
        try {
            WebView wv = CNWebProxy.currentWebView();
            if (wv == null) return false;
            if (wv.getVisibility() != View.VISIBLE) return false;
            return wv.getWidth() > 0 && wv.getHeight() > 0;
        } catch (Throwable t) {
            // 读不出来就当没起来。宁可多等一轮，也不要因为一次异常就判成功、
            // 把兜底关掉。
            return false;
        }
    }

    /** 主线程上的轮询：起来了就收工，到点还没起来就开那一枪。 */
    private static final class Tick implements Runnable {
        private final Handler handler;
        private final long deadlineMs;
        private final long armedAtMs;
        /** 当前连续看见的次数，见 {@link CNBootWatchdog#VISIBLE_STREAK}。 */
        private int visibleStreak;
        /** 武装以来最长的一段连续可见，见 {@link CNBootWatchdog#SAFE_STREAK}。 */
        private int maxStreak;

        Tick(Handler handler, long deadlineMs, long armedAtMs) {
            this.handler = handler;
            this.deadlineMs = deadlineMs;
            this.armedAtMs = armedAtMs;
        }

        @Override public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                if (frontEndUp()) {
                    visibleStreak++;
                    if (visibleStreak > maxStreak) maxStreak = visibleStreak;
                } else {
                    visibleStreak = 0;
                }
                if (visibleStreak >= VISIBLE_STREAK) {
                    CNLog.i(TAG, "前端界面已稳定出现（武装后 " + ((now - armedAtMs) / 1000)
                            + " 秒，连续 " + visibleStreak + " 次），看门狗收工");
                    return;
                }
                if (now >= deadlineMs) {
                    // 只在「从来没有稳稳露过面」时才开枪。露过面就说明启动已经走过黑屏
                    // 那一关——此刻看不见多半是玩家进了战斗/剧情之类由引擎接管屏幕的
                    // 场合，这时候重载页面是纯粹的破坏。
                    if (maxStreak >= SAFE_STREAK) {
                        CNLog.i(TAG, "到点时前端界面不在，但期间稳定露过面（最长连续 "
                                + maxStreak + " 次）—— 不是开机黑屏，不重载，看门狗收工");
                        return;
                    }
                    fire(now - armedAtMs, maxStreak);
                    return;
                }
                handler.postDelayed(this, TICK_MS);
            } catch (Throwable t) {
                // 轮询自己炸了就不再续期：留个日志，别把主线程拖下水。
                CNLog.w(TAG, "轮询异常，看门狗停止: " + t);
            }
        }
    }

    /** 开那一枪：重载当前页面。已在主线程。 */
    private static void fire(long waitedMs, int maxStreak) {
        WebView wv = CNWebProxy.currentWebView();
        if (wv == null) {
            CNLog.w(TAG, "等了 " + (waitedMs / 1000) + " 秒前端界面仍未出现，"
                    + "但取不到 WebView（引擎还没建或刚被销毁），本次放弃重载");
            return;
        }
        String url = null;
        try { url = wv.getUrl(); } catch (Throwable ignore) {}
        CNLog.w(TAG, "等了 " + (waitedMs / 1000) + " 秒，前端界面始终没有稳定出现"
                + "（最长连续 " + maxStreak + " 次，门槛 " + SAFE_STREAK + "）—— 判定卡在开机，"
                + "自动重载一次页面（本进程只做这一次）。当前 URL=" + url);
        try {
            wv.reload();
            CNLog.i(TAG, "已下发 WebView.reload()");
        } catch (Throwable t) {
            CNLog.e(TAG, "重载失败", t);
        }
    }
}
