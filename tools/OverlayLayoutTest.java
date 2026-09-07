import io.kamihama.magianative.CNOverlayLayout;

/**
 * 下载浮层布局决策的算术测试。
 *
 * <p>浮层是纯程序化视图，尺寸算错了只有装到设备上才看得出来，而平板不是随时有的。
 * 决策抽成纯函数之后，「算得对不对」在这里钉死；剩下「看起来对不对」才需要
 * 模拟器截图。契约见 docs/DOWNLOAD_OVERLAY_TABLET_LAYOUT.md。
 *
 * <p>断点两侧各测一格（599/600、839/840）：断点写成 &gt;= 还是 &gt; 是最容易错、
 * 又最不容易被发现的一处——差一格只在恰好那个尺寸的设备上表现出来。
 */
public class OverlayLayoutTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) {
        final float D = 2.0f;   // 常见密度，dp→px 乘 2

        // ── 档位断点 ────────────────────────────────────────────────
        check("360dp 手机是 COMPACT",
                CNOverlayLayout.sizeClass(360) == CNOverlayLayout.COMPACT);
        check("599dp 仍是 COMPACT（断点是闭区间下界）",
                CNOverlayLayout.sizeClass(599) == CNOverlayLayout.COMPACT);
        check("600dp 进 MEDIUM",
                CNOverlayLayout.sizeClass(600) == CNOverlayLayout.MEDIUM);
        check("839dp 仍是 MEDIUM",
                CNOverlayLayout.sizeClass(839) == CNOverlayLayout.MEDIUM);
        check("840dp 进 EXPANDED",
                CNOverlayLayout.sizeClass(840) == CNOverlayLayout.EXPANDED);
        check("1200dp 平板是 EXPANDED",
                CNOverlayLayout.sizeClass(1200) == CNOverlayLayout.EXPANDED);

        // ── 两级决策：档位高但窗口窄，必须降级堆叠 ──────────────────
        check("手机永远堆叠",
                CNOverlayLayout.stackVertically(CNOverlayLayout.COMPACT, 400));
        check("平板全屏不堆叠",
                !CNOverlayLayout.stackVertically(CNOverlayLayout.EXPANDED, 1200));
        check("平板分屏到 400dp 要降级堆叠",
                CNOverlayLayout.stackVertically(CNOverlayLayout.EXPANDED, 400));
        check("平板半屏 599dp 仍降级",
                CNOverlayLayout.stackVertically(CNOverlayLayout.EXPANDED, 599));
        check("平板半屏 600dp 可以两列",
                !CNOverlayLayout.stackVertically(CNOverlayLayout.EXPANDED, 600));

        // ── 判据一：内容宽恒等于视口，字号不参与 ────────────────────
        // 这一条是整份契约的核心。此前是 max(视口, 视口×字号%)，字号一过 100%
        // 内容就比视口宽，右端的操作按钮被推出屏幕。
        check("内容宽 == 视口宽（1836px）",
                CNOverlayLayout.contentWidthPx(1836) == 1836);
        check("内容宽 == 视口宽（1080px）",
                CNOverlayLayout.contentWidthPx(1080) == 1080);
        check("视口为 0 也不返回 0（否则整层不可见）",
                CNOverlayLayout.contentWidthPx(0) >= 1);

        // ── 两列宽度 ────────────────────────────────────────────────
        int handle = Math.round(18 * D);
        int content = 1836;
        int leftMed = CNOverlayLayout.leftColumnPx(
                content, handle, CNOverlayLayout.defaultLeftPercent(CNOverlayLayout.MEDIUM));
        int leftExp = CNOverlayLayout.leftColumnPx(
                content, handle, CNOverlayLayout.defaultLeftPercent(CNOverlayLayout.EXPANDED));
        check("MEDIUM 左栏 40%", Math.abs(leftMed - Math.round((content - handle) * 0.40f)) <= 1);
        check("EXPANDED 左栏 32%", Math.abs(leftExp - Math.round((content - handle) * 0.32f)) <= 1);
        check("屏幕更宽时左栏占比更小（署名区不该跟着摊大）", leftExp < leftMed);
        check("COMPACT 不分栏", CNOverlayLayout.defaultLeftPercent(CNOverlayLayout.COMPACT) == 0);

