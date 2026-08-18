package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.WeakHashMap;

/**
 * 下载浮层内部的显示辅助层：停留、字号缩放、滚动条、左右分界线。
 *
 * <p>所有可见控件都挂在 {@link CNCNDownloadUI#overlayView} 内；关闭下载页即一起
 * 销毁。本类绝不向 decorView 添加悬浮面板，也绝不平移整个下载浮层或游戏画面。
 *
 * <h2>宽度模型：读视口，写内容（2026-08-14 重写）</h2>
 *
 * 这一层历史上在宽度这件事上翻过两次车，方向相反，所以判据必须写清楚。
 *
 * <p><b>第一版：读被自己改的那个 View 的测量宽度。</b>
 * 上一帧按测得的宽度设了新宽度，下一帧再去测，于是越算越大。真机表现是「反复
 * 拖左右分界线，左右越变越长」，而且要反复操作才显形。
 *
 * <p><b>第二版（修上一版的办法）：干脆不读任何 {@code getWidth()}，改用
 * {@code widthPixels − 左右边距} 算死一个像素值</b>，建浮层时算一次存进
 * {@code CNCNDownloadUI.contentBaseWidthPx}，两列再按 weight 去分它。
 * 这条把反馈环掐断了，代价是那个数**经常不等于真实视口**：分屏、旋转、刘海与
 * 手势区 inset、面板自身 padding，任何一项对不上，两列就在按错的总宽分家——
 * 这正是「左右宽度解析有大问题」。而且它只在建浮层那一刻算一次，之后屏幕怎么变
 * 都不会重算。
 *
 * <p><b>现在这版：读<i>视口</i>（{@code hScroll}），写<i>内容</i>
 * （{@code contentRoot}）。</b>两者是父子关系，父的宽度由**它自己的**父布局决定，
 * 不受子节点宽度影响——所以没有反馈环，第一版那个毛病不会回来；而它读的是真实
 * 测量值，第二版那个毛病也不存在。上一版把「不许读 getWidth()」当成了铁律，
 * 那条规矩下得太宽：出事的从来不是「读测量宽度」，是「读了自己马上要改的那个
 * View 的测量宽度」。
 *
 * <p>由此还得到两个白送的好处：
 *
 * <ul>
 *   <li>100% 及以下时内容宽直接交给 {@code MATCH_PARENT} + 容器的
 *       {@code setFillViewport(true)}，<b>一个像素都不用自己算</b>。原先那句
 *       {@code fillViewport(true)} 其实一直是废的：子节点被钉了精确像素宽，
 *       fillViewport 只对 {@code WRAP_CONTENT}/{@code MATCH_PARENT} 生效；</li>
 *   <li>视口变了（旋转、分屏、折叠屏展开）由 {@link ViewportWatch} 收到布局回调
 *       自动重算，不需要谁记得去调一次。</li>
 * </ul>
 */
public final class CNDownloadUiAssist {
    /** buildOverlay 给中央内容和两条真实滚动容器使用的稳定标签。 */
    public static final String TAG_CONTENT_ROOT = "cn-download-content-root";
    public static final String TAG_H_SCROLL = "cn-download-content-hscroll";
    public static final String TAG_V_SCROLL = "cn-download-content-vscroll";
    /**
     * 资源行里那个「文字进度」。它必须是该行的<b>最后一个</b>孩子——右端与下面
     * 整宽进度条的右端对齐是原版的视觉基准，见 {@code CNCNDownloadUI.rebuildSlots}。
     */
    public static final String TAG_SLOT_INFO = "cn-download-slot-info";

    /**
     * 滚动条留出的槽宽（dp）：竖条往右让、横条往下让，都让这么多。
     *
     * <p><b>必须大于滚动条本身的粗细</b>（见 {@link #scrollThumb}）。槽比条还窄的话
     * 条就压在字上——这条不变量由 {@code tools/check-download-ui-contract.py} 钉着。
     * 现在是 7dp 槽配 4dp 条，留 3dp 净空。
     *
     * <p>这个数<b>不是只给滚动条用的</b>：文件列表（{@code slotScroll}）靠右 padding
     * 让出槽位，而它下面那行文字进度与总进度条不在同一个滚动容器里，得用同一个数
     * 做右边距才对得齐。三处一起读这里，谁也别再各写各的——它们错开 1dp 都看得出来。
     *
     * <p>调小它会把竖条连同内容右边界一起往面板边缘推；调大则往里收。
     */
    public static final int SCROLLBAR_GUTTER_DP = 7;

    private static final String LEGACY_TAG = "cn-download-ui-assist";
    private static final String TAG_STAY = "cn-download-stay";
    private static final String TAG_DISPLAY = "cn-download-display";
    private static final String TAG_SPLIT = "cn-download-split";

    private static final String PREFS = "cnv_bootstrap_ui_assist";
    private static final String PREF_SCALE = "font_scale_pct";
    private static final String PREF_SPLIT = "split_left_pct";
    private static final String PREF_SPLIT_HINT = "split_hint_shown";

    /** 玩家可手动调到的字号范围。 */
    private static final int SCALE_MIN = 75;
    private static final int SCALE_MAX = 150;

    /**
     * 左右分界线可拖到的范围与默认值（左列占比 %）。
     *
     * <p>默认 38 = {@code CNCNDownloadUI} 原本写死的 0.38f / 0.62f，不改原设计，
     * 只是让它可调。上下限留得紧：两边都还要放得下东西——左列是 Logo + 署名，
     * 右列是 15 个槽位行，谁被压到 20% 以下都只剩省略号，那种「调得动但没法用」
     * 的自由度不如不给。
     */
    private static final int SPLIT_MIN = 20;
    private static final int SPLIT_MAX = 70;
    private static final int SPLIT_DEFAULT = 38;

    /** 把手宽度，以及静止/调节两态下可见线的内缩量。 */
    private static final int HANDLE_DP = 18;
    private static final int HANDLE_INSET_IDLE = 7;    // → 4dp 可见
    private static final int HANDLE_INSET_ACTIVE = 6;  // → 6dp 可见

    private static final String OLD_LINGER =
            "即将进入游戏；点按浮层（如「教程」胶囊播序章）可稍作停留";
    private static final String NEW_LINGER =
            "检查已完成。可查看日志或管理资源；需要停留请使用“停留本页”。";

    private static final Object STAY_LOCK = new Object();

    /**
     * 字号缩放的基准表：缓存<b>第一次见到的</b>字号与图片尺寸。
     *
     * <p>每次都从基准重算，而不是在当前值上乘——否则反复调字号会指数级放大，
     * 和上面那个宽度反馈环是同一类错误。
     */
    private static final WeakHashMap<TextView, Float> BASE_TEXT_PX =
            new WeakHashMap<TextView, Float>();
    private static final WeakHashMap<ImageView, int[]> BASE_IMAGE_PX =
            new WeakHashMap<ImageView, int[]>();

    /**
     * 建议字号的参考宽度（dp）：内容区有这么宽时建议 100%。
     *
     * <p>720dp ≈ 一台普通手机横屏的内容区宽度。原先用的是 560——那是「返工前
     * 内容区宽度下限」，把一个**下限**当成排版目标，结果几乎每台设备都被推到
     * 115%～150%（实测：1080p 手机 115%，高密度 129%，2K 与平板一律顶到 150%）。
     * 一个「推荐值」如果对所有人都推荐接近最大值，它就没有在推荐任何东西。
     */
    private static final float DESIGN_WIDTH_DP = 720f;

