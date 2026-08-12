import io.kamihama.magianative.CNHotUpdate;
import io.kamihama.magianative.CNHotUpdateCheck;
import io.kamihama.magianative.CNHotUpdateValidate;

public class HotUpdateRoutingTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) {
        CNHotUpdateValidate.VerMeta meta = new CNHotUpdateValidate.VerMeta(
                27, 193897300L, "0123456789abcdef0123456789abcdef");
        String a = CNHotUpdate.hotIdentityForTest(meta);
        String b = CNHotUpdate.hotIdentityForTest(meta);

        check("动态热更不读取基础包分块 manifest",
                !CNHotUpdate.usesChunkManifestForHotUpdateForTest());
        check("同一版本身份键稳定", a.equals(b));
        check("身份键包含版本和 MD5", a.contains("27")
                && a.contains("0123456789ab"));
        check("部分成功不能显示更新完成",
                "部分更新完成".equals(CNHotUpdateCheck.summaryPhaseForTest(true, true)));
        check("全部失败显示更新未完成",
                "更新未完成".equals(CNHotUpdateCheck.summaryPhaseForTest(false, true)));
        check("无失败且有应用才显示更新完成",
                "更新完成".equals(CNHotUpdateCheck.summaryPhaseForTest(true, false)));
        check("无更新无失败显示已是最新",
                "已是最新".equals(CNHotUpdateCheck.summaryPhaseForTest(false, false)));

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
