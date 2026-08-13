import io.kamihama.magianative.CNCNDownloadUI;
import io.kamihama.magianative.CNDownloaderFix;
import io.kamihama.magianative.CNHotUpdate;
import io.kamihama.magianative.CNHotUpdateCheck;
import io.kamihama.magianative.CNHotUpdateValidate;
import io.kamihama.magianative.CNOfflineImport;

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

        // ── 首次安装器的分块清单边界（2026-08-13 真机） ──────────────────
        // 上面第一条只钉住了热更**那一轮**。首次安装器把这两包排在下载队列
        // 最前，走的却是基础包那条路，于是照样去套 manifest.json 的块指纹。
        // manifest 只在基础包整批出包时重算，热更包却由流水线单独重发——脱节
        // 时块指纹指向上一版，而 size 常常没变（同结构 ZIP 重打包尺寸一致），
        // 「清单与文件是否同一身份」那道闸便照样放行，随后每一块都校验失败。
        //
        // 当天四个镜像给出同一个实得值、只有清单对不上，就是这条路走出来的。
        check("首次安装器不给台词包套基础包 manifest",
                !CNDownloaderFix.usesChunkManifest("cn_scenario_update.zip"));
        check("首次安装器不给前端脚本包套基础包 manifest",
                !CNDownloaderFix.usesChunkManifest("cn_js_update.zip"));

        // 反过来同样要钉住：13 个静态基础包**必须**继续走块指纹事务。
        // 把判据写宽（比如「都不套」）能让红条消失，代价是整批基础包退回
        // 只剩 ZIP 结构预检——那才是真正下不得的一步。
        boolean baseAllVerified = true;
        int baseCount = 0;
        for (int i = 0; i < CNCNDownloadUI.FILE_NAMES.length; i++) {
            String n = CNCNDownloadUI.FILE_NAMES[i];
            if (CNOfflineImport.isHotUpdateFile(n)) continue;
            baseCount++;
            if (!CNDownloaderFix.usesChunkManifest(n)) baseAllVerified = false;
        }
        check("13 个静态基础包继续走块指纹事务",
                baseCount == 13 && baseAllVerified);

        // 两处判据必须同源：安装器与离线导入都拿 isHotUpdateFile 认这两包，
        // 各写各的名单迟早会漂。
        check("安装器与离线导入共用同一份热更包判据",
                CNDownloaderFix.usesChunkManifest("cn_base_03.zip")
                        != CNOfflineImport.isHotUpdateFile("cn_base_03.zip")
                && CNDownloaderFix.usesChunkManifest("cn_scenario_update.zip")
                        != CNOfflineImport.isHotUpdateFile("cn_scenario_update.zip"));

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
