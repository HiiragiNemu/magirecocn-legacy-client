package io.kamihama.magianative;

import android.app.Activity;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 资源下载浮层 UI。
 *
 * <p>挂在游戏 Activity 的 decorView 上，在引擎接管画面之前展示下载/解压进度。
 *
 * <p>本类的外部契约（public static 字段与方法）与改版前完全一致，
 * {@code RestClient} 与 {@code CNDownloaderFix} 的调用点无需任何改动：
 * 改动只发生在「怎么把这些数据画出来」这一层。
 *
 * <p>视觉上采用与复兴计划客户端 {@code BootstrapActivity} 一致的样式：
 * 背景图 + 毛玻璃底板 + 左列 Logo/署名区 + 右列阶段/文件槽位/总进度条，
 * 左上角 LOG 胶囊（查看原始安装日志），右上角亮色/夜间主题切换。
 */
public class CNCNDownloadUI {

    // ==================================================================
    // 对外契约：以下 public static 成员的名字与签名不可改动
    // ==================================================================

    public static ViewGroup decorView;
    public static boolean isShowing;
    public static long lastUpdateTime;
    public static FrameLayout overlayView;
    public static ProgressBar progressBarOverall;
    /** 原始安装日志文本视图；现位于 LOG 模态面板内。show() 用它作为建好的哨兵。 */
    public static TextView tvLog;
    /** 总速度标签；现位于总进度条右侧。 */
    public static TextView tvSpeed;
    public static Handler uiHandler;

    public static final String[] FILE_URLS = {
        "https://assets.example.test/cn_base_00_db.zip",
        "https://assets.example.test/cn_base_01_json.zip",
        "https://assets.example.test/cn_base_02.zip",
        "https://assets.example.test/cn_base_03.zip",
        "https://assets.example.test/cn_base_04.zip",
        "https://assets.example.test/cn_base_05.zip",
        "https://assets.example.test/cn_base_06.zip",
        "https://assets.example.test/cn_magica_resource.zip",
        "https://assets.example.test/cn_scenario_img.zip",
        "https://assets.example.test/cn_voice_01.zip",
        "https://assets.example.test/cn_voice_02_done.zip",
        "https://assets.example.test/cn_js_update.zip",
        "https://assets.example.test/movie.zip",
        "https://assets.example.test/movie2.zip",
        "https://assets.example.test/cn_scenario_update.zip"
    };

    public static final String[] FILE_NAMES = {
        "cn_base_00_db.zip", "cn_base_01_json.zip", "cn_base_02.zip",
        "cn_base_03.zip", "cn_base_04.zip", "cn_base_05.zip",
        "cn_base_06.zip", "cn_magica_resource.zip", "cn_scenario_img.zip",
        "cn_voice_01.zip", "cn_voice_02_done.zip", "cn_js_update.zip",
        "movie.zip", "movie2.zip", "cn_scenario_update.zip"
    };

    public static int[]   fileStatus     = new int[15];
    public static int[]   fileProgress   = new int[15];
    public static float[] fileSize       = new float[15];
    public static float[] fileSpeed      = new float[15];
    public static float[] fileDownloaded = new float[15];

    // ==================================================================
    // 以下为改版新增的内部状态（无外部引用）
    // ==================================================================

    /** 文件数量。与原实现一致地固定为 15。 */
    private static final int FILE_COUNT = 15;

    /** 资源目录内的背景图路径。 */
    private static final String BG_ASSET   = "magia/background_light.png";
    /** 资源目录内的游戏 Logo 路径。 */
    private static final String LOGO_ASSET = "magia/logo.png";

    private static final String PREFS_NAME     = "cnv_bootstrap_ui";
    private static final String PREF_DARK_MODE = "dark_mode";

    /** 承载浮层的宿主 Activity；主题切换时需要用它重建视图树。 */
    private static Activity hostActivity;

    /** 由 updateSimple() 写入、在 UpdateRunnable 中渲染的阶段标题与明细。 */
    private static volatile String phaseText  = "准备中";
    private static volatile String detailText = "正在初始化下载器…";

    // ---- 配色（取自 BootstrapActivity 的调色板） ----
    private static int COLOR_CARD_STK;
    private static int COLOR_ACCENT;
    private static int COLOR_ACCENT2;
    private static int COLOR_TEXT;
    private static int COLOR_SUB;
    private static int COLOR_BAR_BG;
    private static int COLOR_LOG_PILL;
    private static int COLOR_DIM;
    private static int COLOR_LOG_PANEL_BG;
    private static int COLOR_LOG_PANEL_TEXT;
    private static int COLOR_LINK;      // 链接文字色（克制，不用强调粉）
    private static int COLOR_GLASS;
    private static int COLOR_GLASS_STK;
    private static boolean darkMode = false;

    private static void loadPalette(boolean dark) {
        if (dark) {
            COLOR_CARD_STK       = 0x55FF80C0;
            COLOR_ACCENT         = 0xFFFF7AC2;
            COLOR_ACCENT2        = 0xFFB87FE0;
            COLOR_TEXT           = 0xFFEFE4F8;
            COLOR_SUB            = 0xFFB9A6C8;
            COLOR_BAR_BG         = 0x44FFFFFF;
            COLOR_LOG_PILL       = 0xE6FF6FB5;
            COLOR_DIM            = 0xAA000000;
            COLOR_LOG_PANEL_BG   = 0xFF1B1029;
            COLOR_LOG_PANEL_TEXT = 0xFFF5ECFB;
            COLOR_LINK           = 0xFF8FC6F0;   // 夜间：浅蓝
            COLOR_GLASS          = 0xCC18112A;
            COLOR_GLASS_STK      = 0x44FF80C0;
        } else {
            COLOR_CARD_STK       = 0x33B53C8C;
            COLOR_ACCENT         = 0xFFD63384;
            COLOR_ACCENT2        = 0xFF9C5BC2;
            COLOR_TEXT           = 0xFF2A1A3B;
            COLOR_SUB            = 0xFF6E5276;
            COLOR_BAR_BG         = 0x22000000;
            COLOR_LOG_PILL       = 0xE6D63384;
            COLOR_DIM            = 0x88000000;
            COLOR_LOG_PANEL_BG   = 0xFFFFFFFF;
            COLOR_LOG_PANEL_TEXT = 0xFF2A1A3B;
            COLOR_LINK           = 0xFF2C6BA8;   // 亮色：沉稳蓝
            COLOR_GLASS          = 0xCCFFFFFF;
            COLOR_GLASS_STK      = 0x33B53C8C;
        }
    }

    /** 未指定颜色时按索引取色的备用调色板（ARGB）。 */
    private static final int[] CONTRIB_PALETTE = {
        0xFF3D7BFF, 0xFF8BB87A, 0xFFE667A0, 0xFF4FB7E6,
        0xFFF2A65A, 0xFF9B8CFF, 0xFF52C7B8, 0xFFE57373,
    };

