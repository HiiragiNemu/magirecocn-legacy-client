import io.kamihama.magianative.CNArchiveInstallTx;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ArchiveInstallTxTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    private static void rm(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File child : children) rm(child);
        }
        f.delete();
    }

    private static void makeZip(File zip, int count) throws Exception {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip));
        for (int i = 0; i < count; i++) {
            ZipEntry e = new ZipEntry("tree/f" + i + ".txt");
            out.putNextEntry(e);
            byte[] data = ("entry-" + i + "-payload").getBytes("UTF-8");
            out.write(data);
            out.closeEntry();
        }
        out.close();
    }

    private static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] data = new byte[(int) f.length()];
        int p = 0;
        while (p < data.length) {
            int n = in.read(data, p, data.length - p);
            if (n < 0) break;
            p += n;
        }
        in.close();
        return new String(data, "UTF-8");
    }

    public static void main(String[] args) throws Exception {
        File work = new File("work/archive-install-tx");
        rm(work); work.mkdirs();
        File zip = new File(work, "payload.zip");
        File root = new File(work, "root");
        File state = new File(work, "payload.extract.tx");
        makeZip(zip, 96);

        final AtomicBoolean cancel = new AtomicBoolean(false);
        boolean interrupted = false;
        try {
            CNArchiveInstallTx.extract(zip, root, state,
                    new CNArchiveInstallTx.Cancel() {
                        public boolean isCancelled() { return cancel.get(); }
                    },
                    new CNArchiveInstallTx.Progress() {
                        public void onProgress(int done, int total, long db, long tb) {
                            if (done >= 32 && done < total) cancel.set(true);
                        }
                    });
        } catch (CNArchiveInstallTx.CancelledException expected) {
            interrupted = true;
        }
        check("中断后保留事务状态", interrupted && state.isFile());
        check("中断前已原子提交一批文件", new File(root, "tree/f0.txt").isFile());

        cancel.set(false);
        CNArchiveInstallTx.extract(zip, root, state,
                new CNArchiveInstallTx.Cancel() {
                    public boolean isCancelled() { return false; }
                }, null);
        check("重启后从事务状态继续并完成", !state.exists()
                && new File(root, "tree/f95.txt").isFile());
        check("最终内容正确", "entry-95-payload".equals(read(new File(root, "tree/f95.txt"))));

        File bad = new File(work, "bad.zip");
        FileOutputStream badOut = new FileOutputStream(bad);
        badOut.write(new byte[]{1,2,3,4,5}); badOut.close();
        boolean corrupt = false;
        try {
            CNArchiveInstallTx.extract(bad, new File(work, "bad-root"),
                    new File(work, "bad.state"), null, null);
        } catch (java.util.zip.ZipException expected) {
            corrupt = true;
        }
        check("损坏 ZIP 被区分为归档错误", corrupt);

        rm(work);
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
