package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 调试悬浮窗<b>本体</b>：给公测玩家的「自助检修台」。设计理念与文案的事实来源是
 * {@code docs/DEBUG_OVERLAY_DESIGN_PRINCIPLES.md}（下称「设计」），本类只是它的投影。
 *
 * <h2>本类的角色（设计 P8 / §8）</h2>
 *
 * 纯视图 + 转发层：开关名单与状态全部来自 {@link CNDebugBridge#flagTable()}，
 * 落盘走 {@link CNDebugBridge#applyAndRestart}，进度只读 {@link CNCNDownloadUI}
 * 的公开数组，日志页只是 {@link CNLog} 的第二个渲染器（过滤状态也在 CNLog 的
 * 静态字段里，两边永远同步）。本类<b>没有</b>自己的下载实现、日志缓冲和状态副本
 * ——设计 §11-10 的分界审计要求全文 grep 不到自建下载/写文件的痕迹。
 *
 * <h2>线程与读盘时机（接线清单「线程与时机」表）</h2>
 *
 * {@code flagTable}/{@code hudText} 会读盘：只在<b>打开面板</b>与<b>保存之后</b>
 * 各读一次（{@link #loadFlags}），渲染期间用的都是那份快照。{@code shareLog}
 * 内部有 flush + 打包、{@code applyAndRestart} 里 {@link CNRestart} 会在调用
 * 线程 sleep，两个都不许在 UI 线程跑——都走 {@link #bgExecutor} 这个单线程
 * 执行器（起的是线程池工作线程，本体自己不维护任何后台循环）。
 *
 * <h2>常驻提示条已搬走</h2>
 *
 * 屏幕上缘那行「调试模式：…」小字现在归 {@link CNDebugHud} 管，挂在 Activity 的
 * decorView 上、不需要悬浮窗权限、也不看 native 总闸。理由见那边的类注释：它是
 * <b>监测</b>用的，而原先它同时被总闸和悬浮窗权限两道闸挡着——某人开了开关又撤掉
 * 权限，开关照旧生效、提示却没了，恰好在最需要它的时候失效。
 *
 * <p>挂载入口是经反射调的 {@link #mount(Activity)}（见 {@link CNDebugBridge#mount}，
 * 反射的理由写在它那里）。本类任何公开路径都不抛异常：悬浮窗出问题最坏应该是
 * 「悬浮窗不出现」，绝不能是「游戏挂了」。
 */
public final class CNDebugOverlay {

    private static final String TAG = "CNDebugOverlay";

    // ── 页面 id ─────────────────────────────────────────────────────
    private static final String PAGE_HOME      = "home";
    private static final String PAGE_RESOURCES = "res";
    private static final String PAGE_ENTER     = "enter";
    private static final String PAGE_GROUPS    = "groups";
    private static final String PAGE_LOG       = "log";
    private static final String PAGE_CAT_PREFIX = "cat:";   // + 分类 id

    // ── 分类体系（设计 §4.2，六类 + P6 降级的「其他」）─────────────────
    private static final String GROUP_A = "A";
    private static final String GROUP_B = "B";
    private static final String GROUP_C = "C";
    private static final String GROUP_D = "D";
    private static final String GROUP_E = "E";
    private static final String GROUP_F = "F";
    private static final String GROUP_OTHER = "OTHER";

    // ── 建议标签（设计 §5 advice 枚举）───────────────────────────────
    private static final String ADVICE_KEEP_OFF    = "KEEP_OFF";
    private static final String ADVICE_LAST_RESORT = "LAST_RESORT";
    private static final String ADVICE_FEEDBACK    = "FEEDBACK";
    private static final String ADVICE_HARMLESS    = "HARMLESS";
    private static final String ADVICE_DEV_ONLY    = "DEV_ONLY";

    // ── 状态色（设计 §9：等待灰/下载蓝/完成绿/出错红/待重启琥珀）─────────
    private static final int C_WAIT   = 0xFF8D8496;
    private static final int C_RUN    = 0xFF3E7CB1;
    private static final int C_DONE   = 0xFF3E9C50;
    private static final int C_ERROR  = 0xFFD64545;
    private static final int C_AMBER  = 0xFFB26A00;
    private static final int C_AMBER_BG = 0x33B26A00;
    private static final int C_DANGER = 0xFFD64545;

    private static final String PREFS = "cn_debug_overlay";
    private static final String PREF_HINT_SHOWN = "hint_shown";

    private static final long IDLE_FADE_MS = 5000L;
    private static final float IDLE_ALPHA = 0.55f;   // 设计 §9：闲置约 55% 透明
    private static final long RES_REFRESH_MS = 800L;

    // ── 窗口与视图引用（全部静态，本类无实例）─────────────────────────
    private static Activity activity;
    private static WindowManager wm;
    private static Handler ui;
    private static SharedPreferences prefs;

    private static TextView ballView;
    private static WindowManager.LayoutParams ballParams;
    private static FrameLayout panelRoot;
    private static TextView pageTitleView;
    private static LinearLayout pageContent;
    private static LinearLayout bottomBar;
    private static FrameLayout permGuide;      // 挂在 decorView 上（没权限时还没有自己的窗口）
    private static ViewTreeObserver.OnGlobalLayoutListener guideRaise;  // 见 keepGuideOnTop
    private static ViewGroup guideHost;        // 摘监听要用同一个宿主
    private static TextView hintView;          // 首次引导气泡（独立小窗）
    private static FrameLayout modalView;      // 面板内的确认/结果弹窗

    // ── 页面栈与面板状态 ─────────────────────────────────────────────
    private static final ArrayList<String> pages = new ArrayList<String>();
    private static String[][] flagSnapshot;          // 开面板/保存后各读一次
    private static final HashSet<String> desired = new HashSet<String>();  // 勾选只改内存（P4）
    private static int dangerTapStage;               // F 类二次展开：0 折叠 1 已点一次 2 已展开
    private static String expandedRadio;             // 手风琴：当前展开的单选控件 id
    private static boolean resourcePageActive;       // 资源页轮询开关
    private static Runnable myLogListener;           // 日志页占着的 listener（离开时按身份归还）
    private static Runnable prevLogListener;         // 被顶掉的那个（多半是下载浮层的），离场原样还回去

    // 本体唯一的后台通道（单线程执行器）：shareLog（内部 flush+打包）与
    // applyAndRestart（CNRestart 在调用线程 sleep）都不许在 UI 线程跑，都经它
    // 转后台；本体不维护任何其它后台执行设施。
    private static ExecutorService bgExecutor;

    private CNDebugOverlay() {}

    // ══ 挂载（反射入口，UI 线程）══════════════════════════════════════

    /**
     * 唯一的挂载入口（{@link CNDebugBridge#mount} 经反射调它，约定在 UI 线程）。
     *
     * <p>顺序即接线清单 A 节的契约：总闸关 → 什么都不建直接 false；没权限 →
     * 挂权限引导页（设计 §3，此时还不能建自己的窗口，引导页挂在 decorView 上）；
     * 一切就绪 → 建小球 + HUD，然后调一次 {@code setActive(true)}（重复挂载被
     * {@code ballView != null} 挡住，保证「只调一次」）。全程不抛。
     */
    public static boolean mount(Activity act) {
        try {
            if (act == null) return false;
            if (!CNDebugBridge.overlayAllowed()) return false;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                // 契约是 UI 线程；万一调用方没守住，转回主线程而不是在错的线程碰窗口。
                new Handler(Looper.getMainLooper()).post(new MountRetry(act));
                return false;
            }
            activity = act;
            // 本面板整套配色都是反射读下载浮层那份调色板取的，而调试悬浮窗完全
            // 可能在下载浮层从未建出来时挂起（资源早装好，直接进游戏）。先确保
            // 调色板按玩家的主题加载过一次，否则读到的是未初始化的 0 = 全透明。
            CNCNDownloadUI.ensurePalette(act);
            if (ballView != null) return true;      // 已挂上；setActive 只调过一次
            if (!CNDebugBridge.canDrawOverlays(act)) {
                // 这一行不能省。2026-08-13 那次排查里，整条挂载链一行日志都没打，
                // 于是「没权限」「没打进包」「时序不对」三种可能在日志上长得一模
                // 一样，只能靠读代码猜。
                CNLog.i(TAG, "没有悬浮窗权限，挂权限引导页并开始轮询");
                showPermissionGuide(act);
                startPermPoll(act);
                return false;
            }
            dismissPermissionGuide();
            wm = (WindowManager) act.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return false;
            if (ui == null) ui = new Handler(Looper.getMainLooper());
            if (prefs == null) prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            createBall(act);
            CNDebugBridge.setActive(true);          // 只调一次（见上）
            CNLog.i(TAG, "调试悬浮窗本体已挂载");
            return true;
        } catch (Throwable t) {
            CNLog.w(TAG, "挂载调试悬浮窗失败（不影响游戏）: " + t);
            return false;
        }
    }

    private static final class MountRetry implements Runnable {
        private final Activity act;
        MountRetry(Activity act) { this.act = act; }
        @Override public void run() { mount(act); }
    }

    // ══ 悬浮球（设计 §9：48dp、白字「</>」、可拖贴边、闲置半透明）════════

    private static void createBall(Activity act) {
        TextView ball = new TextView(act);
        ball.setText("</>");
        ball.setTextColor(0xFFFFFFFF);
        ball.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        ball.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        ball.setGravity(Gravity.CENTER);
        ball.setBackground(ballBackground(act));
        ball.setOnTouchListener(new BallTouch());

        int size = dp(act, 48);
        WindowManager.LayoutParams lp = overlayParams(size, size);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        int sw = act.getResources().getDisplayMetrics().widthPixels;
        int sh = act.getResources().getDisplayMetrics().heightPixels;
        lp.x = Math.max(0, sw - size);
        lp.y = sh / 3;
        wm.addView(ball, lp);
        ballView = ball;
        ballParams = lp;
        scheduleIdleFade();
        maybeShowHint(act);
    }

    /** 首次引导气泡（设计 §9：小球旁一次性气泡，点过后永久消失）。 */
    private static void maybeShowHint(Activity act) {
        try {
            if (prefs != null && prefs.getBoolean(PREF_HINT_SHOWN, false)) return;
            TextView hint = new TextView(act);
            hint.setText("遇到下载问题了吗？点我");
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            hint.setTextColor(color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B));
            hint.setPadding(dp(act, 12), dp(act, 8), dp(act, 12), dp(act, 8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color("COLOR_GLASS", 0xCCFFFFFF));
            bg.setCornerRadius(dp(act, 14));
            bg.setStroke(dp(act, 1), color("COLOR_GLASS_STK", 0x33B53C8C));
            hint.setBackground(bg);
            hint.setOnClickListener(new HintDismissClick());
            WindowManager.LayoutParams lp = overlayParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = Math.max(0, ballParams.x - dp(act, 190));
            lp.y = ballParams.y + dp(act, 8);
            wm.addView(hint, lp);
            hintView = hint;
        } catch (Throwable t) {
            CNLog.i(TAG, "引导气泡没挂出来（忽略）: " + t);
        }
    }

    private static final class HintDismissClick implements View.OnClickListener {
        @Override public void onClick(View v) { dismissHint(true); }
    }

    private static void dismissHint(boolean remember) {
        if (remember && prefs != null) {
            prefs.edit().putBoolean(PREF_HINT_SHOWN, true).apply();
        }
        View v = hintView;
        hintView = null;
        if (v != null) {
            try { wm.removeView(v); } catch (Throwable ignore) {}
        }
    }

    /**
     * 小球手势：拖动跟手、松手贴边、没拖动就算一次点按（开/关面板）。
     * static 嵌套类——匿名/非静态内部类会带 this$0，d8 会崩（check-d8-pitfalls）。
     */
    private static final class BallTouch implements View.OnTouchListener {
        private float downRawX;
        private float downRawY;
        private int startX;
        private int startY;
        private boolean moved;

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startX = ballParams.x;
                    startY = ballParams.y;
                    moved = false;
                    wakeBall();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    int slop = android.view.ViewConfiguration.get(v.getContext())
                            .getScaledTouchSlop();
                    if (!moved && Math.abs(dx) <= slop && Math.abs(dy) <= slop) return true;
                    moved = true;
                    ballParams.x = startX + (int) dx;
                    ballParams.y = startY + (int) dy;
                    clampBall(v);
                    try { wm.updateViewLayout(ballView, ballParams); } catch (Throwable ignore) {}
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        dismissHint(true);
                        togglePanel();
                    } else {
                        snapToEdge(v);
                    }
                    scheduleIdleFade();
                    return true;
                default:
                    return false;
            }
        }

        /** 松手贴边：滚到离得近的那一侧。 */
        private void snapToEdge(View v) {
            int sw = v.getResources().getDisplayMetrics().widthPixels;
            int size = ballView == null ? dp(v, 48) : ballView.getWidth();
            if (size <= 0) size = dp(v, 48);
            ballParams.x = ballParams.x + size / 2 < sw / 2 ? 0 : Math.max(0, sw - size);
            clampBall(v);
            try { wm.updateViewLayout(ballView, ballParams); } catch (Throwable ignore) {}
        }

        private void clampBall(View v) {
            int sw = v.getResources().getDisplayMetrics().widthPixels;
            int sh = v.getResources().getDisplayMetrics().heightPixels;
            int size = ballView == null ? dp(v, 48) : ballView.getWidth();
            if (size <= 0) size = dp(v, 48);
            ballParams.x = Math.max(0, Math.min(ballParams.x, Math.max(0, sw - size)));
            ballParams.y = Math.max(0, Math.min(ballParams.y, Math.max(0, sh - size)));
        }
    }

    private static void wakeBall() {
        if (ballView != null) ballView.setAlpha(1.0f);
        scheduleIdleFade();
    }

    /** 小球底色：每次现取 accent——挂载期间切过昼夜主题时，重取一次就能跟上。 */
    private static GradientDrawable ballBackground(Context ctx) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color("COLOR_ACCENT", 0xFFD63384));
        bg.setStroke(dp(ctx, 1), color("COLOR_GLASS_STK", 0x33B53C8C));
        return bg;
    }

    /**
     * 重取一次 accent 色刷新小球背景。只在开面板时调（点击路径必经过），
     * 不引入轮询——挂载期间切了昼夜主题，下次开面板小球就换上新色。
     */
    private static void refreshBallColor(Context ctx) {
        TextView ball = ballView;
        if (ball == null) return;
        ball.setBackground(ballBackground(ctx));
    }

    private static void scheduleIdleFade() {
        if (ui == null) return;
        ui.removeCallbacks(IDLE_FADE);
        ui.postDelayed(IDLE_FADE, IDLE_FADE_MS);
    }

    private static final Runnable IDLE_FADE = new IdleFadeTask();

    private static final class IdleFadeTask implements Runnable {
        @Override public void run() {
            // 面板开着时不半透明：玩家正在用。
            if (ballView != null && panelRoot == null) ballView.setAlpha(IDLE_ALPHA);
        }
    }

    // ══ 常驻 HUD（独立小窗，不随小球收起；null 时整窗隐藏）═══════════════



    // ══ 权限引导页（设计 §3：没授权时替代一切，挂在 decorView 上）═════════

    private static void showPermissionGuide(final Activity act) {
        try {
            dismissPermissionGuide();
            // 🔴 「谁在最上层」是会变的，所以不能只在挂的这一刻选一次宿主。
            //
            // CNCNDownloadUI 的下载浮层也挂在 decorView 上、全屏。上一版写的是
            // 「浮层在就挂进浮层，不在才退回 decorView」——判断本身没错，错在
            // **问的时机**：本引导页由 mountDebugOverlay 的看门狗触发，而它在
            // Activity 出现后几毫秒就就绪了，那时下载浮层还没建出来。于是这个
            // 三元表达式实际上永远走 decorView 那一支，几百毫秒后下载浮层被加进
            // 同一个 decorView，稳稳盖在引导页上面——玩家看到的还是「什么都没
            // 发生」，和 2026-08-13 第一次排查时一模一样。
            //
            // 所以宿主固定选 decorView（它一定在、也不会被换掉），改为在每次
            // 布局后把引导页重新抬到最前。谁后加进来都盖不住它。
            ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
            FrameLayout mask = new FrameLayout(act);
            mask.setBackgroundColor(color("COLOR_DIM", 0x88000000));
            mask.setClickable(true);
            mask.setFocusable(true);

            LinearLayout card = dialogCard(act);
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    Math.min(dp(act, 380),
                            act.getResources().getDisplayMetrics().widthPixels - dp(act, 40)),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            mask.addView(card, cardLp);

            TextView title = text(act, "需要一项系统授权", 17f,
                    color("COLOR_ACCENT", 0xFFD63384), true);
            card.addView(title, rowLp(act, 0, 10));

            TextView body = text(act,
                    "调试小助手要浮在游戏画面上，系统要求你亲手开一次「显示在其他应用上层」。"
                    + "只在第一次开启时需要。\n\n开完后回到游戏，下次启动小助手就会出现。",
                    13.5f, color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), false);
            body.setLineSpacing(dp(act, 2), 1f);
            card.addView(body, rowLp(act, 0, 16));

            LinearLayout buttons = new LinearLayout(act);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.END);
            TextView later = dialogButton(act, "暂不开启", false, false);
            TextView go = dialogButton(act, "去开启授权", true, false);
            later.setOnClickListener(new PermLaterClick());
            go.setOnClickListener(new PermGoClick());
            buttons.addView(later);
            LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            goLp.leftMargin = dp(act, 10);
            buttons.addView(go, goLp);
            card.addView(buttons, rowLp(act, 0, 0));

            decor.addView(mask, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mask.bringToFront();
            permGuide = mask;
            keepGuideOnTop(decor);
            CNLog.i(TAG, "权限引导页已显示（宿主=decorView，随布局持续置顶）");
        } catch (Throwable t) {
            CNLog.w(TAG, "权限引导页没挂出来: " + t);
        }
    }

    /**
     * 让引导页在后来者加入后仍留在最上层。
     *
     * <p>只在**真的被盖住时**才抬（判据：它不是宿主的最后一个孩子）。无条件
     * 每帧 bringToFront 会让宿主每次布局都重排一次子视图，等于给下载页白白加了
     * 一份持续开销。
     */
    private static void keepGuideOnTop(final ViewGroup host) {
        if (host == null) return;
        GuideRaise r = new GuideRaise(host);
        host.getViewTreeObserver().addOnGlobalLayoutListener(r);
        guideRaise = r;
        guideHost = host;
    }

    private static final class GuideRaise
            implements ViewTreeObserver.OnGlobalLayoutListener {
        private final ViewGroup host;
        GuideRaise(ViewGroup host) { this.host = host; }
        @Override public void onGlobalLayout() {
            try {
                View g = permGuide;
                if (g == null || g.getParent() != host) return;
                if (host.getChildAt(host.getChildCount() - 1) != g) g.bringToFront();
            } catch (Throwable ignore) {}
        }
    }

    private static final class PermLaterClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            // 「暂不开启」可退出，下次启动再引导（设计 §10）——不记任何状态。
            dismissPermissionGuide();
        }
    }

    private static final class PermGoClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            Activity act = activity;
            boolean opened = act != null && CNDebugBridge.requestOverlayPermission(act);
            if (!opened) {
                toast("拉不起系统授权页，请手动到系统设置里找「显示在其他应用上层」");
            }
            // 授权页往返后给一次自动重试：玩家开完回来，不用非得重启才看到小球。
            if (ui != null && act != null) {
                ui.postDelayed(new PermRecheck(act), 10_000L);
            }
        }
    }

    /**
     * 权限轮询。<b>不依赖玩家点「去开启授权」那个按钮</b>——他完全可能自己摸到
     * 系统设置里开掉（2026-08-13 就是这么试的），甚至根本没看见引导页。
     * 只要权限到手就挂上，挂上即停。
     */
    private static final long PERM_POLL_MS      = 5000L;
    private static final long PERM_POLL_SLOW_MS = 30000L;
    /**
     * 快节奏轮询的次数（5 秒 × 24 ≈ 2 分钟），之后转 30 秒一次的慢节奏。
     *
     * <p>原先是 5 秒 × 120 然后<b>彻底停下</b>。玩家去系统设置里翻「显示在其他
     * 应用上层」这一项，慢一点、或者中途被别的事打断，回来就已经过了那 10 分钟：
     * 权限明明开好了，小球还是不出现，而日志里只有一句「等待超时」——看起来就
     * 像功能坏了。授权这件事没有截止时间，轮询也不该有。
     */
    private static final int  PERM_POLL_FAST = 24;
    private static int permPolls;

    private static void startPermPoll(Activity act) {
        if (ui == null) ui = new Handler(Looper.getMainLooper());
        permPolls = 0;
        ui.postDelayed(new PermPoll(act), PERM_POLL_MS);
    }

    private static final class PermPoll implements Runnable {
        private final Activity act;
        PermPoll(Activity act) { this.act = act; }
        @Override public void run() {
            try {
                if (ballView != null) return;                 // 已经挂上了
                if (act.isFinishing()) return;                // Activity 没了，别再拿着它
                if (CNDebugBridge.canDrawOverlays(act)) {
                    CNLog.i(TAG, "检测到悬浮窗权限已授予，挂载小球（等了 "
                            + permPolls + " 轮）");
                    mount(act);
                    return;
                }
                permPolls++;
                if (permPolls == PERM_POLL_FAST) {
                    CNLog.i(TAG, "悬浮窗权限仍未授予，轮询转为 30 秒一次（不再停）");
                }
                long next = permPolls < PERM_POLL_FAST ? PERM_POLL_MS : PERM_POLL_SLOW_MS;
                if (ui != null) ui.postDelayed(new PermPoll(act), next);
            } catch (Throwable ignore) {}
        }
    }

    private static final class PermRecheck implements Runnable {
        private final Activity act;
        PermRecheck(Activity act) { this.act = act; }
        @Override public void run() {
            try {
                if (ballView == null && CNDebugBridge.canDrawOverlays(act)) mount(act);
            } catch (Throwable ignore) {}
        }
    }

    private static void dismissPermissionGuide() {
        View v = permGuide;
        permGuide = null;
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
        // 置顶监听跟着一起摘掉：留着的话每次布局都要多跑一遍判断，
        // 而且它持着宿主 ViewGroup 的强引用。
        try {
            if (guideRaise != null && guideHost != null) {
                guideHost.getViewTreeObserver()
                        .removeOnGlobalLayoutListener(guideRaise);
            }
        } catch (Throwable ignore) {}
        guideRaise = null;
        guideHost = null;
    }

    // ══ 面板窗口 ═══════════════════════════════════════════════════════

    private static void togglePanel() {
        if (panelRoot != null) closePanel();
        else openPanel();
    }

    /**
     * 打开面板：这是两个「读盘时机」之一（另一个是保存后）。快照一次 flagTable，
     * 之后整轮渲染都用它；HUD 也在这里重读。
     */
    private static void openPanel() {
        Activity act = activity;
        if (act == null || wm == null || panelRoot != null) return;
        try {
            loadFlags();
            CNDebugHud.refresh();
            refreshBallColor(act);   // 小球底色只在挂载时取过一次，借开面板重取一次

            FrameLayout root = new FrameLayout(act);
            root.setBackgroundColor(color("COLOR_DIM", 0x88000000));
            root.setOnClickListener(new PanelMaskClick());

            int sw = act.getResources().getDisplayMetrics().widthPixels;
            int sh = act.getResources().getDisplayMetrics().heightPixels;
            LinearLayout card = dialogCard(act);
            card.setOnClickListener(new ConsumeClick());
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    Math.min(dp(act, 420), Math.max(1, sw - dp(act, 32))),
                    Math.max(1, sh * 4 / 5), Gravity.CENTER);
            root.addView(card, cardLp);

            // 顶栏：返回 / 标题 / 关闭
            LinearLayout top = new LinearLayout(act);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView back = text(act, "‹ 返回", 14f, color("COLOR_ACCENT2", 0xFF9C5BC2), true);
            back.setPadding(dp(act, 4), dp(act, 6), dp(act, 12), dp(act, 6));
            back.setOnClickListener(new BackClick());
            pageTitleView = text(act, "", 16f, color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), true);
            TextView close = text(act, "✕", 16f, color("COLOR_SUB", 0xFF6E5276), true);
            close.setPadding(dp(act, 12), dp(act, 6), dp(act, 4), dp(act, 6));
            close.setOnClickListener(new ClosePanelClick());
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            top.addView(back, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            top.addView(pageTitleView, titleLp);
            top.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            back.setTag("back");
            card.addView(top, rowLp(act, 0, 6));

            ScrollView scroll = new ScrollView(act);
            scroll.setTag("pagescroll");
            pageContent = new LinearLayout(act);
            pageContent.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(pageContent, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            card.addView(scroll, scrollLp);

            bottomBar = new LinearLayout(act);
            bottomBar.setOrientation(LinearLayout.HORIZONTAL);
            bottomBar.setGravity(Gravity.END);
            card.addView(bottomBar, rowLp(act, 8, 0));

            WindowManager.LayoutParams lp = overlayParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            wm.addView(root, lp);
            panelRoot = root;

            pages.clear();
            pages.add(PAGE_HOME);
            render();
            wakeBall();
        } catch (Throwable t) {
            CNLog.w(TAG, "打开调试面板失败: " + t);
            panelRoot = null;
        }
    }

    private static void closePanel() {
        leaveLogPage();
        stopResourcePolling();
        View v = panelRoot;
        panelRoot = null;
        pageContent = null;
        bottomBar = null;
        pageTitleView = null;
        modalView = null;
        pages.clear();
        dangerTapStage = 0;
        expandedRadio = null;
        if (v != null) {
            try { wm.removeView(v); } catch (Throwable ignore) {}
        }
    }

    private static final class PanelMaskClick implements View.OnClickListener {
        @Override public void onClick(View v) { closePanel(); }
    }

    private static final class ClosePanelClick implements View.OnClickListener {
        @Override public void onClick(View v) { closePanel(); }
    }

    private static final class BackClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (pages.size() > 1) {
                String leaving = pages.get(pages.size() - 1);
                if (PAGE_LOG.equals(leaving)) leaveLogPage();
                if (PAGE_RESOURCES.equals(leaving)) stopResourcePolling();
                pages.remove(pages.size() - 1);
                render();
            }
        }
    }

    private static final class ConsumeClick implements View.OnClickListener {
        @Override public void onClick(View v) {}
    }

    private static final class EntryClick implements View.OnClickListener {
        private final String page;
        EntryClick(String page) { this.page = page; }
        @Override public void onClick(View v) {
            pages.add(page);
            render();
        }
    }

    // ══ 页面渲染 ═══════════════════════════════════════════════════════

    private static String currentPage() {
        return pages.isEmpty() ? PAGE_HOME : pages.get(pages.size() - 1);
    }

    private static void render() {
        LinearLayout content = pageContent;
        if (content == null || panelRoot == null) return;
        stopResourcePolling();
        leaveLogPage();
        content.removeAllViews();
        bottomBar.removeAllViews();
        bottomBar.setVisibility(View.GONE);
        View back = panelRoot.findViewWithTag("back");
        String page = currentPage();
        if (back != null) back.setVisibility(pages.size() > 1 ? View.VISIBLE : View.INVISIBLE);

        if (PAGE_HOME.equals(page)) renderHome(content);
        else if (PAGE_RESOURCES.equals(page)) renderResources(content);
        else if (PAGE_ENTER.equals(page)) renderEnter(content);
        else if (PAGE_GROUPS.equals(page)) renderGroups(content);
        else if (PAGE_LOG.equals(page)) renderLog(content);
        else if (page.startsWith(PAGE_CAT_PREFIX)) {
            renderCategory(content, page.substring(PAGE_CAT_PREFIX.length()));
        } else {
            renderHome(content);
        }
    }

    // ── 首页：状态卡（结论式，P3）+ 四入口 ────────────────────────────

    private static void renderHome(LinearLayout content) {
        Activity act = activity;
        setTitle("调试小助手");

        // 状态卡：只说结论（设计 P3 / §7）
        LinearLayout card = innerCard(content);
        TextView statusTitle = text(act, homeStatusConclusion(), 14.5f,
                color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), true);
        card.addView(statusTitle, rowLp(act, 0, 4));
        TextView statusSub = text(act,
                "好奇点开看看？平时不用动这里——随便看不影响游戏，改动要点「应用并重启」才会保存。",
                12f, color("COLOR_SUB", 0xFF6E5276), false);
        statusSub.setLineSpacing(dp(act, 2), 1f);
        card.addView(statusSub, rowLp(act, 0, 0));

        addEntry(content, "资源修复", "某个资源包坏了？单独重下它，不用全部重来", PAGE_RESOURCES);
        addEntry(content, "进入游戏", "资源还在后台处理时，可以停在资源页或放行进游戏", PAGE_ENTER);
        addEntry(content, "排查开关 · 进阶", "每个开关都写清了用途和后果，看懂再动", PAGE_GROUPS);
        addEntry(content, "日志与求助", "三步把日志打包发出来，截图时带上屏幕顶部的小字", PAGE_LOG);
    }

    private static String homeStatusConclusion() {
        int pending = countPending(flagSnapshot);
        int busy = 0;
        try { busy = CNDebugBridge.busyCount(); } catch (Throwable ignore) {}
        if (pending > 0 && busy > 0) {
            return "有 " + pending + " 个改动待重启 · 有 " + busy + " 个资源包在处理";
        }
        if (pending > 0) return "有 " + pending + " 个改动待重启";
        if (busy > 0) return "有 " + busy + " 个资源包在处理";
        return "一切正常";
    }

    private static void addEntry(LinearLayout content, String title, String sub, String page) {
        Activity act = activity;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("COLOR_GLASS", 0xCCFFFFFF));
        bg.setCornerRadius(dp(act, 14));
        bg.setStroke(dp(act, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        row.setBackground(bg);
        row.setOnClickListener(new EntryClick(page));
        TextView t = text(act, title, 14.5f, color("COLOR_ACCENT", 0xFFD63384), true);
        row.addView(t, rowLp(act, 0, 2));
        TextView s = text(act, sub, 12f, color("COLOR_SUB", 0xFF6E5276), false);
        s.setLineSpacing(dp(act, 2), 1f);
        row.addView(s, rowLp(act, 0, 0));
        content.addView(row, rowLp(act, 0, 10));
    }

    // ── 资源修复：15 包列表 + 四态结论 + 重下（确认框归本体）────────────

    private static void renderResources(LinearLayout content) {
        Activity act = activity;
        setTitle("资源修复");
        addGuideCard(content, new String[] {
                "下载一直卡住不动",
                "提示校验失败",
                "列表里某个包出错变红",
        });

        int busy = 0;
        try { busy = CNDebugBridge.busyCount(); } catch (Throwable ignore) {}
        TextView counter = text(act, "重下任务 " + busy + "/3（到上限时按钮会变灰）",
                12f, color("COLOR_SUB", 0xFF6E5276), false);
        content.addView(counter, rowLp(act, 2, 8));

        String[] names;
        try { names = CNDebugBridge.resourceNames(); }
        catch (Throwable t) { names = new String[0]; }
        for (int i = 0; i < names.length; i++) {
            content.addView(resourceRow(act, i, names[i]), rowLp(act, 0, 8));
        }
        startResourcePolling();
    }

    private static View resourceRow(Activity act, int index, String name) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("COLOR_GLASS", 0xCCFFFFFF));
        bg.setCornerRadius(dp(act, 12));
        bg.setStroke(dp(act, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        row.setBackground(bg);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameView = text(act, (index + 1) + ". " + name, 13f,
                color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), true);
        head.addView(nameView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int st = resourceStatus(index);
        TextView state = text(act, resourceStateText(st), 11.5f, resourceStateColor(st), true);
        head.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean busyNow;
        try { busyNow = CNDebugBridge.isBusy(index); }
        catch (Throwable t) { busyNow = false; }
        int busyTotal;
        try { busyTotal = CNDebugBridge.busyCount(); }
        catch (Throwable t) { busyTotal = 0; }
        TextView reload = dialogButton(act, "重下", false, false);
        reload.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        boolean enabled = !busyNow && busyTotal < 3;
        reload.setEnabled(enabled);
        reload.setAlpha(enabled ? 1.0f : 0.4f);
        if (enabled) reload.setOnClickListener(new ResourceReloadClick(index));
        LinearLayout.LayoutParams reloadLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reloadLp.leftMargin = dp(act, 8);
        head.addView(reload, reloadLp);
        row.addView(head, rowLp(act, 0, 0));

        if (st == CNCNDownloadUI.ST_RUNNING) {
            row.addView(progressBar(act, resourceProgress(index)), rowLp(act, 8, 0));
            String detail = resourceDetail(index);
            if (detail != null) {
                TextView d = text(act, detail, 10.5f, color("COLOR_SUB", 0xFF6E5276), false);
                row.addView(d, rowLp(act, 4, 0));
            }
        }
        return row;
    }

    /** 四态结论（P3）：等待/下载中/完成/出错，未检查给中性文案。 */
    private static String resourceStateText(int st) {
        switch (st) {
            case CNCNDownloadUI.ST_RUNNING:   return "下载中";
            case CNCNDownloadUI.ST_DONE:      return "完成 ✓";
            case CNCNDownloadUI.ST_ERROR:     return "出错 ✗";
            case CNCNDownloadUI.ST_UNCHECKED: return "未检查";
            default:                          return "等待";
        }
    }

    private static int resourceStateColor(int st) {
        switch (st) {
            case CNCNDownloadUI.ST_RUNNING:   return C_RUN;
            case CNCNDownloadUI.ST_DONE:      return C_DONE;
            case CNCNDownloadUI.ST_ERROR:     return C_ERROR;
            default:                          return C_WAIT;
        }
    }

    private static int resourceStatus(int index) {
        try {
            int[] st = CNCNDownloadUI.fileStatus;
            if (st != null && index >= 0 && index < st.length) return st[index];
        } catch (Throwable ignore) {}
        return CNCNDownloadUI.ST_WAIT;
    }

    private static int resourceProgress(int index) {
        try {
            int[] p = CNCNDownloadUI.fileProgress;
            if (p != null && index >= 0 && index < p.length) {
                return Math.max(0, Math.min(100, p[index]));
            }
        } catch (Throwable ignore) {}
        return 0;
    }

    /** 原始数据只做副信息（P3）：「12.3 / 45.6 MB · 3.2 MB/s」。 */
    private static String resourceDetail(int index) {
        try {
            float[] sz = CNCNDownloadUI.fileSize;
            float[] dl = CNCNDownloadUI.fileDownloaded;
            float[] sp = CNCNDownloadUI.fileSpeed;
            if (sz == null || dl == null || index >= sz.length || index >= dl.length) return null;
            StringBuilder sb = new StringBuilder();
            sb.append(fmtMb(dl[index])).append(" / ").append(fmtMb(sz[index]));
            if (sp != null && index < sp.length && sp[index] > 0f) {
                sb.append(" · ").append(fmtSpeed(sp[index]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 体积显示：输入单位是 <b>MB</b>（{@code CNCNDownloadUI.fileSize/fileDownloaded}
     * 的口径），小值显示 MB、超 1024 转 GB。名字里的 Mb 是「MB」的缩写。
     *
     * <p>曾把参数当 KB 处理、又用 1024 做分界，结果 45.6 MB 被显示成「46 KB」——
     * 输入明明是 MB，判据却按 KB 写。固定：按 MB 收，只在 ≥1024 MB 时升 GB。
     */
    private static String fmtMb(float mb) {
        if (mb >= 1024f) return String.format(java.util.Locale.US, "%.2f GB", mb / 1024f);
        return String.format(java.util.Locale.US, "%.1f MB", Math.max(0f, mb));
    }

    private static String fmtSpeed(float kbps) {
        if (kbps >= 1024f) {
            return String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024f);
        }
        return String.format(java.util.Locale.US, "%.0f KB/s", Math.max(0f, kbps));
    }

    private static View progressBar(Activity act, int pct) {
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable track = new GradientDrawable();
        track.setColor(color("COLOR_BAR_BG", 0x22000000));
        track.setCornerRadius(dp(act, 3));
        bar.setBackground(track);
        int clamped = Math.max(0, Math.min(100, pct));
        if (clamped > 0) {
            View fill = new View(act);
            GradientDrawable fg = new GradientDrawable();
            fg.setColor(color("COLOR_ACCENT", 0xFFD63384));
            fg.setCornerRadius(dp(act, 3));
            fill.setBackground(fg);
            bar.addView(fill, new LinearLayout.LayoutParams(0, dp(act, 6), clamped));
        }
        if (clamped < 100) {
            View rest = new View(act);
            bar.addView(rest, new LinearLayout.LayoutParams(0, dp(act, 6), 100 - clamped));
        }
        return bar;
    }

    /** 资源页开着期间轮询公开数组（纯内存读）；离页即停。 */
    private static void startResourcePolling() {
        if (ui == null || resourcePageActive) return;
        resourcePageActive = true;
        ui.postDelayed(RES_REFRESH, RES_REFRESH_MS);
    }

    private static void stopResourcePolling() {
        resourcePageActive = false;
        if (ui != null) ui.removeCallbacks(RES_REFRESH);
    }

    private static final Runnable RES_REFRESH = new ResRefreshTask();

    private static final class ResRefreshTask implements Runnable {
        @Override public void run() {
            if (!resourcePageActive || panelRoot == null
                    || !PAGE_RESOURCES.equals(currentPage())) return;
            render();
            if (resourcePageActive && ui != null) {
                ui.postDelayed(RES_REFRESH, RES_REFRESH_MS);
            }
        }
    }

    private static final class ResourceReloadClick implements View.OnClickListener {
        private final int index;
        ResourceReloadClick(int index) { this.index = index; }
        @Override public void onClick(View v) { confirmRedownload(index); }
    }

    /**
     * 重下确认框（归本体——{@code redownload()} 不弹）。设计 §7：必须点名资源包、
     * 说清已下载部分会清掉、说清会自动停在资源页。
     */
    private static void confirmRedownload(int index) {
        Activity act = activity;
        if (act == null) return;
        String[] names;
        try { names = CNDebugBridge.resourceNames(); }
        catch (Throwable t) { names = new String[0]; }
        String name = index >= 0 && index < names.length ? names[index] : ("#" + (index + 1));
        showConfirm("重新下载资源包",
                "只重新下载：\n" + name
                + "\n\n已下载的部分会先清掉、从头再下；会自动停在资源页等它下完。"
                + "其它资源包不受影响。",
                "确认重下", false, new ConfirmRedownloadClick(index));
    }

    private static final class ConfirmRedownloadClick implements View.OnClickListener {
        private final int index;
        ConfirmRedownloadClick(int index) { this.index = index; }
        @Override public void onClick(View v) {
            closeModal();
            try { CNDebugBridge.redownload(index); }
            catch (Throwable t) { CNLog.w(TAG, "重下失败: " + t); }
            render();
        }
    }

    // ── 进入游戏：大状态 + 大按钮（停留状态转一道手，点完重读）───────────

    private static void renderEnter(LinearLayout content) {
        Activity act = activity;
        setTitle("进入游戏");
        addGuideCard(content, new String[] {
                "资源还在后台处理，想先停在资源页看着它下完",
                "处理完了，想放行进游戏",
        });

        boolean stay;
        try { stay = CNDebugBridge.isStay(); }
        catch (Throwable t) { stay = false; }

        LinearLayout card = innerCard(content);
        TextView big = text(act, stay ? "现在停在资源页" : "已放行，资源就绪后会自动进游戏",
                16f, color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), true);
        big.setGravity(Gravity.CENTER);
        card.addView(big, rowLp(act, 4, 6));
        TextView sub = text(act, stay
                ? "下载、重下都会照常跑，只是不急着进游戏。"
                : "放心点：进游戏只是继续往下走，不会丢任何下载进度。",
                12f, color("COLOR_SUB", 0xFF6E5276), false);
        sub.setGravity(Gravity.CENTER);
        sub.setLineSpacing(dp(act, 2), 1f);
        card.addView(sub, rowLp(act, 0, 4));

        TextView button = dialogButton(act, stay ? "进入游戏" : "停在资源页", true, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 12);
        button.setOnClickListener(new EnterToggleClick(stay));
        content.addView(button, lp);
    }

    private static final class EnterToggleClick implements View.OnClickListener {
        private final boolean wasStay;
        EnterToggleClick(boolean wasStay) { this.wasStay = wasStay; }
        @Override public void onClick(View v) {
            try { CNDebugBridge.setStay(!wasStay); }
            catch (Throwable t) { CNLog.w(TAG, "切换停留失败: " + t); }
            // 点完必须重读（接线清单 F）：还有任务在跑时这次放行会被消费掉。
            boolean now;
            try { now = CNDebugBridge.isStay(); }
            catch (Throwable t) { now = wasStay; }
            if (wasStay && now) {
                int n;
                try { n = CNDebugBridge.busyCount(); }
                catch (Throwable t) { n = 0; }
                toast("还有 " + Math.max(1, n) + " 个资源包在处理，完成后才能进入游戏");
            }
            render();
        }
    }

    // ── 日志与求助：三步指引 + 预览 + 打包分享 ─────────────────────────

    private static void renderLog(LinearLayout content) {
        Activity act = activity;
        setTitle("日志与求助");
        addGuideCard(content, new String[] {
                "要反馈问题，需要把日志发给开发者",
        });

        LinearLayout steps = innerCard(content);
        TextView s = text(act,
                "三步送出日志：\n① 回到出问题的地方，把问题再操作一遍；\n"
                + "② 回到这里，点下面的「打包并分享日志」；\n"
                + "③ 把日志文件发给开发者。截图时报下屏幕上缘那行「调试模式：…」小字，"
                + "它能省掉一整轮问答。",
                12.5f, color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), false);
        s.setLineSpacing(dp(act, 3), 1f);
        steps.addView(s, rowLp(act, 0, 0));

        // 来源过滤：状态在 CNLog 静态字段里，与下载浮层的日志面板天然同步（P8）。
        LinearLayout filters = new LinearLayout(act);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        TextView fLabel = text(act, "预览过滤：", 12f, color("COLOR_SUB", 0xFF6E5276), false);
        filters.addView(fLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        filters.addView(filterChip(act, "系统日志", CNLog.isShowLogcat(), 0));
        filters.addView(filterChip(act, "引擎日志", CNLog.isShowNative(), 1));
        content.addView(filters, rowLp(act, 4, 6));

        TextView count = text(act, "当前可见 " + CNLog.visibleSize() + " 行（预览取最后 200 行）",
                11f, color("COLOR_SUB", 0xFF6E5276), false);
        content.addView(count, rowLp(act, 0, 4));

        TextView tail = new TextView(act);
        tail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        tail.setTypeface(Typeface.MONOSPACE);
        tail.setTextColor(color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B));
        // ⚠ 绝不能设 setTextIsSelectable(true)。
        //
        // 它会给 TextView 装上 ArrowKeyMovementMethod —— 那本身就是一个**可滚动
        // 且吃触摸**的实现。上一版明明已经把内层 ScrollView 撤了，玩家仍然反馈
        // 「内外两层滑动条打架」，剩下的那一层就是它：手指落在预览框上时，
        // TextView 自己先把竖直手势消费掉，外层页面 ScrollView 抢不到。
        //
        // 要复制日志有「打包并分享日志」，以及下载浮层 LOG 面板里的「复制全部」，
        // 不必为此在这里留一个会抢手势的选中态。
        tail.setTextIsSelectable(false);
        tail.setMovementMethod(null);
        tail.setPadding(dp(act, 10), dp(act, 8), dp(act, 10), dp(act, 8));
        GradientDrawable tailBg = new GradientDrawable();
        tailBg.setColor(color("COLOR_LOG_PANEL_BG", 0xFFFFFFFF));
        tailBg.setCornerRadius(dp(act, 10));
        tailBg.setStroke(dp(act, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        tail.setBackground(tailBg);
        tail.setTag("logtail");
        tail.setText(composeTail());
        // 预览框**不再**自己套一层 ScrollView。
        //
        // 上一版在固定 220dp 的框上又套了个 ScrollView，结果是页面的滚动容器和
        // 它抢同一个竖直手势：手指落在预览框上时两边都想滚，谁抢到看运气，而且
        // 吸底吸的是内层、玩家看到的却是外层没动（2026-08-13 反馈「内外两个滑动条
        // 相互打架，吸底依旧不管用」）。
        //
        // 一页只留一个滚动容器：预览按内容自然高度展开，滚动与吸底都交给外层那个
        // 页面 ScrollView。要看的是最后几行，而它们现在就在这一页的最下面。
        LinearLayout.LayoutParams tailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(tail, tailLp);

        // 新行进来时刷新预览。listener 是 CNLog 的单例槽位：进场占、出场按身份归还，
        // 不踩下载浮层日志面板占着的那一份。
        // 记住被顶掉的那个（下载浮层的 LOG 面板多半正占着），离场时原样还回去。
        // 只在首次占用时记，重复进本页不会把自己记成「前一个」。
        Runnable mine = new LogTailListener();
        Runnable displaced = CNLog.setListener(mine);
        if (myLogListener == null) prevLogListener = displaced;
        myLogListener = mine;
        // 进页面就停在最新那一行：这一页存在的理由就是看最后几行。
        // 页面内容是本方法一路加进去的，post 一次未必赶得上最终布局（分享按钮
        // 还在后面加），所以补一次延时的——两次都只是 fullScroll，重复无害。
        stickPageToBottom();

        TextView share = dialogButton(act, "打包并分享日志", true, false);
        share.setOnClickListener(new ShareLogClick());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shareLp.topMargin = dp(act, 12);
        content.addView(share, shareLp);
    }

    private static TextView filterChip(Activity act, String label, boolean on, int which) {
        TextView chip = dialogButton(act, label, on, false);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        chip.setOnClickListener(new LogFilterClick(which, !on));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(act, 8);
        chip.setLayoutParams(lp);
        return chip;
    }

    private static final class LogFilterClick implements View.OnClickListener {
        private final int which;      // 0=logcat 1=native
        private final boolean next;
        LogFilterClick(int which, boolean next) { this.which = which; this.next = next; }
        @Override public void onClick(View v) {
            if (which == 0) CNLog.setShowLogcat(next);
            else CNLog.setShowNative(next);
            render();
        }
    }

    private static final class LogTailListener implements Runnable {
        @Override public void run() {
            if (panelRoot == null || !PAGE_LOG.equals(currentPage())) return;
            final View tail = panelRoot.findViewWithTag("logtail");
            if (tail instanceof TextView && ui != null) {
                ui.post(new LogTailRefresh((TextView) tail));
            }
        }
    }

    private static final class LogTailRefresh implements Runnable {
        private final TextView tail;
        LogTailRefresh(TextView tail) { this.tail = tail; }
        @Override public void run() {
            if (panelRoot == null || !PAGE_LOG.equals(currentPage())) return;
            View sc = panelRoot.findViewWithTag("pagescroll");
            // 只有本来就贴着底的时候才继续贴底。玩家往回翻着看某一行时，新行
            // 一来就把他拽回底部，那比不自动滚还难用。
            boolean atBottom = sc instanceof ScrollView && isAtBottom((ScrollView) sc);
            tail.setText(composeTail());
            if (atBottom) stickPageToBottom();
        }
    }

    /**
     * 预览用的结构化文本：与下载浮层的日志面板同一个解析器（{@link CNLogFormat}），
     * 不另写一份。
     *
     * <p>纯文字大量快速滚动开发者看着都费劲，何况玩家——这正是当初给下载浮层
     * 那块加解析的理由，而这里一直还是 {@code CNLog.tail(200)} 的裸文本。
     * 悬浮窗里没有富文本渲染的余地（预览框只是个 TextView），所以只做解析器
     * 能给的那部分：来源徽标 + 级别标记 + 时间，让眼睛有落点。
     */
    private static CharSequence composeTail() {
        try {
            java.util.List<CNLog.Line> rows = CNLog.tailRows(200);
            if (rows == null || rows.isEmpty()) return CNLog.tail(200);
            StringBuilder sb = new StringBuilder(rows.size() * 64);
            for (int i = 0; i < rows.size(); i++) {
                CNLog.Line r = rows.get(i);
                CNLogFormat.Parsed p = CNLogFormat.parse(r.src, r.text);
                if (p == null) { sb.append(r.text).append('\n'); continue; }
                sb.append('[').append(p.badge).append(']');
                if (p.time != null && p.time.length() > 0) sb.append(' ').append(p.time);
                if (CNLogFormat.isFatal(p.level)) sb.append(" ‼");
                else if (CNLogFormat.isBad(p.level)) sb.append(" !");
                if (p.comp != null && p.comp.length() > 0) sb.append(' ').append(p.comp);
                sb.append("  ").append(p.text).append('\n');
            }
            return sb;
        } catch (Throwable t) {
            // 解析出任何岔子都退回裸文本：日志面板本身不能因为格式化而看不成
            return CNLog.tail(200);
        }
    }

    /** 把日志页滚到底。分两次：布局这一帧一次，稳定之后再一次。 */
    private static void stickPageToBottom() {
        View sc = panelRoot == null ? null : panelRoot.findViewWithTag("pagescroll");
        if (!(sc instanceof ScrollView)) return;
        final ScrollView sv = (ScrollView) sc;
        stickToBottom(sv);
        if (ui != null) ui.postDelayed(new ScrollBottom(sv), 160L);
    }

    private static boolean isAtBottom(ScrollView sv) {
        try {
            if (sv.getChildCount() == 0) return true;
            int bottom = sv.getChildAt(0).getBottom();
            int cur = sv.getScrollY() + sv.getHeight();
            return bottom - cur <= dp(sv, 24);   // 差不到一行就算贴着底
        } catch (Throwable t) {
            return true;
        }
    }

    /** 滚到底。必须 post：setText 之后这一帧还没重新测量，立刻滚是滚不到位的。 */
    private static void stickToBottom(final ScrollView sv) {
        try {
            sv.post(new ScrollBottom(sv));
        } catch (Throwable ignore) {}
    }

    private static final class ScrollBottom implements Runnable {
        private final ScrollView sv;
        ScrollBottom(ScrollView sv) { this.sv = sv; }
        @Override public void run() {
            try { sv.fullScroll(View.FOCUS_DOWN); } catch (Throwable ignore) {}
        }
    }

    private static void leaveLogPage() {
        if (myLogListener != null) {
            // 归还成**被顶掉的那个**，而不是 null。置 null 等于把下载浮层 LOG
            // 面板的实时刷新一并关掉，且这一整个会话都恢复不了。
            myLogListener = null;
            CNLog.setListener(prevLogListener);
            prevLogListener = null;
        }
    }

    private static final class ShareLogClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            Activity act = activity;
            if (act == null) return;
            toast("正在打包日志…");
            if (bgExecutor == null) {
                bgExecutor = Executors.newSingleThreadExecutor();
            }
            bgExecutor.execute(new ShareLogTask(act));
        }
    }

    /**
     * shareLog 内部有 flush + 打包（读写日志文件），接线清单明确「别在 UI 线程调」。
     * 这里经 {@link #bgExecutor} 转后台，结果回到 UI 线程提示。
     */
    private static final class ShareLogTask implements Runnable {
        private final Activity act;
        ShareLogTask(Activity act) { this.act = act; }
        @Override public void run() {
            File out = null;
            try { out = CNDebugBridge.shareLog(act); }
            catch (Throwable t) { CNLog.w(TAG, "分享日志失败: " + t); }
            if (ui != null) ui.post(new ShareLogResult(out));
        }
    }

    private static final class ShareLogResult implements Runnable {
        private final File out;
        ShareLogResult(File out) { this.out = out; }
        @Override public void run() {
            toast(out != null ? "日志包好了，选择要发去的应用"
                              : "没有可分享的日志（打包失败），请稍后再试");
        }
    }

    // ══ 排查开关 ═══════════════════════════════════════════════════════

    /**
     * 两个读盘时机之一（另一个是保存之后）：刷新快照；勾选态<b>只在没有未应用
     * 改动时</b>才从盘上重置。
     *
     * <p>原先无条件 {@code desired.clear()} 再从盘上填。于是：勾了几个开关 →
     * 顺手退出面板看一眼别的 → 回来发现全没了。玩家会以为自己没点上，或者
     * 以为这个面板坏了；而他刚才那几下恰恰是在照着排查教程一条条勾。
     *
     * <p>现在未应用的改动跨面板开关一直留着，直到「应用并重启」或「放弃改动」。
     * 没有未应用改动时照旧从盘上重置——那条路要的是「反映磁盘现状」。
     */
    private static void loadFlags() {
        boolean keep = dirty();
        try {
            flagSnapshot = CNDebugBridge.flagTable();
        } catch (Throwable t) {
            flagSnapshot = new String[0][];
        }
        if (keep) {
            CNLog.i(TAG, "面板重开：保留 " + countUnapplied() + " 项未应用的改动");
            return;
        }
        resetDesiredFromDisk();
    }

    /** 把内存勾选态对齐到磁盘现状。 */
    private static void resetDesiredFromDisk() {
        desired.clear();
        for (int i = 0; i < flagSnapshot.length; i++) {
            if ("1".equals(flagSnapshot[i][CNDebugBridge.COL_ON_DISK])) {
                desired.add(flagSnapshot[i][CNDebugBridge.COL_NAME]);
            }
        }
    }

    /**
     * 勾选态与磁盘不一致的开关数——也就是「改了但还没点应用」。
     *
     * <p>与 {@link #countPending} 是**两件不同的事**，别混：
     * <ul>
     *   <li>{@code countUnapplied()}：内存 ≠ 磁盘 —— 还没保存，点「应用并重启」才落盘；</li>
     *   <li>{@code countPending()}：磁盘 ≠ 本次启动生效值 —— 已保存但要重启才算数。</li>
     * </ul>
     * 面板原先只提示后者，于是「勾了没保存」这件事在界面上完全没有痕迹（反馈：
     * 「调试开关显示窗口不会提示未应用开关」）。
     */
    static int countUnapplied() {
        if (flagSnapshot == null) return 0;
        int n = 0;
        for (int i = 0; i < flagSnapshot.length; i++) {
            String[] row = flagSnapshot[i];
            if (row == null || row.length < CNDebugBridge.COLS) continue;
            boolean onDisk = "1".equals(row[CNDebugBridge.COL_ON_DISK]);
            if (desired.contains(row[CNDebugBridge.COL_NAME]) != onDisk) n++;
        }
        return n;
    }

    /** 有没有未应用的改动。 */
    static boolean dirty() { return countUnapplied() > 0; }

    // ── 分类总览：警告横幅 + 待重启提醒条 + 六分类入口 ─────────────────

    private static void renderGroups(LinearLayout content) {
        Activity act = activity;
        int total = flagSnapshot == null ? 0 : flagSnapshot.length;
        boolean nativeMissing = !hasNativeRows(flagSnapshot);
        setTitle("排查开关 · 共 " + total + " 项" + (nativeMissing ? "（引擎层未加载）" : ""));

        addGuideCard(content, new String[] {
                "公告或群里的排查教程让你打开某个开关",
                "游戏行为奇怪，想对照排查是哪一环",
        });

        // P5：进开关区先过一道琥珀色警告横幅
        TextView warn = text(act, "这些开关用来排查问题，不是加速器。改动要点「应用并重启」"
                + "才会保存；开关全关 = 和没装小助手一模一样。",
                12.5f, C_AMBER, false);
        warn.setLineSpacing(dp(act, 2), 1f);
        warn.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
        GradientDrawable warnBg = new GradientDrawable();
        warnBg.setColor(C_AMBER_BG);
        warnBg.setCornerRadius(dp(act, 12));
        warnBg.setStroke(dp(act, 1), C_AMBER);
        warn.setBackground(warnBg);
        content.addView(warn, rowLp(act, 0, 10));

        // 提醒条要覆盖**两件不同的事**，原先只提后者：
        //   未应用（内存 ≠ 磁盘）：勾了还没保存，退出面板就白勾——最容易被误以为
        //                          「点不上」，而它此前在界面上一点痕迹都没有；
        //   待重启（磁盘 ≠ 生效值）：已保存，重启一次才算数。
        int unapplied = countUnapplied();
        int pending = countPending(flagSnapshot);
        if (unapplied > 0 || pending > 0) {
            LinearLayout strip = new LinearLayout(act);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setGravity(Gravity.CENTER_VERTICAL);
            strip.setPadding(dp(act, 14), dp(act, 8), dp(act, 14), dp(act, 8));
            GradientDrawable stripBg = new GradientDrawable();
            stripBg.setColor(C_AMBER_BG);
            stripBg.setCornerRadius(dp(act, 12));
            stripBg.setStroke(dp(act, 1), C_AMBER);
            strip.setBackground(stripBg);
            String what;
            if (unapplied > 0 && pending > 0) {
                what = "有 " + unapplied + " 个改动还没保存，另有 " + pending
                        + " 个已保存但要重启才算数。";
            } else if (unapplied > 0) {
                what = "有 " + unapplied + " 个改动还没保存——现在退出面板就白勾了。";
            } else {
                what = "有 " + pending + " 个改动还没生效，重启一次游戏才会算数。";
            }
            TextView msg = text(act, what, 12f, C_AMBER, true);
            msg.setLineSpacing(dp(act, 2), 1f);
            strip.addView(msg, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = dialogButton(act, unapplied > 0 ? "保存并重启" : "去重启", true, false);
            go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            go.setOnClickListener(new ApplyClick());
            strip.addView(go, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(strip, rowLp(act, 0, 10));
        }

        for (int g = 0; g < GROUPS.length; g++) {
            GroupDef def = GROUPS[g];
            int n = countInGroup(flagSnapshot, def.id);
            if (n == 0 && GROUP_OTHER.equals(def.id)) continue;   // 没有未归类的就不显示
            String title = def.title + " · " + n + " 项";
            String sub = def.blurb;
            int pend = countPendingInGroup(flagSnapshot, def.id);
            if (pend > 0) sub = sub + "（" + pend + " 项待重启）";
            addEntry(content, title, sub, PAGE_CAT_PREFIX + def.id);
        }

        // 总览页也要有「应用并重启」。原先它只在分类子页底部——而玩家常常是在
        // 几个分类之间来回勾，勾完自然退回总览，然后在这一页找不到任何提交入口
        // （反馈：「开关总页面下没有重启按钮」）。同一个 ApplyClick，不是第二套逻辑。
        LinearLayout ops = new LinearLayout(act);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setGravity(Gravity.END);
        TextView discard = dialogButton(act, "放弃改动", false, false);
        discard.setOnClickListener(new DiscardClick());
        discard.setVisibility(unapplied > 0 ? View.VISIBLE : View.GONE);
        ops.addView(discard);
        TextView apply = dialogButton(act, "应用并重启", true, false);
        apply.setOnClickListener(new ApplyClick());
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        applyLp.leftMargin = dp(act, 10);
        ops.addView(apply, applyLp);
        content.addView(ops, rowLp(act, 14, 0));
    }

    /** 放弃未应用的改动：把勾选态对齐回磁盘现状。 */
    private static final class DiscardClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            resetDesiredFromDisk();
            CNLog.i(TAG, "已放弃未应用的调试开关改动");
            toast("已放弃未保存的改动");
            render();
        }
    }

    // ── 分类子页：控件列表 + 底部「全部关闭 / 应用并重启」──────────────

    private static void renderCategory(LinearLayout content, String groupId) {
        Activity act = activity;
        GroupDef def = groupDef(groupId);
        int nativeCount = countSide(flagSnapshot, groupId, "native");
        setTitle(def.title);
        addGuideCard(content, def.guide);

        TextView subtitle = text(act,
                def.blurb + (nativeCount > 0 ? "（含引擎层 " + nativeCount + " 项）" : ""),
                11.5f, color("COLOR_SUB", 0xFF6E5276), false);
        content.addView(subtitle, rowLp(act, 0, 4));

        TextView legend = text(act, "勾选 = 重启后生效 · 小绿点 = 现在生效中 · 两者不一致会出现「待重启」",
                11f, color("COLOR_SUB", 0xFF6E5276), false);
        content.addView(legend, rowLp(act, 0, 8));

        if (GROUP_F.equals(groupId) && dangerTapStage < 2) {
            renderDangerGate(content);
        } else {
            List<Control> controls = controlsFor(flagSnapshot, groupId);
            if (controls.isEmpty()) {
                TextView empty = text(act, "这一类现在没有开关。", 12.5f,
                        color("COLOR_SUB", 0xFF6E5276), false);
                content.addView(empty, rowLp(act, 8, 8));
            }
            for (int i = 0; i < controls.size(); i++) {
                Control c = controls.get(i);
                if (c.type == Control.TYPE_RADIO) content.addView(radioView(act, c.radio));
                else content.addView(checkView(act, c.row, GROUP_F.equals(groupId)));
            }
        }

        // 底部：全部关闭 / 应用并重启（P4：每个列表页都有「全部关闭」出口）
        bottomBar.setVisibility(View.VISIBLE);
        TextView offAll = dialogButton(act, "全部关闭", false, false);
        offAll.setOnClickListener(new TurnAllOffClick());
        TextView apply = dialogButton(act, "应用并重启", true, false);
        apply.setOnClickListener(new ApplyClick());
        bottomBar.addView(offAll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams applyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        applyLp.leftMargin = dp(act, 10);
        bottomBar.addView(apply, applyLp);
    }

    /** F 类默认折叠 + 二次展开（P5）。 */
    private static void renderDangerGate(LinearLayout content) {
        Activity act = activity;
        LinearLayout card = innerCard(content);
        TextView warn = text(act,
                "这一页是开发自测用的「故意弄坏」开关：开了之后游戏某个环节一定会表现得像坏了一样，"
                + "用来验证出错时的兜底。正常游玩用不到它们。",
                12.5f, C_DANGER, false);
        warn.setLineSpacing(dp(act, 2), 1f);
        card.addView(warn, rowLp(act, 0, 10));
        TextView gate = dialogButton(act,
                dangerTapStage == 0 ? "我已了解，展开看看" : "再点一次确认展开", false, true);
        gate.setOnClickListener(new DangerGateClick());
        card.addView(gate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static final class DangerGateClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            if (dangerTapStage < 2) dangerTapStage++;
            render();
        }
    }

    // ── 勾选控件（默认型）：勾选跟磁盘，绿点是当前生效，不一致挂角标 ────

    private static View checkView(Activity act, String[] row, boolean danger) {
        String name = row[CNDebugBridge.COL_NAME];
        boolean onBoot = "1".equals(row[CNDebugBridge.COL_ON_BOOT]);
        boolean want = desired.contains(name);
        boolean pending = want != onBoot;

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("COLOR_GLASS", 0xCCFFFFFF));
        bg.setCornerRadius(dp(act, 12));
        bg.setStroke(dp(act, 1), danger ? C_DANGER : color("COLOR_CARD_STK", 0x33B53C8C));
        box.setBackground(bg);
        LinearLayout.LayoutParams boxLp = rowLp(act, 0, 8);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        // 勾选块（跟 COL_ON_DISK 的内存副本 desired）
        TextView check = new TextView(act);
        check.setText(want ? "✓" : "");
        check.setGravity(Gravity.CENTER);
        check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        check.setTextColor(0xFFFFFFFF);
        GradientDrawable checkBg = new GradientDrawable();
        checkBg.setCornerRadius(dp(act, 6));
        if (want) checkBg.setColor(danger ? C_DANGER : color("COLOR_ACCENT", 0xFFD63384));
        else {
            checkBg.setColor(0x00000000);
            checkBg.setStroke(dp(act, 2), danger ? C_DANGER
                    : color("COLOR_CARD_STK", 0x33B53C8C));
        }
        check.setBackground(checkBg);
        head.addView(check, new LinearLayout.LayoutParams(dp(act, 26), dp(act, 26)));

        // 三层信息之一：接口名（P2 第一层，等宽、缩小、原文不翻译）
        TextView nameView = text(act, name, 13f,
                danger ? C_DANGER : color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), true);
        nameView.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.leftMargin = dp(act, 10);
        head.addView(nameView, nameLp);

        // ⚠ 绿点必须走 dotLp()，不能用 wrapLp()。
        //
        // dot() 是一个**裸 View**（背景是个圆形 GradientDrawable，没有内容），
        // 裸 View 在 WRAP_CONTENT 下测出来就是 0×0——而 addView(child, lp) 会把
        // dot() 自己设好的 8dp×8dp 覆盖掉。结果是图例里写着「小绿点 = 现在生效
        // 中」，却指着一个永远画不出来的点：面板上根本看不出哪个开关正在生效。
        if (onBoot) head.addView(dot(act), dotLp(act, 4));
        if (pending) head.addView(badge(act, "待重启"), wrapLp(act, 6));
        box.addView(head, rowLp(act, 0, 0));

        // 第二层：官方说明（接线层 COL_DESC）
        String desc = row[CNDebugBridge.COL_DESC];
        if (desc != null && desc.length() > 0) {
            TextView d = text(act, desc, 11.5f, color("COLOR_SUB", 0xFF6E5276), false);
            d.setLineSpacing(dp(act, 2), 1f);
            box.addView(d, rowLp(act, 6, 0));
        }

        // 第三层：白话注释（P6：查不到就降级为只有官方说明，开关照常显示）
        Note note = noteFor(name);
        if (note != null) {
            if (note.plain != null && note.plain.length() > 0) {
                TextView p = text(act, note.plain, 12f,
                        color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), false);
                p.setLineSpacing(dp(act, 2), 1f);
                box.addView(p, rowLp(act, 6, 0));
            }
            if (note.advice != null) {
                box.addView(badge(act, adviceText(note.advice)), rowLp(act, 6, 0));
            }
            if (note.risk != null && note.risk.length() > 0) {
                TextView r = text(act, note.risk, 11.5f, C_AMBER, false);
                r.setLineSpacing(dp(act, 2), 1f);
                box.addView(r, rowLp(act, 6, 0));
            }
        }
        if (danger) {
            TextView red = text(act, "会让功能看起来坏掉——这是故意的", 11f, C_DANGER, true);
            box.addView(red, rowLp(act, 6, 0));
        }

        box.setOnClickListener(danger ? new DangerToggle(name, want) : new FlagToggle(name));
        box.setLayoutParams(boxLp);
        return box;
    }

    /** 绿点专用：显式给出 8dp×8dp，见 {@link #dot} 与 checkView 里的那条警告。 */
    private static LinearLayout.LayoutParams dotLp(Activity act, int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(act, 8), dp(act, 8));
        lp.leftMargin = dp(act, leftMargin);
        lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    private static LinearLayout.LayoutParams wrapLp(Activity act, int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(act, leftMargin);
        return lp;
    }

    /** 绿点 = 当前生效（COL_ON_BOOT）。 */
    private static View dot(Activity act) {
        View dot = new View(act);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(C_DONE);
        dot.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(act, 8), dp(act, 8));
        lp.gravity = Gravity.CENTER_VERTICAL;
        dot.setLayoutParams(lp);
        return dot;
    }

    private static TextView badge(Activity act, String label) {
        TextView b = text(act, label, 9.5f, C_AMBER, true);
        b.setPadding(dp(act, 6), dp(act, 2), dp(act, 6), dp(act, 2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(C_AMBER_BG);
        bg.setCornerRadius(dp(act, 8));
        bg.setStroke(dp(act, 1), C_AMBER);
        b.setBackground(bg);
        return b;
    }

    private static final class FlagToggle implements View.OnClickListener {
        private final String name;
        FlagToggle(String name) { this.name = name; }
        @Override public void onClick(View v) {
            // 勾选只改内存（P4）：点「应用并重启」才落盘。
            if (desired.contains(name)) desired.remove(name);
            else desired.add(name);
            render();
        }
    }

    /** 危险开关：打开前必弹「哪部分会坏」确认框（P5 / §4.5-M3）；关闭不用弹。 */
    private static final class DangerToggle implements View.OnClickListener {
        private final String name;
        private final boolean want;
        DangerToggle(String name, boolean want) { this.name = name; this.want = want; }
        @Override public void onClick(View v) {
            if (want) {
                desired.remove(name);
                render();
                return;
            }
            Note note = noteFor(name);
            String breaks = note != null && note.risk != null && note.risk.length() > 0
                    ? note.risk
                    : "开着它，对应的功能会表现得像坏了一样。";
            showConfirm("确定打开 " + name + "？",
                    breaks + "\n\n这是开发自测用的「故意弄坏」开关，用完记得回来关掉。",
                    "我确定要开", true, new DangerConfirm(name));
        }
    }

    private static final class DangerConfirm implements View.OnClickListener {
        private final String name;
        DangerConfirm(String name) { this.name = name; }
        @Override public void onClick(View v) {
            desired.add(name);
            closeModal();
            render();
        }
    }

    // ── 单选控件（手风琴；整件一枚「待重启」角标；多文件挂琥珀修正提示）────

    private static View radioView(Activity act, RadioGroup rg) {
        HashSet<String> bootSet = new HashSet<String>();
        HashSet<String> diskSet = new HashSet<String>();
        for (int i = 0; flagSnapshot != null && i < flagSnapshot.length; i++) {
            if ("1".equals(flagSnapshot[i][CNDebugBridge.COL_ON_BOOT])) {
                bootSet.add(flagSnapshot[i][CNDebugBridge.COL_NAME]);
            }
            if ("1".equals(flagSnapshot[i][CNDebugBridge.COL_ON_DISK])) {
                diskSet.add(flagSnapshot[i][CNDebugBridge.COL_NAME]);
            }
        }
        int diskOption = radioSelection(rg.flagsInOrder, diskSet);
        int bootOption = radioSelection(rg.flagsInOrder, bootSet);
        int shownOption = radioSelection(rg.flagsInOrder, desired);
        boolean pending = shownOption != bootOption;   // 整件一枚角标（§4.5）
        boolean multiple = radioHasMultiple(rg.flagsInOrder, diskSet);
        boolean expanded = rg.id.equals(expandedRadio);

        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("COLOR_GLASS", 0xCCFFFFFF));
        bg.setCornerRadius(dp(act, 12));
        bg.setStroke(dp(act, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        box.setBackground(bg);
        box.setLayoutParams(rowLp(act, 0, 8));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(act, rg.title, 13.5f,
                color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), true);
        head.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // 勾选控件那侧「现在生效中」有绿点，单选这侧原先什么都没有：整个 D 类
        // （序章跳段 9 个开关）不管开没开，看上去都一模一样。
        if (bootOption > 0) head.addView(dot(act), dotLp(act, 4));
        if (pending) head.addView(badge(act, "待重启"), wrapLp(act, 6));
        TextView arrow = text(act, expanded ? "▲" : "▼", 11f,
                color("COLOR_SUB", 0xFF6E5276), false);
        head.addView(arrow, wrapLp(act, 8));
        box.addView(head, rowLp(act, 0, 0));

        // 折叠状态下也要看得见英文名——展不展开都是同一个问题：屏幕上那行小字
        // 报的是英文名，面板里必须找得到它。
        String shownFlag = flagOfOption(rg, shownOption);
        TextView current = text(act, "当前：" + rg.optionLabels[shownOption]
                        + (shownFlag == null ? "" : "（" + shownFlag + "）"),
                12f, color("COLOR_ACCENT2", 0xFF9C5BC2), false);
        box.addView(current, rowLp(act, 4, 0));
        if (rg.blurb != null && rg.blurb.length() > 0) {
            TextView blurb = text(act, rg.blurb, 11.5f, color("COLOR_SUB", 0xFF6E5276), false);
            blurb.setLineSpacing(dp(act, 2), 1f);
            box.addView(blurb, rowLp(act, 4, 0));
        }
        // 磁盘上同时有多个（玩家用别的方式写过）：按引擎实际行为显示生效项 + 修正提示
        if (multiple) {
            TextView fix = text(act,
                    "检测到多个跳段标记，实际按最远生效；在这里选一项即可修正。",
                    11.5f, C_AMBER, false);
            fix.setLineSpacing(dp(act, 2), 1f);
            box.addView(fix, rowLp(act, 6, 0));
        }

        if (expanded) {
            for (int i = 0; i < rg.optionLabels.length; i++) {
                box.addView(radioOption(act, rg, i, i == shownOption), rowLp(act, 6, 0));
            }
        }

        box.setOnClickListener(new RadioExpandClick(rg.id));
        return box;
    }

    /**
     * 单选控件的一个选项。
     *
     * <h3>为什么这里必须把英文开关名写出来</h3>
     *
     * 单选控件是把好几个开关合并成一件（C 类 7→6、D 类 9→4，设计 §4.2），合并
     * 之后界面上只剩中文选项标签——于是这些开关的**英文名在整个面板里一次都不
     * 出现**。而常驻小字报的恰恰是英文名：屏幕上写着「调试模式：logI18nMissAll」，
     * 人回到面板里按这个词找，一个字都搜不到，跟这个开关不存在一样。
     *
     * <p>勾选控件那侧本来就把英文名摆在第一行（P2 第一层），这里补齐，两种控件
     * 的口径才一致。
     */
    private static View radioOption(Activity act, RadioGroup rg, int option, boolean selected) {
        int fg = selected ? color("COLOR_ACCENT", 0xFFD63384)
                          : color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B);
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(act, 8), dp(act, 6), dp(act, 8), dp(act, 6));

        TextView label = text(act, (selected ? "● " : "○ ") + rg.optionLabels[option],
                12.5f, fg, selected);
        label.setLineSpacing(dp(act, 2), 1f);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String flag = flagOfOption(rg, option);
        if (flag != null) {
            TextView code = text(act, flag, 10.5f, color("COLOR_SUB", 0xFF6E5276), false);
            code.setTypeface(Typeface.MONOSPACE);
            row.addView(code, wrapLp(act, 8));
        }

        row.setOnClickListener(new RadioOptionClick(rg, option));
        return row;
    }

    /**
     * 选项下标 → 它写的那个开关名；第 0 项是「都不写」的默认，没有对应开关。
     *
     * <p>{@code optionLabels.length == flagsInOrder.length + 1}，所以偏移是 1。
     */
    static String flagOfOption(RadioGroup rg, int option) {
        if (rg == null || option <= 0) return null;
        if (option - 1 >= rg.flagsInOrder.length) return null;
        return rg.flagsInOrder[option - 1];
    }

    private static final class RadioExpandClick implements View.OnClickListener {
        private final String id;
        RadioExpandClick(String id) { this.id = id; }
        @Override public void onClick(View v) {
            expandedRadio = id.equals(expandedRadio) ? null : id;
            render();
        }
    }

    private static final class RadioOptionClick implements View.OnClickListener {
        private final RadioGroup rg;
        private final int option;
        RadioOptionClick(RadioGroup rg, int option) { this.rg = rg; this.option = option; }
        @Override public void onClick(View v) {
            // 写：选哪项就只写哪个开关，同组其余不写（全量语义保证清掉旧值）
            Set<String> next = radioDesired(rg.flagsInOrder, option, desired);
            desired.clear();
            desired.addAll(next);
            render();
        }
    }

    // ── 落盘与重启（接线清单 C）────────────────────────────────────────

    private static final class ApplyClick implements View.OnClickListener {
        @Override public void onClick(View v) { applyNow(desired); }
    }

    private static final class TurnAllOffClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            showConfirm("全部关闭？",
                    "开关全关 = 和没装小助手一模一样。\n\n这会保存并重启一次游戏；"
                    + "重启只是关掉重开，不会丢任何进度。",
                    "全部关闭并重启", false, new TurnAllOffConfirm());
        }
    }

    private static final class TurnAllOffConfirm implements View.OnClickListener {
        @Override public void onClick(View v) {
            closeModal();
            applyNow(new HashSet<String>());
        }
    }

    /**
     * 点「应用并重启」才落盘；成功后是第二个读盘时机（快照 + HUD 各重读一次）。
     *
     * <p>接线清单线程表称 {@code applyAndRestart} 内部自起线程，与实际不符
     * （{@link CNRestart#restartWithNotice} 在<b>调用线程</b> {@code sleep(3000)}，
     * 失败路径可达约 6.7s），UI 线程直调会冻界面约 3 秒、有 ANR 风险，故转
     * {@link #bgExecutor} 后台执行（先例：CNTutorialPrompt 为同一个 sleep 专门
     * 起线程），结果回 UI 线程展示。
     */
    private static void applyNow(Set<String> wanted) {
        // 复制一份快照：desired 之后还会被 UI 线程继续勾选改动，后台任务不能吃活引用。
        HashSet<String> snapshot = new HashSet<String>(wanted);
        if (bgExecutor == null) {
            bgExecutor = Executors.newSingleThreadExecutor();
        }
        bgExecutor.execute(new ApplyTask(snapshot));
    }

    /** 后台段：只负责落盘 + 重启（内部会在工作线程 sleep 倒计时）。 */
    private static final class ApplyTask implements Runnable {
        private final Set<String> wanted;
        ApplyTask(Set<String> wanted) { this.wanted = wanted; }
        @Override public void run() {
            boolean ok;
            try { ok = CNDebugBridge.applyAndRestart(wanted); }
            catch (Throwable t) { ok = false; }
            // 重启成功时进程已死，这行自然到不了；没到就说给玩家听。
            if (ui != null) ui.post(new ApplyResult(ok));
        }
    }

    /** UI 线程段：第二个读盘时机 + 结果弹窗。 */
    private static final class ApplyResult implements Runnable {
        private final boolean ok;
        ApplyResult(boolean ok) { this.ok = ok; }
        @Override public void run() {
            if (ok) {
                loadFlags();
                CNDebugHud.refresh();
                showResult("已经帮你记好了",
                        "改动要重启一次游戏才会生效。马上会自动重开；没重开的话，手动关掉再打开就行。");
            } else {
                showResult("没保存成功",
                        "游戏没有重启，开关也没改。请再试一次；反复失败的话，从「日志与求助」把日志发给开发者。");
            }
            render();
        }
    }

    // ══ 弹窗（全部确认框归本体）═══════════════════════════════════════

    private static void showConfirm(String title, String body, String yesLabel,
                                    boolean danger, View.OnClickListener onYes) {
        showModal(title, body, yesLabel, danger, true, onYes);
    }

    /**
     * 确认/结果弹窗的共用实现。{@code withCancel} 决定是否渲染「取消」：
     * 确认框要（有第二条路可走），结果弹窗不要（事情已经发生，只剩「知道了」）。
     */
    private static void showModal(String title, String body, String yesLabel,
                                  boolean danger, boolean withCancel,
                                  View.OnClickListener onYes) {
        Activity act = activity;
        if (act == null || panelRoot == null) return;
        closeModal();
        FrameLayout mask = new FrameLayout(act);
        mask.setBackgroundColor(color("COLOR_DIM", 0x88000000));
        mask.setClickable(true);
        mask.setFocusable(true);
        mask.setOnClickListener(new ModalDismissClick());

        LinearLayout card = dialogCard(act);
        card.setOnClickListener(new ConsumeClick());
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                Math.min(dp(act, 380),
                        act.getResources().getDisplayMetrics().widthPixels - dp(act, 80)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mask.addView(card, cardLp);

        TextView t = text(act, title, 15.5f,
                danger ? C_DANGER : color("COLOR_ACCENT", 0xFFD63384), true);
        card.addView(t, rowLp(act, 0, 8));
        TextView b = text(act, body, 13f, color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), false);
        b.setLineSpacing(dp(act, 2), 1f);
        card.addView(b, rowLp(act, 0, 16));

        LinearLayout buttons = new LinearLayout(act);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        if (withCancel) {
            TextView cancel = dialogButton(act, "取消", false, false);
            cancel.setOnClickListener(new ModalDismissClick());
            buttons.addView(cancel);
        }
        TextView yes = dialogButton(act, yesLabel, true, danger);
        yes.setOnClickListener(onYes);
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (withCancel) yesLp.leftMargin = dp(act, 10);
        buttons.addView(yes, yesLp);
        card.addView(buttons, rowLp(act, 0, 0));

        panelRoot.addView(mask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        modalView = mask;
    }

    /** 结果弹窗：成功/失败一致，只有「知道了」一枚按钮（不渲染「取消」）。 */
    private static void showResult(String title, String body) {
        showModal(title, body, "知道了", false, false, new ModalDismissClick());
    }

    private static final class ModalDismissClick implements View.OnClickListener {
        @Override public void onClick(View v) { closeModal(); }
    }

    private static void closeModal() {
        View v = modalView;
        modalView = null;
        if (v != null && v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
    }

    // ══ 纯逻辑（无 Android 依赖，public 以便 JVM 测试直达，同 formatHud 的做法）════

    /** 一个分类（设计 §4.2）。id 即 §4.3 表里的字母；OTHER 是 P6 的降级组。 */
    public static final class GroupDef {
        public final String id;
        public final String title;
        public final String blurb;
        public final String[] guide;
        GroupDef(String id, String title, String blurb, String[] guide) {
            this.id = id; this.title = title; this.blurb = blurb; this.guide = guide;
        }
    }

    /** 一个单选控件（设计 §4.5-M1/M2）。flagsInOrder 按生效距离升序，最远=最后。 */
    public static final class RadioGroup {
        public final String id;
        public final String title;
        public final String blurb;
        public final String[] flagsInOrder;
        /** optionLabels.length == flagsInOrder.length + 1；第 0 项是「都不写」的默认。 */
        public final String[] optionLabels;
        RadioGroup(String id, String title, String blurb,
                   String[] flagsInOrder, String[] optionLabels) {
            this.id = id; this.title = title; this.blurb = blurb;
            this.flagsInOrder = flagsInOrder; this.optionLabels = optionLabels;
        }
    }

    /** 白话注释（设计 §5）。查不到 = 开关照常显示，只是没有第三行。 */
    public static final class Note {
        public final String plain;
        public final String advice;
        public final String risk;
        Note(String plain, String advice, String risk) {
            this.plain = plain; this.advice = advice; this.risk = risk;
        }
    }

    /** 分类子页里的一个控件：勾选或单选（§4.5）。 */
    public static final class Control {
        public static final int TYPE_CHECK = 0;
        public static final int TYPE_RADIO = 1;
        public final int type;
        public final String[] row;       // TYPE_CHECK 时的 flagTable 行
        public final RadioGroup radio;   // TYPE_RADIO 时的控件定义
        Control(int type, String[] row, RadioGroup radio) {
            this.type = type; this.row = row; this.radio = radio;
        }
    }

    private static final String[] TUTORIAL_FLAGS = {
            "tutorialSkipToBattle1",
            "tutorialSkipAfterBattle1",
            "tutorialSkipToBattle2",
            "tutorialSkipAfterBattle2",
            "tutorialSkipToBattle3",
            "tutorialSkipAfterBattle3",
    };

    private static final RadioGroup RADIO_TUTORIAL = new RadioGroup(
            "tutorialSkip", "序章从哪段开始",
            "就像视频的章节跳转——选从哪一段开始看序章。",
            TUTORIAL_FLAGS,
            new String[] {
                    "从头完整播放（默认）",
                    "第 1 场战斗前",
                    "第 1 场战斗后",
                    "第 2 场战斗前",
                    "第 2 场战斗后",
                    "CONNECT 教学战前",
                    "直接到结尾剧情（最快）",
            });

    private static final RadioGroup RADIO_I18N_MISS = new RadioGroup(
            "i18nMissLog", "翻译缺漏记录",
            "帮你随手记下路上看到的「没翻译的句子」：打开它，去游戏里逛一圈，"
            + "再从「日志与求助」发出来。",
            new String[] { "logI18nMiss", "logI18nMissAll" },
            new String[] {
                    "不记录（默认）",
                    "只记没翻的句子（去重，推荐）",
                    "记录全部，含英文数字（日志里九成是废话，上一项抓不到时再选）",
            });

    private static final RadioGroup[] RADIO_GROUPS = { RADIO_TUTORIAL, RADIO_I18N_MISS };

    /** 分组映射表（设计 §4.3 归属表；查不到的进「其他」组——P6 降级）。 */
    private static final String[][] GROUP_MAP = {
            // A 启动与更新（5）
            { "skipInstaller", GROUP_A },
            { "skipOverlay", GROUP_A },
            { "skipRestart", GROUP_A },
            { "skipSlowAsk", GROUP_A },
            { "noOverlayGate", GROUP_A },
            // B 下载与网络（5）
            { "useAria2", GROUP_B },
            { "useSingleThread", GROUP_B },
            { "skipWebProxy", GROUP_B },
            { "noProxyEndpoint", GROUP_B },
            { "noHttp2Bump", GROUP_B },
            // C 汉化与文本（7）
            { "logI18nMiss", GROUP_C },
            { "logI18nMissAll", GROUP_C },
            { "noI18nLabel", GROUP_C },
            { "noI18nSetString", GROUP_C },
            { "noInitLabelHook", GROUP_C },
            { "noTtfHooks", GROUP_C },
            // D 教程与序章（9）
            { "skipTutorialPrompt", GROUP_D },
            { "noTutorialForce", GROUP_D },
            { "noTutorialGuard", GROUP_D },
            { "tutorialSkipToBattle1", GROUP_D },
            { "tutorialSkipAfterBattle1", GROUP_D },
            { "tutorialSkipToBattle2", GROUP_D },
            { "tutorialSkipAfterBattle2", GROUP_D },
            { "tutorialSkipToBattle3", GROUP_D },
            { "tutorialSkipAfterBattle3", GROUP_D },
            // E 声音与引擎（1）
            { "noAdxSampleRate", GROUP_E },
            // F 故障演习（5）
            { "failConfigFetch", GROUP_F },
            { "failVersionQuery", GROUP_F },
            { "slowVersionQuery", GROUP_F },
            { "failDownload", GROUP_F },
            { "failHotUpdateApply", GROUP_F },
    };

    private static final GroupDef[] GROUPS = {
            new GroupDef(GROUP_A, "启动与更新", "管「进游戏之前那一串流程」",
                    new String[] { "卡在启动页或下载页，想排查是哪一步" }),
            new GroupDef(GROUP_B, "下载与网络", "管「资源从哪条线、怎么下」",
                    new String[] { "下载慢、失败、或一直连不上" }),
            new GroupDef(GROUP_C, "汉化与文本", "管「游戏里的中文显示和缺漏记录」",
                    new String[] { "字变方块、缺字", "想反馈没翻译的句子" }),
            new GroupDef(GROUP_D, "教程与序章", "管「序章播不播、从哪段播」",
                    new String[] { "想测序章，或快速过掉教程" }),
            new GroupDef(GROUP_E, "声音与引擎", "管「引擎的声音参数」",
                    new String[] { "破音、变调时对照排查" }),
            new GroupDef(GROUP_F, "故障演习 · 仅供开发", "故意让某环节坏掉，验证兜底（开了就像游戏坏了）",
                    new String[] { "开发自测——正常游玩用不到这一页" }),
            new GroupDef(GROUP_OTHER, "其他", "还没归类的新开关",
                    new String[] { "公告让你打开的开关不在上面的分类里" }),
    };

    /** 开关 → 分类（§4.3 归属表；查不到进「其他」，P6 降级）。 */
    public static String groupIdOf(String name) {
        if (name != null) {
            for (int i = 0; i < GROUP_MAP.length; i++) {
                if (GROUP_MAP[i][0].equals(name)) return GROUP_MAP[i][1];
            }
        }
        return GROUP_OTHER;
    }

    /** 取色判据的测试入口：透明（alpha=0）必须当成「没取到」，见 {@link #color}。 */
    public static int colorForTest(String name, int fallback) {
        return color(name, fallback);
    }

    /** 分类 id → 分类定义（设计 §4.2）。public：JVM 测试要钉分类名文案。 */
    public static GroupDef groupDef(String id) {
        for (int i = 0; i < GROUPS.length; i++) {
            if (GROUPS[i].id.equals(id)) return GROUPS[i];
        }
        return GROUPS[GROUPS.length - 1];
    }

    /** 这个开关属于哪个单选控件；不属于任何单选控件时返回 null。 */
    public static RadioGroup radioGroupOf(String name) {
        for (int g = 0; g < RADIO_GROUPS.length; g++) {
            String[] flags = RADIO_GROUPS[g].flagsInOrder;
            for (int i = 0; i < flags.length; i++) {
                if (flags[i].equals(name)) return RADIO_GROUPS[g];
            }
        }
        return null;
    }

    /**
     * 单选控件的读（§4.5）：把磁盘/生效集合映射回选项下标。
     * 0 = 默认（组内一个都不在集合里）；命中多个时按引擎实际行为取<b>最远</b>
     * （下标最大）那一项。
     */
    public static int radioSelection(String[] flagsInOrder, Set<String> onSet) {
        int sel = 0;
        if (flagsInOrder == null || onSet == null) return 0;
        for (int i = 0; i < flagsInOrder.length; i++) {
            if (onSet.contains(flagsInOrder[i])) sel = i + 1;   // 不 break：后者覆盖前者
        }
        return sel;
    }

    /** 磁盘上同组同时存在多个——玩家用别的方式写过，界面要挂琥珀修正提示。 */
    public static boolean radioHasMultiple(String[] flagsInOrder, Set<String> onSet) {
        int n = 0;
        if (flagsInOrder == null || onSet == null) return false;
        for (int i = 0; i < flagsInOrder.length; i++) {
            if (onSet.contains(flagsInOrder[i])) n++;
        }
        return n >= 2;
    }

    /**
     * 单选控件的写（§4.5）：选哪项就只写哪个开关，同组其余一律移出 desired
     * （{@code applyAndRestart} 的全量语义天然保证清掉旧文件）。不动组外开关。
     */
    public static Set<String> radioDesired(String[] flagsInOrder, int optionIndex,
                                           Set<String> current) {
        HashSet<String> next = new HashSet<String>();
        if (current != null) next.addAll(current);
        if (flagsInOrder != null) {
            for (int i = 0; i < flagsInOrder.length; i++) next.remove(flagsInOrder[i]);
            if (optionIndex >= 1 && optionIndex <= flagsInOrder.length) {
                next.add(flagsInOrder[optionIndex - 1]);
            }
        }
        return next;
    }

    /**
     * 把一个分类的 flagTable 行折成控件列表（§4.5）：单选组的成员开关合并成一枚
     * 单选控件（插在组内第一个成员出现的位置），其余保持勾选控件。
     */
    public static List<Control> controlsFor(String[][] table, String groupId) {
        List<Control> out = new ArrayList<Control>();
        if (table == null) return out;
        HashSet<String> radioDone = new HashSet<String>();
        for (int i = 0; i < table.length; i++) {
            String[] row = table[i];
            if (row == null || row.length < CNDebugBridge.COLS) continue;
            String name = row[CNDebugBridge.COL_NAME];
            if (!groupIdOf(name).equals(groupId)) continue;
            RadioGroup rg = radioGroupOf(name);
            if (rg == null) {
                out.add(new Control(Control.TYPE_CHECK, row, null));
            } else if (!radioDone.contains(rg.id)) {
                radioDone.add(rg.id);
                out.add(new Control(Control.TYPE_RADIO, null, rg));
            }
        }
        return out;
    }

    /** 「待重启」行数：磁盘 ≠ 当前生效（§7 两列状态）。 */
    public static int countPending(String[][] table) {
        int n = 0;
        for (int i = 0; table != null && i < table.length; i++) {
            if (table[i] != null && table[i].length >= CNDebugBridge.COLS
                    && !table[i][CNDebugBridge.COL_ON_DISK]
                            .equals(table[i][CNDebugBridge.COL_ON_BOOT])) n++;
        }
        return n;
    }

    static int countPendingInGroup(String[][] table, String groupId) {
        int n = 0;
        for (int i = 0; table != null && i < table.length; i++) {
            if (table[i] != null && table[i].length >= CNDebugBridge.COLS
                    && groupIdOf(table[i][CNDebugBridge.COL_NAME]).equals(groupId)
                    && !table[i][CNDebugBridge.COL_ON_DISK]
                            .equals(table[i][CNDebugBridge.COL_ON_BOOT])) n++;
        }
        return n;
    }

    static int countInGroup(String[][] table, String groupId) {
        int n = 0;
        for (int i = 0; table != null && i < table.length; i++) {
            if (table[i] != null && table[i].length > CNDebugBridge.COL_NAME
                    && groupIdOf(table[i][CNDebugBridge.COL_NAME]).equals(groupId)) n++;
        }
        return n;
    }

    static int countSide(String[][] table, String groupId, String side) {
        int n = 0;
        for (int i = 0; table != null && i < table.length; i++) {
            if (table[i] != null && table[i].length >= CNDebugBridge.COLS
                    && groupIdOf(table[i][CNDebugBridge.COL_NAME]).equals(groupId)
                    && side.equals(table[i][CNDebugBridge.COL_SIDE])) n++;
        }
        return n;
    }

    static boolean hasNativeRows(String[][] table) {
        for (int i = 0; table != null && i < table.length; i++) {
            if (table[i] != null && table[i].length > CNDebugBridge.COL_SIDE
                    && "native".equals(table[i][CNDebugBridge.COL_SIDE])) return true;
        }
        return false;
    }

    /** 白话注释表查询：查不到返回 null（照常显示，只是没有第三行；P6 降级）。 */
    public static Note noteFor(String name) {
        if (name == null) return null;
        for (int i = 0; i < NOTES.length; i++) {
            if (NOTES[i][0].equals(name)) {
                return new Note(NOTES[i][1], NOTES[i][2], NOTES[i][3]);
            }
        }
        return null;
    }

    private static String adviceText(String advice) {
        if (ADVICE_KEEP_OFF.equals(advice)) return "建议保持关闭";
        if (ADVICE_LAST_RESORT.equals(advice)) return "前面的开关都试过没用，再用它做最后对照";
        if (ADVICE_FEEDBACK.equals(advice)) return "反馈问题时可以打开";
        if (ADVICE_HARMLESS.equals(advice)) return "不喜欢默认行为就可以开";
        if (ADVICE_DEV_ONLY.equals(advice)) return "仅供开发";
        return advice;
    }

    // ── 白话注释表（设计 §4.3「白话说明」列 + §5 schema）───────────────
    // {名字, 白话, advice, risk}；多出来的名字（开关已删）静默忽略——
    // 本表只经 noteFor 按名查询，谁也不遍历它对全表。
    private static final String[][] NOTES = {
            { "skipInstaller",
              "相当于跳过装机直接开机：用来对照排查「是不是安装环节出的问题」。",
              ADVICE_KEEP_OFF, "资源不齐时会停在下载页。" },
            { "skipOverlay",
              "只是捂上下载的仪表盘：下载照跑，只是你看不见。排查浮层显示问题时用。",
              ADVICE_KEEP_OFF, "开着就看不到下载进度——别慌，它没在偷懒。" },
            { "skipRestart",
              "装完资源本要关门重开一次才算数；这个开关让它不重开。排查「装完重启失败」时用。",
              ADVICE_KEEP_OFF, null },
            { "skipSlowAsk",
              "网慢时游戏会问「还等吗」，开了它就不问了、自己拿主意。",
              ADVICE_HARMLESS, null },
            { "noOverlayGate",
              "下载时引擎本来被按住暂停；这个开关 = 松手。排查「卡在下载页」时用。",
              ADVICE_KEEP_OFF, "开着可能游戏进去了资源还没下完，画面会变奇怪。" },
            { "useAria2",
              "备用下载器：主水管怎么修都不出水时，换墙上另一根消防管。下载反复失败时打开试试——"
              + "它走独立通道，主引擎的毛病影响不到它。",
              ADVICE_KEEP_OFF, null },
            { "useSingleThread",
              "把下载从「八个人同时搬」改成「一个人慢慢搬」。慢，但很多网络环境下"
              + "只有它能搬完——有些宽带、老路由器和公共 Wi-Fi 会掐同时开的连接数，"
              + "那种情况下多线程怎么重试都是一样的失败。",
              ADVICE_KEEP_OFF, "下载会明显变慢，但不会失败得更多。"
              + "平时下载失败弹窗里也能选，不必特地来这里开。" },
            { "skipWebProxy",
              "游戏内网页本有个前台接待帮忙中转；这个开关 = 跳过前台自己直连。网页打不开时，"
              + "用来判断是不是中转环节的问题。",
              ADVICE_KEEP_OFF, null },
            { "noProxyEndpoint",
              "同上，但管的是数据接口：让游戏自己直连服务器。一直转圈、连不上时对照排查用。",
              ADVICE_KEEP_OFF, null },
            { "noHttp2Bump",
              "我们把下载同时干活的人数从 4 提到 10；这个开关退回 4 人。人多反而互相挤"
              + "（下载变更慢）时开着试试。",
              ADVICE_KEEP_OFF, null },
            { "logI18nMiss",
              "帮你随手记下路上看到的「没翻译的招牌」：想反馈缺漏就打开它，去游戏里逛一圈，"
              + "再从「日志与求助」发出来。",
              ADVICE_FEEDBACK, null },
            { "logI18nMissAll",
              "上面那项抓不到你想找的句子时再用——它连英文数字都记。",
              ADVICE_FEEDBACK, "日志里九成是废话。" },
            { "noI18nLabel",
              "汉化像坐在引擎旁的翻译官；这个开关让他对「启动标签」下班。开了启动页变回日文——"
              + "用来对照「这句汉化是不是我们加的」。",
              ADVICE_KEEP_OFF, "开着时启动页文字回日文。" },
            { "noI18nSetString",
              "同上，但管游戏内大段文本。",
              ADVICE_KEEP_OFF, "开着时大片文本回日文。" },
            { "noInitLabelHook",
              "普通开关是「关掉改装件的功能」，这个是把整套改装件从引擎上卸下来——用来判断问题"
              + "是不是改装件本身造成的。",
              ADVICE_LAST_RESORT, "卸掉后对应汉化全部回日文。" },
            { "noTtfHooks",
              "同上，卸的是字体那套改装件。",
              ADVICE_LAST_RESORT, "卸掉后字体相关改动全部失效。" },
            { "skipTutorialPrompt",
              "不再每次问「要不要看序章」。嫌烦可以开。",
              ADVICE_HARMLESS, null },
            { "noTutorialForce",
              "标记要求重播序章时装作没看见标记。排查「一直被拉去看序章」时用。",
              ADVICE_KEEP_OFF, null },
            { "noTutorialGuard",
              "序章期间有个防卡死的护航员；这个开关让它休息。排查序章卡死时用。",
              ADVICE_KEEP_OFF, "开着护航员不在，卡死风险自己担。" },
            { "tutorialSkipToBattle1", "序章跳到第 1 场战斗前。", ADVICE_HARMLESS, null },
            { "tutorialSkipAfterBattle1", "序章跳到第 1 场战斗后。", ADVICE_HARMLESS, null },
            { "tutorialSkipToBattle2", "序章跳到第 2 场战斗前。", ADVICE_HARMLESS, null },
            { "tutorialSkipAfterBattle2", "序章跳到第 2 场战斗后。", ADVICE_HARMLESS, null },
            { "tutorialSkipToBattle3", "序章跳到 CONNECT 教学战前。", ADVICE_HARMLESS, null },
            { "tutorialSkipAfterBattle3", "序章跳到结尾剧情（收尾最快）。", ADVICE_HARMLESS, null },
            { "noAdxSampleRate",
              "我们给声音统一发了节拍器；这个开关 = 让设备自己打拍子。破音、变调时开着对照试试。",
              ADVICE_KEEP_OFF, null },
            { "failConfigFetch",
              "消防演习用的假警报：故意让配置拉取失败，看兜底系统动不动。",
              ADVICE_DEV_ONLY, "开着它，线路配置一定拉取失败，换线、代理设置都会拿不到。" },
            { "failVersionQuery",
              "消防演习用的假警报：假装「查不到版本」。",
              ADVICE_DEV_ONLY, "开着它，版本查询一定失败。" },
            { "slowVersionQuery",
              "消防演习用的假警报：假装「网特别卡」，用来验慢网询问框。",
              ADVICE_DEV_ONLY, "开着它，版本查询会慢到必定弹出慢网询问框。" },
            { "failDownload",
              "消防演习用的假警报：假装「怎么下都失败」。",
              ADVICE_DEV_ONLY, "开着它，资源和热更下载一定全部失败。" },
            { "failHotUpdateApply",
              "消防演习用的假警报：假装「更新装到一半出事」，用来验回滚。",
              ADVICE_DEV_ONLY, "开着它，热更应用事务会做到一半失败并回滚。" },
    };

    // ══ 构件（形制继承下载浮层，设计 §9）══════════════════════════════

    /** 窗口参数：NOT_FOCUSABLE（不抢游戏输入），类型按 API 分叉（21–25 PHONE）。 */
    private static WindowManager.LayoutParams overlayParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        return lp;
    }

    private static void setTitle(String title) {
        if (pageTitleView != null) pageTitleView.setText(title);
    }

    /** 「什么时候用这里？」指引卡（P1：每个功能页顶部固定一张）。 */
    private static void addGuideCard(LinearLayout content, String[] scenes) {
        Activity act = activity;
        LinearLayout card = innerCard(content);
        TextView title = text(act, "什么时候用这里？", 13f,
                color("COLOR_ACCENT2", 0xFF9C5BC2), true);
        card.addView(title, rowLp(act, 0, 4));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scenes.length; i++) {
            sb.append("· ").append(scenes[i]);
            if (i + 1 < scenes.length) sb.append('\n');
        }
        TextView body = text(act, sb.toString(), 12.5f,
                color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), false);
        body.setLineSpacing(dp(act, 3), 1f);
        card.addView(body, rowLp(act, 0, 0));
    }

    /** 面板内的卡片：玻璃底 + 卡片描边（§9）。 */
    private static LinearLayout innerCard(LinearLayout content) {
        Activity act = activity;
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 14), dp(act, 12), dp(act, 14), dp(act, 12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("COLOR_GLASS", 0xCCFFFFFF));
        bg.setCornerRadius(dp(act, 14));
        bg.setStroke(dp(act, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        card.setBackground(bg);
        content.addView(card, rowLp(act, 0, 10));
        return card;
    }

    /** 玻璃卡片（面板本体与弹窗共用）：毛玻璃 + 20dp 圆角 + 玻璃描边。 */
    private static LinearLayout dialogCard(Activity act) {
        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(act, 20), dp(act, 16), dp(act, 20), dp(act, 16));
        card.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("COLOR_LOG_PANEL_BG", 0xFFFFFFFF));
        bg.setCornerRadius(dp(act, 20));
        bg.setStroke(dp(act, 1), color("COLOR_GLASS_STK", 0x33B53C8C));
        card.setBackground(bg);
        return card;
    }

    /** 胶囊按钮：主按钮强调粉、危险按钮红、次按钮幽灵描边（§9）。 */
    private static TextView dialogButton(Activity act, String label,
                                         boolean primary, boolean danger) {
        TextView v = text(act, label, 12.5f,
                primary ? 0xFFFFFFFF : color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B), primary);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(act, 18), dp(act, 8), dp(act, 18), dp(act, 8));
        v.setClickable(true);
        v.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(act, 16));
        if (primary) bg.setColor(danger ? C_DANGER : color("COLOR_ACCENT", 0xFFD63384));
        else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(act, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        }
        v.setBackground(bg);
        return v;
    }

    private static TextView text(Activity act, String value, float sizeSp,
                                 int colorValue, boolean bold) {
        TextView v = new TextView(act);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        v.setTextColor(colorValue);
        if (bold) v.setTypeface(v.getTypeface(), Typeface.BOLD);
        return v;
    }

    private static LinearLayout.LayoutParams rowLp(Context ctx, int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, topDp);
        lp.bottomMargin = dp(ctx, bottomDp);
        return lp;
    }

    private static void toast(String msg) {
        try {
            Activity act = activity;
            if (act != null) {
                android.widget.Toast.makeText(act.getApplicationContext(), msg,
                        android.widget.Toast.LENGTH_LONG).show();
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 借下载浮层的调色板取色，取不到就用兜底值。
     *
     * <p><b>alpha 为 0 也算取不到。</b>那些 {@code COLOR_*} 字段没有初始值，
     * 默认就是 {@code 0}（{@code #00000000}，全透明）。原先只判「反射有没有抛」，
     * 字段存在但还没被 {@code loadPalette} 填过时照样返回 0，于是整个面板的文字
     * 被画成透明——开关名、说明、「已激活」标签全不见，只剩硬编码白色的主按钮
     * 还在（2026-08-13 反馈「useAria2 的文字疑似会消失」）。
     *
     * <p>源头已在 {@code CNCNDownloadUI} 的静态块里兜住，这里再拦一道：以后谁
     * 新加一个 {@code COLOR_*} 却忘了在 loadPalette 里赋值，最坏也只是用兜底色，
     * 不会变成隐形文字——那种 bug 看起来像「功能没做」，最难查。
     */
    private static int color(String name, int fallback) {
        try {
            Field f = CNCNDownloadUI.class.getDeclaredField(name);
            f.setAccessible(true);
            int v = f.getInt(null);
            return (v >>> 24) == 0 ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int dp(View v, int value) {
        return dp(v.getContext(), value);
    }
}
