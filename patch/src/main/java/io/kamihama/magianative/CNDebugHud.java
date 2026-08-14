package io.kamihama.magianative;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 屏幕上缘那行「调试模式：…」小字。
 *
 * <h2>为什么它必须离开悬浮窗</h2>
 *
 * 这行字是**监测**用的：它存在的全部意义，是让任何人（包括开发者自己）在任何
 * 一张截图上都能一眼看出「这台设备开着调试开关」。而它原先是调试悬浮窗的一个
 * {@code WindowManager} 小窗，于是同时被两道闸挡着：
 *
 * <ul>
 *   <li>native 总闸（{@code DEBUG_OVERLAY_ENABLED}）关掉 → 不挂；</li>
 *   <li>玩家/开发者撤掉了「显示在其他应用上层」权限 → 挂不上。</li>
 * </ul>
 *
 * <p>第二条尤其致命：某人开了调试开关，之后顺手回收了悬浮窗权限——开关还在生效，
 * 提示却没了，<b>恰好在最需要提示的时候失效</b>。而调试开关本身是<b>读文件</b>
 * 生效的，根本不依赖悬浮窗（见 {@link CNDebugFlags}），所以这不是「功能一起没了」，
 * 是「功能还在，指示灯灭了」。开发者也有忘事的时候，这行字就是为那种时候准备的。
 *
 * <h2>做法</h2>
 *
 * 不用 {@code WindowManager}，改挂在 Activity 的 {@code decorView} 上——那是应用
 * 自己的窗口，<b>不需要任何权限</b>。代价是它只覆盖本应用画面（悬浮窗能盖住整个
 * 屏幕），而这正好够用：要监测的是这个游戏的行为，截图截的也是这个游戏。
 *
 * <p>与总闸的关系也随之反过来：<b>本类不看总闸</b>。总闸管的是「能不能<i>改</i>
 * 开关、能不能开面板」，而「有开关正在生效就得说出来」跟能不能改无关——公测结束
 * 后总闸关掉，若某台设备上还留着 flag 文件，这行字照样要出现。
 *
 * <p>没有任何开关生效时 {@link CNDebugBridge#hudText()} 返回 null，整行隐藏，
 * 视觉与性能成本都是零。
 */
public final class CNDebugHud {
    private static final String TAG = "CNDebugHud";

    /**
     * 定期复查的周期。开关是**读文件**生效的，可能被 su 在运行期间改动。
     *
     * <p>两档：屏幕上已经有字时 5 秒（这台设备正在被调试，值得跟紧），什么都没
     * 有时 30 秒。区别不在省那几次 syscall，而在于**这行字现在挂在所有人机器上**
     * ——它不再受总闸和权限约束，正式包里也一直在跑。发布包上的答案永远是「一个
     * 开关都没开」，用不着每 5 秒确认一次。
     */
    private static final long REFRESH_ACTIVE_MS = 5000L;
    private static final long REFRESH_IDLE_MS = 30000L;

    private static TextView view;
    private static ViewGroup host;
    /**
     * 当前宿主 Activity 的<b>弱</b>引用。
     *
     * <p>强引用会把一个已经 destroy 的 Activity 连同它整棵视图树钉在静态字段上。
     * 主 Activity 是 {@code singleTask} 且 {@code configChanges} 盖了旋转/键盘，
     * 但**没盖** locale / uiMode / density / smallestScreenSize——切深色模式、改
     * 系统字体大小、进分屏都会在**同一个进程里**重建它。那时旧的 decorView 就成了
     * 泄漏，而这行字挂在旧窗口上，玩家一眼看去是「提示消失了」。
     */
    private static java.lang.ref.WeakReference<Activity> hostRef;
    private static ViewTreeObserver.OnGlobalLayoutListener raise;
    private static Handler ui;
    private static Thread poller;
    private static volatile boolean polling;

    private CNDebugHud() {}

    /**
     * 把这行字挂到给定 Activity 上（幂等）。任意线程可调，内部转 UI 线程。
     *
     * <p><b>不检查总闸，也不检查悬浮窗权限</b>——理由见类注释。
     */
    public static void mount(final Activity act) {
        if (act == null) return;
        try {
            if (ui == null) ui = new Handler(Looper.getMainLooper());
            if (Looper.myLooper() == Looper.getMainLooper()) mountOnUi(act);
            else ui.post(new MountTask(act));
        } catch (Throwable t) {
            CNLog.w(TAG, "调试提示条挂载失败（不影响游戏）: " + t);
        }
    }

    private static final class MountTask implements Runnable {
        private final Activity act;
        MountTask(Activity act) { this.act = act; }
        @Override public void run() { mountOnUi(act); }
    }

    private static void mountOnUi(Activity act) {
        try {
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            if (view != null && view.getParent() == decor) { refresh(); return; }
            detachOnUi();

            TextView tv = new TextView(act);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(dp(act, 10), dp(act, 3), dp(act, 10), dp(act, 3));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xB0000000);
            bg.setCornerRadius(dp(act, 10));
            tv.setBackground(bg);
            // 纯展示：绝不吃触摸，否则它会在游戏画面顶上开一个死区。
            tv.setClickable(false);
            tv.setFocusable(false);
            // 限宽 + 最多两行 + 省略号。文案那侧已经按 HUD_MAX_NAMES 折成「+N」，
            // 这里只是兜底：万一开关名以后变长，宁可看不全也不让它糊住游戏画面
            // ——要看全的话面板里有完整列表。
            int maxW = Math.max(dp(act, 160),
                    act.getResources().getDisplayMetrics().widthPixels * 3 / 4);
            tv.setMaxWidth(maxW);
            tv.setMaxLines(2);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setVisibility(View.GONE);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = dp(act, 4);
            decor.addView(tv, lp);
            view = tv;
            host = decor;
            hostRef = new java.lang.ref.WeakReference<Activity>(act);

            // 后加进 decorView 的东西（下载浮层、权限引导页）会盖住它，所以布局
            // 变化时把它抬回最前——只在真被盖住时抬，不无条件每帧重排。
            raise = new Raise();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(raise);

            startPoller();
            refresh();
            CNLog.i(TAG, "调试提示条已挂载（decorView，不需要悬浮窗权限）");
        } catch (Throwable t) {
            CNLog.w(TAG, "调试提示条挂载失败（不影响游戏）: " + t);
        }
    }

    private static final class Raise implements ViewTreeObserver.OnGlobalLayoutListener {
        @Override public void onGlobalLayout() {
            try {
                TextView v = view;
                ViewGroup h = host;
                if (v == null || h == null || v.getParent() != h) return;
                if (v.getVisibility() != View.VISIBLE) return;
                if (h.getChildAt(h.getChildCount() - 1) != v) v.bringToFront();
            } catch (Throwable ignore) {}
        }
    }

    /**
     * 催一次复查。<b>立即返回</b>，实际的读盘在轮询线程上做。
     *
     * <p>这里不能直接读：{@link CNDebugBridge#hudText()} 会走两趟
     * {@code File.list()} 加两次 JNI 取表，而本方法的调用方全在 UI 线程上
     * （挂载时、调试面板保存之后）。这行字现在挂在**所有人**的机器上——不再受
     * 总闸和权限约束——所以「每几秒在 UI 线程上碰一次文件系统」不再是开发者
     * 自己承担的成本，而是每个玩家每一局都在付。
     */
    public static void refresh() {
        Thread p = poller;
        if (p != null) p.interrupt();
    }

    /** 轮询线程（幂等）。守护线程，进程退出不拦着。 */
    private static void startPoller() {
        if (polling && poller != null && poller.isAlive()) return;
        polling = true;
        Thread t = new Thread(new Poll(), "cnv-debug-hud");
        t.setDaemon(true);
        poller = t;
        t.start();
    }

    private static final class Poll implements Runnable {
        @Override public void run() {
            // 退出判据是「polling 还开着 **且 poller 还是我**」，不能只看 polling。
            // Activity 被重建时走的是 detachOnUi(polling=false) → startPoller
            // (polling=true)：旧线程正睡着，醒来时标志已经被重新打开，于是它接着
            // 跑——每重建一次 Activity 就多一条线程、多一份读盘。
            while (polling && poller == Thread.currentThread()) {
                String text;
                try {
                    text = CNDebugBridge.hudText();
                } catch (Throwable t) {
                    text = null;
                }
                Handler h = ui;
                if (h != null) h.post(new Apply(text));
                long wait = (text == null || text.length() == 0)
                        ? REFRESH_IDLE_MS : REFRESH_ACTIVE_MS;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    // refresh() 催的，立刻再读一轮。不复位中断标志——本线程
                    // 只在这一处等，没有别的代码会看这个标志。
                }
            }
        }
    }

    /** 把读到的文案贴上去，顺带确认这行字还挂在**当前**那个窗口上。 */
    private static final class Apply implements Runnable {
        private final String text;
        Apply(String text) { this.text = text; }
        @Override public void run() {
            try {
                // Activity 被重建过的话，view 还挂在旧 decorView 上：那既是泄漏，
                // 玩家看到的也是「提示消失了」。发现宿主换人就整体重挂——重挂会
                // 再催一次 refresh，下一轮才贴文案，所以这里直接返回。
                Activity cur = RestClient.getCurrentActivity();
                Activity known = hostRef == null ? null : hostRef.get();
                if (cur != null && cur != known) { mountOnUi(cur); return; }

                TextView v = view;
                if (v == null) return;
                if (text == null || text.length() == 0) {
                    v.setVisibility(View.GONE);
                } else {
                    v.setText(text);
                    v.setVisibility(View.VISIBLE);
                    v.bringToFront();
                }
            } catch (Throwable ignore) {}
        }
    }

    /** Activity 要走了：摘掉监听与视图，别拿着旧窗口。 */
    public static void detach() {
        try {
            if (ui != null && Looper.myLooper() != Looper.getMainLooper()) {
                ui.post(new DetachTask());
                return;
            }
        } catch (Throwable ignore) {}
        detachOnUi();
    }

    private static final class DetachTask implements Runnable {
        @Override public void run() { detachOnUi(); }
    }

    private static void detachOnUi() {
        polling = false;
        try {
            Thread p = poller;
            if (p != null) p.interrupt();
        } catch (Throwable ignore) {}
        poller = null;
        try {
            if (host != null && raise != null) {
                host.getViewTreeObserver().removeOnGlobalLayoutListener(raise);
            }
        } catch (Throwable ignore) {}
        raise = null;
        try {
            TextView v = view;
            if (v != null && v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
        } catch (Throwable ignore) {}
        view = null;
        host = null;
        hostRef = null;
    }

    private static int dp(android.content.Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- JVM 回归测试入口 ----
    /** 本类<b>不</b>看 native 总闸，也不看悬浮窗权限。判据钉在这里。 */
    public static boolean gatedByOverlayForTest() { return false; }
}
