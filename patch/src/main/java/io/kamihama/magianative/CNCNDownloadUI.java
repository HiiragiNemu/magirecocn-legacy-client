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
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

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

    private static final String TAG = "CNCNDownloadUI";

    // ==================================================================
    // 对外契约：以下 public static 成员的名字与签名不可改动
    // ==================================================================

    public static ViewGroup decorView;
    /** volatile：show()/hide() 与心跳线程跨线程读写，必须立即可见。 */
    public static volatile boolean isShowing;
    public static long lastUpdateTime;
    public static FrameLayout overlayView;
    public static ProgressBar progressBarOverall;
    /** 原始安装日志文本视图；现位于 LOG 模态面板内。show() 用它作为建好的哨兵。 */
    public static TextView tvLog;
    /** 总速度标签；现位于总进度条右侧。 */
    public static TextView tvSpeed;
    public static Handler uiHandler;

    // 顺序与 CNDownloaderFix.FILE_NAMES 逐项对齐（三张表按下标并行）。
    // 热更两包排在全部 cn_base_* 之后，理由见 CNDownloaderFix.FILE_NAMES 的注释。
    public static final String[] FILE_NAMES = {
        "cn_base_00_db.zip", "cn_base_01_json.zip", "cn_base_02.zip",
        "cn_base_03.zip", "cn_base_04.zip", "cn_base_05.zip",
        "cn_base_06.zip",
        "cn_scenario_update.zip", "cn_js_update.zip",
        "cn_magica_resource.zip", "cn_scenario_img.zip",
        "cn_voice_01.zip", "cn_voice_02_done.zip",
        "movie.zip", "movie2.zip"
    };

    /**
     * 每个包的**规范 URL**，即身份标识——不是下载地址。
     *
     * <p>安装完成标记里记的就是这一串，{@code isMarkerValid} 做逐字符串比对；
     * 域名废弃了也不能改，否则 15 个标记全部失效、老玩家重下几个 GB。实际
     * 从哪里取字节由 {@code CNMirrors} 的线路给出，与这里无关。
     *
     * <p>由 {@link #FILE_NAMES} 逐项拼出而不是各写一份：两张表必须按下标严格
     * 并行，分开写就有写歪一行的机会，而写歪的后果是那一个包的标记永远对不上。
     */
    public static final String[] FILE_URLS = buildFileUrls();

    private static String[] buildFileUrls() {
        String[] out = new String[FILE_NAMES.length];
        for (int i = 0; i < FILE_NAMES.length; i++) {
            out[i] = CNMirrors.CANONICAL_BASE + FILE_NAMES[i];
        }
        return out;
    }

    // 槽位状态。0/1/2/3 是原有的四个；4 是这次补的。
    //
    // 为什么要补第五个：热更那一轮**只检查两个包**（台词、前端脚本），另外 13 个
    // 基础包压根不在本轮范围里；而版本 json 拉不到时，连那两个也没验成。这些槽位
    // 原先一律沿用安装时留下的 marker 显示成「✓ 完成」——把「上次装好过」说成了
    // 「本轮已确认」。玩家看到满屏绿勾，实际上这一轮什么都没查。
    //
    // 这不是显示问题，是**谎报**：热更没生效时，界面反而最像一切正常。
    public static final int ST_WAIT      = 0;   // 等待中（灰）
    public static final int ST_RUNNING   = 1;   // 下载中
    public static final int ST_DONE      = 2;   // 本轮确认完成（绿 ✓）
    public static final int ST_ERROR     = 3;   // 失败（红 ✗）
    public static final int ST_UNCHECKED = 4;   // **本轮未检查**（中性色，不打勾）

    public static int[]   fileStatus     = new int[15];
    /** {@link #ST_UNCHECKED} 时显示的说明（如「已装」「版本查询失败」）。 */
    public static String[] fileNote      = new String[15];
    public static int[]   fileProgress   = new int[15];
    public static float[] fileSize       = new float[15];
    public static float[] fileSpeed      = new float[15];
    public static float[] fileDownloaded = new float[15];
    private static final Object PROGRESS_LOCK = new Object();
    private static int overallProgressHighWater = 0;

    // ==================================================================
    // 以下为改版新增的内部状态（无外部引用）
    // ==================================================================

    /** 文件数量。与原实现一致地固定为 15。 */
    private static final int FILE_COUNT = 15;

    /**
     * 浮层根视图的标记 tag。hide() 用它把 decorView 上**所有**本类浮层摘除，
     * CreateUIRunnable 用它做幂等守卫——修复 show() 重试/并发在弱机上叠出
     * 多个整屏浮层、hide() 只摘最上层导致残留浮层盖死游戏的问题。
     */
    private static final int TAG_OVERLAY = 0x4C454700;   // "LEG\0"

    /** 资源目录内的背景图路径。 */
    private static final String BG_ASSET   = "magia/background_light.png";
    /** 资源目录内的游戏 Logo 路径。 */
    private static final String LOGO_ASSET = "magia/logo.png";

    private static final String PREFS_NAME     = "cnv_bootstrap_ui";
    private static final String PREF_DARK_MODE = "dark_mode";
    /** LOG 面板三个显示开关的持久化键。 */
    private static final String PREF_LOG_STATUS = "log_show_status";
    private static final String PREF_LOG_LOGCAT = "log_show_logcat";
    private static final String PREF_LOG_NATIVE = "log_show_native";

    /** 面板是否显示「纯文字下载界面」（buildStatusText 那段文件清单）。 */
    private static boolean showStatusBlock = true;

    /** 承载浮层的宿主 Activity；主题切换时需要用它重建视图树。 */
    private static Activity hostActivity;

    /** 由 updateSimple() 写入、在 UpdateRunnable 中渲染的阶段标题与明细。 */
    private static volatile String phaseText  = "准备中";
    /**
     * 首屏文案。这一行在探测完文件大小之前会显示好几秒，正好用来交代
     * 「为什么台词/脚本这两个包排在最前面」——否则玩家看到下载顺序和上一版不同，
     * 只会觉得莫名其妙。
     */
    private static volatile String detailText =
            "正在初始化下载器…\n台词与前端脚本（热更新内容）已排在最前，先下完即可用上最新汉化";

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
    // LOG 面板的结构化渲染用色（来源徽章 / 时刻 / 级别），见 composeLogSpans
    private static int COLOR_LOG_BADGE_APP;
    private static int COLOR_LOG_BADGE_LOGCAT;
    private static int COLOR_LOG_BADGE_NATIVE;
    private static int COLOR_LOG_TIME;
    private static int COLOR_LOG_WARN;
    private static int COLOR_LOG_ERROR;
    private static int COLOR_LINK;      // 链接文字色（克制，不用强调粉）
    private static int COLOR_GLASS;
    private static int COLOR_GLASS_STK;
    private static boolean darkMode = false;

    /**
     * 内容区的基准宽度（px），<b>只由屏幕分辨率与固定边距算出</b>。
     *
     * <p>{@link CNDownloadUiAssist} 的字号缩放与左右分界线拖动都读这个值，
     * 而不是读任何 {@code getWidth()}。读测量宽度会形成反馈环：布局改了宽度、
     * 下一帧又按新宽度算出更大的宽度——真机上表现为「反复拖分界线，左右越变
     * 越长」（2026-08-13）。按分辨率算是确定的，拖多少次结果都一样。
     */
    static volatile int contentBaseWidthPx;

    /**
     * 调色板必须在类加载时就有值。
     *
     * <p>上面那一堆 {@code COLOR_*} 都没有初始值，即默认 {@code 0}——而 0 是
     * {@code #00000000}，<b>全透明</b>。原先只有 {@code buildOverlay} /
     * {@code show} / {@code toggleTheme} 才调 {@link #loadPalette}，于是只要下载
     * 浮层这一轮没被建出来（资源早装好、直接进游戏），这些字段就一直是 0。
     *
     * <p>调试悬浮窗是靠反射读这几个字段取色的（{@code CNDebugOverlay.color}），
     * 读到 0 就把文字画成全透明：面板上 useAria2 这些开关名、说明、「已激活」标签
     * 统统消失，只剩用硬编码白色画的主按钮还看得见——正是 2026-08-13 反馈的
     * 「文字疑似会消失」。而它<b>时有时无</b>，取决于这一次启动有没有建过下载浮层。
     *
     * <p>这里先按亮色兜一份底。真正的主题在 {@link #ensurePalette(Context)} 里按
     * 玩家的偏好再覆盖一次；即便那步也没跑到，至少画出来的是能看见的颜色。
     */
    static { loadPalette(false); }

    /**
     * 确保调色板已按玩家保存的主题加载过一次。供<b>不经过下载浮层</b>的调用方
     * （调试悬浮窗）在渲染前调用，这样它的配色跟着玩家选的主题走，而不是永远亮色。
     */
    static void ensurePalette(Context ctx) {
        if (paletteReady || ctx == null) return;
        try {
            darkMode = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(PREF_DARK_MODE, false);
        } catch (Throwable ignore) {}
        loadPalette(darkMode);
        paletteReady = true;
    }

    /** 见 {@link #ensurePalette(Context)}：只按玩家偏好加载一次。 */
    private static volatile boolean paletteReady;

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
            COLOR_LOG_BADGE_APP    = 0xFFB87FE0;  // 自家日志＝紫，与浮层同族
            COLOR_LOG_BADGE_LOGCAT = 0xFF6E7C99;  // 系统/框架＝灰蓝，刻意不抢眼
            COLOR_LOG_BADGE_NATIVE = 0xFF2FA69B;  // native/引擎＝青
            COLOR_LOG_TIME         = 0xFF8E7BA0;
            COLOR_LOG_WARN         = 0xFFF2B45A;
            COLOR_LOG_ERROR        = 0xFFFF6B6B;
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
            COLOR_LOG_BADGE_APP    = 0xFF9C5BC2;
            COLOR_LOG_BADGE_LOGCAT = 0xFF77839B;
            COLOR_LOG_BADGE_NATIVE = 0xFF1C8C82;
            COLOR_LOG_TIME         = 0xFF8A7A96;
            COLOR_LOG_WARN         = 0xFFB86E00;
            COLOR_LOG_ERROR        = 0xFFD03030;
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
        CNEndpoints.site(0) + "【魔法纪录剧情中日双语阅读网站】",
        CNEndpoints.site(1) + "【MagiaExedra和魔法纪录Live2D网站】",
        CNEndpoints.site(2) + "【魔法少女称呼关系搜索与身高对比网站】",
        "【协助与鸣谢】",
        "国服文件之外的翻译和校对：水银h2oag【阅读器网站为主，资源已同步至游戏】",
        "下载加速及资源自动化推送：CyberNova",
        "国服数据留存：segfault",
        "项目官网：" + CNEndpoints.HOME_HOST + "【通往其他个人网站和提供联系方式】",
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
        CNEndpoints.siteUrl(0),
        CNEndpoints.siteUrl(1),
        CNEndpoints.siteUrl(2),
        "",                                                     // 「协助与鸣谢」小标题
        "https://b23.tv/ovvbrNw",                               // 水银h2oag
        "https://b23.tv/9vyRcI8",                               // CyberNova
        "https://b23.tv/xjXW9DI",                               // segfault
        CNEndpoints.HOME_URL,
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
        CNEndpoints.site(0),
        CNEndpoints.site(1),
        CNEndpoints.site(2),
        "",
        "水银h2oag",
        "CyberNova",
        "segfault",
        CNEndpoints.HOME_HOST,
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

    // ---- 云端可配的署名内容 ----
    //
    // 远端 config.json 的 ui_credits 字段（见 CNMirrors.uiCredits()）可覆盖：
    // 左侧署名列表（名单与个人网站混排）、底部滚动署名、GitHub 胶囊地址。
    // 没配或解析失败时回落到上面的内置默认值，浮层行为与旧版完全一致。

    private static final class CreditsModel {
        int[]    kinds;
        String[] texts;
        String[] urls;
        String[] spans;
    }

    private static CreditsModel creditsModel() {
        // 配置还在路上：先给占位，避免「先默认名单、几秒后突变云端名单」的跳变。
        // 加载失败（configState=2）才回落内置默认。配置到位/失败时
        // CNMirrors.refresh 会调 refreshCredits 补刷，占位不会卡住。
        if (CNMirrors.configState == 0) {
            CreditsModel m = new CreditsModel();
            m.kinds = new int[]{KIND_SUB};
            m.texts = new String[]{"署名加载中…"};
            m.urls  = new String[]{""};
            m.spans = new String[]{""};
            return m;
        }
        JSONObject cfg = CNMirrors.uiCredits();
        if (cfg != null) {
            try {
                JSONArray arr = cfg.getJSONArray("list");
                int n = arr.length();
                CreditsModel m = new CreditsModel();
                m.kinds = new int[n];
                m.texts = new String[n];
                m.urls  = new String[n];
                m.spans = new String[n];
                for (int i = 0; i < n; i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String type = o.optString("type", "item");
                    m.kinds[i] = "title".equals(type) ? KIND_TITLE
                               : "head".equals(type)  ? KIND_HEAD
                               : "sub".equals(type)   ? KIND_SUB
                               : KIND_ITEM;
                    m.texts[i] = o.optString("text", "");
                    m.urls[i]  = o.optString("url", "");
                    m.spans[i] = o.optString("span", "");
                }
                return m;
            } catch (Throwable t) {
                CNLog.w("界面", "ui_credits 解析失败，使用内置署名: " + t);
            }
        }
        CreditsModel m = new CreditsModel();
        m.kinds = CREDIT_KINDS;
        m.texts = CREDIT_TEXTS;
        m.urls  = CREDIT_URLS;
        m.spans = CREDIT_LINK_SPANS;
        return m;
    }

    private static String footerText() {
        if (CNMirrors.configState == 0) return "署名加载中…";
        JSONObject cfg = CNMirrors.uiCredits();
        if (cfg != null) {
            String s = cfg.optString("footer", "").trim();
            if (s.length() > 0) return s;
        }
        return FOOTER_CREDIT;
    }

    private static String githubUrl() {
        JSONObject cfg = CNMirrors.uiCredits();
        if (cfg != null) {
            String s = cfg.optString("github_url", "").trim();
            if (s.length() > 0) return s;
        }
        return URL_GITHUB;
    }

    /** 底部署名是否无限滚动。ui_credits.footer_marquee=false 可远程关闭。 */
    private static boolean footerMarquee() {
        JSONObject cfg = CNMirrors.uiCredits();
        if (cfg != null && cfg.has("footer_marquee")) {
            return cfg.optBoolean("footer_marquee", true);
        }
        return true;
    }

    /** 按当前配置应用底部滚动/静态模式（建成时与 refreshCredits 补刷都会调）。 */
    private static void applyFooterMode() {
        if (vFooter == null) return;
        if (footerMarquee()) {
            vFooter.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
            vFooter.setMarqueeRepeatLimit(-1);
            vFooter.setHorizontallyScrolling(true);
            vFooter.setSelected(true);
        } else {
            // 关掉滚动：清 selected 停止 marquee，恢复普通截断
            vFooter.setSelected(false);
            vFooter.setHorizontallyScrolling(false);
            vFooter.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

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
    private static FrameLayout  supportModal;
    private static TextView     vLogPill;
    private static FrameLayout  logModal;
    private static ScrollView   vLogScroll;
    private static TextView     vFooter;
    private static GradientDrawable themeChipBg;
    private static GradientDrawable logPillBg;
    private static TextView vBgmPill;
    private static TextView vTutorialPill;
    private static TextView vOfflinePill;
    /** 教程询问的模态框。非空即表示正在显示，用于防重入。 */
    private static FrameLayout tutorialModal;
    /** 「网络慢，要不要继续等」询问框。非空即表示正在显示，用于防重入。 */
    private static FrameLayout slowModal;
    /** aria2 备用引擎失败时的「你来定」询问框。非空即表示正在显示，用于防重入。 */
    private static FrameLayout aria2AskModal;
    /** 离线包导入框。非空即表示正在显示，用于防重入（2026-08-12 补，原版会叠框）。 */
    private static FrameLayout offlineModal;
    /** 离线导入结果弹窗（成功/失败确认）。非空即表示正在显示，用于防重入。 */
    private static FrameLayout importResultModal;

    /**
     * 浮层上最后一次用户交互（任意按下）的时间（uptimeMillis）。
     * 供热更收工后的「玩家窗口」判断：玩家刚点过东西，自动收浮层就得顺延。
     * 只在 UI 线程写、后台线程读，volatile 保证立即可见。
     */
    private static volatile long sLastInteractMs = 0L;

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
                    // inScaled=false：assets 下的图没有密度限定目录，默认解码会按
                    // 设备 densityDpi 把它放大（这台 3392×2400 的机器上 1024² 会被
                    // 放成 2688²），白白吃内存，而且解码失败时下面那个 catch 会把
                    // OOM 一声不响地吞掉——表现就是「背景盖不全 / 只剩兜底色」。
                    //
                    // 缩放交给 ImageView 的 CENTER_CROP 去做：它是绘制时的矩阵变换，
                    // 不额外占内存，且按定义一定盖满，与屏幕多大无关。
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inScaled = false;
                    bm = BitmapFactory.decodeStream(is, null, opts);
                } finally {
                    try { is.close(); } catch (Throwable ignore) {}
                }
                if (bm == null) {
                    CNLog.w(TAG, "背景图解码失败（将只剩兜底底色）: " + assetPath);
                    return;
                }
                CNLog.i(TAG, "背景图已解码 " + assetPath
                        + " " + bm.getWidth() + "x" + bm.getHeight());
                act.runOnUiThread(new ApplyBitmap(target, bm));
            } catch (Throwable t) {
                // 以前这里是 catch (Throwable ignore) {}。OOM 被吞掉之后，
                // 「背景盖不全」在日志上没有任何痕迹（2026-08-13 真机）。
                CNLog.w(TAG, "背景图加载失败（将只剩兜底底色）: " + assetPath + " : " + t);
            }
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
        // 打标记：hide() 据此摘除全部本类浮层（而非只摘 overlayView 那一个）
        root.setTag(TAG_OVERLAY);
        // 记下玩家交互时刻：热更收工后的自动收浮层会据此顺延（见
        // CNHotUpdateCheck.awaitPlayerWindow）。return false——只观察、不消费，
        // 触摸照常落到子视图上。
        root.setOnTouchListener(new TouchNote());

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

        // ── 第 2 层：主内容区（固定视口 + 底部横向滚动条） ──
        FrameLayout.LayoutParams mainLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        mainLp.leftMargin   = dp(act, 14) + dp(act, 14);
        mainLp.rightMargin  = dp(act, 14) + dp(act, 14);
        main-hostMargin    = dp(act, 52) + dp(act, 12);
        mainLp.bottomMargin = dp(act, 40) + dp(act, 12);

        HorizontalScrollView mainScroll = new HorizontalScrollView(act);
        mainScroll.setTag(CNDownloadUiAssist.TAG_H_SCROLL);
        mainScroll.setFillViewport(true);
        mainScroll.setHorizontalScrollBarEnabled(false);
        mainScroll.setScrollbarFadingEnabled(false);
        mainScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        mainScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        mainScroll.setClipToPadding(false);
        mainScroll.setPadding(0, 0, 0, dp(act, CNDownloadUiAssist.SCROLLBAR_GUTTER_DP));

        // ⚠ 内容根必须是 MATCH_PARENT，不能是算出来的像素宽。
        //
        // 上面那句 mainScroll.setFillViewport(true) 原先一直是**废的**：
        // fillViewport 只在子节点是 WRAP_CONTENT / MATCH_PARENT 时才把它拉到视口宽，
        // 给了精确像素值就原样照办。而那个像素值是「widthPixels − 左右边距」，建浮层
        // 时算一次存进 contentBaseWidthPx——分屏、旋转、刘海与手势区 inset、面板自身
        // padding，任何一项对不上，两列就在按错的总宽分家。这正是 2026-08-14 反馈的
        // 「左右宽度解析有大问题」，而且它只算一次，之后屏幕怎么变都不会重算。
        //
        // 交给 MATCH_PARENT + fillViewport 之后，100% 时内容恰好等于**真实**视口，
        // 一个像素都不用自己算；>100% 的放大由 CNDownloadUiAssist 按实测视口设定
        // （读父写子，没有反馈环，见那边的类注释）。
        contentBaseWidthPx = Math.max(1,
                act.getResources().getDisplayMetrics().widthPixels
                        - mainLp.leftMargin - mainLp.rightMargin);
        LinearLayout mainRow = new LinearLayout(act);
        mainRow.setTag(CNDownloadUiAssist.TAG_CONTENT_ROOT);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainScroll.addView(mainRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(mainScroll, mainLp);

        // ---- 左列：Logo + 署名区 ----
        LinearLayout leftCol = new LinearLayout(act);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.setPadding(dp(act, 4), 0, dp(act, 12), 0);
        // 比例从 CNDownloadUiAssist 取，不写死：浮层会被重建（切主题、看门狗
        // 发现它掉出视图树），写死的话每次重建都先闪回 38/62，而分界线要等
        // ensureInstalled 那一轮才把它改回来。
        mainRow.addView(leftCol, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT,
                CNDownloadUiAssist.leftWeight(act)));

        // Logo 整幅在上、贡献者列表在下。c1bb57a0 曾改成「Logo 122dp 靠左 +
        // 右侧三行品牌文字」，观感上不成立，已退回。那三行文字也一并去掉：
        // MadeInMagius / PhotonFlow 在下方贡献者列表与底部署名条里都已经有，
        // 品牌区再写一遍是重复，不是信息。
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
                0, ViewGroup.LayoutParams.MATCH_PARENT,
                CNDownloadUiAssist.rightWeight(act)));

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
        // 固定两行：首屏那句要交代「热更新内容已排到最前」，一行放不下。
        // min=max=2 是为了让这一行的高度恒定——否则文案在一行/两行之间变动时，
        // 下面的文件列表会跟着上下跳。
        vStatus.setMinLines(2);
        vStatus.setMaxLines(2);
        vStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        rightCol.addView(vStatus, lpRow(0, dp(act, 6)));

        ScrollView slotScroll = new ScrollView(act);
        slotScroll.setTag(CNDownloadUiAssist.TAG_V_SCROLL);
        slotScroll.setVerticalScrollBarEnabled(true);
        slotScroll.setScrollbarFadingEnabled(false);
        slotScroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        slotScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        slotScroll.setClipToPadding(false);
        slotScroll.setPadding(0, 0, dp(act, CNDownloadUiAssist.SCROLLBAR_GUTTER_DP), 0);
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
        // ⚠ 右侧留白必须与文件区一致。
        //
        // slotScroll 为了给常驻纵向滚动条让位，右边留了 SCROLLBAR_GUTTER_DP；而
        // totalRow 与总进度条是直接加在 rightCol 上的，没有这一份——于是它们比上面
        // 每一行文件都长出同样多，看起来就是「默认情况下进度条偏长」（2026-08-13
        // 反馈）。这跟「重下」按钮无关，那颗胶囊早已撤掉；是滚动条留白只加在了一边。
        LinearLayout totalRowLp0 = totalRow;
        totalRowLp0.setPadding(0, 0, dp(act, CNDownloadUiAssist.SCROLLBAR_GUTTER_DP), 0);
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
        LinearLayout.LayoutParams overallLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 10));
        overallLp.rightMargin = dp(act, CNDownloadUiAssist.SCROLLBAR_GUTTER_DP);   // 与 slotScroll 的滚动条留白同源
        rightCol.addView(progressBarOverall, overallLp);

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
        // LOG 与 BGM 两个胶囊并排放在左上角
        LinearLayout topLeft = new LinearLayout(act);
        topLeft.setOrientation(LinearLayout.HORIZONTAL);
        topLeft.setGravity(Gravity.CENTER_VERTICAL);
        // 左侧胶囊放进自己的横向视口；窄屏/高 DPI 时滚动，不再与右侧主题栏重叠。
        HorizontalScrollView topLeftScroll = new HorizontalScrollView(act);
        topLeftScroll.setHorizontalScrollBarEnabled(false);
        topLeftScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        topLeftScroll.setFillViewport(false);
        topLeftScroll.addView(topLeft, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        topLeft.addView(vLogPill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // BGM 胶囊：点一下在 关闭 → BGM1 → BGM2 → 关闭 之间轮换。
        // 没有可用曲目（bgm.json 缺失或转换失败）时干脆不显示，免得点了没反应。
        if (CNBgm.trackCount(act) > 0) {
            vBgmPill = new TextView(act);
            vBgmPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            vBgmPill.setTypeface(vBgmPill.getTypeface(), Typeface.BOLD);
            vBgmPill.setGravity(Gravity.CENTER);
            vBgmPill.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
            vBgmPill.setOnClickListener(new BgmPillClick(act));
            LinearLayout.LayoutParams bgmLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            bgmLp.leftMargin = dp(act, 8);
            topLeft.addView(vBgmPill, bgmLp);
            // 这里**不主动起播**。浮层的 BGM 与引擎自己的 BGM 互不知情，自动起播
            // 会在「资源已就位、浮层一闪而过直接进游戏」那条路径上撞成二重奏。
            // 只有玩家点了胶囊才会响，CNBgm.current 也只存内存、不落盘。
            styleBgmPill(act);
        }

        // 教程胶囊：点开询问「是否播放序章」。常驻——自动询问只在首次安装
        // 跑完、完成标记落盘那一瞬间弹一次，之后这里就是唯一的入口。
        vTutorialPill = new TextView(act);
        vTutorialPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        vTutorialPill.setTypeface(vTutorialPill.getTypeface(), Typeface.BOLD);
        vTutorialPill.setGravity(Gravity.CENTER);
        vTutorialPill.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
        vTutorialPill.setOnClickListener(new TutorialPillClick(act));
        LinearLayout.LayoutParams tutLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tutLp.leftMargin = dp(act, 8);
        topLeft.addView(vTutorialPill, tutLp);
        styleTutorialPill(act);

        // 离线包胶囊：玩家从网盘下载好官方 zip 后导入，跳过网络下载（兜底）。
        vOfflinePill = new TextView(act);
        vOfflinePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        vOfflinePill.setTypeface(vOfflinePill.getTypeface(), Typeface.BOLD);
        vOfflinePill.setGravity(Gravity.CENTER);
        vOfflinePill.setPadding(dp(act, 12), dp(act, 6), dp(act, 12), dp(act, 6));
        vOfflinePill.setText("📦  导入离线包");
        vOfflinePill.setOnClickListener(new OfflinePillClick(act));
        LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        offLp.leftMargin = dp(act, 8);
        topLeft.addView(vOfflinePill, offLp);
        styleOfflinePill(act);

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
        vGitHubChip.setOnClickListener(new CreditLinkClick(act, githubUrl()));

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

        // GitHub 胶囊为「可变按钮」：config 下发 right_pill 时, 文案/点击动作
        // 替换为配置值(弹窗+跳转); 未配置时保持默认 GitHub 跳转(见 applyRightPill)。
        applyRightPill(act);

        LinearLayout topBar = new LinearLayout(act);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.addView(topLeftScroll, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams headRightLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        headRightLp.leftMargin = dp(act, 8);
        topBar.addView(headRight, headRightLp);
        FrameLayout.LayoutParams topBarLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        topBarLp.gravity = Gravity.TOP | Gravity.START;
        topBarLp.topMargin = dp(act, 10);
        topBarLp.leftMargin = dp(act, 14);
        topBarLp.rightMargin = dp(act, 14);
        root.addView(topBar, topBarLp);

        // ── 第 4 层：底部常驻署名条 ──
        // marquee 可经 ui_credits.footer_marquee=false 远程关闭：
        // 无限滚动会持续触发重绘，让底下的 WebView 不停重建 Vulkan 帧缓冲，
        // 在部分 Adreno 驱动上会放大 vkDestroyFramebuffer 崩溃的触发面。
        vFooter = new TextView(act);
        vFooter.setText(footerText());
        vFooter.setTextColor(COLOR_SUB);
        vFooter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        vFooter.setSingleLine(true);
        applyFooterMode();
        vFooter.setPadding(dp(act, 16), 0, dp(act, 16), dp(act, 8));
        FrameLayout.LayoutParams footerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.gravity = Gravity.BOTTOM | Gravity.START;
        root.addView(vFooter, footerLp);

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

        TextView shareBtn = new TextView(act);
        shareBtn.setText("分享日志");
        shareBtn.setTextColor(0xFFFFFFFF);
        shareBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        shareBtn.setGravity(Gravity.CENTER);
        shareBtn.setPadding(dp(act, 14), dp(act, 6), dp(act, 14), dp(act, 6));
        GradientDrawable shareBg = new GradientDrawable();
        shareBg.setColor(COLOR_ACCENT2);
        shareBg.setCornerRadius(dp(act, 8));
        shareBtn.setBackground(shareBg);
        shareBtn.setOnClickListener(new ShareLogClick(act));
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        shareLp.leftMargin = dp(act, 8);
        logHead.addView(shareBtn, shareLp);

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

        // ── 三个显示开关 ──
        // 异常排查时经常需要「只看某一类」：整机 logcat 很吵，native 日志在
        // 引擎出问题时才有用，而纯文字下载界面在只关心网络时纯属占地方。
        //
        // 用胶囊而不是系统 CheckBox：那个方框是 AppCompat 之外的平台默认样式，
        // 方角、灰底、跟着系统主题走，摆在玻璃拟态的浮层里像块补丁。
        // 横向可滚动，免得窄屏上三个挤成一团或被截断。
        HorizontalScrollView togScroll = new HorizontalScrollView(act);
        togScroll.setHorizontalScrollBarEnabled(false);
        togScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        panel.addView(togScroll, lpRow(0, dp(act, 8)));

        LinearLayout togRow = new LinearLayout(act);
        togRow.setOrientation(LinearLayout.HORIZONTAL);
        togRow.setGravity(Gravity.CENTER_VERTICAL);
        togScroll.addView(togRow, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addLogChip(act, togRow, "下载状态", PREF_LOG_STATUS, 0);
        addLogChip(act, togRow, "logcat",  PREF_LOG_LOGCAT, 1);
        addLogChip(act, togRow, "原生日志", PREF_LOG_NATIVE, 2);

        vLogScroll = new ScrollView(act);
        vLogScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        GradientDrawable logScrollBg = new GradientDrawable();
        logScrollBg.setColor(darkMode ? 0x44FFFFFF : 0x14000000);
        logScrollBg.setCornerRadius(dp(act, 8));
        logScrollBg.setStroke(1, darkMode ? 0x33FFFFFF : 0x22000000);
        vLogScroll.setBackground(logScrollBg);
        vLogScroll.setPadding(dp(act, 8), dp(act, 6), dp(act, 8), dp(act, 6));
        vLogScroll.getViewTreeObserver().addOnScrollChangedListener(new LogScrollWatcher());
        panel.addView(vLogScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 原始安装日志文本（等宽），即改版前的主体文本视图
        tvLog = new TextView(act);
        tvLog.setText("=== MagiaCN Installer ===\n(waiting...)");
        tvLog.setTextColor(COLOR_LOG_PANEL_TEXT);
        tvLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvLog.setTypeface(Typeface.MONOSPACE);
        // 不开 setTextIsSelectable：大文本下它会启用近似 EditText 的机制，
        // 每次 setText 都要重建选择/输入相关结构，是面板卡死的主要来源之一。
        // 复制走标题栏的「复制全部」按钮。
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
            // 先验地址再谈确认：不安全的链接连「再点一次」都不该给，
            // 否则等于把判断推给玩家，而玩家看不出 https://自家域名@evil/ 的门道
            String why = CNSafeLink.reject(url);
            if (why != null) {
                CNLog.e("界面", "拒绝打开署名外链: " + url + " —— " + why);
                toast(act, "这个链接不安全，已拦下：" + why);
                return;
            }
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
            CNSafeLink.open(act, url, "署名条目");
        }
    }

    /**
     * 根据 config.json 的 support_us 刷新「支持我们」胶囊。
     * 未配置（supportUs()==null）或 label 为空 → 隐藏；配置了 → 显示并设置文字。
     * 显示开关、文案、链接全部由云端配置（CNMirrors.rightPill()）。
     */
    private static void applyRightPill(Activity act) {
        // GitHub 胶囊为「可变按钮」：config 下发 right_pill 时, 文案与点击动作
        // 替换为配置值(弹窗+跳转); 未配置时保持默认 GitHub 跳转。
        if (vGitHubChip == null) return;
        JSONObject rp = CNMirrors.rightPill();
        // enabled=false 时**无视其余字段**，直接回落默认 GitHub 胶囊。
        // 之所以要这个显式开关：原先只看「有没有 right_pill 且 label 非空」，
        // 想临时关掉就得把整段删了或把 label 清空，改回来还得重新把字段敲一遍。
        // 缺省 true，老配置行为不变。
        if (rp != null && rp.optBoolean("enabled", true)) {
            String label = rp.optString("label", "").trim();
            if (!label.isEmpty()) {
                vGitHubChip.setText(label);
                vGitHubChip.setOnClickListener(new SupportClick(act));
                return;
            }
        }
        // 默认：GitHub 胶囊
        vGitHubChip.setText("</>  GitHub");
        vGitHubChip.setOnClickListener(new CreditLinkClick(act, githubUrl()));
    }

    /**
     * 调起系统浏览器打开外链。
     *
     * <p>地址来自 {@code config.json} 的 {@code right_pill.url}（右上角可变按钮），
     * 是云端可控的，所以一律先过 {@link CNSafeLink} 的校验
     * （只放行 HTTPS + 允许列表内的域名）。
     */
    private static void openExternalUrl(Activity act, String url) {
        CNSafeLink.open(act, url, "右上角可变按钮");
    }

    /**
     * 「支持我们」胶囊点击：弹窗显示 config 下发的 title/content，
     * 点「去支持」打开 config 下发的 url。每次点击读最新配置。
     */
    private static final class SupportClick implements View.OnClickListener {
        private final Activity act;
        SupportClick(Activity act) { this.act = act; }

        @Override public void onClick(View v) {
            openSupportModal(act);   // 浮层内建样式弹窗（非系统对话框）
        }
    }

    /**
     * 「支持我们」弹窗（浮层内建样式）：遮罩 + 圆角卡片，与 LOG/序章弹窗同一套
     * 配色与按钮。title/content/url 全部来自 config.json 的 support_us。
     * 点遮罩或「取消」关闭；点「去支持」关闭并打开 url。
     */
    private static void openSupportModal(final Activity act) {
        if (overlayView == null) return;
        final JSONObject su = CNMirrors.rightPill();
        if (su == null) return;              // 配置已下架，忽略点击
        if (!su.optBoolean("enabled", true)) return;   // 已关掉，忽略点击

        if (supportModal != null) return;    // 已开着，别叠第二层
        final String title   = su.optString("title", "支持我们");
        final String content = su.optString("content", "");
        final String url     = su.optString("url", "").trim();

        final FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(COLOR_DIM);
        modal.setClickable(true);
        modal.setFocusable(true);
        modal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeSupportModal(); }
        });

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(COLOR_LOG_PANEL_BG);
        panelBg.setCornerRadius(dp(act, 16));
        panelBg.setStroke(dp(act, 1), COLOR_CARD_STK);
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(act, 330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(act, 20);
        modal.addView(panel, panelLp);

        TextView titleV = new TextView(act);
        titleV.setText(title);
        titleV.setTextColor(COLOR_ACCENT);
        titleV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        titleV.setTypeface(titleV.getTypeface(), Typeface.BOLD);
        panel.addView(titleV, lpRow(0, dp(act, 10)));

        TextView msgV = new TextView(act);
        msgV.setText(content.isEmpty() ? title : content);
        msgV.setTextColor(COLOR_LOG_PANEL_TEXT);
        msgV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        msgV.setLineSpacing(dp(act, 2), 1f);
        panel.addView(msgV, lpRow(0, dp(act, 18)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        panel.addView(row, lpRow(0, 0));

        TextView cancel = dialogButton(act, "取消", COLOR_LOG_PANEL_TEXT, 0x00000000, true);
        TextView go     = dialogButton(act, "去支持", 0xFFFFFFFF, COLOR_ACCENT, false);
        LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        goLp.leftMargin = dp(act, 10);
        row.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(go, goLp);

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeSupportModal(); }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                closeSupportModal();
                if (!url.isEmpty()) openExternalUrl(act, url);
            }
        });

        try {
            overlayView.addView(modal, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            supportModal = modal;
        } catch (Throwable t) {
            CNLog.w("界面", "支持我们弹窗打开失败: " + t);
        }
    }

    /** 关闭「支持我们」弹窗（幂等）。 */
    private static void closeSupportModal() {
        if (supportModal == null) return;
        try {
            if (overlayView != null) overlayView.removeView(supportModal);
        } catch (Throwable ignore) {}
        supportModal = null;
        noteInteraction();
    }

    /** 记下一次玩家交互（任意线程可调）。 */
    static void noteInteraction() {
        sLastInteractMs = SystemClock.uptimeMillis();
    }

    /**
     * 浮层根部的触摸观察器：只记录按下时刻、不消费事件。
     * 具名静态嵌套类——方法体里的匿名类会撞 d8 内部错误（见 AGENTS.md §3）。
     */
    private static final class TouchNote implements View.OnTouchListener {
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) noteInteraction();
            return false;
        }
    }

    /** 浮层上最后一次玩家交互的时间（uptimeMillis），没有过交互返回 0。 */
    public static long lastInteractionMs() {
        return sLastInteractMs;
    }

    /**
     * 有任一弹窗/面板开着时为 true——玩家正在操作，自动收浮层必须等。
     * logModal 常驻视图树（GONE/VISIBLE 切换），看可见性；其余三个
     * 非空即在显示。
     */
    public static boolean isModalOpen() {
        if (supportModal != null || tutorialModal != null
                || slowModal != null || versionModal != null) return true;
        FrameLayout lm = logModal;
        return lm != null && lm.getVisibility() == View.VISIBLE;
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
                toast(act, "正在重新安排该文件");
                CNManualRedownload.retry(act, index);
            } catch (Throwable t) {
                CNLog.e("界面", "重试请求失败: " + t, t);
            }
        }
    }

    /**
     * 参数是 {@link Context} 而不是 {@link Activity}：调用方常写
     * {@code toast(RestClient.getCurrentActivity(), …)}，而那个方法完全可能返回
     * null——此时 {@code Toast.makeText(null, …)} 抛 NPE，被下面这个 catch 悄悄
     * 吞掉，玩家一个字都看不到。把入口放宽到 Context，控件就能传自己的
     * {@code getContext()}（永远非 null），提示不再取决于「此刻有没有 Activity」。
     */
    static void toast(Context ctx, String msg) {
        try {
            if (ctx == null) {
                CNLog.w("界面", "toast 没有 Context，丢弃：" + msg);
                return;
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignore) {}
    }

    /** 用署名数据填充左列署名区（云端 ui_credits 优先，内置默认兜底）。 */
    private static void populateContributors(Activity act) {
        if (vContribList == null) return;
        vContribList.removeAllViews();
        CreditsModel credits = creditsModel();
        int itemIndex = 0;
        int renderCount = 0;
        for (int i = 0; i < credits.texts.length; i++) {
            int kind = credits.kinds[i];
            String creditText = credits.texts[i] == null ? "" : credits.texts[i];
            if (creditText.contains("魔法纪录Totentanz中文化")
                    || creditText.contains("核心逆向开发")
                    || creditText.contains("补丁与自动化")
                    || creditText.contains("独立完成汉化引擎")) {
                continue;
            }
            if (kind == KIND_ITEM) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                row-hostMargin = dp(act, 1);
                vContribList.addView(row, rowLp);

                DotView dot = new DotView(act,
                        CONTRIB_PALETTE[itemIndex % CONTRIB_PALETTE.length]);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                        dp(act, 7), dp(act, 7));
                dotLp.rightMargin = dp(act, 7);
                row.addView(dot, dotLp);
                itemIndex++;

                String url  = i < credits.urls.length ? credits.urls[i] : "";
                String span = i < credits.spans.length ? credits.spans[i] : "";
                TextView t = new TextView(act);
                t.setTextColor(COLOR_TEXT);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
                t.setText(highlight(credits.texts[i], span));
                if (url.length() > 0) {
                    row.setPadding(0, dp(act, 1), 0, dp(act, 1));
                    row.setClickable(true);
                    row.setOnClickListener(new CreditLinkClick(act, url));
                }
                row.addView(t, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            } else {
                TextView t = new TextView(act);
                t.setText(credits.texts[i]);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                if (kind == KIND_TITLE) {
                    t.setTextColor(COLOR_ACCENT);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                    t.setTypeface(t.getTypeface(), Typeface.BOLD);
                    lp.bottomMargin = dp(act, 2);
                } else if (kind == KIND_HEAD) {
                    t.setTextColor(COLOR_ACCENT2);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                    t.setTypeface(t.getTypeface(), Typeface.BOLD);
                    lp.topMargin    = dp(act, 4);
                    lp.bottomMargin = dp(act, 1);
                } else {  // KIND_SUB
                    t.setTextColor(COLOR_SUB);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
                    lp.leftMargin = dp(act, 14);
                }
                vContribList.addView(t, lp);
            }
            renderCount++;
        }

    }

    /**
     * 云端配置（ui_credits）到位后重刷署名区与底部滚动署名。
     *
     * <p>浮层经常在 config.json 拉取完成之前就已经用内置默认值建成——
     * CNMirrors.refresh 成功后会调本方法补刷一次。任意线程可调，内部转 UI 线程。
     */
    public static void refreshCredits(final Activity act) {
        if (act == null || !isShowing) return;
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    populateContributors(act);
                    applyRightPill(act);   // GitHub 胶囊可变: config 下发 right_pill 则替换文案/动作
                    if (vFooter != null) {
                        vFooter.setText(footerText());
                        applyFooterMode();
                    }
                } catch (Throwable t) {
                    CNLog.w("界面", "刷新署名失败: " + t);
                }
            }
        });
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
            hrLp.topMargin = dp(act, 2);
            row.addView(headRow, hrLp);

            TextView name = new TextView(act);
            name.setText((i + 1) + ". " + FILE_NAMES[i]);
            name.setTextColor(COLOR_TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            headRow.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 「重试」按钮：仅在该文件失败（status==3）时可见。
            //
            // ⚠ 顺序有讲究：按钮一律排在**文字进度之前**。
            // 原版里文字进度的右端与下面那条整宽进度条的右端是对齐的，这一竖线
            // 是整块的视觉基准。按钮排在它后面时，「重试」一出现就把文字进度整体
            // 往左顶，右边界立刻和进度条错开——玩家看到的就是「一失败排版就散」
            // （2026-08-13 真机连报两次）。让文字进度当这一行的最后一个孩子，
            // 按钮出现与否都不影响那条右边界。
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

            // 文字进度：这一行的最后一个孩子，右端永远贴着整宽进度条的右端。
            // 打上 TAG_SLOT_INFO，好让 CNDownloadUiAssist 的「重下」插到它前面
            // 而不是追加到它后面（追加就等于又把它顶走了）。
            TextView info = new TextView(act);
            info.setText("");
            info.setTextColor(COLOR_SUB);
            info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            info.setGravity(Gravity.END);
            info.setTag(CNDownloadUiAssist.TAG_SLOT_INFO);
            LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            infoLp.leftMargin = dp(act, 8);
            headRow.addView(info, infoLp);

            ProgressBar bar = new ProgressBar(
                    act, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(0);
            tintBar(bar, 0x55888888);
            // 整宽，与上一行右对齐的「文字进度」右端对齐。
            //
            // c1bb57a0 曾把它塞进一个 3:1 的横排里（条占 3、右边留 1 份空白），
            // 于是进度条在 75% 处就断了，而同一行右上角的文字进度仍然顶到最右——
            // 两条右边界对不上，整块就散了。总进度条那个同款 spacer 已在
            // 2261c9a1 退掉，这里是漏网的第二处。
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 6));
            barLp.topMargin = dp(act, 2);
            row.addView(bar, barLp);

            View div = new View(act);
            div.setBackgroundColor(darkMode ? 0x22FFFFFF : 0x18000000);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            divLp.topMargin = dp(act, 2);
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
                // 玩家点了复制，多半接着就要把日志文件也取出来。先把攒着的
                // logcat 行落盘，免得文件比剪贴板里的还短一截。
                CNLog.flushNow();
                cm.setPrimaryClip(ClipData.newPlainText("log-service", composeLogText(true)));
                toast(act, "日志已复制到剪贴板（" + CNLog.size() + " 条）");
            } catch (Throwable t) {
                toast(act, "复制失败：" + t.getMessage());
            }
        }
    }

    /**
     * 「分享日志」：把日志目录里的启动日志文件合并打成一个 txt，走系统分享。
     *
     * <p>复制文本会被 QQ 等截断，也不是人人会用 Termux/adb 取文件——走
     * {@link Intent#ACTION_SEND} 把打包好的日志文件交出去，任何聊天工具都能转发。
     * 文件落在 {@code cacheDir/share/}，由自带的只读 provider
     * {@link CNLogShareProvider}（编译 classpath 没有 androidx，故不用 FileProvider）
     * 以一次性读权限分享。
     */
    private static final class ShareLogClick implements View.OnClickListener {
        private final Activity act;
        ShareLogClick(Activity act) { this.act = act; }
        @Override public void onClick(View v) {
            try {
                // 先落盘：剪贴板复制会 flush，分享走文件更要先把攒着的 logcat 行写进
                // 日志文件，免得导出的包里比实际少一段。
                CNLog.flushNow();
                java.io.File out = CNLogBundle.write(act, CNLog.logDirPath());
                if (out == null) {
                    toast(act, "没有可分享的日志文件");
                    return;
                }
                // 编译 classpath 没有 androidx，用自带的只读 provider 临时授权
                // （只开 cacheDir/share/，见 CNLogShareProvider）。
                Uri uri = Uri.parse("content://" + CNLogShareProvider.AUTHORITY
                        + "/" + Uri.encode(out.getName()));
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.startActivity(Intent.createChooser(send, "分享日志"));
                toast(act, "日志已打包：" + out.getName());
            } catch (Throwable t) {
                CNLog.w("界面", "分享日志失败", t);
                toast(act, "分享失败：" + t.getMessage());
            }
        }
    }

    /**
     * BGM 胶囊：关闭 → BGM1 → BGM2 → 关闭 轮换。
     *
     * <p>做成轮换而不是三个并排的胶囊，是因为左上角还挤着 LOG 胶囊，横向空间有限；
     * 而且这三个状态互斥，轮换比三选一更省地方。
     */
    private static final class BgmPillClick implements View.OnClickListener {
        private final Activity act;
        BgmPillClick(Activity act) { this.act = act; }
        @Override public void onClick(View v) {
            try {
                int n = CNBgm.trackCount(act);
                int next = CNBgm.current() + 1;
                if (next > n) next = 0;          // 越过最后一首就回到关闭
                CNBgm.select(act, next);
                styleBgmPill(act);
                // 顺带把曲名报出来：胶囊上只摆得下「BGM 1」，而玩家想知道的是这是
                // 哪一首。查不到曲名（将来加了曲子而曲名表没跟上）就只报编号，不猜。
                String song = CNBgm.title(next);
                toast(act, next <= 0 ? "BGM 已关闭"
                        : (song == null ? "BGM " + next
                                        : "BGM " + next + " ・ " + song));
            } catch (Throwable t) {
                CNLog.w("界面", "切换 BGM 失败", t);
            }
        }
    }

    /** 按当前状态刷新 BGM 胶囊的文字与配色。开＝实心强调色，关＝暗色描边。 */
    private static void styleBgmPill(Activity act) {
        TextView p = vBgmPill;
        if (p == null) return;
        int cur = CNBgm.current();
        p.setText(cur <= 0 ? "♪ 关" : ("♪ " + cur));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(act, 20));
        if (cur > 0) {
            bg.setColor(COLOR_LOG_PILL);
            p.setTextColor(0xFFFFFFFF);
        } else {
            // 关闭态不用灰色实心：那样看着像「禁用」。空心 + 次要文字色表示
            // 「可用但当前没开」，跟 LOG 面板里那三个开关是同一套语义。
            bg.setColor(0x00000000);
            bg.setStroke(dp(act, 1), COLOR_GLASS_STK);
            p.setTextColor(COLOR_SUB);
        }
        p.setBackground(bg);
    }

    // ==================================================================
    // 新手教程询问
    // ==================================================================

    /**
     * 教程胶囊的点击：无条件弹询问框。
     *
     * <p>不做成「点一下直接切换」是刻意的——它的后果（无视账号进度从头播序章）
     * 比切 BGM 重得多，误触的代价不对等，所以要一次确认。
     */
    private static final class TutorialPillClick implements View.OnClickListener {
        private final Activity act;
        TutorialPillClick(Activity act) { this.act = act; }
        @Override public void onClick(View v) { showTutorialDialog(act, null); }
    }

    /** 「导入离线包」胶囊点击：弹文件选择对话框。 */
    private static final class OfflinePillClick implements View.OnClickListener {
        private final Activity act;
        OfflinePillClick(Activity act) { this.act = act; }
        @Override public void onClick(View v) { showOfflineDialog(act); }
    }

    /** 离线包胶囊样式（常驻，实心强调色）。 */
    private static void styleOfflinePill(Activity act) {
        TextView p = vOfflinePill;
        if (p == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(act, 20));
        bg.setColor(COLOR_ACCENT2);
        p.setBackground(bg);
        p.setTextColor(0xFFFFFFFF);
    }

    /** 按标记状态刷新教程胶囊。已就位＝实心强调色，未就位＝空心。与 BGM 胶囊同语义。 */
    private static void styleTutorialPill(Activity act) {
        TextView p = vTutorialPill;
        if (p == null) return;
        boolean armed = CNTutorialPrompt.isArmed();
        p.setText(armed ? "▶ 序章" : "序章");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(act, 20));
        if (armed) {
            bg.setColor(COLOR_LOG_PILL);
            p.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(act, 1), COLOR_GLASS_STK);
            p.setTextColor(COLOR_SUB);
        }
        p.setBackground(bg);
    }

    /**
     * 自动询问：仅在从未问过时弹一次，问过就直接放行。
     *
     * <p>由 {@link CNDownloaderFix} 在首次安装跑完、完成标记落盘那一瞬间调用。
     * 之后不再自动弹——玩家想改主意就点教程胶囊。
     *
     * <p><b>本方法立即返回</b>，结果通过 {@code onDone} 回调。调用方在工作线程上
     * 需要等待的话，自己拿个闩去卡。
     *
     * @param onDone 询问结束（或无需询问）后执行，可为 null
     */
    public static void askTutorialOnce(Activity act, Runnable onDone) {
        try {
            if (CNDebugFlags.isOn(CNDebugFlags.SKIP_TUTORIAL_PROMPT)) {
                CNLog.i("序章", "调试开关 skipTutorialPrompt 生效，不弹询问框");
                if (onDone != null) onDone.run();
                return;
            }
            if (CNTutorialPrompt.askedOnce()) {
                CNLog.i("序章", "自动询问已问过，跳过");
                if (onDone != null) onDone.run();
                return;
            }
            showTutorialDialog(act, onDone);
        } catch (Throwable t) {
            CNLog.e("序章", "自动询问失败", t);
            if (onDone != null) {
                try { onDone.run(); } catch (Throwable ignore) {}
            }
        }
    }

    /**
     * 弹出离线包导入对话框：列出全部 15 个可导入文件，玩家选一个触发文件选择器。
     * 与教程框同一套自绘模态框样式（系统 AlertDialog 在引擎 Activity 上格格不入）。
     */
    private static void showOfflineDialog(final Activity act) {
        final FrameLayout host = overlayView;
        if (act == null || host == null) {
            CNLog.w(TAG, "浮层不在，无法显示离线导入");
            toast(act, "下载界面未就绪");
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { buildOfflineDialog(act, host); }
                catch (Throwable t) {
                    CNLog.e(TAG, "构建离线导入框失败", t);
                    toast(act, "无法打开离线导入");
                }
            }
        });
    }

    /** 在 UI 线程上构建离线导入对话框。 */
    private static void buildOfflineDialog(final Activity act, FrameLayout host) {
        if (host == null || offlineModal != null) {   // 已开着一个，别叠第二层
            return;
        }
        final FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(COLOR_DIM);
        modal.setClickable(true);
        modal.setFocusable(true);

        // 面板高度封顶：内容（标题+提示+13 行文件列表+关闭）在大字体下会超出屏幕，
        // 列表区放进 ScrollView 可滚动，关闭钮固定在底部永远够得着（2026-08-12）。
        int panelMaxH;
        try {
            panelMaxH = Math.round(act.getResources().getDisplayMetrics().heightPixels * 0.80f);
        } catch (Throwable t) {
            panelMaxH = dp(act, 640);
        }

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));
        // 面板底色与其它弹窗（强更/日志）一致：COLOR_LOG_PANEL_BG（夜间深紫 /
        // 白天白）。曾误用 COLOR_LOG_PILL——那是胶囊的小块强调色，整块糊上去
        // 在夜间是亮粉、白天是暗粉，字都看不清（2026-08-11 反馈「颜色诡异」）。
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setCornerRadius(dp(act, 18));
        panelBg.setColor(COLOR_LOG_PANEL_BG);
        panelBg.setStroke(dp(act, 1), COLOR_CARD_STK);
        panel.setBackground(panelBg);

        // 标题行：左侧标题（weight 1f 把按钮推到右），右侧「去下载」跳离线包静态站。
        // 静态站地址由 config.json 的 settings.offline_url 下发；未配置就不显示按钮。
        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(act);
        title.setText("导入离线包");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String offlineUrl = CNMirrors.offlineUrl();
        if (offlineUrl != null && offlineUrl.length() > 0) {
            TextView dl = new TextView(act);
            dl.setText("去下载");
            dl.setTextColor(0xFFFFFFFF);
            dl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            dl.setGravity(Gravity.CENTER);
            dl.setPadding(dp(act, 14), dp(act, 6), dp(act, 14), dp(act, 6));
            GradientDrawable dlBg = new GradientDrawable();
            dlBg.setColor(COLOR_ACCENT2);
            dlBg.setCornerRadius(dp(act, 8));
            dl.setBackground(dlBg);
            dl.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CNSafeLink.open(act, offlineUrl, "离线包下载");
                }
            });
            LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlLp.leftMargin = dp(act, 8);
            titleRow.addView(dl, dlLp);
        }

        // 右上角 ✕ 关闭（大字体下也够得着）
        TextView x = new TextView(act);
        x.setText("✕");
        x.setTextColor(COLOR_SUB);
        x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        x.setGravity(Gravity.CENTER);
        x.setPadding(dp(act, 10), dp(act, 2), dp(act, 2), dp(act, 2));
        x.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeOfflineDialog(host, modal); }
        });
        titleRow.addView(x, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(act);
        hint.setText("下载引擎不可靠时，点右上角「去下载」手动取包；再从网盘选官方 zip（文件名匹配下方列表）导入，该文件即跳过网络下载。");
        hint.setTextColor(COLOR_SUB);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(act, 8);
        panel.addView(hint, hintLp);

        // 文件列表区放进 ScrollView：13 个基础资源包（热更两包 cn_scenario_update.zip /
        // cn_js_update.zip 走版本 json 通道，不提供离线导入）大字体下能滚动；行宽
        // MATCH_PARENT 让长文件名在面板内换行。
        ScrollView sv = new ScrollView(act);
        LinearLayout list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        String[] names = CNCNDownloadUI.FILE_NAMES;
        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            if (CNOfflineImport.isHotUpdateFile(name)) continue;
            TextView row = new TextView(act);
            String state = CNOfflineImport.hasOffline(name) ? " ✓已导入" : "";
            row.setText(name + state);
            row.setTextColor(COLOR_LINK);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            row.setPadding(dp(act, 4), dp(act, 6), dp(act, 4), dp(act, 6));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { importOne(act, host, modal, name); }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        sv.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 关闭按钮固定在 ScrollView 之外、面板底部，始终可见
        TextView close = dialogButton(act, "关闭", COLOR_LOG_PANEL_TEXT, 0x00000000, true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeOfflineDialog(host, modal); }
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.topMargin = dp(act, 10);
        panel.addView(close, closeLp);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(act, 360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.height = panelMaxH;
        panelLp.leftMargin = panelLp.rightMargin = dp(act, 20);
        modal.addView(panel, panelLp);
        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        offlineModal = modal;
    }

    /** 供 CNDownloaderFix 打开离线导入框（aria2 失败选「改用离线包」时）。 */
    public static void showOfflineImportDialog(Activity act) {
        showOfflineDialog(act);
    }

    /**
     * 离线导入结果弹窗（浮层内建样式）。成功显示「已导入」，失败显示原因。
     * <b>不用系统 Toast</b>——引擎全屏 Activity 上系统 Toast 不可靠，玩家会
     * 以为没导入成功（2026-08-12 反馈）。单「确定」钮，点掉才收。
     *
     * <p>可在任意线程调用，内部切到 UI 线程。浮层不在时记日志了事。
     *
     * @param ok   是否导入成功
     * @param name 文件名
     * @param err  失败原因（成功时 null）
     */
    public static void showImportResultDialog(final Activity act, final boolean ok,
                                              final String name, final String err) {
        final FrameLayout host = overlayView;
        if (act == null || host == null) {
            CNLog.w("离线", "浮层不在，无法显示导入结果弹窗");
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { buildImportResultDialog(act, host, ok, name, err); }
                catch (Throwable t) { CNLog.e("离线", "构建导入结果弹窗失败", t); }
            }
        });
    }

    /** 在 UI 线程上构建导入结果弹窗（与离线/教程框同一套模态样式）。 */
    private static void buildImportResultDialog(final Activity act, FrameLayout host,
                                                final boolean ok, final String name,
                                                final String err) {
        if (host == null || importResultModal != null) {   // 已开着一个，别叠第二层
            return;
        }
        final FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(COLOR_DIM);
        modal.setClickable(true);
        modal.setFocusable(true);

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(COLOR_LOG_PANEL_BG);
        panelBg.setCornerRadius(dp(act, 16));
        panelBg.setStroke(dp(act, 1), COLOR_CARD_STK);
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(act, 330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(act, 20);
        modal.addView(panel, panelLp);

        TextView title = new TextView(act);
        title.setText(ok ? "✓ 导入成功" : "导入失败");
        title.setTextColor(ok ? 0xFF8BB87A : COLOR_ACCENT);   // 成功用绿（同署名调色板）
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, lpRow(0, dp(act, 10)));

        TextView msg = new TextView(act);
        msg.setText(ok
                ? ("「" + name + "」已导入，安装时将跳过网络下载。\n\n"
                   + "继续导入其它文件也行，点「关闭」即可离开。")
                : ("「" + name + "」导入失败："
                   + (err == null ? "未知原因" : err)
                   + "\n\n请确认文件名与下方列表一致、包未损坏后重试。"));
        msg.setTextColor(COLOR_LOG_PANEL_TEXT);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        msg.setLineSpacing(dp(act, 2), 1f);
        panel.addView(msg, lpRow(0, dp(act, 18)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        panel.addView(row, lpRow(0, 0));

        TextView okBtn = dialogButton(act, "确定", 0xFFFFFFFF, COLOR_ACCENT, false);
        row.addView(okBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        okBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeImportResultDialog(); }
        });

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        importResultModal = modal;
    }

    private static void closeImportResultDialog() {
        FrameLayout m = importResultModal;
        importResultModal = null;
        if (m != null && m.getParent() instanceof ViewGroup) {
            ((ViewGroup) m.getParent()).removeView(m);
        }
        noteInteraction();
    }

    /** 关闭离线导入框（右上 ✕ 与底部「关闭」共用）。 */
    private static void closeOfflineDialog(FrameLayout host, FrameLayout modal) {
        offlineModal = null;
        try { host.removeView(modal); } catch (Throwable ignore) {}
    }

    /** 触发导入某文件；成功后关闭对话框。 */
    private static void importOne(final Activity act, final FrameLayout host,
                                  final FrameLayout modal, final String name) {
        boolean started = CNOfflineImportActivity.requestImport(act, name,
                new CNOfflineImportActivity.Callback() {
                    @Override public void onResult(boolean ok, String fn, String err) {
                        // 先关掉离线导入框，再改浮层内建弹窗给结果——系统 Toast 在
                        // 引擎 Activity 上不可靠，玩家会以为没导入成功（2026-08-12）。
                        offlineModal = null;
                        try { host.removeView(modal); } catch (Throwable ignore) {}
                        if (ok && vOfflinePill != null) {
                            vOfflinePill.setText("📦  导入离线包 ✓");
                        }
                        showImportResultDialog(act, ok, fn, err);
                        // 🔴 导入成功必须**立刻去用它**。
                        //
                        // 离线检查在 installArchive 的开头，而安装器的 15 文件循环
                        // 启动时只跑一次：已经处理过那个文件就「导入了没反应」，
                        // 正在下那个文件就「红条一直重试」——包躺在那没人消费
                        // （2026-08-13 真机反馈的两个症状，同一个根因）。
                        if (ok) applyOfflineAsync(fn);
                    }
                });
        if (!started) showImportResultDialog(act, false, name, "无法打开文件选择器");
    }

    /**
     * 把刚导入的离线包交给安装器立刻应用。跑在后台线程——
     * {@code installOfflineNow} 会解压整个包，绝不能放在 UI 线程上。
     */
    private static void applyOfflineAsync(String name) {
        try {
            Thread t = new Thread(new ApplyOfflineTask(name), "cnv-offline-apply");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            CNLog.w(TAG, "离线包应用线程起不来: " + e);
        }
    }

    /** static 嵌套类：匿名/非静态内部类带 this$0，d8 撞上直接 NPE。 */
    private static final class ApplyOfflineTask implements Runnable {
        private final String name;
        ApplyOfflineTask(String name) { this.name = name; }
        @Override public void run() {
            try {
                int idx = CNDownloaderFix.indexOfArchive(name);
                if (idx < 0) {
                    CNLog.w(TAG, "离线包文件名不在资源表里: " + name);
                    return;
                }
                updateSimple("应用离线包", name + "：正在解压校验…", 0);
                boolean ok = CNDownloaderFix.installOfflineNow(idx);
                updateSimple(ok ? "离线包已应用" : "离线包应用失败",
                        ok ? (name + "：已就位，不再从网络下载")
                           : (name + "：解压或校验未通过，将继续走网络下载"), 0);
                throttledUpdate();
            } catch (Throwable t) {
                CNLog.e(TAG, "应用离线包失败: " + name, t);
            }
        }
    }

    private static LinearLayout.LayoutParams lpWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * 弹出教程询问框。用浮层自己的调色板与圆角，与 LOG 面板同一套模态框样式——
     * 宿主是引擎的 Activity，系统 AlertDialog 在上面格格不入。
     *
     * <p>可在任意线程调用，内部会切到 UI 线程。浮层没建起来时无处可挂，此时
     * 直接走 {@code onDone}，不把调用方卡死。
     */
    private static void showTutorialDialog(final Activity act, final Runnable onDone) {
        final FrameLayout host = overlayView;
        if (act == null || host == null) {
            CNLog.w("序章", "浮层不在，无法显示教程询问");
            if (onDone != null) onDone.run();
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { buildTutorialDialog(act, host, onDone); }
                catch (Throwable t) {
                    CNLog.e("序章", "构建教程询问框失败", t);
                    if (onDone != null) onDone.run();
                }
            }
        });
    }

    /** 在 UI 线程上真正把询问框建出来。 */
    private static void buildTutorialDialog(final Activity act, FrameLayout host,
                                            final Runnable onDone) {
        if (tutorialModal != null) {           // 已经开着，别叠第二层
            if (onDone != null) onDone.run();
            return;
        }
        final FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(COLOR_DIM);
        modal.setClickable(true);              // 吃掉点击，不许点框外关掉：
        modal.setFocusable(true);              // 这是必须做出的选择，不是可略过的提示

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(COLOR_LOG_PANEL_BG);
        panelBg.setCornerRadius(dp(act, 16));
        panelBg.setStroke(dp(act, 1), COLOR_CARD_STK);
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(act, 330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(act, 20);
        modal.addView(panel, panelLp);

        TextView title = new TextView(act);
        title.setText("序章");
        title.setTextColor(COLOR_ACCENT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, lpRow(0, dp(act, 10)));

        TextView msg = new TextView(act);
        msg.setText("是否从头播放开场序章？\n\n"
                  + "· 「是」：进游戏后从头播放序章（剧情与教学战斗），播完自动重启回到正常游戏。\n"
                  + "· 「否」：正常进入游戏。\n\n"
                  + "走的是游戏自己的序章场景，只改本机状态，不动账号。");
        msg.setTextColor(COLOR_LOG_PANEL_TEXT);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        msg.setLineSpacing(dp(act, 2), 1f);
        panel.addView(msg, lpRow(0, dp(act, 18)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        panel.addView(row, lpRow(0, 0));

        TextView no  = dialogButton(act, "否", COLOR_LOG_PANEL_TEXT, 0x00000000, true);
        TextView yes = dialogButton(act, "是", 0xFFFFFFFF, COLOR_ACCENT, false);
        LinearLayout.LayoutParams yesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        yesLp.leftMargin = dp(act, 10);
        row.addView(no, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(yes, yesLp);

        no.setOnClickListener(new TutorialChoice(act, false, onDone));
        yes.setOnClickListener(new TutorialChoice(act, true,  onDone));

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        tutorialModal = modal;
    }

    /** 询问框的按钮。{@code hollow} 为真时用空心描边（次要动作）。 */
    private static TextView dialogButton(Activity act, String text,
                                         int fg, int bg, boolean hollow) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(act, 26), dp(act, 9), dp(act, 26), dp(act, 9));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(act, 10));
        d.setColor(bg);
        if (hollow) d.setStroke(dp(act, 1), COLOR_GLASS_STK);
        b.setBackground(d);
        b.setClickable(true);
        return b;
    }

    /**
     * 询问框的选择处理：落地标记 → 记「已问过」→ 关框 → 刷新胶囊 → 收尾。
     *
     * <p>收尾分两种，取决于是谁弹的框：
     * <ul>
     *   <li><b>安装收尾的自动询问</b>（{@code onDone != null}）：交回给
     *       {@link CNDownloaderFix}，它问完还要收浮层，重启由它统一做，
     *       这里不能自己重启，否则会重启两次。</li>
     *   <li><b>教程胶囊</b>（{@code onDone == null}）：自己走「Toast + 3 秒 +
     *       重启」。不重启的话玩家点完什么反应都没有，也没法确认设置生没生效；
     *       而且引擎可能已经走过首个 pushSceneTop 了，那时标记要到下次启动
     *       才会被消费——重启一次把这件事变确定。</li>
     * </ul>
     */
    private static final class TutorialChoice implements View.OnClickListener {
        private final Activity act;
        private final boolean  yes;
        private final Runnable onDone;
        TutorialChoice(Activity act, boolean yes, Runnable onDone) {
            this.act = act; this.yes = yes; this.onDone = onDone;
        }
        @Override public void onClick(View v) {
            boolean handedBack = false;
            try {
                boolean armed = CNTutorialPrompt.set(yes);
                CNTutorialPrompt.markAsked();
                CNLog.i("序章", yes ? ("玩家选择播放序章，标记就位=" + armed)
                                    : "玩家选择跳过序章");
                closeTutorialDialog();
                styleTutorialPill(act);
            } catch (Throwable t) {
                CNLog.e("序章", "处理教程选择失败", t);
                try { closeTutorialDialog(); } catch (Throwable ignore) {}
            } finally {
                if (onDone != null) {
                    handedBack = true;
                    // 安装收尾的自动询问中，“是”本身就意味着继续进入游戏。
                    if (yes) {
                        try { CNDownloadUiAssist.setStayOnPage(false); }
                        catch (Throwable ignore) {}
                    }
                    try { onDone.run(); } catch (Throwable ignore) {}
                }
            }
            if (!handedBack) {
                if (yes) {
                    CNDownloadUiAssist.setStayOnPage(false);
                    restartAfterTutorialChoice(act, true);
                } else {
                    CNDownloadUiAssist.setStayOnPage(true);
                    toast(act, "已设为正常进入游戏；仍停留在资源页");
                }
            }
        }
    }

    /**
     * 教程胶囊改完设置后的重启。本方法在 UI 线程上被调用，而
     * {@code noticeAndRestart} 要睡 3 秒，所以另起线程。
     */
    private static void restartAfterTutorialChoice(final Activity act, final boolean yes) {
        try {
            final String head = yes ? "已设为进游戏后播放序章" : "已设为正常进入游戏";
            // 安装还在跑：不能重启，会把下载打断。安装收尾自己会重启一次，
            // 那时这个设置照样生效，等它就好。
            if (CNDownloaderFix.isInstalling()) {
                CNLog.i("序章", "安装进行中，改完教程设置不立刻重启，等安装收尾");
                toast(act, head + "，安装完成后重启生效");
                return;
            }
            // 热更检查还在跑：同理，重启会打断下载或解压到一半。挂到检查的
            // 收尾上去做。
            final String msg = head + "，3 秒后自动重启游戏";
            if (CNHotUpdateCheck.requestRestartWhenDone(msg)) {
                CNLog.i("序章", "热更检查进行中，重启接力给检查收尾");
                toast(act, head + "，热更检查完成后重启");
                return;
            }
            Thread t = new Thread("cnv-tutorial-restart") {
                @Override public void run() {
                    try {
                        CNDownloaderFix.noticeAndRestart(msg);
                    } catch (Throwable th) {
                        CNLog.e("序章", "改完教程设置后重启失败", th);
                    }
                }
            };
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            CNLog.e("序章", "起不了重启线程", t);
            toast(act, "设置已保存，请手动重启游戏生效");
        }
    }

    private static void closeTutorialDialog() {
        FrameLayout m = tutorialModal;
        tutorialModal = null;
        if (m != null && m.getParent() instanceof ViewGroup) {
            ((ViewGroup) m.getParent()).removeView(m);
        }
        noteInteraction();
    }

    // ==================================================================
    // 网络慢时的「你来定」询问框
    // ==================================================================

    /** {@link #askSlowNetwork} 的返回值：玩家选了「再来一次」（继续等 / 重试）。 */
    public static final int SLOW_WAIT = 1;
    /** {@link #askSlowNetwork} 的返回值：玩家选了「算了」，或者根本没条件问。 */
    public static final int SLOW_SKIP = 2;

    /** 询问结果的信箱。用数组是为了让具名内部类能写回去（不能捕获非 final 局部量）。 */
    private static final class SlowAnswer {
        final int[] choice = new int[]{ SLOW_SKIP };
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
    }

    /**
     * 网络慢到超过预算时，<b>问玩家</b>要不要继续等，而不是替他决定。
     *
     * <h3>为什么要问</h3>
     *
     * 这件事众口难调：网好的人觉得被慢线路拖着，网差的人觉得刚开始就被放弃。
     * 任何一个写死的超时都会得罪一半人，而且两种得罪都是<b>静默</b>的——
     * 玩家只看到「进游戏了但台词没更新」或者「白屏久了一点」，根本不知道
     * 刚刚发生过一次取舍。所以把这个取舍摆到台面上，让他自己选。
     *
     * <h3>用法与线程</h3>
     *
     * <b>阻塞调用，只能在后台线程上用。</b>内部切到 UI 线程建框，然后在调用线程上
     * 等玩家点。在 UI 线程上调会死锁，所以那种情况直接返回 {@link #SLOW_SKIP}
     * 并记一条日志——宁可退回旧行为，也不能把主线程锁死。
     *
     * <p>浮层不在（还没建/已经收了）时无处挂框，同样返回 {@link #SLOW_SKIP}：
     * 问不了就只能沿用原来的 fail-open，但会留下日志说明是「没条件问」而不是
     * 「玩家选了跳过」——这两件事在排查时完全不同。
     *
     * <h3>两种形状</h3>
     *
     * 按钮文案是参数，因为两个调用点的取舍轴不一样：热更新那边玩家在<b>真的等</b>
     * （继续等 / 跳过），线路表那边没人在等（重试 / 用内置线路继续）。框是同一个，
     * 语义由调用方说清楚——别让玩家去猜「跳过」到底跳过了什么。
     *
     * @param act      宿主 Activity
     * @param title    框标题，如「热更新」「线路表」
     * @param what     出了什么事，一句话
     * @param waitLabel 正面按钮文案，如「继续等待」「再试一次」
     * @param skipLabel 次要按钮文案，如「跳过」「用内置线路」
     * @param waitDesc 选正面按钮意味着什么
     * @param skipCost 选次要按钮的代价，要说人话
     * @param waitedMs 已经等了多久；&lt;=0 表示不显示时长
     * @return {@link #SLOW_WAIT} 或 {@link #SLOW_SKIP}
     */
    public static int askSlowNetwork(final Activity act, final String title,
                                     final String what,
                                     final String waitLabel, final String skipLabel,
                                     final String waitDesc, final String skipCost,
                                     final long waitedMs) {
        if (CNDebugFlags.isOn(CNDebugFlags.SKIP_SLOW_ASK)) {
            CNLog.i(TAG, "[慢网询问] 调试开关 skipSlowAsk 生效，按跳过处理：" + what);
            return SLOW_SKIP;
        }
        if (act == null || overlayView == null) {
            CNLog.w(TAG, "[慢网询问] 浮层不在，无法询问「" + what + "」，按跳过处理");
            return SLOW_SKIP;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            CNLog.e(TAG, "[慢网询问] 被在 UI 线程上调用，会死锁；按跳过处理：" + what);
            return SLOW_SKIP;
        }
        final SlowAnswer ans = new SlowAnswer();
        try {
            act.runOnUiThread(new SlowBuild(act, title, what, waitLabel, skipLabel,
                                            waitDesc, skipCost, waitedMs, ans));
            ans.latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return SLOW_SKIP;
        } catch (Throwable t) {
            CNLog.e(TAG, "[慢网询问] 建框失败，按跳过处理", t);
            return SLOW_SKIP;
        }
        return ans.choice[0];
    }

    /** 在 UI 线程上把询问框建出来。建不出来就立刻放行调用线程，别把它吊死。 */
    private static final class SlowBuild implements Runnable {
        private final Activity act;
        private final String title, what, waitLabel, skipLabel, waitDesc, skipCost;
        private final long waited; private final SlowAnswer ans;
        SlowBuild(Activity act, String title, String what, String waitLabel,
                  String skipLabel, String waitDesc, String skipCost,
                  long waited, SlowAnswer ans) {
            this.act = act; this.title = title; this.what = what;
            this.waitLabel = waitLabel; this.skipLabel = skipLabel;
            this.waitDesc = waitDesc; this.skipCost = skipCost;
            this.waited = waited; this.ans = ans;
        }
        @Override public void run() {
            try { buildSlowDialog(act, title, what, waitLabel, skipLabel,
                                  waitDesc, skipCost, waited, ans); }
            catch (Throwable t) {
                CNLog.e(TAG, "[慢网询问] 构建失败，按跳过处理", t);
                ans.latch.countDown();
            }
        }
    }

    /** 与教程询问框同一套样式：同样的调色板、圆角、按钮，宿主是引擎 Activity。 */
    private static void buildSlowDialog(final Activity act, String title, String what,
                                        String waitLabel, String skipLabel,
                                        String waitDesc, String skipCost,
                                        long waited, SlowAnswer ans) {
        FrameLayout host = overlayView;
        if (host == null || slowModal != null) {   // 浮层没了 / 已经开着一个
            ans.latch.countDown();
            return;
        }
        final FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(COLOR_DIM);
        modal.setClickable(true);        // 吃掉点击：这是必须做出的选择，
        modal.setFocusable(true);        // 不许点框外糊弄过去

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(COLOR_LOG_PANEL_BG);
        panelBg.setCornerRadius(dp(act, 16));
        panelBg.setStroke(dp(act, 1), COLOR_CARD_STK);
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(act, 330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(act, 20);
        modal.addView(panel, panelLp);

        TextView t = new TextView(act);
        t.setText(title + "：网络似乎不太顺");
        t.setTextColor(COLOR_ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        panel.addView(t, lpRow(0, dp(act, 10)));

        TextView msg = new TextView(act);
        msg.setText(what
                  + (waited > 0 ? ("，已经等了 " + (waited / 1000) + " 秒还没有结果。")
                                : "。")
                  + "\n\n"
                  + "· 「" + waitLabel + "」：" + waitDesc + "\n"
                  + "· 「" + skipLabel + "」：" + skipCost + "\n\n"
                  + "两种都不会损坏存档，也不影响账号。");
        msg.setTextColor(COLOR_LOG_PANEL_TEXT);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        msg.setLineSpacing(dp(act, 2), 1f);
        panel.addView(msg, lpRow(0, dp(act, 18)));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        panel.addView(row, lpRow(0, 0));

        TextView skip = dialogButton(act, skipLabel, COLOR_LOG_PANEL_TEXT, 0x00000000, true);
        TextView wait = dialogButton(act, waitLabel, 0xFFFFFFFF, COLOR_ACCENT, false);
        LinearLayout.LayoutParams waitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        waitLp.leftMargin = dp(act, 10);
        row.addView(skip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(wait, waitLp);

        skip.setOnClickListener(new SlowChoice(SLOW_SKIP, ans));
        wait.setOnClickListener(new SlowChoice(SLOW_WAIT, ans));

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        slowModal = modal;
    }

    /** 记下选择 → 关框 → 放行等在后台线程上的调用方。 */
    private static final class SlowChoice implements View.OnClickListener {
        private final int choice; private final SlowAnswer ans;
        SlowChoice(int choice, SlowAnswer ans) { this.choice = choice; this.ans = ans; }
        @Override public void onClick(View v) {
            try {
                ans.choice[0] = choice;
                CNLog.i(TAG, "[慢网询问] 玩家选择："
                        + (choice == SLOW_WAIT ? "正面（继续/重试）" : "次要（跳过/放弃）"));
                closeSlowDialog();
            } catch (Throwable t) {
                CNLog.e(TAG, "[慢网询问] 处理选择失败", t);
            } finally {
                ans.latch.countDown();   // 无论如何都要放行，否则后台线程永远卡在这
            }
        }
    }

    private static void closeSlowDialog() {
        FrameLayout m = slowModal;
        slowModal = null;
        if (m != null && m.getParent() instanceof ViewGroup) {
            ((ViewGroup) m.getParent()).removeView(m);
        }
        noteInteraction();
    }

    // ==================================================================
    // aria2 备用引擎失败时的「你来定」询问框
    // ==================================================================

    /** {@link #askAria2Fallback} 的返回值：玩家选了「重试备用引擎」。 */
    public static final int ARIA2_RETRY    = 1;
    /** 返回值：玩家选了「继续用主引擎下载」，或询问没条件进行（默认）。 */
    public static final int ARIA2_CONTINUE = 2;
    /** 返回值：玩家选了「改用离线包」。 */
    public static final int ARIA2_OFFLINE  = 3;
    /**
     * 返回值：玩家选了「改用单线程下载」。调用方应当已经由本框把
     * {@link CNDownloadMode} 切好，接着<b>重试</b>即可。
     */
    public static final int DL_SINGLE      = 4;
    /** 返回值：玩家选了「改回多线程下载」。同样已切好，调用方只管重试。 */
    public static final int DL_MULTI       = 5;

    /** 询问结果的信箱。用数组是为了让具名内部类能写回去（不能捕获非 final 局部量）。 */
    private static final class Aria2Answer {
        final int[] choice = new int[]{ ARIA2_CONTINUE };
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
    }

    /**
     * aria2 备用引擎下载失败时<b>问玩家</b>接下来怎么办，而不是闷头回退主引擎。
     *
     * <p>三选一：重试备用引擎（临时故障可救）、继续用主引擎下载（旧行为）、改用
     * 离线包（打开离线导入框，玩家手动下载导入）。备用引擎已经过真机崩溃（JNI 版
     * 曾直接杀掉游戏进程），现在虽然改成了崩溃隔离的子进程，但失败时把取舍摆到
     * 台面上仍是对的——玩家是唯一知道「现在该不该继续等网络」的人。
     *
     * <p><b>阻塞调用，只能在后台线程上用。</b>内部切到 UI 线程建框，然后在调用线程
     * 上等玩家点；超时 60 秒按「继续用主引擎」兜底，免得询问框出问题时安装线程
     * 永远卡住。在 UI 线程上调会死锁，直接返回 {@link #ARIA2_CONTINUE}。
     *
     * @param act      宿主 Activity
     * @param fileName 失败的资源包名（展示给玩家看）
     * @param canRetry 是否还能给「重试备用引擎」这一项（重试次数用尽时传 false）
     * @return {@link #ARIA2_RETRY} / {@link #ARIA2_CONTINUE} / {@link #ARIA2_OFFLINE}
     */
    public static int askAria2Fallback(final Activity act, final String fileName,
                                       final boolean canRetry) {
        return askDownloadFallback(act, fileName, canRetry, true);
    }

    /**
     * 下载失败时<b>问玩家</b>接下来怎么办的通用入口。三个失败点共用它：
     * 备用引擎失败、主引擎重试用尽、热更新包下载失败。
     *
     * <p>比起闷头重试，把取舍摆到台面上更对——玩家是唯一知道「现在这条网到底
     * 怎么了」的人。而且这三处的可选项本来就是同一组：换引擎、降并发、拿离线包。
     *
     * @param aria2Failed true=失败的是备用引擎（aria2），false=失败的是主引擎/热更
     */
    public static int askDownloadFallback(final Activity act, final String fileName,
                                          final boolean canRetry,
                                          final boolean aria2Failed) {
        return askDownloadFallback(act, fileName, canRetry, aria2Failed, true);
    }

    /**
     * @param offerOffline 给不给「改用离线包」这一项。热更两包（scenario/js）走
     *     版本 JSON 通道，<b>离线导入根本不覆盖它们</b>——给了就是个死路按钮，
     *     玩家点进去发现列表里没有这个包，只会更慌。
     */
    public static int askDownloadFallback(final Activity act, final String fileName,
                                          final boolean canRetry,
                                          final boolean aria2Failed,
                                          final boolean offerOffline) {
        if (act == null || overlayView == null) {
            CNLog.w(TAG, "[下载询问] 浮层不在，按「继续用主引擎」处理：" + fileName);
            return ARIA2_CONTINUE;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            CNLog.e(TAG, "[下载询问] 被在 UI 线程上调用，会死锁；按「继续用主引擎」处理");
            return ARIA2_CONTINUE;
        }
        final Aria2Answer ans = new Aria2Answer();
        try {
            act.runOnUiThread(new Aria2Build(act, fileName, canRetry, aria2Failed,
                    offerOffline, ans));
            if (!ans.latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                CNLog.w(TAG, "[aria2询问] 60 秒未选择，按「继续用主引擎」处理：" + fileName);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ARIA2_CONTINUE;
        } catch (Throwable t) {
            CNLog.e(TAG, "[aria2询问] 建框失败，按「继续用主引擎」处理", t);
            return ARIA2_CONTINUE;
        }
        return ans.choice[0];
    }

    /** 在 UI 线程上把询问框建出来。建不出来就立刻放行调用线程，别把它吊死。 */
    private static final class Aria2Build implements Runnable {
        private final Activity act;
        private final String fileName;
        private final boolean canRetry;
        private final boolean aria2Failed;
        private final boolean offerOffline;
        private final Aria2Answer ans;
        Aria2Build(Activity act, String fileName, boolean canRetry,
                   boolean aria2Failed, boolean offerOffline, Aria2Answer ans) {
            this.act = act; this.fileName = fileName; this.canRetry = canRetry;
            this.aria2Failed = aria2Failed; this.offerOffline = offerOffline; this.ans = ans;
        }
        @Override public void run() {
            try { buildAria2Dialog(act, fileName, canRetry, aria2Failed, offerOffline, ans); }
            catch (Throwable t) {
                CNLog.e(TAG, "[aria2询问] 构建失败，按「继续用主引擎」处理", t);
                ans.latch.countDown();
            }
        }
    }

    /** 与慢网/教程询问框同一套样式：同样的调色板、圆角、按钮，宿主是引擎 Activity。 */
    private static void buildAria2Dialog(final Activity act, String fileName,
                                         boolean canRetry, boolean aria2Failed,
                                         boolean offerOffline, Aria2Answer ans) {
        FrameLayout host = overlayView;
        if (host == null || aria2AskModal != null) {   // 浮层没了 / 已开着一个询问
            ans.latch.countDown();
            return;
        }
        final FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(COLOR_DIM);
        modal.setClickable(true);        // 吃掉点击：这是必须做出的选择，
        modal.setFocusable(true);        // 不许点框外糊弄过去

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(COLOR_LOG_PANEL_BG);
        panelBg.setCornerRadius(dp(act, 16));
        panelBg.setStroke(dp(act, 1), COLOR_CARD_STK);
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(act, 330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(act, 20);
        modal.addView(panel, panelLp);

        // 单线程已经开着（或被调试开关/云端强制）时不再给这一项——给了也没用，
        // 只会让人点完发现「还是一样失败」，然后不再相信这个框里的任何按钮。
        final boolean offerSingle = !CNDownloadMode.singleThread();
        // 已经在单线程、且是玩家自己选的 → 给回头路。被调试开关/云端强制时不给：
        // 那两层玩家关不掉，摆个关不掉的按钮只会让人以为按钮坏了。
        final boolean offerMulti = CNDownloadMode.singleThread()
                && !CNDownloadMode.forcedOn();

        TextView title = new TextView(act);
        title.setText(aria2Failed ? "备用引擎下载失败" : "下载失败");
        title.setTextColor(COLOR_ACCENT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, lpRow(0, dp(act, 10)));

        StringBuilder body = new StringBuilder();
        body.append(aria2Failed
                ? "备用下载引擎（aria2）下载「" + fileName + "」失败。\n\n"
                : "「" + fileName + "」多次下载失败。\n\n");
        body.append("接下来怎么办：\n");
        body.append(aria2Failed
                ? "· 「继续用主引擎下载」：改用分块下载引擎重新下载。\n"
                : "· 「再试一次」：换条线路重新下载。\n");
        if (offerSingle) {
            // 这一项要说清楚「为什么会有用」。玩家看不出「单线程」和「重试」
            // 的区别时，只会当成又一个重试按钮，那它就白加了。
            body.append("· 「改用单线程下载」：只开一条连接，慢但稳。"
                      + "运营商限并发、老路由器、公共 Wi-Fi 上多线程会一直失败，"
                      + "这时只有它管用。\n");
        }
        if (canRetry) {
            body.append(aria2Failed
                    ? "· 「重试备用引擎」：可能只是临时故障，再试一次。\n"
                    : "· 「改用备用引擎」：换 aria2 引擎试试。\n");
        }
        if (offerOffline) {
            body.append("· 「改用离线包」：打开离线包页面，手动下载后导入。\n");
        }
        if (offerMulti) {
            body.append("· 「改回多线程下载」：当前是单线程；网络已经好转的话可以换回来。\n");
        }
        if (!offerSingle) {
            body.append("\n（当前下载模式：" + CNDownloadMode.describe() + "）");
        }
        body.append("\n\n");
        body.append("都不会损坏存档，也不影响账号。");

        TextView msg = new TextView(act);
        msg.setText(body.toString());
        msg.setTextColor(COLOR_LOG_PANEL_TEXT);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        msg.setLineSpacing(dp(act, 2), 1f);
        panel.addView(msg, lpRow(0, dp(act, 18)));

        // 纵向全宽排布，主钮实心。项数随可用性变化（1～4 项）
        TextView cont = dialogButton(act,
                aria2Failed ? "继续用主引擎下载" : "再试一次",
                0xFFFFFFFF, COLOR_ACCENT, false);
        LinearLayout.LayoutParams contLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contLp.topMargin = dp(act, 18);
        panel.addView(cont, contLp);
        cont.setOnClickListener(new Aria2Choice(ARIA2_CONTINUE, ans));

        if (offerSingle) {
            TextView single = dialogButton(act, "改用单线程下载（慢但稳）",
                    COLOR_LOG_PANEL_TEXT, 0x00000000, true);
            LinearLayout.LayoutParams singleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            singleLp.topMargin = dp(act, 10);
            panel.addView(single, singleLp);
            single.setOnClickListener(new Aria2Choice(DL_SINGLE, ans));
        }

        if (offerMulti) {
            TextView multi = dialogButton(act, "改回多线程下载",
                    COLOR_LOG_PANEL_TEXT, 0x00000000, true);
            LinearLayout.LayoutParams multiLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            multiLp.topMargin = dp(act, 10);
            panel.addView(multi, multiLp);
            multi.setOnClickListener(new Aria2Choice(DL_MULTI, ans));
        }

        if (canRetry) {
            TextView retry = dialogButton(act,
                    aria2Failed ? "重试备用引擎" : "改用备用引擎",
                    COLOR_LOG_PANEL_TEXT, 0x00000000, true);
            LinearLayout.LayoutParams retryLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            retryLp.topMargin = dp(act, 10);
            panel.addView(retry, retryLp);
            retry.setOnClickListener(new Aria2Choice(ARIA2_RETRY, ans));
        }

        if (offerOffline) {
            TextView offline = dialogButton(act, "改用离线包",
                    COLOR_LOG_PANEL_TEXT, 0x00000000, true);
            LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            offLp.topMargin = dp(act, 10);
            panel.addView(offline, offLp);
            offline.setOnClickListener(new Aria2Choice(ARIA2_OFFLINE, ans));
        }

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        aria2AskModal = modal;
    }

    /** 记下选择 → 关框 → 放行等在后台线程上的调用方。 */
    private static final class Aria2Choice implements View.OnClickListener {
        private final int choice; private final Aria2Answer ans;
        Aria2Choice(int choice, Aria2Answer ans) { this.choice = choice; this.ans = ans; }
        @Override public void onClick(View v) {
            try {
                ans.choice[0] = choice;
                CNLog.i(TAG, "[下载询问] 玩家选择："
                        + (choice == ARIA2_RETRY ? "备用引擎"
                           : choice == ARIA2_OFFLINE ? "改用离线包"
                           : choice == DL_SINGLE ? "改用单线程下载"
                           : choice == DL_MULTI ? "改回多线程下载" : "继续用主引擎"));
                // 模式在**这里**切，不留给每个调用方各切一次：三个失败点都要用
                // 这一项，分散写迟早会漏掉一个，而漏掉的表现是「点了没反应」。
                if (choice == DL_SINGLE) CNDownloadMode.setPlayerChoice(true);
                else if (choice == DL_MULTI) CNDownloadMode.setPlayerChoice(false);
                closeAria2AskDialog();
            } catch (Throwable t) {
                CNLog.e(TAG, "[aria2询问] 处理选择失败", t);
            } finally {
                ans.latch.countDown();   // 无论如何都要放行，否则后台线程永远卡在这
            }
        }
    }

    private static void closeAria2AskDialog() {
        FrameLayout m = aria2AskModal;
        aria2AskModal = null;
        if (m != null && m.getParent() instanceof ViewGroup) {
            ((ViewGroup) m.getParent()).removeView(m);
        }
        noteInteraction();
    }

    // ==================================================================
    // 强制更新弹窗（客户端版本检查）
    // ==================================================================

    /** 强制更新弹窗的模态框。非空即表示正在显示，用于防重入。 */
    private static FrameLayout versionModal;

    /**
     * 强制更新弹窗：云端客户端版本高于本端时由 {@link CNVersionCheck} 调用。
     * 模态、不可点框外关闭——玩家的去路只有「前往更新」（调起系统浏览器）和
     * 「退出游戏」两条；下次启动还会再查再拦，这就是「强制」的含义。
     *
     * <p>用浮层自己的调色板与圆角，与教程询问框同一套模态框样式；宿主是引擎的
     * Activity，系统 AlertDialog 在上面格格不入。
     *
     * <p>可在任意线程调用，内部会切到 UI 线程。浮层没建起来时无处可挂，记日志
     * 了事（版本检查的日志里已有完整的版本与地址信息）。
     *
     * @param local  本端版本（native 内置）
     * @param cloud  云端版本（config.json 的 client.version）
     * @param url    新包下载地址（client.apk_url）
     * @param note   云端附言（client.note，可为空串）
     */
    public static void showVersionUpdateDialog(final Activity act, final String local,
                                               final String cloud, final String url,
                                               final String note) {
        final FrameLayout host = overlayView;
        if (act == null || host == null) {
            CNLog.w("界面", "浮层不在，无法显示强制更新框");
            return;
        }
        act.runOnUiThread(new Runnable() {
            @Override public void run() {
                try { buildVersionUpdateDialog(act, host, local, cloud, url, note); }
                catch (Throwable t) { CNLog.e("界面", "构建强制更新框失败", t); }
            }
        });
    }

    /** 在 UI 线程上真正把强制更新框建出来。 */
    private static void buildVersionUpdateDialog(final Activity act, FrameLayout host,
                                                 String local, String cloud,
                                                 final String url, String note) {
        if (versionModal != null) return;      // 已经开着，别叠第二层

        final FrameLayout modal = new FrameLayout(act);
        modal.setBackgroundColor(COLOR_DIM);
        modal.setClickable(true);              // 吃掉点击，不许点框外关掉
        modal.setFocusable(true);

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(act, 22), dp(act, 20), dp(act, 22), dp(act, 18));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(COLOR_LOG_PANEL_BG);
        panelBg.setCornerRadius(dp(act, 16));
        panelBg.setStroke(dp(act, 1), COLOR_CARD_STK);
        panel.setBackground(panelBg);
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                dp(act, 330), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        panelLp.leftMargin = panelLp.rightMargin = dp(act, 20);
        modal.addView(panel, panelLp);

        TextView title = new TextView(act);
        title.setText("客户端更新");
        title.setTextColor(COLOR_ACCENT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        panel.addView(title, lpRow(0, dp(act, 10)));

        TextView msg = new TextView(act);
        String text = "发现新版本客户端：v" + cloud + "（当前 v" + local + "）\n\n"
                + "客户端版本过旧，继续游戏可能无法正常运行，请下载并安装最新版本。\n\n"
                + "· 「前往更新」：打开浏览器下载新包（覆盖安装即可，数据不丢）\n"
                + "· 「退出游戏」：本次不玩，下次启动会再次提醒";
        if (note != null && !note.isEmpty()) text += "\n\n" + note;
        msg.setText(text);
        msg.setTextColor(COLOR_LOG_PANEL_TEXT);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        msg.setLineSpacing(dp(act, 2), 1f);
        // 消息区包进 ScrollView，高度按屏幕物理像素封顶：系统字体调大时 SP 字号
        // 等比放大、文本变高，不封顶会把面板撑出屏幕、把下方按钮挤出可视区。
        // ScrollView 只滚动消息，标题与按钮始终留在面板内。
        ScrollView msgScroll = new ScrollView(act);
        msgScroll.setVerticalScrollBarEnabled(false);
        msgScroll.setFillViewport(false);
        LinearLayout.LayoutParams msgSvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msgSvLp.bottomMargin = dp(act, 18);
        // 高度上限：屏幕物理高度的一半，最多 320dp。拿 StaticLayout 量出的自然高度
        // 与上限取小——正常字号没有空隙，超大字体才触发滚动、按钮始终留在面板内。
        android.util.DisplayMetrics dmm = act.getResources().getDisplayMetrics();
        int screenCap = Math.max(dp(act, 120), Math.min(dp(act, 320),
                (int) (dmm.heightPixels * 0.5f)));
        int msgCap = screenCap;
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                android.text.TextPaint tp = new android.text.TextPaint(msg.getPaint());
                tp.setTextSize(msg.getTextSize());
                int naturalW = Math.max(1, dp(act, 330) - dp(act, 44));
                android.text.StaticLayout sl = new android.text.StaticLayout(
                        msg.getText(), tp, naturalW,
                        android.text.Layout.Alignment.ALIGN_NORMAL, 1.0f,
                        dp(act, 2), false);
                int natural = sl.getHeight() + dp(act, 6);
                if (natural < msgCap) msgCap = Math.max(natural, dp(act, 40));
            } catch (Throwable ignore) {}
        }
        msgSvLp.height = msgCap;
        msgScroll.setLayoutParams(msgSvLp);
        msgScroll.addView(msg, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(msgScroll);

        // 系统字体放大到 1.2x 以上时两个按钮并排会超出面板宽（各带 26dp 内边距、
        // SP 字号放大），改成竖排避免被面板裁掉、点不到。
        float fontScale = act.getResources().getConfiguration().fontScale;
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(fontScale >= 1.2f
                ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        panel.addView(row, lpRow(0, 0));

        TextView quit = dialogButton(act, "退出游戏", COLOR_LOG_PANEL_TEXT, 0x00000000, true);
        TextView go   = dialogButton(act, "前往更新", 0xFFFFFFFF, COLOR_ACCENT, false);
        if (fontScale >= 1.2f) {
            LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            q-hostMargin = dp(act, 10);
            LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gLp.topMargin = dp(act, 10);
            row.addView(quit, qLp);
            row.addView(go, gLp);
        } else {
            LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            goLp.leftMargin = dp(act, 10);
            row.addView(quit, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(go, goLp);
        }

        quit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CNLog.i("界面", "玩家在强制更新框选择退出游戏");
                try { act.finishAffinity(); }
                catch (Throwable t) {
                    try { act.finish(); } catch (Throwable ignore) {}
                }
            }
        });
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CNLog.i("界面", "玩家在强制更新框选择前往更新: " + url);
                // apk_url 同样是云端下发的。这一处尤其要卡死：玩家在这个框里
                // 是被明确引导去「装一个包」的，跳到哪里就装哪里的东西。
                CNSafeLink.open(act, url, "强制更新");
            }
        });

        host.addView(modal, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        versionModal = modal;
        CNLog.i("界面", "强制更新框已显示：本端 v" + local + " → 云端 v" + cloud);
    }

    /**
     * 造一个显示开关胶囊并挂到 {@code row} 上。
     *
     * @param which 0=下载状态块 1=logcat 2=原生日志
     */
    private static void addLogChip(Activity act, LinearLayout row,
            String label, String prefKey, int which) {
        LogChip chip = new LogChip(act, label, prefKey, which);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(act, 8);
        row.addView(chip.view(), lp);
    }

    /**
     * 日志来源开关的胶囊。
     *
     * <p>选中＝强调色描边 + 同色半透明填充 + 同色文字；未选＝细描边空心 + 次要
     * 文字色。前缀的 ✓／○ 不是装饰：开与关只靠颜色区分，在色觉异常或强光下
     * 分不出来，加个形状差异就稳了。
     */
    private static final class LogChip implements View.OnClickListener {
        private final Activity act;
        private final TextView view;
        private final String   label;
        private final String   prefKey;
        private final int      which;
        private boolean on;

        LogChip(Activity act, String label, String prefKey, int which) {
            this.act = act; this.label = label;
            this.prefKey = prefKey; this.which = which;

            boolean init = true;
            try {
                init = act.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                          .getBoolean(prefKey, true);
            } catch (Throwable ignore) {}
            this.on = init;

            TextView t = new TextView(act);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(act, 12), dp(act, 5), dp(act, 12), dp(act, 5));
            t.setOnClickListener(this);
            this.view = t;

            applyLogToggle(which, on);
            restyle();
        }

        TextView view() { return view; }

        private void restyle() {
            view.setText((on ? "✓ " : "○ ") + label);
            view.setTextColor(on ? COLOR_ACCENT : COLOR_SUB);

            GradientDrawable bg = new GradientDrawable();
            // 半高圆角：给个远大于控件高度的值，系统会自己收敛成胶囊
            bg.setCornerRadius(dp(act, 100));
            if (on) {
                // 强调色的 20% 填充：既能一眼看出选中，又不会跟右上角那两个
                // 实心动作按钮抢层级——这三个只是过滤器，不是主操作。
                bg.setColor((COLOR_ACCENT & 0x00FFFFFF) | 0x33000000);
                bg.setStroke(dp(act, 1), COLOR_ACCENT);
            } else {
                bg.setColor(0x00000000);
                bg.setStroke(dp(act, 1), COLOR_GLASS_STK);
            }
            view.setBackground(bg);
        }

        @Override public void onClick(View v) {
            on = !on;
            try {
                act.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                   .edit().putBoolean(prefKey, on).apply();
            } catch (Throwable ignore) {}
            applyLogToggle(which, on);
            restyle();
            CNLog.i("界面", "日志开关 " + prefKey + " = " + on);
            renderLogModal();      // 立即生效：过滤发生在渲染期，不必等新日志
        }
    }

    /**
     * 应用一个开关。<b>只改显示，绝不碰采集。</b>
     *
     * <p>这三个开关是**渲染期的过滤器**，不是采集开关。原先的实现「两个都关就
     * 停掉采集线程」，与这个定位自相矛盾，而且真机上造成过一次完整的诊断失败：
     *
     * <pre>
     *   22:36:09  logcat 回收已启动                  ← CNLog.init() 起线程
     *   22:36:09  下载浮层已创建                      ← 本方法按上次保存的开关恢复
     *   22:36:09  logcat 回收不可用: read interrupted by close()
     * </pre>
     *
     * 玩家上一次把两个开关关掉，状态存进了 SharedPreferences；这一次浮层一建起来
     * 就把采集线程杀了。于是**整个会话的 native 日志全部丢失**——`[proxy]`、
     * `[font]`、引擎报错、崩溃栈，一条都没落盘。而日志文件看上去是「正常结束」的，
     * 没人会想到是被自己的开关掐掉的。
     *
     * <p>现在：采集由 {@link CNLog#init} 起、跑到进程结束（体积上限那条另说），
     * 开关只决定面板里显不显示。副作用是关掉再打开能立刻看到这期间的日志——
     * 原先那样是补不回来的。
     */
    private static void applyLogToggle(int which, boolean on) {
        if (which == 0) {
            showStatusBlock = on;
        } else if (which == 1) {
            CNLog.setShowLogcat(on);
        } else {
            CNLog.setShowNative(on);
        }
    }

    /** 面板里最多渲染多少行日志。缓冲区本身仍保留 3000 行，供「复制全部」。 */
    private static final int PANEL_LOG_LINES = 300;

    /**
     * LOG 面板显示的内容：文件安装状态 + 运行日志的**尾部**。
     *
     * @param full true 时取全部日志（供「复制全部」），false 时只取尾部（供渲染）
     */
    private static String composeLogText(boolean full) {
        StringBuilder sb = new StringBuilder();
        if (full) {
            // 复制出去的内容带上文件位置，便于对照落盘的完整日志
            sb.append("日志文件：").append(CNLog.currentLogPath()).append('\n');
            sb.append("日志目录：").append(CNLog.logDirPath())
              .append("（保留最近若干次启动）\n");
            sb.append("本次为第 ").append(CNLog.launchSeq()).append(" 次启动\n\n");
        }
        if (showStatusBlock) {
            sb.append(buildStatusText());
            sb.append("\n──────── 运行日志 ────────\n");
        }
        String log = full ? CNLog.snapshot() : CNLog.tail(PANEL_LOG_LINES);
        if (log.length() == 0) {
            sb.append("（暂无日志；若已关闭 logcat 与原生日志，这里只会有本补丁自己的记录）\n");
        } else {
            int vis = CNLog.visibleSize();
            if (!full && vis > PANEL_LOG_LINES) {
                sb.append("（仅显示最近 ").append(PANEL_LOG_LINES).append(" 行，共 ")
                  .append(vis).append(" 行；「复制全部」可取完整日志）\n");
            }
            sb.append(log);
        }
        return sb.toString();
    }

    /**
     * 面板里渲染成「来源徽章 + 时刻 + 正文」，级别高的上色。
     *
     * <h3>为什么不再是原样文本</h3>
     *
     * 原先把 {@link #composeLogText} 拼出来的一大坨直接 setText。真机上 logcat
     * 一秒能灌几百行，看到的就是<b>满屏等宽字在飞</b>——开发的人也只能靠肉眼扫
     * 有没有红字，普通玩家更是只看得见字在动。而这个面板的用途恰恰是「把现场
     * 发给客服」，看不懂就等于没有。
     *
     * <h3>为什么是 Spannable，不是一行一个 View</h3>
     *
     * 一行一个 View 更接近设计稿（能画真圆角徽章），但 300 行 × 3 个 View = 900 个
     * View 每 250ms 重排一次，必炸。这个面板<b>已经因为渲染太重卡死过一次</b>
     * （见 {@link #LOG_REFRESH_MS} 上方那段注释），不能再来一遍。
     *
     * <p>所以走单个 TextView + span：布局开销与原来的纯文本同量级，只多了每行
     * 三四个 span。徽章的「内边距」用空格凑，等宽字体下够齐。
     */
    private static CharSequence composeLogSpans() {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (showStatusBlock) {
            sb.append(buildStatusText());
            sb.append("\n──────── 运行日志 ────────\n");
        }
        java.util.List<CNLog.Line> rows = CNLog.tailRows(PANEL_LOG_LINES);
        if (rows.isEmpty()) {
            sb.append("（暂无日志；若已关闭 logcat 与原生日志，这里只会有本补丁自己的记录）\n");
            return sb;
        }
        int vis = CNLog.visibleSize();
        if (vis > PANEL_LOG_LINES) {
            sb.append("（仅显示最近 ").append(String.valueOf(PANEL_LOG_LINES))
              .append(" 行，共 ").append(String.valueOf(vis))
              .append(" 行；「复制全部」可取完整日志）\n");
        }
        for (int i = 0; i < rows.size(); i++) {
            CNLog.Line row = rows.get(i);
            appendLogRow(sb, CNLogFormat.parse(row.src, row.text));
        }
        return sb;
    }

    /** 渲染一行。span 数量刻意压到最少——每行多一个，300 行就是多 300 个。 */
    private static void appendLogRow(android.text.SpannableStringBuilder sb,
                                     CNLogFormat.Parsed p) {
        int badgeStart = sb.length();
        sb.append(' ').append(p.badge).append(' ');
        sb.setSpan(new android.text.style.BackgroundColorSpan(badgeColor(p.badge)),
                badgeStart, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF),
                badgeStart, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        if (p.time.length() > 0) {
            int t0 = sb.length();
            sb.append(' ').append(p.time);
            sb.setSpan(new android.text.style.ForegroundColorSpan(COLOR_LOG_TIME),
                    t0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        int textStart = sb.length();
        sb.append(' ');
        // 组件名只在本补丁自己的日志里显示：logcat 的 tag 大多是噪音
        // （chromium、ActivityManager…），占了宽度又帮不上忙。
        if (CNLogFormat.BADGE_APP.equals(p.badge) && p.comp.length() > 0) {
            sb.append(p.comp).append(": ");
        }
        sb.append(p.text.length() > 0 ? p.text : "(空行)");
        if (CNLogFormat.isBad(p.level)) {
            int color = CNLogFormat.isFatal(p.level) ? COLOR_LOG_ERROR : COLOR_LOG_WARN;
            sb.setSpan(new android.text.style.ForegroundColorSpan(color),
                    textStart, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sb.append('\n');
    }

    private static int badgeColor(String badge) {
        if (CNLogFormat.BADGE_APP.equals(badge)) return COLOR_LOG_BADGE_APP;
        if (CNLogFormat.BADGE_NATIVE.equals(badge)) return COLOR_LOG_BADGE_NATIVE;
        return COLOR_LOG_BADGE_LOGCAT;
    }

    /** 把最新内容刷进面板；仅在面板可见时做，避免无谓的字符串拼接。 */
    private static void renderLogModal() {
        if (logModal == null || tvLog == null) return;
        if (logModal.getVisibility() != View.VISIBLE) return;
        // 渲染出错也不能让面板空着——退回原样文本，起码内容还在
        try {
            tvLog.setText(composeLogSpans());
        } catch (Throwable t) {
            tvLog.setText(composeLogText(false));
        }
    }

    private static void openLogModal() {
        if (logModal == null) return;
        logAutoScroll = true;          // 每次打开都从底部开始看
        logModal.setVisibility(View.VISIBLE);
        renderLogModal();
        if (vLogScroll != null) {
            vLogScroll.post(new ScrollToBottom());
        }
    }

    private static void closeLogModal() {
        if (logModal != null) logModal.setVisibility(View.GONE);
        noteInteraction();
    }

    /**
     * 待刷新标记。logcat 一秒能灌进来几百行，若每行都 post 一次渲染，主线程
     * 就会被成百上千次大文本重排压死（表现为打开 LOG 面板即掉帧/卡死）。
     * 这里把它们合并成「最多每 {@value #LOG_REFRESH_MS} 毫秒渲染一帧」。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean LOG_DIRTY =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long LOG_REFRESH_MS = 250L;

    /** 请求刷新日志面板（可从任意线程调用，自动合并）。 */
    private static void scheduleLogRefresh() {
        Handler h = uiHandler;
        if (h == null || logModal == null) return;
        if (logModal.getVisibility() != View.VISIBLE) return;
        if (LOG_DIRTY.compareAndSet(false, true)) {
            h.postDelayed(new RenderLog(), LOG_REFRESH_MS);
        }
    }

    /**
     * {@link CNLog} 的缓冲区变更回调。日志可能来自任意下载线程，所以要切回主线程
     * 再碰视图；面板不可见时直接跳过。
     */
    private static final class LogChanged implements Runnable {
        @Override public void run() {
            scheduleLogRefresh();
        }
    }

    /**
     * 是否保持吸底。
     *
     * <p>早先是在每次渲染**之前**用几何关系临时判断「当前是不是在底部」，再决定
     * 渲染后要不要滚下去。问题是那次判断用的是**旧内容**的高度，而 setText 之后
     * 高度立刻就变了；再加上 ScrollView 在内容变化时会把 scrollY 夹回合法范围，
     * 判定几乎总是落空——表现就是根本不吸底。
     *
     * <p>改为记录**用户意图**：默认吸底；用户手动往上滚就关掉；滚回底部再打开。
     * 渲染后只看这个标记，不再依赖时序敏感的几何判断。
     */
    private static boolean logAutoScroll = true;

    private static final class RenderLog implements Runnable {
        @Override public void run() {
            LOG_DIRTY.set(false);
            renderLogModal();
            if (logAutoScroll && vLogScroll != null) {
                vLogScroll.post(new ScrollToBottom());
            }
        }
    }

    /** 监听用户滚动，维护 {@link #logAutoScroll}。 */
    private static final class LogScrollWatcher
            implements android.view.ViewTreeObserver.OnScrollChangedListener {
        @Override public void onScrollChanged() {
            try {
                ScrollView sv = vLogScroll;
                if (sv == null || sv.getChildCount() == 0) return;
                View content = sv.getChildAt(0);
                int rest = content.getHeight() - sv.getHeight() - sv.getScrollY();
                // 距底部一屏的 1/6 以内都算「还在底部」，给手指一点容差
                logAutoScroll = rest <= Math.max(dpStatic(32), sv.getHeight() / 6);
            } catch (Throwable ignore) {}
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
                if (dv == null) return;

                // 先按 tag 认领**已在视图树上**的本类浮层：场景切换可能换过
                // decorView 内容，静态 overlayView 与树脱节。若树里已有我们的
                // 浮层，直接认领它即可，绝不再 build 一份——否则会在旧残留层
                // 之上再叠一层（双浮层 bug 的第二条入口，CreateUIRunnable 的
                // 守卫拦不到这里）。
                FrameLayout existing = null;
                for (int i = 0; i < dv.getChildCount(); i++) {
                    View c = dv.getChildAt(i);
                    Object tag = (c == null) ? null : c.getTag();
                    if (tag instanceof Integer && (Integer) tag == TAG_OVERLAY
                            && c instanceof FrameLayout) {
                        existing = (FrameLayout) c;
                        break;
                    }
                }
                if (existing != null) {
                    overlayView = existing;
                    decorView   = dv;
                    isShowing   = true;
                    CNDownloadUiAssist.ensureInstalled();
                    CNLog.w("界面", "认领已在视图树上的浮层，跳过重建");
                    return;
                }

                if (ov != null) {
                    // 仅仅是脱离了父节点：直接挂回去，保留现有状态
                    try { dv.addView(ov, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT)); } catch (Throwable ignore) {}
                    decorView = dv;
                    CNDownloadUiAssist.ensureInstalled();
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
                CNDownloadUiAssist.ensureInstalled();
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
            CNDownloadUiAssist.ensureInstalled();
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
        scheduleLogRefresh();

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
                    // 本轮未检查的槽位**分子分母都不计**：把 13 个基础包（体积
                    // 占绝大头）算进分母，热更那两个小包再怎么动，进度条也基本
                    // 不动——玩家会以为卡住了。本轮进度就只该反映本轮的事。
                    if (status[i] == 4) continue;
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
            synchronized (PROGRESS_LOCK) {
                if (overall < overallProgressHighWater) overall = overallProgressHighWater;
                else overallProgressHighWater = overall;
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
                    case 4:  color = 0x553F51B5;   break;   // 本轮未检查（中性蓝灰）
                    default: color = 0x55888888;   break;   // 等待（灰）
                }
                if (Build.VERSION.SDK_INT >= 21) {
                    sv.bar.setProgressTintList(
                            android.content.res.ColorStateList.valueOf(color));
                }

                sv.retryView.setVisibility(st == 3 ? View.VISIBLE : View.GONE);
                if (st == 2) {
                    sv.infoView.setTextColor(0xFF66BB6A);
                    sv.infoView.setText(size != null && size[i] > 0f
                            ? ("✓ " + formatMb(size[i])) : "✓");
                } else if (st == 3) {
                    sv.infoView.setTextColor(0xFFE53935);
                    sv.infoView.setText("✗");
                } else if (st == 4) {
                    // 中性色、**不打勾**：勾是「本轮确认过」的意思，这里没确认过。
                    sv.infoView.setTextColor(COLOR_SUB);
                    String note = (fileNote != null) ? fileNote[i] : null;
                    if (note == null || note.length() == 0) note = "本轮未检查";
                    sv.infoView.setText(note);
                } else if (st == 1) {
                    sv.infoView.setTextColor(COLOR_SUB);
                    StringBuilder sb = new StringBuilder();
                    float exactPct = pct;
                    if (downloaded != null && size != null && size[i] > 0f) {
                        exactPct = Math.max(exactPct,
                                Math.min(100f, Math.max(0f, downloaded[i] * 100f / size[i])));
                    }
                    sb.append(String.format(Locale.US, "%.1f%%", exactPct));
                    if (downloaded != null && size != null && size[i] > 0f) {
                        sb.append("  ").append(formatMb(downloaded[i]))
                          .append(" / ").append(formatMb(size[i]));
                    }
                    if (speed != null && speed[i] > 0f) {
                        sb.append("  ").append(formatMbps(speed[i]));
                    }
                    sv.infoView.setText(sb.toString());
                } else {
                    // 等待中：只要大小已经探到就显示出来。
                    // 早先这里是空串，于是「还没开始下载的文件不显示大小」——
                    // 即便开跑前已经探完，玩家也看不到，观感上就像没探。
                    sv.infoView.setTextColor(COLOR_SUB);
                    if (size != null && size[i] > 0f) {
                        sv.infoView.setText("等待中 · " + formatMb(size[i]));
                    } else {
                        sv.infoView.setText("等待中");
                    }
                }
            }
        }

        // 汇总：已完成文件数 + 总体积
        if (vAggregate != null && status != null) {
            // 分母只数**本轮涉及**的槽位：把未检查的也算进分母，会得到
            // 「2 / 15 文件」这种看着像坏了的数字，而实际上本轮就只该管 2 个。
            int done = 0, inScope = 0, unchecked = 0;
            for (int i = 0; i < FILE_COUNT; i++) {
                if (status[i] == 4) { unchecked++; continue; }
                inScope++;
                if (status[i] == 2) done++;
            }
            String t = done + " / " + inScope + " 文件";
            if (unchecked > 0) t += "（另 " + unchecked + " 项本轮未检查）";
            vAggregate.setText(t);
        }
        if (vOverallText != null) {
            String text = "总进度";
            if (size != null && downloaded != null && status != null) {
                float totalSize = 0f, totalDone = 0f;
                for (int i = 0; i < FILE_COUNT; i++) {
                    if (status[i] == 4) continue;      // 同上：本轮未检查的不计
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

                // ⚠ 幂等守卫：decorView 上已挂着本类浮层就直接返回，不再叠一层。
                // 旧实现里每次 show() 都无条件 buildOverlay + addView——弱机主线程
                // 繁忙导致 show() 的 3 秒等待超时、isShowing 误判为 false 后，版本检查/
                // 热更的重试循环会再投一份 CreateUIRunnable，同一 decorView 上叠出
                // 多个整屏浮层；而 hide() 只 removeView(overlayView) 摘最上层，
                // 下层不透明浮层残留盖死游戏、marquee 持续制造 WebView 渲染竞争。
                ViewGroup dv = CNCNDownloadUI.decorView;
                if (dv != null) {
                    for (int i = 0; i < dv.getChildCount(); i++) {
                        Object tag = dv.getChildAt(i).getTag();
                        if (tag instanceof Integer && (Integer) tag == TAG_OVERLAY) {
                            return;
                        }
                    }
                }

                hostActivity = activity;
                // 日志落盘目录用应用私有目录；此前安装器已经写入的内容仍在内存
                // 缓冲里，会随第一次刷新一起显示出来
                // 日志已在 native 入口（CNLog.initEarly）开好，这里不要重开：
                // 重开会再分配一次启动序号、另起一个文件，把前半段记录分家。
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
                CNDownloadUiAssist.ensureInstalled();
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
                CNLog.i("界面", "下载浮层关闭（日志继续记录）");
                // stopOverlayFlag() 已在 hide() 里同步删掉标记；现在从 UI 线程
                // 明确投递到 GL 线程释放 deferred top，不再碰运气等下一次文本 hook。
                releaseEngineGate();
                // 先摘掉监听再拆视图，避免拆到一半又被日志回调碰上
                CNLog.setListener(null);
                // ⚠ 这里**不再**停 logcat 捕获、不再关文件。
                //
                // 原先是关掉的，结果日志正好在「浮层收工」这一刻断掉——而我们真正
                // 要看的东西（native 的 [Tutorial] / [SceneCmd]、引擎报错、序章
                // 表现）全都发生在这之后。拿到的日志永远停在游戏还没开始的地方，
                // 等于没有。捕获改为一直跑到进程结束，由 CNLog 自己的体积上限收口。
                ViewGroup dv = CNCNDownloadUI.decorView;
                if (dv != null) {
                    // 摘除**所有**本类浮层，而不是只摘 overlayView 那一个。
                    // 旧实现只 removeView(overlayView)：一旦 show() 重试/并发叠出
                    // 两层，下层不透明浮层永远残留盖在游戏上。按 tag 逆序摘除，
                    // 顺手把「overlayView 已置空但还有残留层」的脏状态一并清理。
                    for (int i = dv.getChildCount() - 1; i >= 0; i--) {
                        View child = dv.getChildAt(i);
                        Object tag = (child == null) ? null : child.getTag();
                        if (tag instanceof Integer && (Integer) tag == TAG_OVERLAY) {
                            dv.removeViewAt(i);
                        }
                    }
                }
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
                vTutorialPill = null;
                tutorialModal = null;
                slowModal     = null;
                vLogScroll    = null;
                themeChipBg   = null;
                logPillBg     = null;
                hostActivity  = null;
                // 这四个此前漏在清理之外。它们和上面那些一样是 static，各自持有
                // Context → Activity，而 static 字段的生命周期是整个进程：浮层收了
                // 之后 Activity 本该能回收，却被它们钉住。vGitHubChip 尤其要清——
                // 它身上挂着 SupportClick，那个监听器里还捏着一个 Activity。
                vGitHubChip   = null;
                githubChipBg  = null;
                supportModal  = null;
                vFooter       = null;
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
            sb.append(st == 2 ? "[OK] " : st == 1 ? "[ > ] " : st == 3 ? "[ERR] "
                      : st == 4 ? "[ - ] " : "[  ] ")
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

    /**
     * 浮层撤掉以后，显式在 Cocos GL 线程通知 native 释放被闸住的主页跳转/BGM。
     *
     * 旧实现只靠后续 Label::setString / LoadingSceneLayerInfo::setText 等 hook
     * 顺带调用 maybeReleaseDeferredTop()。如果浮层恰好在最后一次文本更新之后关闭，
     * 就再也没有回调来补推主页，表现为热更已经结束但游戏永久黑屏。
     */
    private static void releaseEngineGate() {
        final Runnable release = new Runnable() {
            @Override public void run() {
                try {
                    CNLog.i(TAG, "[Overlay] GL thread entered; requesting native deferred release");
                    CNDownloaderFix.nativeReleaseDeferredTop();
                } catch (Throwable t) {
                    CNLog.e(TAG, "[Overlay] native deferred release failed", t);
                }
            }
        };
        try {
            Class<?> helper = Class.forName("org.cocos2dx.lib.Cocos2dxHelper");
            java.lang.reflect.Method m = helper.getMethod("runOnGLThread", Runnable.class);
            m.invoke(null, release);
            CNLog.i(TAG, "[Overlay] native release scheduled via Cocos2dxHelper.runOnGLThread");
            return;
        } catch (Throwable first) {
            CNLog.e(TAG, "[Overlay] Cocos2dxHelper scheduling unavailable; trying Activity.runOnGLThread", first);
        }
        try {
            Activity act = RestClient.getCurrentActivity();
            if (act == null) throw new IllegalStateException("Activity is null");
            java.lang.reflect.Method m = act.getClass().getMethod("runOnGLThread", Runnable.class);
            m.invoke(act, release);
            CNLog.i(TAG, "[Overlay] native release scheduled via Activity.runOnGLThread");
        } catch (Throwable second) {
            // Old setString hooks remain as the last safety net. Do NOT call native scene
            // commands from an arbitrary Java worker/UI thread.
            CNLog.e(TAG, "[Overlay] GL scheduling failed; opportunistic native hook remains fallback", second);
        }
    }

    public static void hide() {
        // 浮层要收了，音乐也得停——否则安装完了背景音还在响。
        // 放在 isShowing 判断之前：即使浮层没建起来，也要保证不会有残留的播放线程。
        stopOverlayFlag();  // 先撤引擎闸门标记，引擎才能继续推进
        try { CNBgm.stop(); } catch (Throwable ignore) {}
        try { CNDownloadUiAssist.onOverlayDetached(); } catch (Throwable ignore) {}
        Handler handler;
        if (!isShowing || (handler = uiHandler) == null) {
            // 即使浮层没真正建成/handler 已丢，也必须释放 native 闸门。
            releaseEngineGate();
            return;
        }
        handler.post(new HideRunnable());
        isShowing = false;
        vBgmPill = null;
        vTutorialPill = null;
        tutorialModal = null;
        slowModal = null;
    }

    /**
     * 把某个槽位标成「本轮未检查」。
     *
     * @param note 给玩家看的说明，如「已装」「版本查询失败」。为空则显示「本轮未检查」。
     */
    public static void markFileUnchecked(int i, String note) {
        int[] status = fileStatus;
        if (status != null && i >= 0 && i < status.length) {
            status[i] = ST_UNCHECKED;
            String[] notes = fileNote;
            if (notes != null && i < notes.length) notes[i] = note;
        }
        throttledUpdate();
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

    /**
     * 把一个已经安装、但本轮确认需要热更新的槽位切回“等待本轮更新”。
     * 其它有有效 marker 的基础资源保持 100% / 已完成，不再在热更新页伪装成 0%。
     */
    public static void markFilePending(int i) {
        if (i < 0 || i >= FILE_COUNT) return;
        resetFileProgress(i);
        Handler handler = uiHandler;
        if (handler != null) handler.post(new UpdateRunnable());
        try { CNLog.i(TAG, "[Hotupdate UI] slot=" + i + " -> pending/downloading"); } catch (Throwable ignore) {}
    }

    public static void setDownloadSpeed(int i, float f) {
        float[] speed = fileSpeed;
        if (speed != null && i >= 0 && i < speed.length) {
            speed[i] = Float.isNaN(f) || Float.isInfinite(f) || f < 0f ? 0f : f;
        }
    }

    public static void setFileDownloaded(int i, float f) {
        synchronized (PROGRESS_LOCK) {
            float[] downloaded = fileDownloaded;
            if (downloaded != null && i >= 0 && i < downloaded.length) {
                float clean = Float.isNaN(f) || Float.isInfinite(f) || f < 0f ? 0f : f;
                if (clean > downloaded[i]) downloaded[i] = clean;
            }
        }
    }

    /** Deliberate user restart/run reset. Normal callbacks are monotonic. */
    public static void resetFileProgress(int i) {
        if (i < 0 || i >= FILE_COUNT) return;
        synchronized (PROGRESS_LOCK) {
            if (fileStatus != null) fileStatus[i] = ST_WAIT;
            if (fileProgress != null) fileProgress[i] = 0;
            if (fileSpeed != null) fileSpeed[i] = 0f;
            if (fileDownloaded != null) fileDownloaded[i] = 0f;
        }
        Handler handler = uiHandler;
        if (handler != null) handler.post(new UpdateRunnable());
    }

    public static void resetOverallProgress() {
        synchronized (PROGRESS_LOCK) { overallProgressHighWater = 0; }
        ProgressBar pb = progressBarOverall;
        if (pb != null) pb.setProgress(0);
    }

    public static void setFileSize(int i, float f) {
        float[] size = fileSize;
        if (size != null) {
            size[i] = f;
        }
    }

    public static void show(Activity activity) {
        if (CNDebugFlags.isOn(CNDebugFlags.SKIP_OVERLAY)) {
            // 浮层不显示 → startOverlayFlag() 不会跑 → native 的引擎闸门标记
            // 也不会下发，引擎从头到尾不被闸住。排查「闸门是否卡住场景跳转」时用。
            CNLog.i(TAG, "调试开关 skipOverlay 生效，不显示浮层（引擎闸门也不下发）");
            return;
        }
        // 早先无条件 return 是个坑：进程未被杀死（如后台回收后重启 Activity）
        // 时 isShowing 可能仍为 true，但 overlayView 已脱离视图树甚至为 null。
        // 这时需要当做未显示来处理，重建浮层。
        if (isShowing) {
            if (overlayView != null && overlayView.getParent() != null) {
                return;  // 确实还在，跳过
            }
            // 状态不一致：标记位还在但视图没了，重置以便重建
            CNLog.w("界面", "isShowing=true 但 overlayView 已脱离，重置状态");
            isShowing = false;
            overlayView = null;
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
            } else {
                startOverlayFlag();
            }
        } catch (Throwable e) {
            isShowing = (overlayView != null);
            CNLog.e("界面", "show() 失败: " + e, e);
        }
    }

    // ---- 浮层激活标记（native 引擎闸门用）----
    //
    // 浮层显示期间，native 侧会闸住引擎的主页跳转和 BGM
    // （见 MagiaLegacy.cpp 的 overlayActive/maybeReleaseDeferredTop）。
    // 这里 show 成功时创建标记文件，每 2 秒心跳 touch 续期；
    // hide 时停止心跳并删除。进程被杀导致心跳中断时，标记 6 秒后自动失效，
    // native 侧自动放行，引擎不会被闸死。
    private static final String OVERLAY_FLAG =
        CNPaths.filesDir() + "/madomagi/cn_overlay_active.flag";
    private static Thread overlayHeartbeat;

    private static void startOverlayFlag() {
        try {
            java.io.File f = new java.io.File(OVERLAY_FLAG);
            java.io.File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            f.createNewFile();
        } catch (Throwable t) {
            CNLog.w("界面", "引擎闸门标记创建失败（引擎将不被闸住）: " + t);
        }
        if (overlayHeartbeat != null && overlayHeartbeat.isAlive()) return;
        overlayHeartbeat = new Thread(new Runnable() {
            @Override public void run() {
                java.io.File f = new java.io.File(OVERLAY_FLAG);
                while (isShowing) {
                    try { f.setLastModified(System.currentTimeMillis()); } catch (Throwable ignore) {}
                    try { Thread.sleep(2000L); } catch (InterruptedException ie) { return; }
                }
            }
        }, "cn-overlay-flag");
        overlayHeartbeat.setDaemon(true);
        overlayHeartbeat.start();
    }

    private static void stopOverlayFlag() {
        Thread t = overlayHeartbeat;
        overlayHeartbeat = null;
        if (t != null) t.interrupt();
        try {
            java.io.File f = new java.io.File(OVERLAY_FLAG);
            boolean existed = f.exists();
            boolean deleted = !existed || f.delete();
            CNLog.i(TAG, "[Overlay] flag delete requested existed=" + existed
                    + " deleted=" + deleted + " path=" + OVERLAY_FLAG);
        } catch (Throwable th) {
            CNLog.e(TAG, "[Overlay] flag delete failed", th);
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
        synchronized (PROGRESS_LOCK) {
            int[] progress = fileProgress;
            if (progress != null && i >= 0 && i < progress.length) {
                int clean = Math.max(0, Math.min(100, i2));
                if (clean > progress[i]) progress[i] = clean;
                int[] status = fileStatus;
                if (status != null && status[i] != ST_DONE) fileStatus[i] = ST_RUNNING;
            }
        }
        throttledUpdate();
    }

    /**
     * 两参便捷重载。{@code CNDownloaderFix.probeAllSizes()} 用的是这个签名，
     * 但此前只存在三参版本——当前 main 因此编译不过。百分比参数本就未被使用
     * （见三参版本），这里补一个重载而不是改调用点，改动面最小。
     */
    public static void updateSimple(String str, String str2) {
        updateSimple(str, str2, 0);
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
