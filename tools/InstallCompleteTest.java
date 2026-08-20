import io.kamihama.magianative.CNAtomicReplace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * 原子换入（F-073）的回归。
 *
 * <p>这条缺陷<b>坏得很安静</b>：换入把文件丢了，代码一路都不报错，等玩家发现
 * 时已经是「进游戏缺资源」或「凭空重下几个 GB」。所以
 * 这里钉的不是「正常路径能跑通」——那本来就没坏过——而是几条<b>反向</b>断言：
 *
 * <ul>
 *   <li>换入失败时目标必须保持原样（旧内容还在，或者本来就不存在）；
 *   <li>两个候选文件名不能撞车，否则并发写会互相截断；
 *   <li>{@code sweep} 要同时收走新旧两种格式的残留，且绝不误伤目标与邻居。
 * </ul>
 *
 * <p>宿主 JVM 上 {@code android.system.Os.rename} 是个抛异常的桩，因此这里跑的
 * 正是助手的 {@link File#renameTo} 回退分支——Android 上那条主路径与它同为
 * {@code rename(2)}，语义一致。
 */
public class InstallCompleteTest {

    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    public static void main(String[] args) throws Exception {
        File dir = File.createTempFile("install-complete", "");
        if (!dir.delete() || !dir.mkdirs()) throw new IOException("临时目录建不出来");
        try {
            stage(dir);
            commit(dir);
            discard(dir);
            sweep(dir);
        } finally {
            deleteTree(dir);
        }
        System.out.println("InstallCompleteTest: " + pass + " 通过, " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    // ---- stage：候选名必须唯一且同目录 ----

    private static void stage(File dir) {
        File target = new File(dir, "cn_base_00_db.zip.done");
        Set<String> seen = new HashSet<String>();
        boolean sameDir = true;
        boolean tmpPrefixed = true;
        for (int i = 0; i < 200; i++) {
            File c = CNAtomicReplace.stage(target);
            seen.add(c.getPath());
            if (!dir.equals(c.getParentFile())) sameDir = false;
            if (!c.getName().startsWith(target.getName() + ".tmp")) tmpPrefixed = false;
        }
        // 撞车就是 F-073 的病根：两条线程拿到同一个候选名，后写的把先写的截断。
        check("200 次 stage 不出现重名候选", seen.size() == 200);
        // 跨目录 rename 可能跨文件系统，那时原子性直接没了。
        check("候选与目标同目录", sameDir);
        // 清残留靠这个前缀认人。
        check("候选名以 <目标名>.tmp 起头", tmpPrefixed);
    }

    // ---- commit：换入不预删，失败时目标不动 ----

    private static void commit(File dir) throws IOException {
        File target = new File(dir, "flag");
        write(target, "旧内容");
        File cand = CNAtomicReplace.stage(target);
        write(cand, "新内容");
        CNAtomicReplace.commit(cand, target);
        check("换入后目标是新内容", "新内容".equals(read(target)));
        check("换入后候选已消失", !cand.exists());

        File fresh = new File(dir, "fresh");
        File c2 = CNAtomicReplace.stage(fresh);
        write(c2, "首次");
        CNAtomicReplace.commit(c2, fresh);
        check("目标不存在时也能换入", "首次".equals(read(fresh)));

        // 换入失败（候选压根不存在）时，旧目标必须原样留着——这正是「先删再改名」
        // 丢文件的那一步：删了目标，改名又没成，两头落空。
        File keep = new File(dir, "keep");
        write(keep, "必须还在");
        File ghost = CNAtomicReplace.stage(keep);
        boolean threw = false;
        try {
            CNAtomicReplace.commit(ghost, keep);
        } catch (IOException e) {
            threw = true;
        }
        check("候选缺失时换入报错", threw);
        check("换入失败后旧目标原样保留", keep.isFile() && "必须还在".equals(read(keep)));
    }

    // ---- discard：只删自己那一份 ----

    private static void discard(File dir) throws IOException {
        File target = new File(dir, "d");
        write(target, "目标");
        File mine = CNAtomicReplace.stage(target);
        File others = CNAtomicReplace.stage(target);
        write(mine, "我的");
        write(others, "别人的");
        CNAtomicReplace.discard(mine);
        check("discard 删掉本次候选", !mine.exists());
        check("discard 不碰别的线程的候选", others.isFile());
        check("discard 不碰目标", target.isFile());
        others.delete();
    }

    // ---- sweep：新旧两种格式都要收走 ----

    private static void sweep(File dir) throws IOException {
        File target = new File(dir, "s.meta");
        write(target, "目标");
        File legacy = new File(target.getPath() + ".tmp");           // 旧格式
        File modern = CNAtomicReplace.stage(target);                 // 新格式
        File neighbour = new File(dir, "s.meta.part");               // 无关邻居
        File otherTarget = new File(dir, "t.meta");
        File otherCand = CNAtomicReplace.stage(otherTarget);
        write(legacy, "旧残留");
        write(modern, "新残留");
        write(neighbour, "邻居");
        write(otherCand, "别人的候选");

        CNAtomicReplace.sweep(target);

        check("sweep 收走旧格式残留", !legacy.exists());
        check("sweep 收走新格式残留", !modern.exists());
        check("sweep 不碰目标本身", target.isFile());
        check("sweep 不碰无关邻居", neighbour.isFile());
        check("sweep 不碰别的目标的候选", otherCand.isFile());
    }

    // ---- 小工具 ----

    private static void write(File f, String s) throws IOException {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static String read(File f) throws IOException {
        byte[] buf = new byte[(int) f.length()];
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (int i = 0; i < kids.length; i++) deleteTree(kids[i]);
        f.delete();
    }
}
