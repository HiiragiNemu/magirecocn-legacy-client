package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 「是否进入新手教程」询问弹窗。
 *
 * <h3>为什么需要它</h3>
 *
 * 复刻服对任何账号都下发「已通关」的存档，引擎的正常流程<b>永远</b>不会播新手
 * 教程——即使是第一次玩的人也直接落到主页。想看序章只能靠外力。
 *
 * <p>做法与复兴计划客户端（{@code BootstrapActivity} + {@code MagiaClient.cpp}）
 * 一致：这里只负责问一句、然后写一个标记文件；真正改场景的是 native 侧——
 * {@code libMagiaLegacy.so} 拦下引擎首个「进主页」命令
 * （{@code web::SceneCommand::pushSceneTop}），看到标记就改调
 * {@code pushScenePrologue}，进序章。标记<b>一次性消费</b>：native 侧读到就
 * 立刻删除，所以序章放完回主页时不会被再打回去。
 *
 * <h3>什么时候问</h3>
 *
 * 只问一次，在「资源已就位、即将正式进入游戏」的那次启动上。答过就记在
 * SharedPreferences 里，之后再也不问——所以它不是每次启动都要点掉的东西。
 *
 * <p>已经在玩的人升级到这一版时也会被问到一次。这是刻意的：他们同样从没
 * 见过序章（原因见上），给一次机会比精确区分「新人 / 老人」更有价值，而且
 * 我们本来也没有可靠依据把两者分开——{@code cn_base_done.flag} 只说明资源
 * 装过，不说明玩过。
 *
 * <h3>为什么不用 AlertDialog</h3>
 *
 * 宿主是引擎的 Activity，主题是引擎自己的；系统对话框在上面会显得格格不入
 * （之前那三个系统 CheckBox 就被吐槽过）。这里沿用浮层同一套配色与圆角，
 * 直接挂在 decorView 上。
 */
public final class CNTutorialPrompt {

    private static final String TAG = "MagiaCNTutorial";

    /**
     * 强制教程标记。⚠ 必须与 {@code MagiaLegacy.cpp} 的
     * {@code FORCE_TUTORIAL_FLAG_PATH} 逐字一致。
     *
     * <p>放在安装标记的同一个目录：资源装完时该目录必定存在，不必额外建。
     */
    private static final String FORCE_TUTORIAL_FLAG =
            "/data/data/io.kamihama.totentanz/files/madomagi/magica/cn_force_tutorial.flag";

    private static final String PREFS_NAME = "cnv_tutorial";
    /** 是否已经问过（无论答什么）。 */
    private static final String KEY_ASKED  = "prompt_answered";

    /** 与浮层共用的主题偏好，保证深浅色一致。 */
    private static final String PREFS_UI   = "cnv_bootstrap_ui";
    private static final String KEY_DARK   = "dark_mode";

    /** 等 Activity 可用的上限：100 × 100ms = 10 秒。 */
    private static final int  WAIT_TRIES   = 100;
    private static final long WAIT_STEP_MS = 100L;

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 弹窗的根视图，用于关闭时摘掉。只在 UI 线程上读写。 */
    private static FrameLayout overlay;

    private CNTutorialPrompt() {}

    // ==================================================================
    // 入口
    // ==================================================================

    /**
     * 若尚未问过，则在合适的时机弹出询问。不抛异常，不阻塞调用方。
     *
     * <p>由 {@link CNHotUpdateCheck} 在热更检查结束、浮层收掉之后调用——
     * 那正是「即将正式进入游戏」的时刻，而且此时不会和浮层抢同一片屏幕。
     */
    public static void maybeAsk() {
        try {
            if (!STARTED.compareAndSet(false, true)) return;
            if (alreadyAsked()) {
                CNLog.i(TAG, "已经问过新手教程，跳过");
                return;
            }
            Thread t = new Thread("cnv-tutorial-prompt") {
                @Override public void run() {
                    try { askInner(); }
                    catch (Throwable th) { CNLog.e(TAG, "教程弹窗异常: " + th, th); }
                }
            };
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            try { android.util.Log.e(TAG, "教程弹窗启动失败", t); } catch (Throwable ignore) {}
        }
    }

