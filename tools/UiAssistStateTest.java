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

        // 左右分界线与「重下」胶囊已按维护者要求整体撤回（2026-08-13），
        // 这里原有的夹紧范围断言一并移除——判据没了，测试留着只会误导。

        // ── 按分辨率建议字号 ────────────────────────────────────────
        // 参考宽度处的建议必须正好是 100%，否则整条标度就是歪的。
        float ref = CNDownloadUiAssist.designWidthDpForTest();
        check("参考宽度处建议 100%",
                CNDownloadUiAssist.suggestFromDpForTest(ref) == 100);

        // 2026-08-13：判据被刻意压平了。dp 宽度只是「屏幕看起来多大」的粗糙代理，
        // 方向甚至可能是反的（720p 低密度手机报出的 dp 比 1080p 手机还多），所以
        // 斜率取半、建议值夹在 85–125。改判据前的旧标度对**几乎所有设备**都给出
        // 115%–150%——一个对所有人都推荐接近最大值的「推荐」，没有在推荐任何东西。
        int lo = CNDownloadUiAssist.suggestMinForTest();
        int hi = CNDownloadUiAssist.suggestMaxForTest();
        check("建议量程明显窄于手动量程（75–150）", lo >= 80 && hi <= 130 && lo < hi);
        check("宽一倍只多半程，且夹在上限内",
                CNDownloadUiAssist.suggestFromDpForTest(ref * 2f) == hi);
        check("窄一半只少半程，且夹在下限内",
                CNDownloadUiAssist.suggestFromDpForTest(ref / 2f) == lo);
        check("略宽于参考时按半速率给建议",
                CNDownloadUiAssist.suggestFromDpForTest(ref * 1.2f) == 110);
        check("略窄于参考时按半速率给建议",
                CNDownloadUiAssist.suggestFromDpForTest(ref * 0.8f) == 90);
        // 单调性：更宽的屏幕不该建议更小的字号。
        check("建议随宽度单调不减",
                CNDownloadUiAssist.suggestFromDpForTest(ref * 0.9f)
                        <= CNDownloadUiAssist.suggestFromDpForTest(ref)
                && CNDownloadUiAssist.suggestFromDpForTest(ref)
                        <= CNDownloadUiAssist.suggestFromDpForTest(ref * 1.1f));
        // 真机代表值：改判据前这几台一律 115%–150%，现在落在合理区间。
        check("典型设备不再一律顶到上限",
                CNDownloadUiAssist.suggestFromDpForTest(642f) < 100
                && CNDownloadUiAssist.suggestFromDpForTest(724f) == 100
                && CNDownloadUiAssist.suggestFromDpForTest(1224f) <= hi);
        // 取不到分辨率时不能给出 0 或负的字号——那会让整页字消失。
        check("0 宽度回落到 100%", CNDownloadUiAssist.suggestFromDpForTest(0f) == 100);
        check("负宽度回落到 100%", CNDownloadUiAssist.suggestFromDpForTest(-1f) == 100);
        int edgeLo = CNDownloadUiAssist.suggestFromDpForTest(1f);
        int edgeHi = CNDownloadUiAssist.suggestFromDpForTest(100000f);
        check("建议值恒在 75–150 内",
                edgeLo >= 75 && edgeLo <= 150 && edgeHi >= 75 && edgeHi <= 150);

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

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
