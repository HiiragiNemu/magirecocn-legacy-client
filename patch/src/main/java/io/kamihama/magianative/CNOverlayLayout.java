package io.kamihama.magianative;

/**
 * 下载浮层的**布局决策**——只做算术，不碰任何视图。
 *
 * <p>契约与理由见 {@code docs/DOWNLOAD_OVERLAY_TABLET_LAYOUT.md}，本文件是它的
 * 可执行部分。三条硬判据：
 *
 * <ol>
 *   <li><b>内容永不超出视口。</b>不允许横向滚动。字号只改字号，不改任何容器宽度。</li>
 *   <li><b>可触区域永远在可见区域内。</b>每一行的操作按钮不滚动就够得到。</li>
 *   <li><b>屏幕尺寸只影响布局，不影响语义。</b>手机与平板表达同一件事。</li>
 * </ol>
 *
 * <h3>为什么单独抽一个类出来</h3>
 *
 * 浮层是纯程序化视图，尺寸决定散在建视图的代码里，只有装到设备上才看得出对错，
 * 而平板设备不是随时有的。把决策抽成**不依赖 Context 的纯函数**之后，
 * 「算得对不对」可以在 JVM 上直接测（{@code tools/OverlayLayoutTest.java}），
 * 剩下「看起来对不对」才需要真机或模拟器。
 *
 * <p>另一个作用是消灭散落的尺寸来源。此前 {@code widthPixels} 在好几处各读一次，
 * 其中一处算出来的值甚至没人读——多个来源迟早互相对不上，而对不上的表现是
 * 「两列按错的总宽分家」，不会有任何报错。**档位与宽度只在这里算一次**。
 *
 * <h3>写法约束</h3>
 *
 * 全部是 static 方法、只收基本类型：这样测试不需要 Context，也不受
 * {@code minSdk 21} 的 API 限制（CLAUDE.md 铁律 3）。不要在这里引入
 * {@code Resources} 或 {@code Configuration}——一旦引入，这个类就再也测不动了。
 */
public final class CNOverlayLayout {

    private CNOverlayLayout() {}

    /* ── 尺寸档 ──────────────────────────────────────────────────────── */

    /** 手机竖屏、平板小分屏。 */
    public static final int COMPACT = 0;
    /** 小平板、折叠屏展开、平板半屏。 */
    public static final int MEDIUM = 1;
    /** 平板横屏、桌面模式。 */
    public static final int EXPANDED = 2;

    /**
     * 断点取业界通用值，不自造。自造的断点通常是「我手上这台正好的那个值」，
     * 换一台就不对，而且没人知道它为什么是那个数。
     */
    public static final int BP_MEDIUM_DP = 600;
    public static final int BP_EXPANDED_DP = 840;

    /**
     * 按 {@code smallestScreenWidthDp} 定档——它描述的是**设备**有多大，
     * 横竖屏切换不变。当前窗口有多宽是另一回事，见
     * {@link #stackVertically(int, int)}。
     */
    public static int sizeClass(int smallestWidthDp) {
        if (smallestWidthDp >= BP_EXPANDED_DP) return EXPANDED;
        if (smallestWidthDp >= BP_MEDIUM_DP) return MEDIUM;
        return COMPACT;
    }

    /* ── 两级决策的第二级：当前窗口放不放得下 ───────────────────────── */

    /**
     * 可用宽度低于这个值就一律纵向堆叠，无论档位多高。
     *
     * <p>这是**保险丝**，不是主逻辑：一部 EXPANDED 平板可以只给浮层一个 400dp 宽的
     * 分屏窗口。没有这一级，平板分屏就会拿着两列方案去挤一个手机宽度的窗口。
     */
    public static final int STACK_BELOW_DP = 600;

    public static boolean stackVertically(int sizeClass, int availableWidthDp) {
        return sizeClass == COMPACT || availableWidthDp < STACK_BELOW_DP;
    }

    /* ── 内容宽度：判据一 ───────────────────────────────────────────── */

    /**
     * 内容宽度**恒等于视口宽度**。
     *
     * <p>这个函数看起来什么都没做，但它是判据一的落点。此前这里是
     * {@code max(viewport, viewport × 字号百分比 / 100)}：字号一超过 100%，内容就比
     * 视口宽，右边被推出屏幕——而每一行的操作按钮恰好在右端。典型 1200dp 宽的平板
     * 拿到 125% 的建议字号，于是右边四分之一永远够不到，只能横向滚动去找。
     *
     * <p>**字号只改字号。** 视口放不下的时候，缩的是内容（换行、堆叠、省略），
     * 不是把内容撑破视口。
     */
    public static int contentWidthPx(int viewportPx) {
        return Math.max(1, viewportPx);
    }

    /* ── 每档的排布 ─────────────────────────────────────────────────── */

    /**
     * 左栏（署名区）默认占比。COMPACT 下两列不成立，返回 0 表示「不分栏」。
     *
     * <p>EXPANDED 比 MEDIUM 更窄是有意的：屏幕变宽时该变宽的是内容主体（文件列表），
     * 署名区是固定长度的东西，跟着一起变宽只会留下大片空白。
     */
    public static int defaultLeftPercent(int sizeClass) {
        if (sizeClass == EXPANDED) return 32;
        if (sizeClass == MEDIUM) return 40;
        return 0;
    }

    /** 手动拖动分界线时的夹紧范围。 */
    public static final int SPLIT_MIN_PERCENT = 20;
    public static final int SPLIT_MAX_PERCENT = 70;

    public static int clampLeftPercent(int percent) {
        if (percent < SPLIT_MIN_PERCENT) return SPLIT_MIN_PERCENT;
        if (percent > SPLIT_MAX_PERCENT) return SPLIT_MAX_PERCENT;
        return percent;
    }

