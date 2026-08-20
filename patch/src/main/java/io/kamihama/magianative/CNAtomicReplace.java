package io.kamihama.magianative;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同目录原子替换：把一个已经写好的候选文件换成正式文件。
 *
 * <h3>为什么要有这一份</h3>
 *
 * 补丁层里「先写临时文件、再改名换入」的地方有六处，六处都是各写各的，而且都写成了
 * 同一个错误形状：
 *
 * <pre>
 *     File tmp = new File(target.getPath() + ".tmp");   // ← 固定名
 *     ...写 tmp...
 *     if (target.exists() &amp;&amp; !target.delete()) throw ...;   // ← 先删
 *     if (!tmp.renameTo(target)) throw ...;
 * </pre>
 *
 * 这个形状有两个独立的缺陷，各自都能把「原子替换」变成「有概率丢文件」：
 *
 * <h4>缺陷一：候选文件名固定，并发写同一个目标会互相截断</h4>
 *
 * 下载/安装是多线程的（首次安装 4 条、手动重下 3 条），同一个目标的写入并不保证
 * 互斥——完成标记与总标记的提交路径本身就没有同步。两个线程同时
 * {@code new FileOutputStream(tmp, false)}，后进来的那个会把前一个刚写完、刚
 * fsync 过的内容截成 0 字节；前一个随后 rename，换进去的就是半截文件。
 * 最坏情况是安装总标记被换成一个内容不全的文件，而校验只看「文件在不在」。
 *
 * <h4>缺陷二：先删再改名，两步之间被杀就两头落空</h4>
 *
 * {@code delete()} 与 {@code renameTo()} 之间进程被杀（安装期正是内存压力最大的
 * 时候），目标没了、候选还叫临时名，等于把「最后一次可用状态」删掉换成什么都没有。
 * 而这一步<b>完全没必要</b>：POSIX 的 {@code rename(2)} 对同目录、同文件系统的
 * 已存在目标本来就是原子替换，要么完整换成新的、要么保持旧的。
 * {@code CNOfflineImport} 早就是这么做的（F-026），这里只是把同一条结论推广开。
 *
 * <h3>用法</h3>
 *
 * <pre>
 *     File cand = CNAtomicReplace.stage(target);
 *     try {
 *         ...写 cand，写完 flush + fsync...
 *         CNAtomicReplace.commit(cand, target);
 *     } catch (Throwable t) {
 *         CNAtomicReplace.discard(cand);   // 只删自己那一份
 *         throw t;
 *     }
 * </pre>
 *
 * 小文本文件（标记、sidecar、元数据头）直接用 {@link #writeText}。
 *
 * <h3>候选文件名与清理</h3>
 *
 * 候选名是 {@code <目标名>.tmp.<序号>-<线程号>}——仍以 {@code .tmp} 起头，是为了
 * 让既有的「删残留」逻辑一眼能认。清理请一律走 {@link #sweep}：它同时收走旧格式的
 * {@code <目标名>.tmp} 与新格式的全部候选，漏掉任何一种都会在下载目录里攒垃圾。
 */
public final class CNAtomicReplace {

    private CNAtomicReplace() {}

    private static final String TAG = "CNAtomicReplace";

    /** 候选名的固定前缀部分（不含唯一后缀），也是 {@link #sweep} 的匹配前缀。 */
    private static final String TMP = ".tmp";

    /** 进程内单调序号。与线程号一起构成候选名的唯一部分。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * 为 {@code target} 造一个同目录、本次调用独占的候选文件。
     *
     * <p>必须同目录：跨目录 rename 可能跨文件系统，那时 {@code rename(2)} 会失败，
     * 「原子替换」就退化成复制，整套保证一起没了。
     */
    public static File stage(File target) {
        long n = SEQ.incrementAndGet();
        long tid = Thread.currentThread().getId();
        return new File(target.getPath() + TMP + "."
                + Long.toString(n, 36) + "-" + Long.toString(tid, 36));
    }

    /**
     * 把候选文件原子换入目标位置，随后同步父目录。
     *
     * <p><b>绝不预删目标。</b>见类注释「缺陷二」。
     *
     * <p>父目录同步失败不算失败：文件内容本身已经 fsync 过，缺的只是「目录项 vs
     * 掉电」这最后一个窗口，为它把一次成功的换入判成失败得不偿失——判失败的话调用方
     * 通常会删掉产物重来，反而更容易丢东西。这条取舍与 {@code CNArchiveInstallTx}
     * 一致。
     *
     * @throws IOException 换入失败。此时目标保持原样（旧内容或不存在），候选仍在原地，
     *                     由调用方 {@link #discard} 掉。
     */
    public static void commit(File candidate, File target) throws IOException {
        rename(candidate, target);
        CNArchiveInstallTx.syncDir(target.getParentFile());
    }

    /**
     * {@code rename(2)}。优先走 {@code android.system.Os}——它把 errno 带回来，
     * 「目标目录只读」和「跨文件系统」在日志里能分开；拿不到（非 Android 环境、
     * 精简 android.jar 的桩）时退回 {@link File#renameTo}，在 Linux 上底层同样是
     * {@code rename(2)}，语义一致，只是失败原因看不见。
     */
    private static void rename(File from, File to) throws IOException {
        try {
            android.system.Os.rename(from.getAbsolutePath(), to.getAbsolutePath());
            return;
        } catch (Throwable t) {
            // ErrnoException 也走这里：它不是 IOException，且我们不能在编译期引用
            // android.system.ErrnoException（精简 android.jar 未必暴露）。
            if (from.renameTo(to)) return;
            throw new IOException("原子换入失败: " + from + " -> " + to + " : " + t);
        }
    }

    /**
     * 小文本文件的完整原子写：建父目录 → 写候选 → fsync → 换入。
     *
     * <p>失败时候选被清掉，目标保持原样。
     */
    public static void writeText(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建目录: " + parent);
        }
        File cand = stage(target);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(cand, false);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
            out.close();
            out = null;
            commit(cand, target);
        } catch (IOException e) {
            discard(cand);
            throw e;
        } catch (Throwable t) {
            discard(cand);
            throw new IOException("原子写失败: " + target + " : " + t);
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 失败路径：只删本次的候选文件，绝不碰目标，也不碰别人的候选。 */
    public static void discard(File candidate) {
        if (candidate == null) return;
        try {
            if (candidate.exists() && !candidate.delete()) {
                CNLog.w(TAG, "候选文件删除失败，留待下次清理: " + candidate);
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "候选文件删除异常: " + candidate + " : " + t);
        }
    }

    /**
     * 收走 {@code target} 的全部候选残留：旧格式的 {@code <名>.tmp} 与新格式的
     * {@code <名>.tmp.*}。
     *
     * <p>只在确认没有别的线程正在写这个目标时调用（各包的收尾清理路径）——它会连
     * 在途的候选一起删掉。
     */
    public static void sweep(File target) {
        if (target == null) return;
        File parent = target.getParentFile();
        String prefix = target.getName() + TMP;
        if (parent == null) {
            deleteQuietly(new File(target.getPath() + TMP));
            return;
        }
        File[] kids = parent.listFiles();
        if (kids == null) {
            deleteQuietly(new File(target.getPath() + TMP));
            return;
        }
        for (int i = 0; i < kids.length; i++) {
            String name = kids[i].getName();
            if (name.startsWith(prefix)) deleteQuietly(kids[i]);
        }
    }

    private static void deleteQuietly(File f) {
        try { if (f.exists()) f.delete(); } catch (Throwable ignore) {}
    }
}
