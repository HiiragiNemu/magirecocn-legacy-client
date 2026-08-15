import io.kamihama.magianative.CNCNDownloadUI;
import io.kamihama.magianative.CNManualRedownload;

import java.io.File;
import java.io.FileOutputStream;

public class ManualRedownloadTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    private static void write(File f, String s) throws Exception {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes("UTF-8"));
        out.close();
    }

    private static String marker(String name) {
        return "schema=1\nfile=" + name + "\nurl=" + io.kamihama.magianative.CNMirrors.CANONICAL_BASE
                + name + "\nbytes=123\netag=x\n";
    }

    private static void rm(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] a = f.listFiles();
            if (a != null) for (File x : a) rm(x);
        }
        f.delete();
    }

    public static void main(String[] args) throws Exception {
        File work = new File("work/manual-redownload-test");
        rm(work);
        File state = new File(work, "state");
        state.mkdirs();

        System.out.println("[1] 任何 ZIP 都不依赖其它 marker");
        check("不再要求另外 14 个 marker 齐全",
                !CNManualRedownload.requiresOtherMarkersForTest());
        check("允许三个不同 ZIP 同时进入协调器",
                CNManualRedownload.maxParallelForTest() == 3
                && CNManualRedownload.claimForTest(0)
                && CNManualRedownload.claimForTest(5)
                && CNManualRedownload.claimForTest(14));
        check("同一 ZIP 去重", !CNManualRedownload.claimForTest(5));
        CNManualRedownload.releaseForTest(0);
        CNManualRedownload.releaseForTest(5);
        CNManualRedownload.releaseForTest(14);
        check("释放后可再次选择", CNManualRedownload.claimForTest(5));
        CNManualRedownload.releaseForTest(5);

        System.out.println("[2] marker 校验只判断自身，不读取其它文件");
        String name = CNCNDownloadUI.FILE_NAMES[5];
        File marker = new File(state, name + ".done");
        write(marker, marker(name));
        check("所选 marker 可独立验证",
                CNManualRedownload.markerValidForTest(marker, name));
        new File(state, CNCNDownloadUI.FILE_NAMES[3] + ".done").delete();
        check("其它 marker 缺失不影响所选 marker",
                CNManualRedownload.markerValidForTest(marker, name));

        rm(work);
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