    /**
     * 建议值随宽度变化的斜率，以及建议值自己的上下限。
     *
     * <p><b>为什么要压斜率、收量程</b>：dp 宽度只是「屏幕看起来多大」的<b>粗糙
     * 代理</b>，而且方向还可能是反的——dp = px / density，一台 720p 低密度手机
     * 报出的 dp（797）比 1080p 手机（642）还多，于是前者被建议放得更大。真正
     * 决定可读性的是物理尺寸与视距，而 {@code xdpi} 在 Android 上普遍不可信。
     *
     * <p>既然判据只能是粗糙代理，那就别让它有能力给出荒谬答案：斜率取半
     * （宽一倍只多 50%），建议值夹在 85%–125%。玩家手动仍可调到 75%–150%——
     * 那是他自己的选择，我们只是不主动把人推到极端。
     */
    private static final float SUGGEST_SLOPE = 0.5f;
    private static final int SUGGEST_MIN = 85;
    private static final int SUGGEST_MAX = 125;

    private static View attachedOverlay;
    private static View contentRoot;
    private static HorizontalScrollView hScroll;
    private static ScrollView vScroll;
    private static TextView stayChip;
    private static TextView displayChip;
    private static TextView scaleLabel;
    private static SeekBar scaleSeek;
    private static FrameLayout displayModal;
    private static View splitHandle;
    private static ViewportWatch viewportWatch;
    private static SharedPreferences prefs;

    private static int scalePct = 100;
    private static int splitPct = SPLIT_DEFAULT;

    private static volatile boolean stayRequested;
    private static volatile boolean leaveRequested;
    private static volatile Thread stayThread;

    private static final Runnable INSTALL = new InstallTask();
    private static final Runnable APPLY = new ApplyTask();
    private static final Runnable DETACH = new DetachTask();

    private CNDownloadUiAssist() {}

    // ══ 生命周期 ═══════════════════════════════════════════════════════

    /** 可从任意线程调用；浮层尚未创建时直接返回。 */
    public static void ensureInstalled() {
        if (CNCNDownloadUI.overlayView == null || CNCNDownloadUI.decorView == null) return;
        try {
            Looper main = Looper.getMainLooper();
            if (Looper.myLooper() == main) installOnMain();
            else {
                Handler h = CNCNDownloadUI.uiHandler;
                if (h == null) h = new Handler(main);
                h.post(INSTALL);
            }
        } catch (Throwable t) {
            try { CNLog.w("界面", "内嵌显示控件安装失败: " + t); } catch (Throwable ignore) {}
        }
    }

    private static final class InstallTask implements Runnable {
        @Override public void run() { installOnMain(); }
    }

    private static final class ApplyTask implements Runnable {
        @Override public void run() {
            styleStay();
            styleDisplay();
            styleScrollbars();
            applyScale();
        }
    }

    private static final class DetachTask implements Runnable {
        @Override public void run() { detachOnMain(); }
    }

    private static void installOnMain() {
        FrameLayout overlay = CNCNDownloadUI.overlayView;
        if (overlay == null) return;
        removeLegacy();
        overlay.setTranslationX(0f);
        overlay.setTranslationY(0f);

        if (attachedOverlay != overlay) {
            detachRefs();
            attachedOverlay = overlay;
            contentRoot = overlay.findViewWithTag(TAG_CONTENT_ROOT);
            View hs = overlay.findViewWithTag(TAG_H_SCROLL);
            View vs = overlay.findViewWithTag(TAG_V_SCROLL);
            hScroll = hs instanceof HorizontalScrollView ? (HorizontalScrollView) hs : null;
            vScroll = vs instanceof ScrollView ? (ScrollView) vs : null;
            loadPrefs(overlay.getContext());
            watchViewport();
        }

        replaceLinger(overlay);
        installStay(overlay);
        installDisplay(overlay);
        installSplit();
        styleStay();
        styleDisplay();
        styleScrollbars();
        applyScale();
        try { CNManualRedownload.recoverCompletedRequest(); } catch (Throwable ignore) {}

        // 浮层是分几帧长齐的（槽位行、Logo 图片后到），所以补几次。
        Handler h = CNCNDownloadUI.uiHandler;
        if (h != null) {
            h.removeCallbacks(APPLY);
            h.postDelayed(APPLY, 120L);
            h.postDelayed(APPLY, 800L);
            h.postDelayed(APPLY, 1800L);
        }
        if (stayRequested) startStayWatchdog();
    }

    private static void loadPrefs(Context context) {
        if (prefs == null && context != null) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        if (prefs == null) return;
        // 没调过时用按分辨率算的建议值；调过一次之后一律以玩家的选择为准。
        // 用 -1 而不是 100 当「没调过」的哨兵：100 是合法选择，分不出
        // 「玩家特意选了 100%」和「从没动过」。
        int saved = prefs.getInt(PREF_SCALE, -1);
        scalePct = saved < 0 ? suggestedScale(context) : clamp(saved, SCALE_MIN, SCALE_MAX);
        splitPct = clamp(prefs.getInt(PREF_SPLIT, SPLIT_DEFAULT), SPLIT_MIN, SPLIT_MAX);
    }

    /** 浮层关闭前调用，终止所有引用和看门狗，保证控件绝不进入游戏主界面。 */
    public static void onOverlayDetached() {
        synchronized (STAY_LOCK) {
            stayRequested = false;
            leaveRequested = false;
            STAY_LOCK.notifyAll();
        }
        stopStayWatchdog();
        try {
            Handler h = CNCNDownloadUI.uiHandler;
            if (h != null) {
                h.removeCallbacks(INSTALL);
                h.removeCallbacks(APPLY);
            }
            if (Looper.myLooper() == Looper.getMainLooper()) detachOnMain();
            else if (h != null) h.post(DETACH);
            else detachRefs();
        } catch (Throwable t) {
            detachRefs();
        }
    }

    private static void detachOnMain() {
        removeLegacy();
        View overlay = attachedOverlay;
        if (overlay != null) {
            try {
                overlay.setTranslationX(0f);
                overlay.setTranslationY(0f);
            } catch (Throwable ignore) {}
        }
        detachRefs();
    }

    private static void detachRefs() {
        unwatchViewport();
        lastGeometry = null;
        attachedOverlay = null;
        contentRoot = null;
        hScroll = null;
        vScroll = null;
        stayChip = null;
        displayChip = null;
        scaleLabel = null;
        scaleSeek = null;
        displayModal = null;
        splitHandle = null;
        BASE_TEXT_PX.clear();
        BASE_IMAGE_PX.clear();
    }

    private static void removeLegacy() {
        try {
            ViewGroup decor = CNCNDownloadUI.decorView;
            if (decor == null) return;
            while (true) {
                View old = decor.findViewWithTag(LEGACY_TAG);
                if (old == null || !(old.getParent() instanceof ViewGroup)) break;
                ((ViewGroup) old.getParent()).removeView(old);
                CNLog.i("界面", "已移除旧版独立字/平移悬浮控件");
            }
        } catch (Throwable ignore) {}
    }

