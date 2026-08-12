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
        return "schema=1\nfile=" + name + "\nurl=https://assets.example.test/"
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
        File root = new File(work, "files");
        File state = new File(root, "madomagi/magica/.cn_installer/r128-downloader-v1");
        state.mkdirs();

        String[] names = CNCNDownloadUI.FILE_NAMES;
        for (String name : names) write(new File(state, name + ".done"), marker(name));

        System.out.println("[1] 另外 14 个 marker 都有效时允许单文件模式");
        check("选择 03 时其余 marker 有效",
                CNManualRedownload.firstInvalidOtherForTest(state, 5) == null);

        System.out.println("[2] 其它 marker 缺失时必须拒绝谎称‘只下一个’");
        new File(state, names[3] + ".done").delete();
        check("能指出另一个无效文件",
                names[3].equals(CNManualRedownload.firstInvalidOtherForTest(state, 5)));
        check("缺失项本身作为所选文件时可继续",
                CNManualRedownload.firstInvalidOtherForTest(state, 3) == null);
        write(new File(state, names[3] + ".done"), marker(names[3]));

        System.out.println("[3] 清理范围只命中所选 ZIP");
        String selected = names[5];
        String other = names[4];
        write(new File(root, selected), "x");
        write(new File(root, selected + ".aria2"), "x");
        write(new File(root, selected + ".part"), "x");
        write(new File(root, selected + ".part.meta"), "x");
        write(new File(root, selected + ".cpart"), "x");
        write(new File(root, selected + ".cpart.prog"), "x");
        write(new File(root, selected + ".cpart.block.0.7"), "x");
        write(new File(state, "offline/" + selected), "x");
        write(new File(root, other), "keep");
        write(new File(state, "offline/" + other), "keep");

        CNManualRedownload.cleanupArtifactsForTest(root, state, 5);
        check("所选成品与全部断点已删",
                !new File(root, selected).exists()
                && !new File(root, selected + ".aria2").exists()
                && !new File(root, selected + ".part").exists()
                && !new File(root, selected + ".part.meta").exists()
                && !new File(root, selected + ".cpart").exists()
                && !new File(root, selected + ".cpart.prog").exists()
                && !new File(root, selected + ".cpart.block.0.7").exists());
        check("所选离线候选也删，确保走网络重下",
                !new File(state, "offline/" + selected).exists());
        check("其它 ZIP 与离线包完全保留",
                new File(root, other).isFile()
                && new File(state, "offline/" + other).isFile());

        rm(work);
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
