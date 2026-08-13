import io.kamihama.magianative.CNDownloadUiAssist;

public class UiAssistStateTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) {
        CNDownloadUiAssist.onOverlayDetached();
        check("初始不处于停留状态", !CNDownloadUiAssist.shouldStayOnPage());
        check("初始没有离开请求", !CNDownloadUiAssist.consumeLeaveRequest());

        CNDownloadUiAssist.setStayOnPage(true);
        check("停留按钮能锁住页面", CNDownloadUiAssist.shouldStayOnPage());
        check("进入停留不会误发离开请求", !CNDownloadUiAssist.consumeLeaveRequest());

        CNDownloadUiAssist.setStayOnPage(false);
        check("进入游戏会解除停留", !CNDownloadUiAssist.shouldStayOnPage());
        check("进入游戏请求只消费一次", CNDownloadUiAssist.consumeLeaveRequest());
        check("离开请求消费后清零", !CNDownloadUiAssist.consumeLeaveRequest());

        CNDownloadUiAssist.onOverlayDetached();
        check("浮层销毁会清空所有状态", !CNDownloadUiAssist.shouldStayOnPage()
                && !CNDownloadUiAssist.consumeLeaveRequest());

        // 左右分界线：拖动要真机，但夹紧范围是纯算术，而越界正是唯一能把界面
        // 拖坏的途径——某一列被压到只剩省略号。
        int min = CNDownloadUiAssist.splitMinForTest();
        int max = CNDownloadUiAssist.splitMaxForTest();
        int def = CNDownloadUiAssist.splitDefaultForTest();
        check("分界线默认值落在可调范围内", def >= min && def <= max);
        check("分界线范围两侧都留得下东西", min >= 15 && max <= 85 && min < max);

        CNDownloadUiAssist.setSplitForTest(def);
        check("分界线可设为默认值", CNDownloadUiAssist.splitPctForTest() == def);
        CNDownloadUiAssist.setSplitForTest(-500);
        check("向左拖过头夹在下限", CNDownloadUiAssist.splitPctForTest() == min);
        CNDownloadUiAssist.setSplitForTest(500);
        check("向右拖过头夹在上限", CNDownloadUiAssist.splitPctForTest() == max);
        CNDownloadUiAssist.setSplitForTest(def);

        // ── 按分辨率建议字号 ────────────────────────────────────────
        // 真机反馈：大屏上 100% 就是一行蚂蚁。依据是「内容区有多少 dp 宽」，
        // 参考宽度处的建议必须正好是 100%，否则整条标度就是歪的。
        float ref = CNDownloadUiAssist.designWidthDpForTest();
        check("参考宽度处建议 100%",
                CNDownloadUiAssist.suggestFromDpForTest(ref) == 100);
        check("宽一倍时建议更大（但夹在上限内）",
                CNDownloadUiAssist.suggestFromDpForTest(ref * 2f) == 150);
        check("窄一半时建议更小（但夹在下限内）",
                CNDownloadUiAssist.suggestFromDpForTest(ref / 2f) == 75);
        check("略宽于参考时按比例给建议",
                CNDownloadUiAssist.suggestFromDpForTest(ref * 1.2f) == 120);
        // 取不到分辨率时不能给出 0 或负的字号——那会让整页字消失。
        check("0 宽度回落到 100%", CNDownloadUiAssist.suggestFromDpForTest(0f) == 100);
        check("负宽度回落到 100%", CNDownloadUiAssist.suggestFromDpForTest(-1f) == 100);
        int lo = CNDownloadUiAssist.suggestFromDpForTest(1f);
        int hi = CNDownloadUiAssist.suggestFromDpForTest(100000f);
        check("建议值恒在 75–150 内", lo >= 75 && lo <= 150 && hi >= 75 && hi <= 150);

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