        // 两列都不能是 0 宽——那一列的内容会直接消失，且不会报错。
        int tinyUsable = Math.max(2, 10 - 50);
        check("内容窄于把手时仍有解",
                CNOverlayLayout.leftColumnPx(10, 50, 40) >= 1
                        && CNOverlayLayout.leftColumnPx(10, 50, 40) <= tinyUsable - 1);
        check("越界占比被夹紧（0%）", CNOverlayLayout.clampLeftPercent(0)
                == CNOverlayLayout.SPLIT_MIN_PERCENT);
        check("越界占比被夹紧（99%）", CNOverlayLayout.clampLeftPercent(99)
                == CNOverlayLayout.SPLIT_MAX_PERCENT);

        // ── 面板最大宽度 ────────────────────────────────────────────
        check("手机不设面板上限",
                CNOverlayLayout.panelMaxWidthDp(CNOverlayLayout.COMPACT) == 0);
        check("小平板不设面板上限",
                CNOverlayLayout.panelMaxWidthDp(CNOverlayLayout.MEDIUM) == 0);
        check("大平板设 1100dp 上限",
                CNOverlayLayout.panelMaxWidthDp(CNOverlayLayout.EXPANDED) == 1100);
        // 2560px @2.0 = 1280dp，超过上限，应被截到 1100dp=2200px 并居中留白
        check("超宽屏面板被截到上限",
                CNOverlayLayout.panelWidthPx(CNOverlayLayout.EXPANDED, 2560, D) == 2200);
        check("未超上限时占满",
                CNOverlayLayout.panelWidthPx(CNOverlayLayout.EXPANDED, 1600, D) == 1600);
        check("手机档占满不截断",
                CNOverlayLayout.panelWidthPx(CNOverlayLayout.COMPACT, 1080, D) == 1080);
        check("外边距随档位变大",
                CNOverlayLayout.panelMarginDp(CNOverlayLayout.COMPACT)
                        < CNOverlayLayout.panelMarginDp(CNOverlayLayout.MEDIUM)
                        && CNOverlayLayout.panelMarginDp(CNOverlayLayout.MEDIUM)
                        < CNOverlayLayout.panelMarginDp(CNOverlayLayout.EXPANDED));

        // ── 字号 ────────────────────────────────────────────────────
        check("手机默认 100%",
                CNOverlayLayout.defaultTextScalePct(CNOverlayLayout.COMPACT) == 100);
        check("小平板默认 100%",
                CNOverlayLayout.defaultTextScalePct(CNOverlayLayout.MEDIUM) == 100);
        check("大平板默认 105%（只给这点余量）",
                CNOverlayLayout.defaultTextScalePct(CNOverlayLayout.EXPANDED) == 105);
        // 关键回归：大平板的默认字号绝不能再回到会撑破视口的那个量级
        check("大平板默认字号远低于旧标度的 125%",
                CNOverlayLayout.defaultTextScalePct(CNOverlayLayout.EXPANDED) <= 110);
        check("没调过时用档位默认",
                CNOverlayLayout.resolveTextScalePct(
                        CNOverlayLayout.EXPANDED, CNOverlayLayout.TEXT_SCALE_UNSET) == 105);
        check("调过就用玩家的值",
                CNOverlayLayout.resolveTextScalePct(CNOverlayLayout.EXPANDED, 90) == 90);
        check("玩家值超上限被夹紧",
                CNOverlayLayout.resolveTextScalePct(CNOverlayLayout.COMPACT, 999)
                        == CNOverlayLayout.TEXT_SCALE_MAX);
        check("玩家值低于下限被夹紧",
                CNOverlayLayout.resolveTextScalePct(CNOverlayLayout.COMPACT, 10)
                        == CNOverlayLayout.TEXT_SCALE_MIN);

        // ── 弹窗 ────────────────────────────────────────────────────
        // 大平板：560dp 上限 → 1120px
        check("大屏弹窗被截到 560dp",
                CNOverlayLayout.dialogWidthPx(2560, D) == 1120);
        // 手机 1080px：1080 − 2×64 = 952px，未超上限
        check("手机弹窗按可用宽减两侧留白",
                CNOverlayLayout.dialogWidthPx(1080, D) == 952);
        // 极窄分屏：不得超过可用宽度，否则按钮跑到窗口外
        int narrow = CNOverlayLayout.dialogWidthPx(300, D);
        check("极窄窗口下弹窗不超出窗口", narrow <= 300 && narrow >= 1);
        check("弹窗宽度恒为正", CNOverlayLayout.dialogWidthPx(1, D) >= 1);
        // 高度上限按窗口算，不是屏幕
        check("弹窗高度上限按窗口高度",
                CNOverlayLayout.dialogMaxHeightPx(1000) == 800);
        check("多窗口下上限不超过窗口本身",
                CNOverlayLayout.dialogMaxHeightPx(600) < 600);

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