    private static void replaceLinger(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            CharSequence text = t.getText();
            if (text != null && OLD_LINGER.contentEquals(text)) t.setText(NEW_LINGER);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) replaceLinger(g.getChildAt(i));
        }
    }

    // ══ 宽度模型 ═══════════════════════════════════════════════════════

    /**
     * 当前**视口**宽度（px）：内容能占多宽由容器说了算。
     *
     * <p>读的是 {@code hScroll} 自己的测量宽度。它是 {@code contentRoot} 的父，
     * 宽度由再上一层决定，不受我们改子节点宽度的影响——所以这里没有反馈环。
     * 详见类注释「宽度模型」。
     *
     * <p>只有首帧之前（还没测量过）才回落到屏幕分辨率推算，那时给个近似值也只是
     * 为了让「建议字号」有个数可算；真值会在 {@link ViewportWatch} 那一刻补上。
     */
    private static int viewportPx(Context ctx) {
        HorizontalScrollView hs = hScroll;
        if (hs != null) {
            int w = hs.getWidth() - hs.getPaddingLeft() - hs.getPaddingRight();
            if (w > 0) return w;
            if (ctx == null) ctx = hs.getContext();
        }
        View root = contentRoot;
        if (ctx == null && root != null) ctx = root.getContext();
        if (ctx == null) return 0;
        try {
            android.util.DisplayMetrics m = ctx.getResources().getDisplayMetrics();
            // 与 buildOverlay 的 mainLp 左右边距一致（各 14+14dp）。这只是首帧兜底。
            return Math.max(1, m.widthPixels - Math.round(56 * m.density));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 视口变了（旋转、分屏、折叠屏展开）就重算内容宽度。 */
    private static void watchViewport() {
        HorizontalScrollView hs = hScroll;
        if (hs == null) return;
        unwatchViewport();
        viewportWatch = new ViewportWatch();
        hs.addOnLayoutChangeListener(viewportWatch);
    }

    private static void unwatchViewport() {
        try {
            HorizontalScrollView hs = hScroll;
            if (hs != null && viewportWatch != null) {
                hs.removeOnLayoutChangeListener(viewportWatch);
            }
        } catch (Throwable ignore) {}
        viewportWatch = null;
    }

    /**
     * 视口尺寸变化的回调。
     *
     * <p>只在**宽度真的变了**时才动手。这一点连同「读父写子」一起保证了不会
     * 自激：改的是 {@code contentRoot} 的宽，而本回调听的是 {@code hScroll} 的
     * 布局；后者的宽度由它自己的父布局决定，不会因为前者变宽而变化，所以下一轮
     * 回调里 {@code now == was}，到此为止。
     */
    private static final class ViewportWatch implements View.OnLayoutChangeListener {
        @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                             int oldL, int oldT, int oldR, int oldB) {
            try {
                if ((r - l) == (oldR - oldL)) return;
                applyWidth();
            } catch (Throwable ignore) {}
        }
    }

    /**
     * 把当前字号换算成内容根该占的宽度。
     *
     * <ul>
     *   <li>≤100%：交给 {@code MATCH_PARENT} + 容器的 {@code fillViewport}——
     *       内容恰好等于视口，一个像素都不用自己算，横向也就没有可滚的东西；</li>
     *   <li>&gt;100%：按<b>实测视口</b>放大。放大后比视口宽，横向滚动条才有意义。</li>
     * </ul>
     */
    private static void applyWidth() {
        View root = contentRoot;
        if (root == null) return;
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (lp == null) return;
        int viewport = viewportPx(root.getContext());
        int want;
        if (scalePct <= 100 || viewport <= 0) {
            want = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            want = Math.max(viewport, Math.round(viewport * scalePct / 100f));
        }
        if (lp.width != want) {
            lp.width = want;
            root.setLayoutParams(lp);
        } else {
            root.requestLayout();
        }
        // 内容宽变了，两列的像素宽就得跟着重算——它们不再靠 weight 自适应，
        // 原因见 applySplit() 里那段红字。
        applySplit();
        // 宽度变了就得把横向滚动状态一起归位。少了这一步的表现正是「调大字号后
        // 两栏被撑大，再调小就回不去」：内容宽度确实缩回去了，但 scrollX 还停在
        // 原处，而滚动条又已经按「没溢出」关掉——看起来就是两栏歪着且拉不回来。
        styleScrollbars();
        logGeometry();
    }

    /** 上一次打出来的几何快照，用来判重——只在数字真的变了时才记一行。 */
    private static String lastGeometry;

    /**
     * 把左右宽度**实际算成了什么**打进日志。
     *
     * <h3>为什么这行日志值得常驻</h3>
     *
     * 这一层的宽度问题已经复发过三轮（反馈环 → 算死像素 → 现在这版），而每一轮的
     * 排查都卡在同一个地方：<b>看得见现象，看不见数字</b>。「左右宽度不对」这句话
     * 对应至少四种完全不同的故障——视口取错、weight 没生效、内容被自己的最小宽度
     * 顶开、缩放把某一列撑爆——而它们在屏幕上长得一样，靠读代码分不出来。
     *
     * <p>成本接近零：布局稳定后每种尺寸只记一行（{@link #lastGeometry} 判重），
     * 屏幕不变就再也不打。这点开销换掉一整轮「猜—发版—再猜」。
     */
    private static void logGeometry() {
        try {
            View root = contentRoot;
            HorizontalScrollView hs = hScroll;
            if (root == null || hs == null) return;
            // 还没测量过就别记：那时候全是 0，只会污染日志。
            if (hs.getWidth() <= 0 || root.getWidth() <= 0) return;

            StringBuilder sb = new StringBuilder();
            sb.append("视口=").append(hs.getWidth())
              .append('(').append(hs.getPaddingLeft()).append('/')
              .append(hs.getPaddingRight()).append(')')
              .append(" 内容=").append(root.getWidth())
              .append(" lp=").append(root.getLayoutParams() == null
                      ? "?" : String.valueOf(root.getLayoutParams().width))
              .append(" 字号=").append(scalePct).append('%')
              .append(" 分界=").append(splitPct).append('%');
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                sb.append(" 列=[");
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (i > 0) sb.append(',');
                    View c = g.getChildAt(i);
                    sb.append(c.getWidth());
                    ViewGroup.LayoutParams clp = c.getLayoutParams();
                    if (clp instanceof LinearLayout.LayoutParams) {
                        sb.append('@').append(((LinearLayout.LayoutParams) clp).weight);
                    }
                }
                sb.append(']');
            }
            android.util.DisplayMetrics m = root.getResources().getDisplayMetrics();
            sb.append(" 屏=").append(m.widthPixels).append('x').append(m.heightPixels)
              .append(" d=").append(m.density);

            String now = sb.toString();
            if (now.equals(lastGeometry)) return;
            lastGeometry = now;
            CNLog.i("界面", "浮层几何 " + now);
        } catch (Throwable ignore) {}
    }

    // ══ 字号 ═══════════════════════════════════════════════════════════

    /**
     * 按设备分辨率推荐一个字号。
     *
     * <p>只是<b>建议</b>：玩家没自己调过时拿它当默认值，调过之后一律以玩家的
     * 选择为准。默默覆盖玩家的设置比给个烂默认值更糟。
     */
    static int suggestedScale(Context ctx) {
        try {
            if (ctx == null) return 100;
            float density = ctx.getResources().getDisplayMetrics().density;
            if (density <= 0f) return 100;
            int viewport = viewportPx(ctx);
            if (viewport <= 0) return 100;
            return suggestFromDp(viewport / density);
        } catch (Throwable t) {
            return 100;
        }
    }

    /**
     * {@link #suggestedScale} 的纯算术部分：内容区有多少 dp 宽 → 建议百分比。
     * 抽出来是为了能在 JVM 上直接测——那边 {@code getResources()} 是桩。
     */
    static int suggestFromDp(float dpWidth) {
        if (!(dpWidth > 0f)) return 100;
        float delta = (dpWidth - DESIGN_WIDTH_DP) / DESIGN_WIDTH_DP;
        return clamp(Math.round(100f + delta * SUGGEST_SLOPE * 100f),
                SUGGEST_MIN, SUGGEST_MAX);
    }

    private static void setScale(int value) {
        scalePct = clamp(value, SCALE_MIN, SCALE_MAX);
        if (prefs != null) prefs.edit().putInt(PREF_SCALE, scalePct).apply();
        if (scaleSeek != null && scaleSeek.getProgress() != scalePct - SCALE_MIN) {
            scaleSeek.setProgress(scalePct - SCALE_MIN);
        }
        if (scaleLabel != null) scaleLabel.setText("字体 " + scalePct + "%");
        styleDisplay();
        applyScale();
        CNCNDownloadUI.noteInteraction();
    }

    private static void applyScale() {
        View root = contentRoot;
        if (root == null) return;
        applyContentScale(root);
        applyWidth();
    }

    /**
     * 按当前字号缩放内容：文字<b>和图片一起</b>。
     *
     * <p>原先只缩文字，Logo 和图标保持原尺寸——字放到 150% 时图还是原来那么大，
     * 看着就是别扭。图片按同一个百分比缩放它的 LayoutParams，与文字同步。
     */
    private static void applyContentScale(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            Float base = BASE_TEXT_PX.get(tv);
            if (base == null) {
                base = Float.valueOf(tv.getTextSize());
                BASE_TEXT_PX.put(tv, base);
            }
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    base.floatValue() * scalePct / 100f);
        } else if (v instanceof ImageView) {
            scaleImage((ImageView) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyContentScale(g.getChildAt(i));
        }
    }

    /** 图片跟着字号走。只动固定尺寸的那一维，MATCH_PARENT / WRAP_CONTENT 不碰。 */
    private static void scaleImage(ImageView iv) {
        try {
            ViewGroup.LayoutParams lp = iv.getLayoutParams();
            if (lp == null) return;
            int[] base = BASE_IMAGE_PX.get(iv);
            if (base == null) {
                base = new int[] { lp.width, lp.height };
                BASE_IMAGE_PX.put(iv, base);
            }
            boolean changed = false;
            // 负数是 MATCH_PARENT(-1) / WRAP_CONTENT(-2)，那两种由父布局说了算，
            // 乘一下只会得出别的负数，把布局搞坏。
            if (base[0] > 0) {
                int w = Math.max(1, Math.round(base[0] * scalePct / 100f));
                if (lp.width != w) { lp.width = w; changed = true; }
            }
            if (base[1] > 0) {
                int h = Math.max(1, Math.round(base[1] * scalePct / 100f));
                if (lp.height != h) { lp.height = h; changed = true; }
            }
            if (changed) iv.setLayoutParams(lp);
        } catch (Throwable ignore) {}
    }

    // ══ 滚动条 ═════════════════════════════════════════════════════════

    private static void styleScrollbars() {
        HorizontalScrollView hs = hScroll;
        ScrollView vs = vScroll;
        if (hs != null) {
            // 只有放大到 >100% 时内容才可能宽过视口——≤100% 那一支是
            // MATCH_PARENT，内容恰好等于视口，没有可滚的东西。
            boolean overflow = contentRoot != null && scalePct > 100;
            hs.setHorizontalScrollBarEnabled(overflow);
            hs.setScrollbarFadingEnabled(true); // 系统原生：淡出
            if (!overflow) hs.scrollTo(0, 0);
            hs.setClipToPadding(false);
            // 横条往下让：槽要比条宽，否则条压在最后一行字上。
            hs.setPadding(hs.getPaddingLeft(), hs.getPaddingTop(),
                    hs.getPaddingRight(), dp(hs, SCROLLBAR_GUTTER_DP));
        }
        if (vs != null) {
            vs.setVerticalScrollBarEnabled(true);
            vs.setScrollbarFadingEnabled(true); // 系统原生：淡出
            vs.setClipToPadding(false);
            // 竖条往右让。同一个数还被文件列表下面那行文字进度与总进度条用作右
            // 边距——它们不在这个滚动容器里，靠这个数才对得齐。
            vs.setPadding(vs.getPaddingLeft(), vs.getPaddingTop(),
                    dp(vs, SCROLLBAR_GUTTER_DP), vs.getPaddingBottom());
        }
    }

    /**
     * 滑块：视觉上是「细胶囊」。**它的 setSize 不决定滚动条宽度**——Android 的
     * {@code getVerticalScrollbarWidth()} 优先读 track 的 intrinsicWidth（见
     * {@link #scrollTrack}），只有 track 为 null 时才会看 thumb，而 {@code styleScrollbars}
     * 总是两个都挂。想调滚动条粗细请改 {@link #scrollTrack}，改这里的数字看不出变化。
     *
     * <p>圆角取半宽才是一颗真的胶囊——圆角大于半宽只会被裁掉，视觉上没差别，
     * 但读的人会以为这里还有别的意图。
     */
    private static GradientDrawable scrollThumb(View v) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color("COLOR_ACCENT", 0xFFD63384));
        d.setCornerRadius(dp(v, 2));
        d.setSize(dp(v, 4), dp(v, 4));
        return d;
    }

    /**
     * 轨道：**滚动条宽度的真正开关**。{@code getVerticalScrollbarWidth()} 会先取
     * {@code ScrollBarDrawable.getSize()}——track 的 intrinsicWidth 优先，thumb 次之，
     * 都为 0 才回退系统 scrollBarSize（约 10dp）。所以这里 setSize 的宽度就是滚动条
     * 的实际宽度，改细改粗都改它。比滑块细 1dp：轨道只是「这里能滚」的暗示，
     * 不该和滑块抢注意力。
     *
     * <p>粗细与 {@link #SCROLLBAR_GUTTER_DP} 是一对：槽必须比条宽，否则条压在字上。
     */
    private static GradientDrawable scrollTrack(View v) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color("COLOR_BAR_BG", 0x335B4661));
        d.setCornerRadius(dp(v, 2));
        d.setSize(dp(v, 3), dp(v, 3));
        return d;
    }

    /**
     * 给任意滚动视图挂<b>浮层内建</b>滚动条样式，让浮层里所有滚动条外观统一：
     * 非淡出（一直可见）、{@code INSIDE_OVERLAY}、自定义 track/thumb（API29+），
     * 并在现有 padding 上叠出槽边距（竖向加右侧、横向加底部）——只该在创建时
     * 调一次，别重复叠加。
     *
     * <p>除 {@code styleScrollbars()} 处理的 hScroll/vScroll（主内容 + 文件列表）
     * 之外的滚动区（日志面板、离线列表、贡献者、顶部胶囊、开关行、消息框）都走
     * 这里，省得各创建点手抄一份系统默认条。
     */
    public static void applyBuiltinScrollbar(ViewGroup v, boolean horizontal) {
        if (v == null) return;
        v.setScrollbarFadingEnabled(false);
        v.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        v.setClipToPadding(false);
        if (horizontal) {
            v.setHorizontalScrollBarEnabled(true);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), v.getPaddingBottom() + dp(v, SCROLLBAR_GUTTER_DP));
            if (Build.VERSION.SDK_INT >= 29) {
                v.setHorizontalScrollbarThumbDrawable(scrollThumb(v));
                v.setHorizontalScrollbarTrackDrawable(scrollTrack(v));
            }
        } else {
            v.setVerticalScrollBarEnabled(true);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight() + dp(v, SCROLLBAR_GUTTER_DP), v.getPaddingBottom());
            if (Build.VERSION.SDK_INT >= 29) {
                v.setVerticalScrollbarThumbDrawable(scrollThumb(v));
                v.setVerticalScrollbarTrackDrawable(scrollTrack(v));
            }
        }
    }

    /**
     * 给任意滚动视图挂<b>系统原生</b>滚动条样式：淡出（滚动时浮现、停手隐藏）、
     * 系统默认滑块/轨道、{@code INSIDE_OVERLAY}。布局侧仍保留
     * {@code clipToPadding(false)} 与槽边距（滚动条不压字需要，与内建版一致）。
     *
     * <p>2026-08-18：浮层滚动条整体换成系统原生观感；{@link #applyBuiltinScrollbar}
     * 与其 {@link #scrollThumb}/{@link #scrollTrack} 内建实现保留不删（若将来要
     * 切回非淡出的胶囊条，改回调用它即可）。只该在创建时调一次，别重复叠加。
     */
    public static void applyNativeScrollbar(ViewGroup v, boolean horizontal) {
        if (v == null) return;
        v.setScrollbarFadingEnabled(true);
        v.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        v.setClipToPadding(false);
        if (horizontal) {
            v.setHorizontalScrollBarEnabled(true);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), v.getPaddingBottom() + dp(v, SCROLLBAR_GUTTER_DP));
        } else {
            v.setVerticalScrollBarEnabled(true);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight() + dp(v, SCROLLBAR_GUTTER_DP), v.getPaddingBottom());
        }
    }

    // ══ 顶部胶囊：停留 / 显示 ═══════════════════════════════════════════

    private static LinearLayout findTopLeftRow(View root) {
        TextView log = findText(root, "LOG");
        return log != null && log.getParent() instanceof LinearLayout
                ? (LinearLayout) log.getParent() : null;
    }

    /**
     * 调试悬浮窗<b>真的挂在屏幕上</b>时，浮层不再重复摆同一颗按钮。
     *
     * <p>判据用 {@link CNDebugBridge#isActive()}（真挂上了）而不是「允许挂」：
     * 本体没实现、权限没授予、挂载抛异常——任何一种情况下这些按钮都得原样留着。
     * 「停留」是玩家卡住时自救的手段，不能因为另一处入口<b>可能</b>存在就先撤掉。
     */
    private static boolean overlayTookOver() {
        try { return CNDebugBridge.isActive(); }
        catch (Throwable t) { return false; }
    }

    private static void dropChip(LinearLayout row, String tag) {
        View old = row.findViewWithTag(tag);
        if (old != null) row.removeView(old);
    }

    private static void installStay(View root) {
        LinearLayout row = findTopLeftRow(root);
        if (row == null) return;
        if (overlayTookOver()) {
            dropChip(row, TAG_STAY);
            stayChip = null;
            return;
        }
        View existing = row.findViewWithTag(TAG_STAY);
        if (existing instanceof TextView) {
            stayChip = (TextView) existing;
            return;
        }
        TextView chip = createTopChip(row, TAG_STAY);
        chip.setOnClickListener(new StayClick());
        row.addView(chip, topChipLp(chip));
        stayChip = chip;
    }

    private static void installDisplay(View root) {
        LinearLayout row = CNCNDownloadUI.headRightRow;
        if (row == null) return;
        View existing = row.findViewWithTag(TAG_DISPLAY);
        if (existing instanceof TextView) {
            displayChip = (TextView) existing;
            return;
        }
        TextView chip = createTopChip(row, TAG_DISPLAY);
        chip.setOnClickListener(new DisplayClick());
        // 插在主题切换胶囊之后（右上角）；headRight 里 0=主题、1=GitHub。
        row.addView(chip, 1, topChipLp(chip));
        displayChip = chip;
    }

    /** 新胶囊照抄同一行里现成胶囊的字号/字重/内边距，免得看起来是外来的。 */
    private static TextView createTopChip(LinearLayout row, String tag) {
        TextView template = null;
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            if (row.getChildAt(i) instanceof TextView) {
                template = (TextView) row.getChildAt(i);
                break;
            }
        }
        TextView chip = new TextView(row.getContext());
        chip.setTag(tag);
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                template == null ? sp(chip, 11f) : template.getTextSize());
        chip.setTypeface(template == null ? Typeface.DEFAULT_BOLD : template.getTypeface(),
                Typeface.BOLD);
        int px = template == null ? dp(chip, 12) : template.getPaddingLeft();
        int py = template == null ? dp(chip, 6) : template.getPaddingTop();
        chip.setPadding(px, py, px, py);
        chip.setClickable(true);
        chip.setFocusable(true);
        return chip;
    }

    private static LinearLayout.LayoutParams topChipLp(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(v, 8);
        return lp;
    }

    private static final class StayClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            Activity act = RestClient.getCurrentActivity();
            if (stayRequested && CNManualRedownload.handleLeaveRequest(act)) return;
            boolean next = !stayRequested;
            setStayOnPage(next);
            CNCNDownloadUI.noteInteraction();
            if (next) {
                CNCNDownloadUI.toast(act, "已停留；点“进入游戏”再离开资源页");
            } else if (CNHotUpdateCheck.isRunning() || CNDownloaderFix.isInstalling()) {
                CNCNDownloadUI.toast(act, "将在当前任务安全收尾后进入游戏");
            } else {
                CNCNDownloadUI.hide();
            }
        }
    }

    private static final class DisplayClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            openDisplay(RestClient.getCurrentActivity());
            CNCNDownloadUI.noteInteraction();
        }
    }

    private static void styleStay() {
        TextView v = stayChip;
        if (v == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(v, 20));
        if (stayRequested) {
            v.setText("进入游戏");
            v.setTextColor(0xFFFFFFFF);
            bg.setColor(color("COLOR_ACCENT", 0xFFD63384));
        } else {
            v.setText("停留本页");
            v.setTextColor(color("COLOR_SUB", 0xFF6E5276));
            bg.setColor(0x00000000);
            bg.setStroke(dp(v, 1), color("COLOR_GLASS_STK", 0x55B53C8C));
        }
        v.setBackground(bg);
    }

    private static void styleDisplay() {
        TextView v = displayChip;
        if (v == null) return;
        v.setText("Aa " + scalePct + "%");
        v.setTextColor(color("COLOR_ACCENT2", 0xFF9C5BC2));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setCornerRadius(dp(v, 20));
        bg.setStroke(dp(v, 1), color("COLOR_ACCENT2", 0xFF9C5BC2));
        v.setBackground(bg);
    }

    // ══ 停留 ═══════════════════════════════════════════════════════════

    public static boolean shouldStayOnPage() {
        return stayRequested;
    }

    /** “进入游戏”是一次性动作；热更新停留窗口消费后自动清掉。 */
    public static boolean consumeLeaveRequest() {
        synchronized (STAY_LOCK) {
            if (!leaveRequested) return false;
            leaveRequested = false;
            return true;
        }
    }

    /** 下载 UI 自己的模态框也必须阻止自动收页。 */
    public static boolean isModalOpen() {
        return displayModal != null;
    }

    /** 包内调用：教程“否”、单包重下和顶部胶囊共用同一停留状态。 */
    public static void setStayOnPage(boolean stay) {
        synchronized (STAY_LOCK) {
            stayRequested = stay;
            leaveRequested = !stay;
            STAY_LOCK.notifyAll();
        }
        if (stay) startStayWatchdog();
        else stopStayWatchdog();
        postInstall();
    }

    /** 首次安装收尾调用：玩家点了“停留本页”时一直等到其点“进入游戏”。 */
    public static void awaitReleaseIfRequested() {
        synchronized (STAY_LOCK) {
            while (stayRequested) {
                try {
                    STAY_LOCK.wait(250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** 停留期间浮层可能被别处收起，看门狗负责把它按回来。 */
    private static void startStayWatchdog() {
        Thread current = stayThread;
        if (current != null && current.isAlive()) return;
        synchronized (CNDownloadUiAssist.class) {
            current = stayThread;
            if (current != null && current.isAlive()) return;
            Thread t = new Thread(new StayLoop(), "cnv-overlay-stay");
            t.setDaemon(true);
            stayThread = t;
            t.start();
        }
    }

    private static void stopStayWatchdog() {
        Thread t = stayThread;
        stayThread = null;
        if (t != null) t.interrupt();
    }

    private static final class StayLoop implements Runnable {
        @Override public void run() {
            while (stayRequested && stayThread == Thread.currentThread()) {
                try {
                    if (CNCNDownloadUI.overlayView != null) postInstall();
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable ignore) {
                    return;
                }
            }
        }
    }

    private static void postInstall() {
        try {
            Handler h = CNCNDownloadUI.uiHandler;
            if (h != null) h.post(INSTALL);
            else ensureInstalled();
        } catch (Throwable ignore) {}
    }

    // ══ 显示设置弹窗 ═══════════════════════════════════════════════════

    private static void openDisplay(Activity act) {
        FrameLayout host = CNCNDownloadUI.overlayView;
        if (act == null || host == null) return;
        closeDisplay();

        FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(color("COLOR_DIM", 0x88000000));
        modal.setClickable(true);
        modal.setFocusable(true);
        modal.setOnClickListener(new CloseDisplayClick());

        LinearLayout panel = dialogPanel(act);
        panel.setOnClickListener(new ConsumeClick());
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                adaptiveDialogWidth(panel), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(panel, 20);
        modal.addView(panel, panelLp);

        TextView title = text(act, "显示大小", 16f, color("COLOR_ACCENT", 0xFFD63384));
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, rowLp(title, 0, 8));

        TextView explain = text(act,
                "只调整下载页中央内容，图片会跟着一起缩放。100% 时内容正好占满可用"
                + "宽度；放大后可用右侧纵向滚动条和底部横向滚动条查看超出部分，"
                + "游戏画面不会移动。左右两栏的分界线可以长按拖动。",
                12.5f, color("COLOR_LOG_PANEL_TEXT", 0xFF2A1A3B));
        explain.setLineSpacing(dp(explain, 2), 1f);
        panel.addView(explain, rowLp(explain, 0, 12));

        scaleLabel = text(act, "字体 " + scalePct + "%", 14f,
                color("COLOR_ACCENT2", 0xFF9C5BC2));
        scaleLabel.setGravity(Gravity.CENTER);
        scaleLabel.setTypeface(scaleLabel.getTypeface(), Typeface.BOLD);
        panel.addView(scaleLabel, rowLp(scaleLabel, 0, 4));

        scaleSeek = new SeekBar(act);
        scaleSeek.setMax(SCALE_MAX - SCALE_MIN);
        scaleSeek.setProgress(scalePct - SCALE_MIN);
        if (Build.VERSION.SDK_INT >= 21) {
            int accent = color("COLOR_ACCENT", 0xFFD63384);
            scaleSeek.setProgressTintList(ColorStateList.valueOf(accent));
            scaleSeek.setThumbTintList(ColorStateList.valueOf(accent));
            scaleSeek.setProgressBackgroundTintList(
                    ColorStateList.valueOf(color("COLOR_BAR_BG", 0x335B4661)));
        }
        scaleSeek.setOnSeekBarChangeListener(new ScaleSeekListener());
        panel.addView(scaleSeek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(scaleSeek, 42)));

        LinearLayout controls = new LinearLayout(act);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        panel.addView(controls, rowLp(controls, 4, 12));
        TextView minus = dialogButton(act, "A−", false);
        // 「推荐」给的是**按这台设备算出来的**建议值，不是死的 100%。
        int suggest = suggestedScale(act);
        TextView reset = dialogButton(act, "推荐 " + suggest + "%", false);
        TextView plus = dialogButton(act, "A+", false);
        minus.setOnClickListener(new ScaleStepClick(-5));
        reset.setOnClickListener(new ScaleResetClick());
        plus.setOnClickListener(new ScaleStepClick(5));
        controls.addView(minus);
        LinearLayout.LayoutParams resetLp = topChipLp(reset);
        controls.addView(reset, resetLp);
        LinearLayout.LayoutParams plusLp = topChipLp(plus);
        controls.addView(plus, plusLp);

        TextView done = dialogButton(act, "完成", true);
        done.setOnClickListener(new CloseDisplayClick());
        panel.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        displayModal = modal;
    }

    private static void closeDisplay() {
        FrameLayout m = displayModal;
        displayModal = null;
        scaleLabel = null;
        scaleSeek = null;
        if (m != null && m.getParent() instanceof ViewGroup) {
            try { ((ViewGroup) m.getParent()).removeView(m); } catch (Throwable ignore) {}
        }
    }

    private static final class ScaleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser) setScale(progress + SCALE_MIN);
        }
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) {}
    }

    private static final class ScaleStepClick implements View.OnClickListener {
        private final int delta;
        ScaleStepClick(int delta) { this.delta = delta; }
        @Override public void onClick(View v) { setScale(scalePct + delta); }
    }

    private static final class ScaleResetClick implements View.OnClickListener {
        @Override public void onClick(View v) { setScale(suggestedScale(v.getContext())); }
    }

    private static final class ConsumeClick implements View.OnClickListener {
        @Override public void onClick(View v) { /* 吃掉点击，别穿透到遮罩 */ }
    }

    private static final class CloseDisplayClick implements View.OnClickListener {
        @Override public void onClick(View v) {
            closeDisplay();
            CNCNDownloadUI.noteInteraction();
        }
    }

    // ══ 左右分界线 ═════════════════════════════════════════════════════
    //
    // `CNCNDownloadUI` 建的 mainRow 是 [左列][右列]，两列之间原本连条线都没有。
    // 这里在中间插一根**可拖**的把手，改的只是两侧的 weight，不动任何一列的
    // 内部布局——所以对面怎么返工那两列都不受影响。
    //
    // 为什么是「长按之后才拖」而不是直接拖：把手正好落在 mainScroll
    // （HorizontalScrollView）里，字号放大到 >100% 时那层是要横向滚动的。直接拖
    // 会和滚动抢同一个手势，误触率高得离谱。长按先确认意图，再
    // requestDisallowInterceptTouchEvent 把手势从滚动那里要过来。

    private static void installSplit() {
        LinearLayout row = splitRow();
        if (row == null) return;

        View existing = row.findViewWithTag(TAG_SPLIT);
        if (existing != null) {
            splitHandle = existing;
            styleSplit(false);
            applySplit();
            return;
        }
        if (row.getChildCount() < 2) return;      // 还没建出两列

        View handle = new View(row.getContext());
        handle.setTag(TAG_SPLIT);
        handle.setLongClickable(true);            // 没有这个收不到长按
        SplitDrag drag = new SplitDrag();
        handle.setOnLongClickListener(drag);
        handle.setOnTouchListener(drag);
        row.addView(handle, 1, new LinearLayout.LayoutParams(
                dp(handle, HANDLE_DP), ViewGroup.LayoutParams.MATCH_PARENT));
        splitHandle = handle;
        styleSplit(false);
        applySplit();
        hintSplitOnce(handle);
    }

    /**
     * 第一次装出把手时提示一次「可以长按拖」。
     *
     * <p>这条不是装饰。把手静止态只是一条竖线，而它的能力（长按后拖动、双击复位）
     * 在界面上没有任何其它痕迹——2026-08-14 维护者的原话是「分界线没加回来」，
     * 而当时它就在屏幕上：看不见的入口等于不存在。提示只给一次，记在 prefs 里。
     */
    private static void hintSplitOnce(View handle) {
        try {
            if (prefs == null || prefs.getBoolean(PREF_SPLIT_HINT, false)) return;
            prefs.edit().putBoolean(PREF_SPLIT_HINT, true).apply();
            CNCNDownloadUI.toast(handle.getContext(),
                    "中间那条线可长按拖动，调整左右两栏宽度；双击复位");
        } catch (Throwable ignore) {}
    }

    /** mainRow：横向的那个内容根。形状不对（对面改了布局）一律返回 null。 */
    private static LinearLayout splitRow() {
        View cr = contentRoot;
        if (!(cr instanceof LinearLayout)) return null;
        LinearLayout row = (LinearLayout) cr;
        return row.getOrientation() == LinearLayout.HORIZONTAL ? row : null;
    }

    /**
     * 把当前占比写进两侧的 weight。
     *
     * <p>只在「两列都是按 weight 排的」时才动手：谁哪天把某一列改成固定宽度，
     * 这里就该什么都不做，而不是把它的宽度清零——那会直接让半个界面消失。
     */
    /**
     * 把当前占比写成两列的<b>精确像素宽</b>。
     *
     * <h3>🔴 为什么这里不能用 weight（2026-08-14，第三轮才查到的真根因）</h3>
     *
     * 两列原本是 {@code width=0 + weight=0.38/0.62}，这在普通 {@code LinearLayout}
     * 里没问题，但它们装在 {@code HorizontalScrollView} 里，而框架那两段凑一起会
     * <b>把 weight 布局毁掉</b>：
     *
     * <pre>
     * // HorizontalScrollView.measureChild —— 无视子节点的 lp.width，一律 UNSPECIFIED
     * childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
     *
     * // LinearLayout.measureHorizontal —— 父不是 EXACTLY 时，把 lp.width 就地改写
     * if (lp.width == 0 &amp;&amp; lp.weight &gt; 0) {
     *     lp.width = LayoutParams.WRAP_CONTENT;   // ⚠ 永久改掉了 LayoutParams
     * }
     * </pre>
     *
     * 第一次测量时容器给的是 UNSPECIFIED，于是 {@code LinearLayout} 把两列的
     * {@code lp.width} <b>就地改写成 WRAP_CONTENT</b>。之后 {@code fillViewport}
     * 再用 EXACTLY 量一遍，可 {@code lp.width} 已经不是 0 了——weight 这时只负责
     * 分配「各列按内容撑开之后<b>剩下</b>的那点空间」，38/62 从此不成立：列宽变成
     * 「内容想要多宽 + 剩余空间的加权零头」。
     *
     * <p>这解释了为什么换过两版宽度模型都没修好——两版都经由同一个 UNSPECIFIED；
     * 也解释了最早那版「反复拖分界线，左右越变越长」：它是在被改写过的
     * WRAP_CONTENT 宽度上继续累加。
     *
     * <p>所以在这个容器里<b>不能靠 weight</b>。这里把 weight 清零，直接按视口算出
     * 两列的像素宽——EXACTLY 与否都不影响结果，框架没有任何机会再改写它。
     */
    private static void applySplit() {
        LinearLayout row = splitRow();
        if (row == null || row.getChildCount() < 3) return;
        View left = row.getChildAt(0);
        View handle = row.getChildAt(1);
        View right = row.getChildAt(2);
        if (!(left.getLayoutParams() instanceof LinearLayout.LayoutParams)
                || !(right.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            return;   // 对面改了布局形状，这里就该什么都不做
        }
        int viewport = viewportPx(row.getContext());
        if (viewport <= 0) return;                 // 还没测量，等下一轮布局回调
        int content = scalePct <= 100 ? viewport
                : Math.max(viewport, Math.round(viewport * scalePct / 100f));

        ViewGroup.LayoutParams hlp = handle.getLayoutParams();
        int handleW = hlp != null && hlp.width > 0 ? hlp.width : dp(row, HANDLE_DP);
        int usable = usableWidth(content, handleW);
        int lw = splitLeftPx(content, handleW, splitPct);
        setExactWidth(left, lw);
        setExactWidth(right, usable - lw);
        row.requestLayout();
    }

    /** 两列能分的总宽（扣掉把手）。至少留 2px，好让下面的夹紧永远有解。 */
    static int usableWidth(int content, int handleW) {
        return Math.max(2, content - handleW);
    }

    /**
     * 左列该占多少像素。纯算术，抽出来是为了能在 JVM 上直接测。
     *
     * <p>两头各夹 1px：占比再极端也不能让某一列变成 0 宽——那一列会整个消失，
     * 而它在界面上和「布局崩了」长得一样。
     */
    static int splitLeftPx(int content, int handleW, int pct) {
        int usable = usableWidth(content, handleW);
        return clamp(Math.round(usable * pct / 100f), 1, usable - 1);
    }

    /** 钉死一列的宽度：给精确像素并把 weight 清零，见 {@link #applySplit()}。 */
    private static void setExactWidth(View v, int px) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (lp.width == px && lp.weight == 0f) return;
        lp.width = px;
        lp.weight = 0f;
        v.setLayoutParams(lp);
    }

    /**
     * 静止是一条<b>看得见</b>的线，进入调节后加粗并换成强调色。
     *
     * <p>上一版静止态是 2dp 宽、alpha 0x33（20%）的描边色，压在毛玻璃底板上基本
     * 等于隐形；加上「必须长按」，玩家看不到线、随手拖又没反应，得出的结论是
     * 「这个功能没做」。现在静止就有 4dp 宽、60% 不透明的强调色。
     */
    private static void styleSplit(boolean active) {
        View v = splitHandle;
        if (v == null) return;
        v.setBackground(splitBackground(v, active));
    }

    /**
     * 分割线背景：中间竖线 + 竖直中点两侧各一个小三角（◁ ▷），
     * 作为「可长按拖动」的可见提示（2026-08-16）。
     */
    private static Drawable splitBackground(final View v, final boolean active) {
        final int color = active ? color("COLOR_ACCENT", 0xFFD63384)
                : (color("COLOR_ACCENT2", 0xFF9C5BC2) & 0x00FFFFFF) | 0x99000000;
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                paint.setColor(color);
                paint.setStyle(Paint.Style.FILL);
            }
            @Override public void draw(Canvas canvas) {
                int w = getBounds().width(), h = getBounds().height();
                int inset = dp(v, active ? HANDLE_INSET_ACTIVE : HANDLE_INSET_IDLE);
                // 竖线（上下各留 6dp）
                RectF r = new RectF(inset, dp(v, 6), w - inset, h - dp(v, 6));
                canvas.drawRoundRect(r, dp(v, 3), dp(v, 3), paint);
                // 两个小三角（◁ ▷）夹在竖线中点两侧
                float midY = h / 2f;
                int tri = dp(v, 4);
                int leftX = w / 2 - inset / 2 - tri;
                int rightX = w / 2 + inset / 2 + tri;
                Path lp = new Path();
                lp.moveTo(leftX, midY);            // 左三角尖朝左
                lp.lineTo(leftX + tri, midY - tri);
                lp.lineTo(leftX + tri, midY + tri);
                lp.close();
                canvas.drawPath(lp, paint);
                Path rp = new Path();
                rp.moveTo(rightX, midY);           // 右三角尖朝右
                rp.lineTo(rightX - tri, midY - tri);
                rp.lineTo(rightX - tri, midY + tri);
                rp.close();
                canvas.drawPath(rp, paint);
            }
            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(ColorFilter cf) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private static void setSplit(int value) {
        splitPct = clamp(value, SPLIT_MIN, SPLIT_MAX);
        applySplit();
    }

    private static void persistSplit() {
        if (prefs != null) prefs.edit().putInt(PREF_SPLIT, splitPct).apply();
    }

    /**
     * 拖动期间把手势从祖先的滚动容器手里要过来（松手时还回去）。
     *
     * <p>把手在 {@code HorizontalScrollView} 里，不要过来的话一横向移动就被滚动
     * 吃掉，长按之后根本拖不动。
     */
    private static void grabGesture(View v, boolean grab) {
        try {
            android.view.ViewParent p = v.getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(grab);
        } catch (Throwable ignore) {}
    }

    /**
     * 长按进调节、拖动改占比、双击复位。
     *
     * <p>static 嵌套类——匿名/非静态内部类会带合成字段 this$0，d8 撞上直接 NPE
     * （CLAUDE.md 铁律，CI 的 check-d8-pitfalls.py 会拦）。
     */
    private static final class SplitDrag
            implements View.OnTouchListener, View.OnLongClickListener {
        private boolean dragging;
        private float startX;
        private int startPct;
        private long lastTapAt;

        @Override public boolean onLongClick(View v) {
            dragging = true;
            startPct = splitPct;
            grabGesture(v, true);
            try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }
            catch (Throwable ignore) {}
            styleSplit(true);
            CNCNDownloadUI.noteInteraction();
            // 用把手自己的 Context，不要 RestClient.getCurrentActivity()：后者可能
            // 是 null，那样 Toast 会在 CNCNDownloadUI.toast 里被 catch 悄悄吞掉，
            // 玩家长按之后毫无反馈——看起来就是「这条线根本拖不动」。
            CNCNDownloadUI.toast(v.getContext(), "左右拖动调整分界；松手保存，双击复位");
            CNLog.i("界面", "分界线：进入拖动态 起始=" + startPct + "%");
            return true;
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startPct = splitPct;
                    dragging = false;
                    return false;            // 交给 View 自己去产生长按
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) return false;
                    // 换算用**视口**宽度：拖动改的是两列在视口里的占比。
                    // 这里读的是 hScroll（父），改的是两列的 weight（孙），
                    // 同样不构成反馈环，理由见类注释。
                    int w = viewportPx(v.getContext());
                    if (w > 0) {
                        float delta = e.getRawX() - startX;
                        setSplit(startPct + Math.round(delta * 100f / w));
                    } else {
                        CNLog.w("界面", "分界线：拖动中取不到视口宽度，本次位移被丢弃");
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) {
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) checkDoubleTap(v, e);
                        return false;
                    }
                    dragging = false;
                    grabGesture(v, false);
                    styleSplit(false);
                    persistSplit();
                    CNCNDownloadUI.noteInteraction();
                    return true;
                default:
                    return false;
            }
        }

        /**
         * 双击分界线复位到默认。
         *
         * <p>复位入口放在分界线自己身上，而不是借「显示大小」弹窗的地儿：那个
         * 弹窗是字号的，不该塞进别的功能。双击也不跟长按抢——长按走的是
         * {@code dragging} 那条路，到不了这里。
         */
        private void checkDoubleTap(View v, MotionEvent e) {
            int slop = android.view.ViewConfiguration.get(v.getContext())
                    .getScaledTouchSlop();
            long now = android.os.SystemClock.uptimeMillis();
            boolean moved = Math.abs(e.getRawX() - startX) > slop;
            if (!moved && lastTapAt != 0L
                    && now - lastTapAt <= android.view.ViewConfiguration.getDoubleTapTimeout()) {
                lastTapAt = 0L;
                setSplit(SPLIT_DEFAULT);
                persistSplit();
                try { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); }
                catch (Throwable ignore) {}
                CNCNDownloadUI.noteInteraction();
                CNCNDownloadUI.toast(v.getContext(), "分界已复位到 " + SPLIT_DEFAULT + "%");
            } else {
                lastTapAt = moved ? 0L : now;
            }
        }
    }

    // ══ 建浮层时的列宽 ═════════════════════════════════════════════════

    /**
     * 建浮层时左列该占的 weight。
     *
     * <p><b>浮层必须<i>建出来就是</i>玩家调好的比例</b>，而不是先按硬编码的
     * 38/62 建好、再由 {@link #applySplit()} 改回去。写死的话每次重建浮层
     * （切主题、看门狗发现它掉出视图树）都会先闪回默认比例，而 installSplit 要等
     * ensureInstalled 那一轮才跑——中间那段就是玩家看到的「刷新一下比例被重置了」。
     *
     * <p>参数带 Context 是因为这可能是本进程第一次碰它：prefs 还没打开过时得先把
     * 玩家存的值读进来，否则又是一个「默认值假装成玩家的选择」。
     */
    public static float leftWeight(Context ctx) {
        loadPrefs(ctx);
        return splitPct / 100f;
    }

    /** 见 {@link #leftWeight(Context)}。两个加起来恒为 1。 */
    public static float rightWeight(Context ctx) {
        return 1f - leftWeight(ctx);
    }

    // ══ 小工具 ═════════════════════════════════════════════════════════

    /** 弹窗宽度永远不超过当前逻辑屏幕减 40dp，覆盖窄屏、分屏和高 DPI。 */
    private static int adaptiveDialogWidth(View v) {
        int available = v.getResources().getDisplayMetrics().widthPixels - dp(v, 40);
        return Math.max(1, Math.min(dp(v, 380), available));
    }

    private static LinearLayout dialogPanel(Activity act) {
        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(panel, 22), dp(panel, 20), dp(panel, 22), dp(panel, 18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("COLOR_LOG_PANEL_BG", 0xFFFFFFFF));
        bg.setCornerRadius(dp(panel, 18));
        bg.setStroke(dp(panel, 1), color("COLOR_CARD_STK", 0x33B53C8C));
        panel.setBackground(bg);
        return panel;
    }

    private static TextView dialogButton(Activity act, String label, boolean primary) {
        TextView b = new TextView(act);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setPadding(dp(b, 18), dp(b, 10), dp(b, 18), dp(b, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(b, 20));
        if (primary) {
            bg.setColor(color("COLOR_ACCENT", 0xFFD63384));
            b.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(b, 1), color("COLOR_ACCENT2", 0xFF9C5BC2));
            b.setTextColor(color("COLOR_ACCENT2", 0xFF9C5BC2));
        }
        b.setBackground(bg);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private static TextView findText(View v, String expected) {
        if (v instanceof TextView) {
            CharSequence s = ((TextView) v).getText();
            if (s != null && expected.contentEquals(s)) return (TextView) v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView hit = findText(g.getChildAt(i), expected);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static TextView text(Activity act, String value, float size, int colorValue) {
        TextView v = new TextView(act);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        v.setTextColor(colorValue);
        return v;
    }

    private static LinearLayout.LayoutParams rowLp(View v, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(v, top);
        lp.bottomMargin = dp(v, bottom);
        return lp;
    }

    /**
     * 反射取 {@code CNCNDownloadUI} 的调色板。
     *
     * <p>那些字段没有初始值，默认是 0 —— 而 0 是 #00000000，全透明。所以
     * <b>取到 0 也算没取到</b>：只在反射抛异常时才用兜底值的写法，会把整块面板
     * 画成透明（真机上时有时无，取决于这次启动有没有建过下载浮层）。
     */
    private static int color(String name, int fallback) {
        try {
            // 带上一个真 Context，好让它按玩家的深浅色偏好选调色板；拿不到就算了，
            // CNCNDownloadUI 有静态初始化兜底，字段不会是空的。
            View any = contentRoot != null ? contentRoot : attachedOverlay;
            CNCNDownloadUI.ensurePalette(any == null ? null : any.getContext());
        } catch (Throwable ignore) {}
        try {
            Field f = CNCNDownloadUI.class.getDeclaredField(name);
            f.setAccessible(true);
            int v = f.getInt(null);
            return (v >>> 24) == 0 ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int dp(View v, int value) {
        return (int) (value * v.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static float sp(View v, float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                v.getResources().getDisplayMetrics());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---- JVM 回归测试入口 ----
    // 拖动与布局要真机，但**夹紧范围**是纯算术，而它恰恰是把界面搞坏的唯一途径：
    // 越界一格，某一列就变成一条只剩省略号的缝。
    public static int splitLeftPxForTest(int content, int handleW, int pct) {
        return splitLeftPx(content, handleW, pct);
    }
    public static int splitUsableForTest(int content, int handleW) {
        return usableWidth(content, handleW);
    }
    public static int splitPctForTest() { return splitPct; }
    public static void setSplitForTest(int value) { setSplit(value); }
    public static int splitDefaultForTest() { return SPLIT_DEFAULT; }
    public static int splitMinForTest() { return SPLIT_MIN; }
    public static int splitMaxForTest() { return SPLIT_MAX; }
    public static int suggestFromDpForTest(float dpWidth) { return suggestFromDp(dpWidth); }
    public static float designWidthDpForTest() { return DESIGN_WIDTH_DP; }
    public static int suggestMinForTest() { return SUGGEST_MIN; }
    public static int suggestMaxForTest() { return SUGGEST_MAX; }
}