    // ---- 署名区数据 ----
    // 文案与改版前的大段署名 TextView 完全一致，仅按条目重新排版。
    private static final int KIND_TITLE = 0;
    private static final int KIND_HEAD  = 1;
    private static final int KIND_ITEM  = 2;
    private static final int KIND_SUB   = 3;

    private static final int[] CREDIT_KINDS = {
        KIND_TITLE, KIND_ITEM, KIND_SUB, KIND_HEAD, KIND_ITEM, KIND_ITEM,
        KIND_ITEM, KIND_HEAD, KIND_ITEM, KIND_ITEM, KIND_ITEM, KIND_ITEM, KIND_ITEM
    };

    private static final String[] CREDIT_TEXTS = {
        "魔法纪录Totentanz中文化",
        "【核心逆向开发】MadeInMagius【B站ID】",
        "(独立完成汉化引擎以及下载系统和日服国服资源合并)",
        "其他个人网站",
        "reader.pages.example【魔法纪录剧情中日双语阅读网站】",
        "live2d.pages.example【MagiaExedra和魔法纪录Live2D网站】",
        "callsearch.pages.example【魔法少女称呼关系搜索与身高对比网站】",
        "【协助与鸣谢】",
        "国服文件之外的翻译和校对：水银h2oag【阅读器网站为主，资源已同步至游戏】",
        "下载加速及资源自动化推送：CyberNova",
        "国服数据留存：segfault",
        "项目官网：www.example.test【通往其他个人网站和提供联系方式】",
        "bilibili视频教程：BV1faRiBBExk"
    };

    /**
     * 与 {@link #CREDIT_TEXTS} 一一对应的外链地址；空串表示该条不可点击。
     *
     * <p>点击行为是「两段式」的：第一下只弹 Toast 提示，第二下才真正调起系统
     * 浏览器。下载界面盖在游戏之上，误触直接跳出去会打断安装，所以要求确认。
     */
    private static final String[] CREDIT_URLS = {
        "",                                                     // 标题
        "https://b23.tv/aNjcz1p",                               // MadeInMagius
        "",                                                     // 说明
        "",                                                     // 「其他个人网站」小标题
        "https://reader.pages.example",
        "https://live2d.pages.example",
        "https://callsearch.pages.example",
        "",                                                     // 「协助与鸣谢」小标题
        "https://b23.tv/ovvbrNw",                               // 水银h2oag
        "https://b23.tv/9vyRcI8",                               // CyberNova
        "https://b23.tv/xjXW9DI",                               // segfault
        "https://www.example.test",
        "https://www.bilibili.com/video/BV1faRiBBExk"
    };

    /**
     * 每条可点击条目里**只有这一段**会被染成链接色。
     *
     * <p>整行都上强调色 + 下划线太吵——署名区本来就是一大段文字，全刷成粉色下划线
     * 会盖过进度信息。这里只把「网址」或「人名」那一小段标出来，其余保持正文色，
     * 既能看出可点，又不喧宾夺主。空串表示整行都不特殊着色。
     */
    private static final String[] CREDIT_LINK_SPANS = {
        "",
        "MadeInMagius",
        "",
        "",
        "reader.pages.example",
        "live2d.pages.example",
        "callsearch.pages.example",
        "",
        "水银h2oag",
        "CyberNova",
        "segfault",
        "www.example.test",
        "BV1faRiBBExk"
    };

    /**
     * 右上角 GitHub 胶囊指向的地址。
     *
     * <p>APK 内原有的署名里没有出现任何 GitHub 地址，这一条是我按「仓库风格」补的，
     * 改成别的只需要动这一行。
     */
    private static final String URL_GITHUB = "https://github.com/MagirecoCN-Revival-Project";

    /** 二次确认的有效期：超过这个时间没点第二下，就要重新从第一下开始。 */
    private static final long CONFIRM_WINDOW_MS = 6000L;

    /** 当前处于「已提示、等待第二次点击」状态的地址；null 表示没有待确认项。 */
    private static String pendingUrl  = null;
    /** {@link #pendingUrl} 的提示时刻。 */
    private static long   pendingAtMs = 0L;

    /** 底部常驻署名条：原先塞在速度行里的那句长文案，原文保留。 */
    private static final String FOOTER_CREDIT =
        "核心开发: B站 @MadeInMagius【B站xhs tx同名】 | 国内加速+修复：@PhotonFlow "
        + "| 如果需要联系请先b站私信，会提供群聊 | 该游戏支持后续剧情更新";

    // ---- 视图引用 ----
    private static TextView     vPhase;
    private static TextView     vStatus;
    private static TextView     vAggregate;
    private static TextView     vOverallText;
    private static LinearLayout slotContainer;
    private static LinearLayout vContribList;
    private static TextView     vThemeChip;
    private static TextView     vGitHubChip;
    private static GradientDrawable githubChipBg;
    private static TextView     vLogPill;
    private static FrameLayout  logModal;
    private static ScrollView   vLogScroll;
    private static GradientDrawable themeChipBg;
    private static GradientDrawable logPillBg;

    /** 每个文件一个槽位。 */
    private static final class SlotViews {
        final TextView    nameView;
        final TextView    infoView;
        final TextView    retryView;
        final ProgressBar bar;
        final View        divider;
        SlotViews(TextView n, TextView i, TextView r, ProgressBar b, View d) {
            nameView = n; infoView = i; retryView = r; bar = b; divider = d;
        }
    }

    private static final List<SlotViews> slotList = new ArrayList<SlotViews>();

    // ==================================================================
    // 视图构建
    // ==================================================================

    /**
     * 毛玻璃底板：圆角矩形 + 半透明填充 + 描边。
     * 与 BootstrapActivity.GlassPanelView 保持一致（含异步模糊图的接入点）。
     */
    private static final class GlassPanelView extends View {
        private final Paint  paint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF  bounds = new RectF();
        private final int    fillColor;
        private final int    strokeColor;
        private final float  radius;
        private       Bitmap blurBitmap;

        GlassPanelView(Context ctx, int fill, int stroke, float radiusPx) {
            super(ctx);
            fillColor   = fill;
            strokeColor = stroke;
            radius      = radiusPx;
        }