    /**
     * 左栏像素宽。两列宽度**必须是精确像素**：{@code HorizontalScrollView} 用
     * UNSPECIFIED 测量子视图，{@code LinearLayout} 会把 weight 布局改写成
     * WRAP_CONTENT，权重当场失效（见 DOWNLOAD_TRANSACTIONAL_CHUNKS.md 记的根因）。
     *
     * <p>返回值保证 ≥ 1 且 ≤ 可用宽度 − 1：两列都不能是 0 宽，否则该列的内容
     * 直接消失，而这件事不会报错。
     */
    public static int leftColumnPx(int contentWidthPx, int handleWidthPx, int leftPercent) {
        int usable = Math.max(2, contentWidthPx - Math.max(0, handleWidthPx));
        int w = Math.round(usable * clampLeftPercent(leftPercent) / 100f);
        if (w < 1) w = 1;
        if (w > usable - 1) w = usable - 1;
        return w;
    }

    /**
     * 面板最大宽度（dp）。0 表示不设上限、占满。
     *
     * <p>13 寸平板上一行文字横跨整个屏幕，眼睛要从最左扫到最右才读完一行，反而比
     * 手机难读。给面板设上限、两侧留白，是让行长回到可读区间，不是为了好看。
     */
    public static final int PANEL_MAX_DP = 1100;

    public static int panelMaxWidthDp(int sizeClass) {
        return sizeClass == EXPANDED ? PANEL_MAX_DP : 0;
    }

    /** 面板实际宽度（像素）：受最大宽度约束，其余占满。 */
    public static int panelWidthPx(int sizeClass, int availableWidthPx, float density) {
        int max = panelMaxWidthDp(sizeClass);
        if (max <= 0) return Math.max(1, availableWidthPx);
        int maxPx = Math.round(max * density);
        return Math.max(1, Math.min(availableWidthPx, maxPx));
    }

    /** 面板外边距（dp）：屏幕越大留白越多，否则大屏上像一张贴满边框的图。 */
    public static int panelMarginDp(int sizeClass) {
        if (sizeClass == EXPANDED) return 32;
        if (sizeClass == MEDIUM) return 20;
        return 12;
    }

    /* ── 字号 ───────────────────────────────────────────────────────── */

    /**
     * 各档的字号基准（百分比）。
     *
     * <p>EXPANDED 只给到 105%：平板离眼睛远，字确实该大一点，但**大的是字，
     * 不是布局**。105% 是在不触发换行/截断变化的前提下能给的余量；再往上该调的是
     * 行高与间距。此前那个按 dp 宽度线性推、上限 125% 的标度，本意是解决「界面在
     * 大屏上显得小」，实际造成的是内容溢出视口。
     */
    public static int defaultTextScalePct(int sizeClass) {
        return sizeClass == EXPANDED ? 105 : 100;
    }

    /** 手动字号量程。 */
    public static final int TEXT_SCALE_MIN = 75;
    public static final int TEXT_SCALE_MAX = 150;
    /** 表示「玩家从没调过」的哨兵值。 */
    public static final int TEXT_SCALE_UNSET = -1;

    /**
     * 最终字号：玩家调过就用他调的（夹紧到量程），没调过用档位默认。
     *
     * <p>注意这里**不掺系统字号**：系统字号已经体现在 sp 解析出来的基准像素里，
     * 再乘一次就是平方。此前的实现在捕获基准像素之后又乘一遍百分比，系统字号
     * 1.3 的平板于是拿到约 1.6 倍——行高撑大、文件名提前截断，而这是两处各自
     * 「合理」的逻辑叠出来的，单看哪一处都不像有问题。
     */
    public static int resolveTextScalePct(int sizeClass, int savedPct) {
        if (savedPct == TEXT_SCALE_UNSET) return defaultTextScalePct(sizeClass);
        if (savedPct < TEXT_SCALE_MIN) return TEXT_SCALE_MIN;
        if (savedPct > TEXT_SCALE_MAX) return TEXT_SCALE_MAX;
        return savedPct;
    }

    /* ── 弹窗 ───────────────────────────────────────────────────────── */

    public static final int DIALOG_MAX_DP = 560;
    public static final int DIALOG_MIN_DP = 280;
    public static final int DIALOG_GUTTER_DP = 32;

    /**
     * 弹窗宽度：{@code min(560dp, 可用宽度 − 2×32dp)}，且不小于 280dp。
     *
     * <p>此前有六个弹窗各自写死 330 或 360dp：平板上是巨大暗色层中间一张邮票，
     * 平板分屏里反而溢出窗口。宽度只能有一个来源。
     */
    public static int dialogWidthPx(int availableWidthPx, float density) {
        int gutter = Math.round(DIALOG_GUTTER_DP * density);
        int want = availableWidthPx - 2 * gutter;
        int max = Math.round(DIALOG_MAX_DP * density);
        int min = Math.round(DIALOG_MIN_DP * density);
        if (want > max) want = max;
        // 窗口窄到连最小宽度都放不下时，宁可贴边也不要小到读不了；
        // 但绝不能超过可用宽度，否则按钮又跑到窗口外。
        if (want < min) want = Math.min(min, Math.max(1, availableWidthPx));
        return Math.max(1, want);
    }

    /**
     * 弹窗高度上限：按**窗口**高度算，不是屏幕高度。
     *
     * <p>此前用整屏高度乘 0.8，在多窗口下会算出比窗口还高的值，于是对话框底部的
     * 关闭按钮跑到窗口外——而那个上限当初正是为了防这件事加的。
     */
    public static int dialogMaxHeightPx(int windowHeightPx) {
        return Math.max(1, Math.round(Math.max(1, windowHeightPx) * 0.8f));
    }
}