    private static void askInner() {
        final Activity act = awaitActivity();
        if (act == null) {
            // 拿不到界面就什么都不做，也**不**记「已问过」——下次启动再试。
            CNLog.w(TAG, "等不到可用的 Activity，本次不弹教程询问（下次启动重试）");
            STARTED.set(false);
            return;
        }
        CNLog.i(TAG, "弹出新手教程询问");
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { build(act); }
                catch (Throwable t) {
                    CNLog.e(TAG, "构建教程弹窗失败", t);
                    // 建不出来就别把「已问过」写死，留给下次
                    STARTED.set(false);
                }
            }
        });
    }

    /**
     * 等一个真正能挂 View 的 Activity。与 {@link CNHotUpdateCheck} 同一判据：
     * {@code getCurrentActivity()} 读的是 {@code ActivityThread.mActivities}，
     * 记录在 {@code onCreate} 之前就登记，非空不等于窗口已就绪。
     */
    private static Activity awaitActivity() {
        for (int i = 0; i < WAIT_TRIES; i++) {
            Activity act = null;
            try { act = RestClient.getCurrentActivity(); } catch (Throwable ignore) {}
            if (act != null) {
                try {
                    if (act.getWindow() != null && act.getWindow().peekDecorView() != null) {
                        return act;
                    }
                } catch (Throwable ignore) {}
            }
            try { Thread.sleep(WAIT_STEP_MS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    // ==================================================================
    // 视图
    // ==================================================================

    private static void build(final Activity act) {
        boolean dark = false;
        try {
            dark = act.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
                      .getBoolean(KEY_DARK, false);
        } catch (Throwable ignore) {}

        final int cDim    = dark ? 0xAA000000 : 0x88000000;
        final int cPanel  = dark ? 0xFF1B1029 : 0xFFFFFFFF;
        final int cStroke = dark ? 0x55FF80C0 : 0x33B53C8C;
        final int cText   = dark ? 0xFFF5ECFB : 0xFF2A1A3B;
        final int cSub    = dark ? 0xFFB9A6C8 : 0xFF6E5276;
        final int cYes    = dark ? 0xFFFF7AC2 : 0xFFD63384;
        final int cNo     = dark ? 0x33FFFFFF : 0x22000000;

        ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();

        final FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(cDim);
        root.setClickable(true);          // 吃掉点击，别透到引擎画面上
        root.setFocusable(true);

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(cPanel);
        panelBg.setCornerRadius(dp(act, 16));
        panelBg.setStroke(dp(act, 1), cStroke);
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(act, 320), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(act, 24);
        root.addView(panel, panelLp);

        TextView title = new TextView(act);
        title.setText("新手教程");
        title.setTextColor(cYes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, rowLp(0, dp(act, 10)));

        TextView msg = new TextView(act);
        msg.setText("是否播放新手教程（序章）？\n\n"
                  + "· 选「是」：无视账号进度，从头播放序章。\n"
                  + "· 选「否」：直接进入游戏。\n\n"
                  + "只询问这一次，之后不会再问。");
        msg.setTextColor(cSub);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        msg.setLineSpacing(dp(act, 2), 1f);
        panel.addView(msg, rowLp(0, dp(act, 18)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        panel.addView(row, rowLp(0, 0));

        TextView no  = button(act, "否", cText,     cNo);
        TextView yes = button(act, "是", 0xFFFFFFFF, cYes);
        LinearLayout.LayoutParams noLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yesLp.leftMargin = dp(act, 10);
        row.addView(no,  noLp);
        row.addView(yes, yesLp);

        no.setOnClickListener(new Choice(act, false));
        yes.setOnClickListener(new Choice(act, true));

        decor.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = root;
    }

    /** 按钮点击。用具名内部类而不是 lambda：本工程按 minSdk 21 / source 8 编。 */
    private static final class Choice implements View.OnClickListener {
        private final Activity act;
        private final boolean  yes;
        Choice(Activity a, boolean y) { this.act = a; this.yes = y; }
        @Override public void onClick(View v) {
            try {
                if (yes) {
                    writeFlag();
                    CNLog.i(TAG, "玩家选择播放新手教程，已写出强制标记");
                } else {
                    clearFlag();
                    CNLog.i(TAG, "玩家选择跳过新手教程");
                }
                markAsked(act);
            } catch (Throwable t) {
                CNLog.e(TAG, "处理教程选择失败", t);
            } finally {
                dismiss();
            }
        }
    }

    private static void dismiss() {
        try {
            FrameLayout v = overlay;
            overlay = null;
            if (v != null && v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
        } catch (Throwable ignore) {}
    }

    private static TextView button(Activity act, String text, int fg, int bg) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(act, 28), dp(act, 9), dp(act, 28), dp(act, 9));
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(act, 10));
        b.setBackground(d);
        b.setClickable(true);
        return b;
    }

    private static LinearLayout.LayoutParams rowLp(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================================================================
    // 标记与记忆
    // ==================================================================

    private static void writeFlag() {
        try {
            File f = new File(FORCE_TUTORIAL_FLAG);
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                CNLog.e(TAG, "建不出目录 " + dir + "，强制教程标记写不了");
                return;
            }
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(("force:" + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
            } finally {
                try { fos.close(); } catch (Throwable ignore) {}
            }
            CNLog.i(TAG, "已写出强制教程标记：" + f.getAbsolutePath());
        } catch (Throwable t) {
            CNLog.e(TAG, "写强制教程标记失败", t);
        }
    }

    /** 选「否」时清掉可能残留的标记，免得上次没消费干净的标记误触发。 */
    private static void clearFlag() {
        try {
            File f = new File(FORCE_TUTORIAL_FLAG);
            if (f.exists() && f.delete()) CNLog.i(TAG, "已清除残留的强制教程标记");
        } catch (Throwable ignore) {}
    }

    private static boolean alreadyAsked() {
        try {
            Context ctx = appContext();
            if (ctx == null) return false;
            return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                      .getBoolean(KEY_ASKED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markAsked(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            // commit 而非 apply：选「是」之后引擎很快就会进序章，进程也可能被
            // 玩家直接杀掉，异步落盘不保险——写丢了就会再问一次。
            p.edit().putBoolean(KEY_ASKED, true).commit();
        } catch (Throwable t) {
            CNLog.e(TAG, "记录「已问过」失败，下次启动会再问一次", t);
        }
    }

    /** 与 {@link CNHotUpdateCheck} 同一手法：补丁类不由框架实例化，只能反射取。 */
    private static Context appContext() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object thread = cls.getMethod("currentActivityThread").invoke(null);
            return (Context) cls.getMethod("getApplication").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }
}
