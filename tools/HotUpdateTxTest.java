import io.kamihama.magianative.CNHotUpdateTx;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 验证 {@link CNHotUpdateTx} 的事务语义：提交、回滚、崩溃恢复。
 *
 * <p>纯文件系统操作，不需要设备也不需要测试服务器：
 *
 * <pre>
 *   javac -nowarn -source 8 -target 8 -encoding UTF-8 \
 *         -cp .cache/deps/android.jar -d .build-test \
 *         $(find patch/src/main/java -name '*.java') tools/HotUpdateTxTest.java
 *   java -cp .build-test HotUpdateTxTest
 * </pre>
 *
 * <p>（源码里 {@code RestClient} 是编译期桩，编译整棵树时按 build-local.sh 的做法
 * 补一个即可；本测试自身不碰它。）
 */
public class HotUpdateTxTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  ✅ " + name + (detail.isEmpty() ? "" : "  — " + detail)); }
        else    { fail++; System.out.println("  ❌ " + name + "  — " + detail); }
    }

    // ---- 小工具 ----

    static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }

    static String read(File f) throws IOException {
        if (!f.isFile()) return null;
        FileInputStream i = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, n;
        while (off < b.length && (n = i.read(b, off, b.length - off)) > 0) off += n;
        i.close();
        return new String(b, 0, off, "UTF-8");
    }

    /** 造一个包：entries 是 {相对路径, 内容} 的交替数组。 */
    static File zip(File dest, String... entries) throws IOException {
        dest.getParentFile().mkdirs();
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(dest));
        for (int i = 0; i < entries.length; i += 2) {
            byte[] body = entries[i + 1].getBytes("UTF-8");
            ZipEntry e = new ZipEntry(entries[i]);
            e.setSize(body.length);
            z.putNextEntry(e);
            z.write(body);
            z.closeEntry();
        }
        z.close();
        return dest;
    }

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] k = f.listFiles();
            if (k != null) for (File x : k) rmrf(x);
        }
        f.delete();
    }

    static File txOf(File root, String tag) {
        return new File(new File(root, ".cnv_tx"), tag);
    }

    // ==================================================================

    public static void main(String[] args) throws Exception {
        File base = new File("tx-test");
        rmrf(base);
        base.mkdirs();

        // ---------------------------------------------------------------
        System.out.println("\n[1] 正常提交：覆盖已有文件 + 新增文件");
        {
            File root = new File(base, "case1");
            write(new File(root, "js/a.js"), "OLD-A");
            write(new File(root, "js/keep.js"), "UNTOUCHED");
            File pkg = zip(new File(base, "p1.zip"),
                    "js/a.js", "NEW-A",
                    "js/b.js", "NEW-B",
                    "tpl/deep/c.html", "NEW-C");

            CNHotUpdateTx.apply(pkg, root, "js");

            check("覆盖的文件是新内容", "NEW-A".equals(read(new File(root, "js/a.js"))),
                    String.valueOf(read(new File(root, "js/a.js"))));
            check("新增的文件已就位", "NEW-B".equals(read(new File(root, "js/b.js"))), "");
            check("多层目录的新增文件已就位",
                    "NEW-C".equals(read(new File(root, "tpl/deep/c.html"))), "");
            check("包外的文件没被动过",
                    "UNTOUCHED".equals(read(new File(root, "js/keep.js"))), "");
            check("事务工作区已清干净", !new File(root, ".cnv_tx").exists(), "");
        }

        // ---------------------------------------------------------------
        System.out.println("\n[2] 提交中途失败 → 整体回滚，活动树回到动手之前");
        {
            File root = new File(base, "case2");
            write(new File(root, "js/a.js"), "OLD-A");
            write(new File(root, "js/keep.js"), "UNTOUCHED");
            // 活动树上 blocked 是个**普通文件**，而包里要求它是目录 →
            // 换入到 blocked/x.txt 时 ensureParent 建不出目录，提交中途抛异常
            write(new File(root, "blocked"), "I-AM-A-FILE");
            File pkg = zip(new File(base, "p2.zip"),
                    "js/a.js", "NEW-A",
                    "js/b.js", "NEW-B",
                    "blocked/x.txt", "NEW-X");

            boolean threw = false;
            try { CNHotUpdateTx.apply(pkg, root, "js"); }
            catch (IOException e) { threw = true; }

            check("apply 抛出异常", threw, "");
            check("被覆盖的文件已还原为旧内容",
                    "OLD-A".equals(read(new File(root, "js/a.js"))),
                    String.valueOf(read(new File(root, "js/a.js"))));
            check("本次新增的文件已被撤掉", !new File(root, "js/b.js").exists(), "");
            check("挡路的那个文件原样保留",
                    "I-AM-A-FILE".equals(read(new File(root, "blocked"))), "");
            check("包外的文件没被动过",
                    "UNTOUCHED".equals(read(new File(root, "js/keep.js"))), "");
            check("回滚成功后事务工作区已清掉", !new File(root, ".cnv_tx").exists(), "");
        }

        // ---------------------------------------------------------------
        System.out.println("\n[3] 崩溃在提交中途（无 COMMITTED）→ recover 向后滚");
        {
            File root = new File(base, "case3");
            // 手工伪造「提交到一半被杀」的现场：
            //   a.js 已换入、旧内容在 backup 里
            //   b.js 是新增的、已换入
            //   c.js 计划里有但还没轮到，活动树上仍是旧内容、backup 里没有
            write(new File(root, "js/a.js"), "NEW-A");
            write(new File(root, "js/b.js"), "NEW-B");
            write(new File(root, "js/c.js"), "OLD-C");
            File tx = txOf(root, "js");
            write(new File(tx, "backup/js/a.js"), "OLD-A");
            write(new File(tx, "journal"), "1\tjs/a.js\n0\tjs/b.js\n1\tjs/c.js\n");

            CNHotUpdateTx.recover(root);

            check("已换入的覆盖文件回到旧内容",
                    "OLD-A".equals(read(new File(root, "js/a.js"))),
                    String.valueOf(read(new File(root, "js/a.js"))));
            check("已换入的新增文件被删掉", !new File(root, "js/b.js").exists(), "");
            check("还没轮到的文件保持旧内容",
                    "OLD-C".equals(read(new File(root, "js/c.js"))), "");
            check("事务工作区已清掉", !new File(root, ".cnv_tx").exists(), "");
        }

        // ---------------------------------------------------------------
        System.out.println("\n[4] 崩溃在提交之后（有 COMMITTED）→ recover 向前滚");
        {
            File root = new File(base, "case4");
            write(new File(root, "js/a.js"), "NEW-A");
            write(new File(root, "js/b.js"), "NEW-B");
            File tx = txOf(root, "js");
            write(new File(tx, "backup/js/a.js"), "OLD-A");
            write(new File(tx, "journal"), "1\tjs/a.js\n0\tjs/b.js\n");
            write(new File(tx, "COMMITTED"), "ok\n");

            CNHotUpdateTx.recover(root);

            check("新内容保持不变（不回滚已完成的事务）",
                    "NEW-A".equals(read(new File(root, "js/a.js"))),
                    String.valueOf(read(new File(root, "js/a.js"))));
            check("新增文件保留", "NEW-B".equals(read(new File(root, "js/b.js"))), "");
            check("事务工作区已清掉", !new File(root, ".cnv_tx").exists(), "");
        }

        // ---------------------------------------------------------------
        System.out.println("\n[5] 包试图写入事务工作区 → 拒收整个包");
        {
            File root = new File(base, "case5");
            write(new File(root, "js/a.js"), "OLD-A");
            File pkg = zip(new File(base, "p5.zip"),
                    "js/a.js", "NEW-A",
                    ".cnv_tx/js/backup/evil", "PWN");

            boolean threw = false;
            try { CNHotUpdateTx.apply(pkg, root, "js"); }
            catch (IOException e) { threw = true; }

            check("apply 抛出异常", threw, "");
            check("活动树一个字节都没被改",
                    "OLD-A".equals(read(new File(root, "js/a.js"))),
                    String.valueOf(read(new File(root, "js/a.js"))));
            check("事务工作区已清掉", !new File(root, ".cnv_tx").exists(), "");
        }

        // ---------------------------------------------------------------
        System.out.println("\n[6] 上一轮的残留事务不会污染这一轮");
        {
            File root = new File(base, "case6");
            write(new File(root, "js/a.js"), "NEW-A-FROM-LAST-RUN");
            // 上一轮跑到一半被杀，留下 backup 与 journal
            File tx = txOf(root, "js");
            write(new File(tx, "backup/js/a.js"), "OLD-A");
            write(new File(tx, "journal"), "1\tjs/a.js\n");

            File pkg = zip(new File(base, "p6.zip"), "js/a.js", "NEWEST-A");
            CNHotUpdateTx.apply(pkg, root, "js");

            check("本轮内容正确落地",
                    "NEWEST-A".equals(read(new File(root, "js/a.js"))),
                    String.valueOf(read(new File(root, "js/a.js"))));
            check("事务工作区已清干净", !new File(root, ".cnv_tx").exists(), "");
        }

        // ---------------------------------------------------------------
        System.out.println("\n[7] 幂等：同一个包连应用两次结果一致");
        {
            File root = new File(base, "case7");
            write(new File(root, "js/a.js"), "OLD-A");
            File pkg = zip(new File(base, "p7.zip"), "js/a.js", "NEW-A", "js/b.js", "NEW-B");
            CNHotUpdateTx.apply(pkg, root, "js");
            CNHotUpdateTx.apply(pkg, root, "js");
            check("覆盖文件内容正确", "NEW-A".equals(read(new File(root, "js/a.js"))), "");
            check("新增文件内容正确", "NEW-B".equals(read(new File(root, "js/b.js"))), "");
            check("事务工作区已清干净", !new File(root, ".cnv_tx").exists(), "");
        }

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