        void setBlurBitmap(Bitmap b) {
            blurBitmap = b;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            bounds.set(0, 0, getWidth(), getHeight());
            if (blurBitmap != null) {
                Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
                Matrix m = new Matrix();
                m.setScale((float) getWidth()  / blurBitmap.getWidth(),
                           (float) getHeight() / blurBitmap.getHeight());
                BitmapShader bs = new BitmapShader(blurBitmap,
                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bs.setLocalMatrix(m);
                bp.setShader(bs);
                canvas.drawRoundRect(bounds, radius, radius, bp);
                paint.setColor(fillColor & 0x88FFFFFF);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(bounds, radius, radius, paint);
            } else {
                paint.setColor(fillColor);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(bounds, radius, radius, paint);
            }
            paint.setColor(strokeColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            canvas.drawRoundRect(bounds, radius, radius, paint);
        }
    }

    /** 圆点：署名条目前的彩色小圆。 */
    private static final class DotView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        DotView(Context ctx, int color) {
            super(ctx);
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
        }
        @Override protected void onDraw(Canvas c) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static LinearLayout.LayoutParams lpRow(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin    = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    /** 把已解码的 Bitmap 设置到 ImageView（主线程执行）。 */
    private static final class ApplyBitmap implements Runnable {
        private final ImageView target;
        private final Bitmap    bitmap;
        ApplyBitmap(ImageView t, Bitmap b) { target = t; bitmap = b; }
        @Override public void run() {
            try { target.setImageBitmap(bitmap); } catch (Throwable ignore) {}
        }
    }

    /** 后台线程：从 assets 解码图片，完成后回到主线程设置。缺失时静默忽略。 */
    private static final class AssetBitmapLoader implements Runnable {
        private final Activity  act;
        private final String    assetPath;
        private final ImageView target;
        AssetBitmapLoader(Activity a, String p, ImageView t) {
            act = a; assetPath = p; target = t;
        }
        @Override public void run() {
            try {
                Bitmap bm;
                InputStream is = act.getAssets().open(assetPath);
                try {
                    bm = BitmapFactory.decodeStream(is);
                } finally {
                    try { is.close(); } catch (Throwable ignore) {}
                }
                if (bm == null) return;
                act.runOnUiThread(new ApplyBitmap(target, bm));
            } catch (Throwable ignore) {}
        }
    }

    /** 异步从 assets 载入图片，成功后回到主线程设置。缺失时静默忽略。 */
    private static void loadBitmapFromAssets(final Activity act,
                                             final String assetPath,
                                             final ImageView target) {
        new Thread(new AssetBitmapLoader(act, assetPath, target),
                   "cnv-img-load").start();
    }

    /**
     * 构建整棵浮层视图树。主题切换时会被重新调用。
     */
    private static FrameLayout buildOverlay(final Activity act) {
        FrameLayout root = new FrameLayout(act);
        root.setClickable(true);

        // ── 第 0 层：背景图 ──
        ImageView bgView = new ImageView(act);
        bgView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // 资源缺失时的兜底底色，保证浮层始终不透明、不漏出游戏画面
        bgView.setBackgroundColor(darkMode ? 0xFF150E22 : 0xFFF3E9F5);
        loadBitmapFromAssets(act, BG_ASSET, bgView);
        if (darkMode) bgView.setColorFilter(0xAA000000, PorterDuff.Mode.SRC_ATOP);
        root.addView(bgView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 第 1 层：毛玻璃底板 ──
        GlassPanelView glass = new GlassPanelView(
                act, COLOR_GLASS, COLOR_GLASS_STK, dp(act, 20));
        FrameLayout.LayoutParams glassLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        glassLp.leftMargin   = dp(act, 14);
        glassLp.rightMargin  = dp(act, 14);
        glassLp.topMargin    = dp(act, 52);
        glassLp.bottomMargin = dp(act, 40);
        root.addView(glass, glassLp);

        // ── 第 2 层：主内容区（左右两列） ──
        LinearLayout mainRow = new LinearLayout(act);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams mainLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        mainLp.leftMargin   = dp(act, 14) + dp(act, 14);
        mainLp.rightMargin  = dp(act, 14) + dp(act, 14);
        main-hostMargin    = dp(act, 52) + dp(act, 12);
        mainLp.bottomMargin = dp(act, 40) + dp(act, 12);
        root.addView(mainRow, mainLp);

        // ---- 左列：Logo + 署名区 ----
        LinearLayout leftCol = new LinearLayout(act);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setPadding(dp(act, 4), 0, dp(act, 12), 0);
        mainRow.addView(leftCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.38f));

        ImageView logoView = new ImageView(act);
        logoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        loadBitmapFromAssets(act, LOGO_ASSET, logoView);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 64));
        logoLp.bottomMargin = dp(act, 8);
        leftCol.addView(logoView, logoLp);

        View divider = new View(act);
        divider.setBackgroundColor(COLOR_CARD_STK);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1));
        divLp.bottomMargin = dp(act, 8);
        leftCol.addView(divider, divLp);

