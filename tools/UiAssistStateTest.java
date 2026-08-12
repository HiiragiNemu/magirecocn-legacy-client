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

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
