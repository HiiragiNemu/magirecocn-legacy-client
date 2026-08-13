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

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    private static int lastIndexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = hay.length - needle.length; i >= 0; i--) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
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

        // ── 谎报未压缩长度的 ZIP：必须在**写出去之前**被拦下 ─────────────
        //
        // 2026-08-13 把两套解压收敛成一份时，原 extractChecked 的「边写边看」
        // 差点丢掉。本类原先是读到 EOF 才比 copied != entry.getSize()——等它报错，
        // 磁盘已经铺满了，而 zip 炸弹的伤害恰恰就是「写出去」这件事本身。
        //
        // 这里不手写字节流造假 ZIP，而是拿一个**真 ZIP** 去改它中央目录里那个
        // 「未压缩长度」字段：ZipFile 的 getSize() 读的正是这里，而 Inflater 照旧
        // 解出真实长度。这就是「声明撒谎」的最小复现。
        File liar = new File(work, "liar.zip");
        ZipOutputStream lout = new ZipOutputStream(new FileOutputStream(liar));
        ZipEntry le = new ZipEntry("tree/big.bin");
        lout.putNextEntry(le);
        byte[] payload = new byte[1024 * 1024];    // 1 MB 全 0，压得很小
        lout.write(payload);
        lout.closeEntry();
        lout.close();

        byte[] raw = readAll(liar);
        int cd = lastIndexOf(raw, new byte[] { 0x50, 0x4b, 0x01, 0x02 });
        check("样本里找得到中央目录", cd > 0);
        // 中央目录记录 +24 处是 4 字节小端的「未压缩长度」，改成 100000
        // （比一个 64KB 缓冲大，但远小于真实的 1 MB——这样「写之前拦」与
        //   「写完再拒」的落盘量差着一个数量级，测得出来）
        raw[cd + 24] = (byte) 0xA0; raw[cd + 25] = (byte) 0x86;
        raw[cd + 26] = 0x01; raw[cd + 27] = 0;
        FileOutputStream lw = new FileOutputStream(liar);
        lw.write(raw); lw.close();

        File liarRoot = new File(work, "liar-root");
        boolean caught = false;
        try {
            CNArchiveInstallTx.extract(liar, liarRoot,
                    new File(work, "liar.state"), null, null);
        } catch (java.util.zip.ZipException expected) {
            caught = true;
        }
        check("谎报长度的条目被拒", caught);

        // 这条才是真正的判据。光看「抛没抛」区分不出来——读到 EOF 之后那句
        // copied != entry.getSize() 同样会抛 ZipException。区别在**抛之前写了多少**：
        //   写之前拦下 → 落盘量不超过声明的 100000（多算一个缓冲的余量）
        //   写完再拒   → 整整 1 MB 已经落过盘了
        // 停用即时判据重跑本测试，这一条会红；上一条不会。
        long wrote = CNArchiveInstallTx.writtenThisRunForTest();
        check("拦截发生在写出去之前（落盘 " + wrote + " 字节，远小于 1MB）",
                wrote <= 100000L + 64L * 1024L);

        File written = new File(liarRoot, "tree/big.bin");
        check("被拒的条目没有留下完整文件", !written.isFile());
        // 半截临时文件也不许留下——它和目标同名加后缀，没人会再看它一眼。
        File leftover = new File(liarRoot, "tree/big.bin.cnv-install.tmp");
        check("半截临时文件没有泄漏", !leftover.isFile());

        rm(work);
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