        ScrollView contribScroll = new ScrollView(act);
        contribScroll.setFillViewport(true);
        LinearLayout contribList = new LinearLayout(act);
        contribList.setOrientation(LinearLayout.VERTICAL);
        contribScroll.addView(contribList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        vContribList = contribList;
        leftCol.addView(contribScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        populateContributors(act);

        // ---- 右列：阶段 / 槽位 / 总进度 ----
        LinearLayout rightCol = new LinearLayout(act);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setPadding(dp(act, 10), dp(act, 4), dp(act, 4), dp(act, 4));
        mainRow.addView(rightCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.62f));

        LinearLayout headRow = new LinearLayout(act);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        rightCol.addView(headRow, lpRow(0, dp(act, 4)));

        vPhase = new TextView(act);
        vPhase.setText(phaseText);
        vPhase.setTextColor(COLOR_ACCENT);
        vPhase.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        vPhase.setTypeface(vPhase.getTypeface(), Typeface.BOLD);
        vPhase.setSingleLine(true);
        vPhase.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headRow.addView(vPhase, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        vAggregate = new TextView(act);
        vAggregate.setText("");
        vAggregate.setTextColor(COLOR_SUB);
        vAggregate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        vAggregate.setGravity(Gravity.END);
        headRow.addView(vAggregate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        vStatus = new TextView(act);
        vStatus.setText(detailText);
        vStatus.setTextColor(COLOR_TEXT);
        vStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        vStatus.setSingleLine(true);
        vStatus.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        rightCol.addView(vStatus, lpRow(0, dp(act, 6)));

        ScrollView slotScroll = new ScrollView(act);
        slotContainer = new LinearLayout(act);
        slotContainer.setOrientation(LinearLayout.VERTICAL);
        slotScroll.addView(slotContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        rightCol.addView(slotScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rebuildSlots(act);

        LinearLayout totalRow = new LinearLayout(act);
        totalRow.setOrientation(LinearLayout.HORIZONTAL);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);
        rightCol.addView(totalRow, lpRow(dp(act, 8), dp(act, 2)));

        vOverallText = new TextView(act);
        vOverallText.setText("总进度");
        vOverallText.setTextColor(COLOR_TEXT);
        vOverallText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        totalRow.addView(vOverallText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvSpeed = new TextView(act);
        tvSpeed.setText("");
        tvSpeed.setTextColor(COLOR_SUB);
        tvSpeed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvSpeed.setGravity(Gravity.END);
        totalRow.addView(tvSpeed, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        progressBarOverall = new ProgressBar(
                act, null, android.R.attr.progressBarStyleHorizontal);
        progressBarOverall.setMax(100);
        progressBarOverall.setProgress(0);
        tintBar(progressBarOverall, COLOR_ACCENT);
        rightCol.addView(progressBarOverall, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 10)));

        // ── 第 3 层：左上角 LOG 胶囊 ──
        logPillBg = new GradientDrawable();
        logPillBg.setColor(COLOR_LOG_PILL);
        logPillBg.setCornerRadius(dp(act, 20));
        vLogPill = new TextView(act);
        vLogPill.setText("LOG");
        vLogPill.setTextColor(0xFFFFFFFF);
        vLogPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        vLogPill.setTypeface(vLogPill.getTypeface(), Typeface.BOLD);
        vLogPill.setGravity(Gravity.CENTER);
        vLogPill.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
        vLogPill.setBackground(logPillBg);
        vLogPill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openLogModal(); }
        });
        FrameLayout.LayoutParams logPillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        logPillLp.gravity    = Gravity.TOP | Gravity.START;
        logPillLp.topMargin  = dp(act, 10);
        logPillLp.leftMargin = dp(act, 14);
        root.addView(vLogPill, logPillLp);

        // ── 第 3 层：右上角主题切换胶囊 ──
        themeChipBg = new GradientDrawable();
        themeChipBg.setCornerRadius(dp(act, 20));
        themeChipBg.setColor(darkMode ? 0xCCFFE4A0 : COLOR_ACCENT2);
        vThemeChip = new TextView(act);
        vThemeChip.setText(darkMode ? "☀  亮色" : "☾  夜间");
        vThemeChip.setTextColor(darkMode ? 0xFF2A1A3B : 0xFFFFFFFF);
        vThemeChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        vThemeChip.setTypeface(vThemeChip.getTypeface(), Typeface.BOLD);
        vThemeChip.setGravity(Gravity.CENTER);
        vThemeChip.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
        vThemeChip.setBackground(themeChipBg);
        vThemeChip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleTheme(act); }
        });
        // GitHub 胶囊：与主题切换并排放在右上角。同样走两段式确认。
        githubChipBg = new GradientDrawable();
        githubChipBg.setCornerRadius(dp(act, 20));
        githubChipBg.setColor(COLOR_ACCENT2);
        vGitHubChip = new TextView(act);
        vGitHubChip.setText("</>  GitHub");
        vGitHubChip.setTextColor(0xFFFFFFFF);
        vGitHubChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        vGitHubChip.setTypeface(vGitHubChip.getTypeface(), Typeface.BOLD);
        vGitHubChip.setGravity(Gravity.CENTER);
        vGitHubChip.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
        vGitHubChip.setBackground(githubChipBg);
        vGitHubChip.setOnClickListener(new CreditLinkClick(act, URL_GITHUB));

        LinearLayout headRight = new LinearLayout(act);
        headRight.setOrientation(LinearLayout.HORIZONTAL);
        headRight.setGravity(Gravity.CENTER_VERTICAL);
        headRight.addView(vThemeChip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams ghLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ghLp.leftMargin = dp(act, 8);
        headRight.addView(vGitHubChip, ghLp);

        FrameLayout.LayoutParams themeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        themeLp.gravity     = Gravity.TOP | Gravity.END;
        theme-hostMargin   = dp(act, 10);
        themeLp.rightMargin = dp(act, 14);
        root.addView(headRight, themeLp);

        // ── 第 4 层：底部常驻署名条 ──
        TextView footer = new TextView(act);
        footer.setText(FOOTER_CREDIT);
        footer.setTextColor(COLOR_SUB);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        footer.setSingleLine(true);
        footer.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        footer.setMarqueeRepeatLimit(-1);
        footer.setSelected(true);
        footer.setHorizontallyScrolling(true);
        footer.setPadding(dp(act, 16), 0, dp(act, 16), dp(act, 8));
        FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.gravity = Gravity.BOTTOM | Gravity.START;
        root.addView(footer, footerLp);

        // ── 第 5 层：日志模态面板（默认隐藏） ──
        logModal = new FrameLayout(act);
        logModal.setBackgroundColor(COLOR_DIM);
        logModal.setVisibility(View.GONE);
        logModal.setClickable(true);
        logModal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeLogModal(); }
        });
        root.addView(logModal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) { return true; }
        });
        panel.setPadding(dp(act, 16), dp(act, 16), dp(act, 16), dp(act, 16));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(COLOR_LOG_PANEL_BG);
        panelBg.setCornerRadius(dp(act, 16));
        panelBg.setStroke(dp(act, 1), COLOR_CARD_STK);
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.leftMargin   = dp(act, 20);
        panelLp.rightMargin  = dp(act, 20);
        panel-hostMargin    = dp(act, 20);
        panelLp.bottomMargin = dp(act, 20);
        logModal.addView(panel, panelLp);

        LinearLayout logHead = new LinearLayout(act);
        logHead.setOrientation(LinearLayout.HORIZONTAL);
        logHead.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(logHead, lpRow(0, dp(act, 8)));

        TextView logTitle = new TextView(act);
        logTitle.setText("安装日志");
        logTitle.setTextColor(COLOR_ACCENT);
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        logHead.addView(logTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = new TextView(act);
        copyBtn.setText("复制全部");
        copyBtn.setTextColor(0xFFFFFFFF);
        copyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        copyBtn.setGravity(Gravity.CENTER);
        copyBtn.setPadding(dp(act, 14), dp(act, 6), dp(act, 14), dp(act, 6));
        GradientDrawable copyBg = new GradientDrawable();
        copyBg.setColor(COLOR_ACCENT2);
        copyBg.setCornerRadius(dp(act, 8));
        copyBtn.setBackground(copyBg);
        copyBtn.setOnClickListener(new CopyLogClick(act));
        logHead.addView(copyBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView closeBtn = new TextView(act);
        closeBtn.setText("关闭");
        closeBtn.setTextColor(0xFFFFFFFF);
        closeBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(dp(act, 16), dp(act, 6), dp(act, 16), dp(act, 6));
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setColor(COLOR_ACCENT);
        closeBg.setCornerRadius(dp(act, 8));
        closeBtn.setBackground(closeBg);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeLogModal(); }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = dp(act, 8);
        logHead.addView(closeBtn, closeLp);

        vLogScroll = new ScrollView(act);
        vLogScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        GradientDrawable logScrollBg = new GradientDrawable();
        logScrollBg.setColor(darkMode ? 0x44FFFFFF : 0x14000000);
        logScrollBg.setCornerRadius(dp(act, 8));
        logScrollBg.setStroke(1, darkMode ? 0x33FFFFFF : 0x22000000);
        vLogScroll.setBackground(logScrollBg);
        vLogScroll.setPadding(dp(act, 8), dp(act, 6), dp(act, 8), dp(act, 6));
        panel.addView(vLogScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 原始安装日志文本（等宽），即改版前的主体文本视图
        tvLog = new TextView(act);
        tvLog.setText("=== MagiaCN Installer ===\n(waiting...)");
        tvLog.setTextColor(COLOR_LOG_PANEL_TEXT);
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextIsSelectable(true);
        vLogScroll.addView(tvLog, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    /**
     * 外链条目的两段式点击处理。
     *
     * <p>第一下：记下待确认地址并弹 Toast；第二下（同一条、且在
     * {@link #CONFIRM_WINDOW_MS} 之内）：调起系统浏览器。点到别的条目会重新
     * 从第一下开始，超时同理。
     */
    private static final class CreditLinkClick implements View.OnClickListener {
        private final Activity act;
        private final String   url;
        CreditLinkClick(Activity act, String url) { this.act = act; this.url = url; }

        @Override public void onClick(View v) {
            long now = System.currentTimeMillis();
            boolean armed = url.equals(pendingUrl)
                    && (now - pendingAtMs) <= CONFIRM_WINDOW_MS;
            if (!armed) {
                pendingUrl  = url;
                pendingAtMs = now;
                CNLog.i("界面", "外链待确认: " + url);
                toast(act, "即将离开游戏打开：" + url + "\n再点一次继续");
                return;
            }
            pendingUrl  = null;
            pendingAtMs = 0L;
            CNLog.i("界面", "外链已确认，调起系统浏览器: " + url);
            try {
                Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(it);
            } catch (Throwable t) {
                CNLog.w("界面", "打开外链失败: " + url, t);
                toast(act, "无法打开链接：" + url);
            }
        }
    }

    /**
     * 把 {@code text} 里的 {@code span} 这一段染成链接色，其余不变。
     * {@code span} 为空或找不到时原样返回。
     */
    private static CharSequence highlight(String text, String span) {
        if (text == null) return "";
        if (span == null || span.length() == 0) return text;
        int at = text.indexOf(span);
        if (at < 0) return text;
        android.text.SpannableString ss = new android.text.SpannableString(text);
        ss.setSpan(new android.text.style.ForegroundColorSpan(COLOR_LINK),
                   at, at + span.length(),
                   android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** 「重试」按钮：把该文件交还给安装器重新下载。 */
    private static final class RetryClick implements View.OnClickListener {
        private final Activity act;
        private final int      index;
        RetryClick(Activity act, int index) { this.act = act; this.index = index; }
        @Override public void onClick(View v) {
            try {
                v.setVisibility(View.GONE);
                CNLog.i("界面", "玩家点击重试: index=" + index);
                toast(act, "已加入重试队列");
                CNDownloaderFix.requestRetry(index);
            } catch (Throwable t) {
                CNLog.e("界面", "重试请求失败: " + t, t);
            }
        }
    }

    private static void toast(Activity act, String msg) {
        try {
            Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) {}
    }

    /** 用固定署名数据填充左列署名区。 */
    private static void populateContributors(Activity act) {
        if (vContribList == null) return;
        vContribList.removeAllViews();
        int itemIndex = 0;
        for (int i = 0; i < CREDIT_TEXTS.length; i++) {
            int kind = CREDIT_KINDS[i];
            if (kind == KIND_ITEM) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                row-hostMargin = dp(act, 3);
                vContribList.addView(row, rowLp);

                DotView dot = new DotView(act,
                        CONTRIB_PALETTE[itemIndex % CONTRIB_PALETTE.length]);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                        dp(act, 7), dp(act, 7));
                dotLp.rightMargin = dp(act, 7);
                row.addView(dot, dotLp);
                itemIndex++;

                String url  = i < CREDIT_URLS.length ? CREDIT_URLS[i] : "";
                String span = i < CREDIT_LINK_SPANS.length ? CREDIT_LINK_SPANS[i] : "";
                TextView t = new TextView(act);
                t.setTextColor(COLOR_TEXT);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                t.setText(highlight(CREDIT_TEXTS[i], span));
                if (url.length() > 0) {
                    row.setPadding(0, dp(act, 3), 0, dp(act, 3));
                    row.setClickable(true);
                    row.setOnClickListener(new CreditLinkClick(act, url));
                }
                row.addView(t, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                TextView t = new TextView(act);
                t.setText(CREDIT_TEXTS[i]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (kind == KIND_TITLE) {
                    t.setTextColor(COLOR_ACCENT);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                    t.setTypeface(t.getTypeface(), Typeface.BOLD);
                    lp.bottomMargin = dp(act, 4);
                } else if (kind == KIND_HEAD) {
                    t.setTextColor(COLOR_ACCENT2);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                    t.setTypeface(t.getTypeface(), Typeface.BOLD);
                    lp.topMargin    = dp(act, 8);
                    lp.bottomMargin = dp(act, 2);
                } else {  // KIND_SUB
                    t.setTextColor(COLOR_SUB);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
                    lp.leftMargin = dp(act, 14);
                }
                vContribList.addView(t, lp);
            }
        }
    }

    /** 为 15 个文件各建一个进度槽位。 */
    private static void rebuildSlots(Activity act) {
        if (slotContainer == null) return;
        slotContainer.removeAllViews();
        slotList.clear();
        for (int i = 0; i < FILE_COUNT; i++) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            slotContainer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout headRow = new LinearLayout(act);
            headRow.setOrientation(LinearLayout.HORIZONTAL);
            headRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hrLp.topMargin = dp(act, 5);
            row.addView(headRow, hrLp);

            TextView name = new TextView(act);
            name.setText((i + 1) + ". " + FILE_NAMES[i]);
            name.setTextColor(COLOR_TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            headRow.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView info = new TextView(act);
            info.setText("");
            info.setTextColor(COLOR_SUB);
            info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            info.setGravity(Gravity.END);
            headRow.addView(info, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            // 「重试」按钮：仅在该文件失败（status==3）时可见
            TextView retry = new TextView(act);
            retry.setText("重试");
            retry.setTextColor(0xFFFFFFFF);
            retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            retry.setGravity(Gravity.CENTER);
            retry.setPadding(dp(act, 10), dp(act, 3), dp(act, 10), dp(act, 3));
            GradientDrawable retryBg = new GradientDrawable();
            retryBg.setColor(0xFFE53935);
            retryBg.setCornerRadius(dp(act, 10));
            retry.setBackground(retryBg);
            retry.setVisibility(View.GONE);
            retry.setOnClickListener(new RetryClick(act, i));
            LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            retryLp.leftMargin = dp(act, 8);
            headRow.addView(retry, retryLp);

            ProgressBar bar = new ProgressBar(
                    act, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(0);
            tintBar(bar, 0x55888888);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 6));
            barLp.topMargin = dp(act, 2);
            row.addView(bar, barLp);

            View div = new View(act);
            div.setBackgroundColor(darkMode ? 0x22FFFFFF : 0x18000000);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            divLp.topMargin = dp(act, 4);
            row.addView(div, divLp);

            slotList.add(new SlotViews(name, info, retry, bar, div));
        }
    }

    private static void tintBar(ProgressBar pb, int color) {
        if (Build.VERSION.SDK_INT >= 21) {
            pb.setProgressTintList(
                    android.content.res.ColorStateList.valueOf(color));
            pb.setProgressBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(COLOR_BAR_BG));
        }
    }

    // ==================================================================
    // 交互：日志面板 / 主题切换
    // ==================================================================

    /** 「复制全部」：把面板里看到的内容原样送进剪贴板。 */
    private static final class CopyLogClick implements View.OnClickListener {
        private final Activity act;
        CopyLogClick(Activity act) { this.act = act; }
        @Override public void onClick(View v) {
            try {
                ClipboardManager cm = (ClipboardManager)
                        act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null) return;
                cm.setPrimaryClip(ClipData.newPlainText("log-service", composeLogText()));
                toast(act, "日志已复制到剪贴板（" + CNLog.size() + " 条）");
            } catch (Throwable t) {
                toast(act, "复制失败：" + t.getMessage());
            }
        }
    }

    /** LOG 面板的完整内容：文件安装状态 + 运行日志。 */
    private static String composeLogText() {
        StringBuilder sb = new StringBuilder();
        sb.append(buildStatusText());
        sb.append("\n──────── 运行日志 ────────\n");
        String log = CNLog.snapshot();
        sb.append(log.length() == 0 ? "（暂无日志）\n" : log);
        return sb.toString();
    }

    /** 把最新内容刷进面板；仅在面板可见时做，避免无谓的字符串拼接。 */
    private static void renderLogModal() {
        if (logModal == null || tvLog == null) return;
        if (logModal.getVisibility() != View.VISIBLE) return;
        tvLog.setText(composeLogText());
    }

    private static void openLogModal() {
        if (logModal == null) return;
        logModal.setVisibility(View.VISIBLE);
        renderLogModal();
        if (vLogScroll != null) {
            vLogScroll.post(new ScrollToBottom());
        }
    }

    private static void closeLogModal() {
        if (logModal != null) logModal.setVisibility(View.GONE);
    }

    /**
     * {@link CNLog} 的缓冲区变更回调。日志可能来自任意下载线程，所以要切回主线程
     * 再碰视图；面板不可见时直接跳过。
     */
    private static final class LogChanged implements Runnable {
        @Override public void run() {
            Handler h = uiHandler;
            if (h == null || logModal == null) return;
            if (logModal.getVisibility() != View.VISIBLE) return;
            h.post(new RenderLog());
        }
    }

    private static final class RenderLog implements Runnable {
        @Override public void run() {
            boolean atBottom = false;
            if (vLogScroll != null && tvLog != null) {
                int bottom = vLogScroll.getScrollY() + vLogScroll.getHeight();
                atBottom = bottom >= tvLog.getHeight() - dpStatic(24);
            }
            renderLogModal();
            if (atBottom && vLogScroll != null) {
                vLogScroll.post(new ScrollToBottom());
            }
        }
    }

    /** 把日志滚动区滚到底部。 */
    private static final class ScrollToBottom implements Runnable {
        @Override public void run() {
            if (vLogScroll != null) vLogScroll.fullScroll(View.FOCUS_DOWN);
        }
    }

    /** 没有 Context 时的粗略 dp 换算（只用于滚动位置判定，精度无所谓）。 */
    private static int dpStatic(int v) {
        return (int) (v * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density + 0.5f);
    }

    /**
     * 确保浮层仍然挂在 decorView 上；掉了就重新挂。
     *
     * <p>为什么需要：引擎在切场景时可能把 decorView 的内容整体换掉，我们的浮层
     * 就此脱离视图树——屏幕上随即露出引擎自带的下载场景，也就是必须避免的
     * 「原生安装界面」。安装期间由看门狗每秒调一次，发现脱离就立刻补回去。
     *
     * <p>可从任意线程调用；内部会切到主线程执行。
     */
    public static void ensureVisible(final Activity act) {
        if (act == null) return;
        try {
            act.runOnUiThread(new EnsureVisible(act));
        } catch (Throwable ignore) {}
    }

    private static final class EnsureVisible implements Runnable {
        private final Activity act;
        EnsureVisible(Activity act) { this.act = act; }
        @Override public void run() {
            try {
                FrameLayout ov = overlayView;
                if (ov != null && ov.getParent() != null) return;   // 还在，无需处理

                ViewGroup dv = (ViewGroup) act.getWindow().getDecorView();
                if (ov != null) {
                    // 仅仅是脱离了父节点：直接挂回去，保留现有状态
                    try { dv.addView(ov, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT)); } catch (Throwable ignore) {}
                    decorView = dv;
                    CNLog.w("界面", "浮层曾脱离视图树，已重新挂上");
                    return;
                }
                // 整个浮层都没了（或从未建成）：重建一份
                if (hostActivity == null) hostActivity = act;
                loadPalette(darkMode);
                FrameLayout fresh = buildOverlay(act);
                dv.addView(fresh, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                decorView   = dv;
                overlayView = fresh;
                isShowing   = true;
                renderAll();
                CNLog.w("界面", "浮层缺失，已重建并挂上");
            } catch (Throwable t) {
                CNLog.e("界面", "重挂浮层失败: " + t, t);
            }
        }
    }

    /** 切换亮色/夜间主题：保存偏好后原地重建浮层视图树。 */
    private static void toggleTheme(Activity act) {
        try {
            darkMode = !darkMode;
            SharedPreferences sp = act.getSharedPreferences(
                    PREFS_NAME, Context.MODE_PRIVATE);
            sp.edit().putBoolean(PREF_DARK_MODE, darkMode).apply();
            loadPalette(darkMode);

            ViewGroup dv = decorView;
            FrameLayout old = overlayView;
            if (dv == null) return;
            FrameLayout fresh = buildOverlay(act);
            if (old != null) dv.removeView(old);
            dv.addView(fresh, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            overlayView = fresh;
            // 立即把当前进度重新渲染到新视图上
            renderAll();
        } catch (Throwable t) {
            CNLog.e("界面", "主题切换失败: " + t);
        }
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    private static String formatMb(float mb) {
        if (mb <= 0f) return "0 MB";
        if (mb < 1024f) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024f);
    }

    private static String formatMbps(float mbps) {
        if (mbps <= 0f) return "";
        return String.format(Locale.US, "%.2f MB/s", mbps);
    }

    /**
     * 把当前的数组状态整体画到视图上。
     *
     * <p>数据来源与改版前完全相同（fileStatus / fileProgress / fileSize /
     * fileSpeed / fileDownloaded），只是渲染成槽位样式。
     */
    private static void renderAll() {
        // 日志面板内容（安装状态 + 运行日志）。
        // 这里**必须**走 renderLogModal()：早先直接 setText(buildStatusText())
        // 会把刚拼进去的日志段整段抹掉，而 renderAll 每 500ms 就跑一次——
        // 表现就是日志行刚打印出来就转瞬即逝。
        renderLogModal();

        int[]   status     = fileStatus;
        int[]   progress   = fileProgress;
        float[] size       = fileSize;
        float[] speed      = fileSpeed;
        float[] downloaded = fileDownloaded;

        // ── 总进度 ──
        // 优先按**体积加权**：已下字节数 / 总字节数。
        // 改版前用的是「15 个文件百分比的算术平均」，那等于把 2MB 的小包和
        // 1GB 的大包算作同等分量，进度条会随着小包秒完而猛冲、再被大包拖住，
        // 观感就是来回跳。安装器开跑前已经把所有文件的大小探完（probeAllSizes），
        // 所以这里的分母是定值。
        //
        // 万一尺寸探测整体失败（分母为 0），退回原来的算术平均，保证有进度可看。
        {
            float totalSize = 0f, totalDone = 0f;
            if (size != null && downloaded != null && status != null) {
                for (int i = 0; i < FILE_COUNT; i++) {
                    if (size[i] <= 0f) continue;
                    totalSize += size[i];
                    // 已完成的文件按整包计入，避免它的 downloaded 被清零后
                    // 总进度倒退
                    totalDone += (status[i] == 2) ? size[i] : Math.min(downloaded[i], size[i]);
                }
            }
            int overall;
            if (totalSize > 0f) {
                overall = (int) Math.min(100L, Math.max(0L, (long) (totalDone * 100f / totalSize)));
            } else if (progress != null) {
                int sum = 0;
                for (int i = 0; i < FILE_COUNT; i++) sum += progress[i];
                overall = sum / FILE_COUNT;
            } else {
                overall = 0;
            }
            ProgressBar pb = progressBarOverall;
            if (pb != null) pb.setProgress(overall);
        }

        // 总速度：与改版前一致 —— 仅累加处于「下载中」状态的文件速度
        float totalSpeed = 0f;
        if (speed != null && status != null) {
            for (int i = 0; i < FILE_COUNT; i++) {
                if (status[i] == 1) totalSpeed += speed[i];
            }
        }
        TextView sp = tvSpeed;
        if (sp != null) sp.setText(formatMbps(totalSpeed));

        // 阶段 / 明细
        if (vPhase  != null) vPhase.setText(phaseText);
        if (vStatus != null) vStatus.setText(detailText);

        // 槽位
        if (!slotList.isEmpty() && status != null && progress != null) {
            for (int i = 0; i < FILE_COUNT && i < slotList.size(); i++) {
                SlotViews sv = slotList.get(i);
                int st  = status[i];
                int pct = progress[i];
                sv.bar.setProgress(pct);

                int color;
                switch (st) {
                    case 1:  color = COLOR_ACCENT; break;   // 下载中
                    case 2:  color = 0xFF66BB6A;   break;   // 完成（绿）
                    case 3:  color = 0xFFE53935;   break;   // 失败（红）
                    default: color = 0x55888888;   break;   // 等待（灰）
                }
                if (Build.VERSION.SDK_INT >= 21) {
                    sv.bar.setProgressTintList(
                            android.content.res.ColorStateList.valueOf(color));
                }

                sv.retryView.setVisibility(st == 3 ? View.VISIBLE : View.GONE);
                if (st == 2) {
                    sv.infoView.setTextColor(0xFF66BB6A);
                    sv.infoView.setText("✓");
                } else if (st == 3) {
                    sv.infoView.setTextColor(0xFFE53935);
                    sv.infoView.setText("✗");
                } else if (st == 1) {
                    sv.infoView.setTextColor(COLOR_SUB);
                    StringBuilder sb = new StringBuilder();
                    sb.append(pct).append('%');
                    if (downloaded != null && size != null && size[i] > 0f) {
                        sb.append("  ").append(formatMb(downloaded[i]))
                          .append(" / ").append(formatMb(size[i]));
                    }
                    if (speed != null && speed[i] > 0f) {
                        sb.append("  ").append(formatMbps(speed[i]));
                    }
                    sv.infoView.setText(sb.toString());
                } else {
                    sv.infoView.setTextColor(COLOR_SUB);
                    sv.infoView.setText("");
                }
            }
        }

        // 汇总：已完成文件数 + 总体积
        if (vAggregate != null && status != null) {
            int done = 0;
            for (int i = 0; i < FILE_COUNT; i++) if (status[i] == 2) done++;
            vAggregate.setText(done + " / " + FILE_COUNT + " 文件");
        }
        if (vOverallText != null) {
            String text = "总进度";
            if (size != null && downloaded != null && status != null) {
                float totalSize = 0f, totalDone = 0f;
                for (int i = 0; i < FILE_COUNT; i++) {
                    if (size[i] <= 0f) continue;
                    totalSize += size[i];
                    totalDone += (status[i] == 2) ? size[i] : Math.min(downloaded[i], size[i]);
                }
                if (totalSize > 0f) {
                    text += "  " + formatMb(totalDone) + " / " + formatMb(totalSize);
                }
            }
            vOverallText.setText(text);
        }
    }

    // ==================================================================
    // Runnable：创建 / 隐藏 / 更新
    // ==================================================================

    public static class CreateUIRunnable implements Runnable {
        private final Activity context;

        public CreateUIRunnable(Activity activity) {
            this.context = activity;
        }

        @Override
        public void run() {
            try {
                Activity activity = this.context;
                if (activity == null) return;

                hostActivity = activity;
                // 日志落盘目录用应用私有目录；此前安装器已经写入的内容仍在内存
                // 缓冲里，会随第一次刷新一起显示出来
                try {
                    CNLog.init(activity.getFilesDir());
                } catch (Throwable ignore) {}
                CNLog.setListener(new LogChanged());
                // 把整机 logcat 并进面板：native hook（MagiaClientJNI）、引擎、
                // 以及任何 Java 异常栈都能在设备上直接看到，不必接电脑
                CNLog.startLogcatCapture();

                try {
                    darkMode = activity
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .getBoolean(PREF_DARK_MODE, false);
                } catch (Throwable ignore) {
                    darkMode = false;
                }
                loadPalette(darkMode);
                CNLog.i("界面", "下载浮层已创建，主题=" + (darkMode ? "夜间" : "亮色"));

                CNCNDownloadUI.decorView =
                        (ViewGroup) activity.getWindow().getDecorView();
                FrameLayout root = buildOverlay(activity);
                CNCNDownloadUI.decorView.addView(root,
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                CNCNDownloadUI.overlayView = root;
                renderAll();
            } catch (Throwable e) {
                // 捕获 Throwable 而非 Exception：构建视图时的 Error（如 OOM）
                // 若逃逸出去，会沿 JNI 冒泡回 native hook，导致引擎放行原生
                // 下载界面。
                CNLog.e("界面", "浮层创建失败: " + e, e);
            }
        }
    }

    public static class HideRunnable implements Runnable {
        @Override
        public void run() {
            try {
                CNLog.i("界面", "下载浮层关闭");
                // 先摘掉监听再拆视图，避免拆到一半又被日志回调碰上
                CNLog.setListener(null);
                CNLog.stopLogcatCapture();
                CNLog.close();
                ViewGroup dv = CNCNDownloadUI.decorView;
                FrameLayout ov = CNCNDownloadUI.overlayView;
                if (dv == null || ov == null) return;
                dv.removeView(ov);
                CNCNDownloadUI.overlayView         = null;
                CNCNDownloadUI.tvLog               = null;
                CNCNDownloadUI.progressBarOverall  = null;
                CNCNDownloadUI.tvSpeed             = null;
                CNCNDownloadUI.decorView           = null;
                CNCNDownloadUI.uiHandler           = null;
                // 改版新增的视图引用一并释放，避免持有已销毁的 Activity
                vPhase        = null;
                vStatus       = null;
                vAggregate    = null;
                vOverallText  = null;
                slotContainer = null;
                vContribList  = null;
                vThemeChip    = null;
                vLogPill      = null;
                logModal      = null;
                vLogScroll    = null;
                themeChipBg   = null;
                logPillBg     = null;
                hostActivity  = null;
                slotList.clear();
            } catch (Throwable e) {
            }
        }
    }

    public static class UpdateRunnable implements Runnable {
        @Override
        public void run() {
            try {
                renderAll();
            } catch (Throwable e) {
            }
        }
    }

    // ==================================================================
    // 对外方法：签名与语义均与改版前一致
    // ==================================================================

    /** 生成原始文本形式的安装状态（LOG 面板内容）。逻辑与改版前完全一致。 */
    public static String buildStatusText() {
        String[] names      = FILE_NAMES;
        int[]    status     = fileStatus;
        int[]    progress   = fileProgress;
        float[]  size       = fileSize;
        float[]  speed      = fileSpeed;
        float[]  downloaded = fileDownloaded;
        if (names == null || status == null || progress == null) {
            return "=== MagiaCN Installer ===\n(initializing...)";
        }
        StringBuilder sb = new StringBuilder("=== MagiaCN Installer ===\n");
        for (int i = 0; i < FILE_COUNT; i++) {
            int st = status[i];
            sb.append(st == 2 ? "[OK] " : st == 1 ? "[ > ] " : st == 3 ? "[ERR] " : "[  ] ")
              .append(i + 1).append(".").append(names[i]);
            if (st == 1) {
                sb.append("  ").append(progress[i]).append("%");
                if (downloaded != null && size != null) {
                    String d = Float.toString(downloaded[i]);
                    if (d.length() > 6) d = d.substring(0, 6);
                    sb.append("  ").append(d).append("/");
                    String s = Float.toString(size[i]);
                    if (s.length() > 6) s = s.substring(0, 6);
                    sb.append(s).append("MB");
                }
                if (speed != null) {
                    String v = Float.toString(speed[i]);
                    if (v.length() > 4) v = v.substring(0, 4);
                    sb.append("  ").append(v).append("MB/s");
                }
                if (status[i] != 0) {
                    int pct = progress[i];
                    sb.append("\n  [");
                    for (int k = 0; k < 10; k++) {
                        sb.append(k * 10 < pct ? "█" : "░");
                    }
                    sb.append("]");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public static void hide() {
        Handler handler;
        if (!isShowing || (handler = uiHandler) == null) {
            return;
        }
        handler.post(new HideRunnable());
        isShowing = false;
    }

    public static void markFileDone(int i) {
        int[] status = fileStatus;
        if (status != null) {
            status[i] = 2;
            int[] progress = fileProgress;
            if (progress != null) {
                progress[i] = 100;
            }
        }
        float[] speed = fileSpeed;
        if (speed != null) {
            speed[i] = 0;
        }
        Handler handler = uiHandler;
        if (handler != null) {
            handler.post(new UpdateRunnable());
        }
    }

    public static void setDownloadSpeed(int i, float f) {
        float[] speed = fileSpeed;
        if (speed != null) {
            speed[i] = f;
        }
    }

    public static void setFileDownloaded(int i, float f) {
        float[] downloaded = fileDownloaded;
        if (downloaded != null) {
            downloaded[i] = f;
        }
    }

    public static void setFileSize(int i, float f) {
        float[] size = fileSize;
        if (size != null) {
            size[i] = f;
        }
    }

    public static void show(Activity activity) {
        if (isShowing) {
            return;
        }
        try {
            uiHandler = new Handler(Looper.getMainLooper());
            activity.runOnUiThread(new CreateUIRunnable(activity));
            int i = 0;
            while (tvLog == null && i < 30) {
                i++;
                try {
                    Thread.sleep(100L);
                } catch (InterruptedException unused) {
                }
            }
            // 只有浮层**确实挂上了 decorView** 才算显示成功。
            // 早先无条件置 true 是个坑：一旦创建失败，后续每次 show() 都会在
            // 开头的 isShowing 判断处直接返回，本进程内再也没机会把浮层建起来，
            // 屏幕上就只剩引擎自己的画面了。
            isShowing = (overlayView != null);
            if (!isShowing) {
                CNLog.e("界面", "浮层创建失败（overlayView 为空），将允许后续重试");
            }
        } catch (Throwable e) {
            isShowing = (overlayView != null);
            CNLog.e("界面", "show() 失败: " + e, e);
        }
    }

    public static void throttledUpdate() {
        Handler handler = uiHandler;
        if (handler == null || System.currentTimeMillis() - lastUpdateTime < 500) {
            return;
        }
        lastUpdateTime = System.currentTimeMillis();
        handler.post(new UpdateRunnable());
    }

    public static void updateFileProgress(int i, int i2) {
        int[] progress = fileProgress;
        if (progress != null) {
            progress[i] = i2;
            int[] status = fileStatus;
            if (status != null && status[i] != 2) {
                fileStatus[i] = 1;
            }
        }
        throttledUpdate();
    }

    /**
     * 阶段 / 明细文本更新。
     *
     * <p>改版前这两个参数被直接丢弃；现在把它们渲染到右列顶部的阶段行与状态行，
     * 调用点与调用时机不变。
     */
    public static void updateSimple(String str, String str2, int i) {
        if (str != null && str.length() > 0)   phaseText  = str;
        if (str2 != null && str2.length() > 0) detailText = str2;
        Handler handler = uiHandler;
        if (handler != null) {
            handler.post(new UpdateRunnable());
        }
    }
}
