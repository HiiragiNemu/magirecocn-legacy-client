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

    /** 定期复查的周期。开关是**读文件**生效的，可能被 su 在运行期间改动。 */
    private static final long REFRESH_MS = 5000L;

    private static TextView view;
    private static ViewGroup host;
    private static ViewTreeObserver.OnGlobalLayoutListener raise;
    private static Handler ui;
    private static Runnable ticker;

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
            // 「大型滚木」的防线（2026-08-13 反馈）：开关名是英文小驼峰，开几个
            // 就能连成横跨整屏的一长条。限宽 + 最多两行 + 省略号，宁可看不全也
            // 不让它糊住游戏画面——要看全的话面板里有完整列表。
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

            // 后加进 decorView 的东西（下载浮层、权限引导页）会盖住它，所以布局
            // 变化时把它抬回最前——只在真被盖住时抬，不无条件每帧重排。
            raise = new Raise();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(raise);

            refresh();
            startTicker();
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
     * 重读当前生效的开关并刷新这行字。
     *
     * <p>读盘，所以不是每帧调：挂载时一次、定期一次、以及调试面板保存之后一次。
     */
    public static void refresh() {
        TextView v = view;
        if (v == null) return;
        String text;
        try {
            text = CNDebugBridge.hudText();
        } catch (Throwable t) {
            text = null;
        }
        if (text == null || text.length() == 0) {
            v.setVisibility(View.GONE);
        } else {
            v.setText(text);
            v.setVisibility(View.VISIBLE);
            v.bringToFront();
        }
    }

    private static void startTicker() {
        if (ui == null) return;
        if (ticker != null) ui.removeCallbacks(ticker);
        ticker = new Ticker();
        ui.postDelayed(ticker, REFRESH_MS);
    }

    private static final class Ticker implements Runnable {
        @Override public void run() {
            try {
                if (view == null) return;
                refresh();
                if (ui != null) ui.postDelayed(this, REFRESH_MS);
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
        try {
            if (ui != null && ticker != null) ui.removeCallbacks(ticker);
        } catch (Throwable ignore) {}
        ticker = null;
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
    }

    private static int dp(android.content.Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- JVM 回归测试入口 ----
    /** 本类<b>不</b>看 native 总闸，也不看悬浮窗权限。判据钉在这里。 */
    public static boolean gatedByOverlayForTest() { return false; }
}
