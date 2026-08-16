package io.kamihama.magianative;

import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置 bsdtar（libarchive 3.7.4，全静态）的包装：用子进程校验/解压 zip，替代
 * {@code java.util.zip.ZipFile}。
 *
 * <h3>为什么要有它</h3>
 *
 * 资源包在打包时被工具多写了一份<b>冗余 ZIP64</b>（普通 EOCD 完全自洽，却额外
 * 挂了一个 {@code version=45} 的 zip64 EOCD）。voice_02 是「必要 zip64」（条目数
 * 溢出 65535），解析器会正确走 zip64 路径；而 03 这种「普通 EOCD 自洽 + 多余
 * zip64」正好落进 zip 解析器的经典灰色地带——某些 Android 定制 ROM / 老设备的
 * {@code java.util.zip.ZipFile} 遇到它直接抛「ZipFile structure invalid」，
 * 完工校验（{@link CNArchiveValidate#isZipStructurallyValid}）跟着失败，玩家反复
 * 看到「zip 结构非法」。
 *
 * <p>Info-ZIP unzip 和 libarchive bsdtar 都实测能正确处理这个冗余 zip64。
 * 本类选用 <b>libarchive bsdtar</b>（BSD-2-Clause，GPLv3 兼容，无静态链接义务），
 * 以<b>独立子进程</b>运行，与游戏本体既不链接也不同进程——和 {@link CNAria2}
 * 处理 GPL 组件是同一套「单纯聚合」原则。
 *
 * <h3>「可用」必须真探测，不能只看文件存在</h3>
 *
 * 「应用 exec 自己私有目录里的二进制」在两类设备上会直接失败，且失败时文件
 * 明明好好地躺在那里：
 *
 * <ul>
 *   <li><b>SELinux exec 闸</b>：Android 10 起 targetSdkVersion ≥ 29 的应用域
 *       （untrusted_app_29+）没有 {@code app_data_file:file execute} 权限，
 *       execve 返回 EACCES（日志里 {@code error=13, Permission denied}）。
 *       本包基线 targetSdk ≤ 28 不在闸内，但 OEM 加固 ROM 可能另行拦截。</li>
 *   <li><b>16KB 页设备</b>：Android 15+ 部分新机的内核页从 4KB 升到 16KB，
 *       要求 ELF 段 16KB 对齐；4KB 对齐的旧二进制 execve 返回 ENOEXEC
 *       （{@code error=8, Exec format error}）。</li>
 * </ul>
 *
 * 所以 {@link #isAvailable()} 在确认二进制落盘之后还必须真跑一次
 * {@code bsdtar --version}（与 {@link CNAria2} 的 checkExecutable 同一模式）：
 * 探测失败 = 整个会话标记不可用，调用方走 ZipFile 回退。这样「二进制起不来」
 * 永远不会被误报成「zip 结构非法」——那两种设备的 ZipFile 恰恰都是新版
 * OpenJDK 实现，反而打得开冗余 ZIP64，两条路天然互补。
 *
 * <h3>解压进度从哪来</h3>
 *
 * bsdtar 的 zip 读取器走中央目录：{@code -tvf} 列表不扫数据区（实测 210MB 包
 * 0.01s 出全表），输出含每条目的未压缩大小；{@code -xvf} 每解完一个条目往
 * stderr 打一行 {@code x <名字>}（行打出来时该条目已写完），且条目顺序与列表
 * 顺序一致（两种 zip 实测均一致）。因此「先列表拿大小表，再边解压边数行」
 * 就能得到字节级精确的进度，替代原来的 0%→100% 两跳。
 */
public final class CNZipTool {
    private static final String TAG = "CNZipTool";

    /** ABI → assets 路径 → 期望字节数。与 {@link CNAria2#ASSETS} 同构。 */
    private static final String[][] ASSETS = {
            {"arm64-v8a", "bsdtar/bsdtar-arm64", "7131808"},
            {"armeabi-v7a", "bsdtar/bsdtar-arm", "5770080"},
    };

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initDone;
    private static volatile boolean available;
    /** 解压子进程忙门：同一时刻只允许一个解压。 */
    private static final AtomicBoolean busy = new AtomicBoolean(false);

    private CNZipTool() {}

    /**
     * bsdtar 是否可用：二进制已落盘 <b>且真的 exec 起来过</b>（{@code --version}
     * 探测）。探测失败多见于 SELinux exec 闸 / 16KB 页设备（见类说明），此时
     * 返回 false，调用方回退 ZipFile 路径。
     */
    public static boolean isAvailable() {
        if (initDone) return available;
        synchronized (INIT_LOCK) {
            if (initDone) return available;
            boolean ok = false;
            try {
                ok = ensureBinary() && probeExec();
            } catch (Throwable t) {
                CNLog.w(TAG, "bsdtar 初始化异常: " + t);
                ok = false;
            }
            available = ok;
            initDone = true;
            return available;
        }
    }

    // -----------------------------------------------------------------
    // 列表（结构校验 + 条目大小表，一次进程两个用途）
    // -----------------------------------------------------------------

    /**
     * {@code bsdtar -tvf} 的解析结果：条目数、总未压缩大小、按解压顺序逐个
     * 条目的未压缩大小（与 {@code -xvf} 的解压顺序一致，实测验证）。
     */
    public static final class EntryTable {
        public final int count;
        public final long totalBytes;
        final long[] sizes;
        /** false = 有行没解析出来，sizes 不全，进度退化为按条目数估算。 */
        final boolean sizesReliable;

        EntryTable(long[] sizes, boolean sizesReliable) {
            this.count = sizes.length;
            long sum = 0L;
            for (long s : sizes) sum += s;
            this.totalBytes = sum;
            this.sizes = sizes;
            this.sizesReliable = sizesReliable;
        }
    }

    /**
     * 列出 zip 的条目表。bsdtar 的 zip 读取器走中央目录，不扫数据区，大文件
     * 也是秒出。返回 null = 结构不可解析（等同于旧的「ZipFile 打不开」）。
     */
    public static EntryTable list(File zip) {
        if (zip == null || !zip.isFile() || !isAvailable()) return null;
        Process p = null;
        try {
            File bin = binFile();
            if (bin == null) return null;
            p = new ProcessBuilder(bin.getAbsolutePath(), "-tvf", zip.getAbsolutePath())
                    .redirectErrorStream(true).start();
            ListJob job = new ListJob(p.getInputStream());
            Thread t = new Thread(job, "cnzip-list");
            t.setDaemon(true);
            t.start();
            boolean exited = p.waitFor(60, TimeUnit.SECONDS);
            if (!exited) {
                CNLog.w(TAG, "bsdtar -tvf 超时(60s): " + zip.getName());
                try { p.destroy(); } catch (Throwable ignore) {}
                return null;
            }
            t.join(2000);
            if (p.exitValue() != 0) {
                CNLog.w(TAG, "bsdtar -tvf 退出码 " + p.exitValue() + ": " + zip.getName());
                return null;
            }
            return job.table();
        } catch (Throwable t) {
            CNLog.w(TAG, "bsdtar 列表失败: " + t);
            return null;
        }
    }

    /** {@code -tvf} 输出的逐行解析。静态嵌套类：不捕获任何外部引用。 */
    private static final class ListJob implements Runnable {
        private final BufferedReader in;
        private final ArrayList<Long> sizes = new ArrayList<Long>();
        private boolean reliable = true;

        ListJob(InputStream stream) {
            // 名字只用于数数、不进任何逻辑，解码错误（非 UTF-8 名）会变成
            // U+FFFD，不会产生多余的换行，不影响行计数。
            this.in = new BufferedReader(
                    new InputStreamReader(stream, Charset.forName("UTF-8")), 8192);
        }

        @Override public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    // 表行形如：
                    //   -rw-------  0 0      0         200 Aug 16 16:48 dir/a.txt
                    // 警告/错误行不匹配这个外形，直接跳过。
                    if (line.isEmpty()) continue;
                    char c0 = line.charAt(0);
                    if (c0 != '-' && c0 != 'd') continue;
                    String[] parts = line.split("\\s+", 9);
                    if (parts.length < 9) { reliable = false; continue; }
                    long size;
                    try {
                        size = Long.parseLong(parts[4]);
                    } catch (Throwable t) {
                        reliable = false;
                        size = 0L;
                    }
                    sizes.add(size);
                }
            } catch (Throwable ignore) {
                // 进程被 destroy 时流会断，由调用方按退出码判定成败。
            }
        }

        EntryTable table() {
            long[] arr = new long[sizes.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = sizes.get(i);
            return new EntryTable(arr, reliable);
        }
    }

    /**
     * 校验 zip 结构是否合法。替代 {@code java.util.zip.ZipFile} 的打开测试：
     * 能列出条目表即视为可解析。
     */
    public static boolean isValid(File zip) {
        EntryTable t = list(zip);
        return t != null && t.count > 0;
    }

    // -----------------------------------------------------------------
    // 解压（流式进度）
    // -----------------------------------------------------------------

    /** 解压进度。{@code doneBytes} 仅在条目大小表完整时精确，否则恒为 0。 */
    public interface ExtractProgress {
        void onProgress(int doneEntries, long doneBytes);
    }

    /**
     * 解压 zip 到目标目录。用 {@code bsdtar -xvf} 整包解压，逐行数 stderr 的
     * {@code x <名字>} 汇报进度（行出现 = 该条目已写完）。
     *
     * <p>调用方需自行预检磁盘空间（{@code CNArchiveInstallTx} 用
     * {@link EntryTable#totalBytes} 做精确预检）。中断/失败则整包重解。
     *
     * @param table {@link #list} 的结果（可为 null，则进度只有条目数）
     */
    public static boolean extract(File zip, File dest, EntryTable table,
                                  ExtractProgress cb) {
        if (zip == null || dest == null || !zip.isFile() || !dest.isDirectory()) {
            return false;
        }
        if (!isAvailable()) return false;
        if (!busy.compareAndSet(false, true)) {
            CNLog.w(TAG, "已有解压在进行，拒绝并发: " + zip.getName());
            return false;
        }
        Process p = null;
        try {
            File bin = binFile();
            if (bin == null) return false;
            p = new ProcessBuilder(bin.getAbsolutePath(), "-xvf", zip.getAbsolutePath(),
                    "-C", dest.getAbsolutePath())
                    .redirectErrorStream(true).start();
            ExtractJob job = new ExtractJob(p.getInputStream(), table, cb);
            Thread t = new Thread(job, "cnzip-extract");
            t.setDaemon(true);
            t.start();
            boolean exited = p.waitFor(60L * 60L, TimeUnit.SECONDS);
            if (!exited) {
                CNLog.w(TAG, "bsdtar 解压超时(1h): " + zip.getName());
                try { p.destroy(); } catch (Throwable ignore) {}
                return false;
            }
            t.join(2000);
            return p.exitValue() == 0;
        } catch (Throwable t) {
            CNLog.w(TAG, "bsdtar 解压失败: " + t);
            return false;
        } finally {
            busy.set(false);
        }
    }

    /** {@code -xvf} 输出的逐行计数。静态嵌套类：构造参数传值，无 this$0。 */
    private static final class ExtractJob implements Runnable {
        private final BufferedReader in;
        private final EntryTable table;
        private final ExtractProgress cb;
        private int done;
        private long doneBytes;

        ExtractJob(InputStream stream, EntryTable table, ExtractProgress cb) {
            this.in = new BufferedReader(
                    new InputStreamReader(stream, Charset.forName("UTF-8")), 8192);
            this.table = table;
            this.cb = cb;
        }

        @Override public void run() {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    // 只数「x 」开头的条目行；错误/警告行（若有）不进计数。
                    if (!line.startsWith("x ")) continue;
                    int idx = done;
                    done++;
                    if (table != null && table.sizesReliable && idx < table.count) {
                        doneBytes += table.sizes[idx];
                    }
                    if (cb != null) {
                        cb.onProgress(Math.min(done, table == null ? done : table.count),
                                doneBytes);
                    }
                }
            } catch (Throwable ignore) {
                // 进程被 destroy 时流会断，由调用方按退出码判定成败。
            }
        }
    }

    // -----------------------------------------------------------------
    // 二进制提取与探测（复用 CNAria2 的模式）
    // -----------------------------------------------------------------

    private static File binFile() {
        File dir = new File(CNPaths.filesDir(), "bsdtar");
        return new File(dir, "bsdtar");
    }

    /**
     * 真跑一次 {@code bsdtar --version} 验证可执行（有界 5s，同
     * {@link CNAria2} 的 checkExecutable）。失败时把异常原文记进日志——
     * {@code error=13} 是 SELinux exec 闸，{@code error=8} 是 16KB 页不对齐，
     * 真机日志里一眼可辨。
     */
    private static boolean probeExec() {
        File bin = binFile();
        try {
            Process p = new ProcessBuilder(bin.getAbsolutePath(), "--version")
                    .redirectErrorStream(true).start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                try { p.destroy(); } catch (Throwable ignore) {}
                CNLog.w(TAG, "bsdtar --version 超时(5s)");
                return false;
            }
            if (p.exitValue() != 0) {
                CNLog.w(TAG, "bsdtar --version 退出码 " + p.exitValue());
                return false;
            }
            return true;
        } catch (Throwable t) {
            CNLog.w(TAG, "bsdtar 探测执行失败（exec 被系统拦截？）: " + t);
            return false;
        }
    }

    private static boolean ensureBinary() {
        String abi = primaryAbi();
        String asset = null;
        long expect = -1;
        for (String[] row : ASSETS) {
            if (abi != null && abi.startsWith(row[0])) {
                asset = row[1];
                try { expect = Long.parseLong(row[2]); } catch (Throwable ignore) {}
                break;
            }
        }
        if (asset == null) {
            CNLog.w(TAG, "不支持的 ABI: " + abi + "（bsdtar 不可用）");
            return false;
        }
        try {
            File dir = new File(CNPaths.filesDir(), "bsdtar");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                CNLog.w(TAG, "创建 bsdtar 目录失败: " + dir);
                return false;
            }
            File bin = new File(dir, "bsdtar");
            if (bin.isFile() && bin.length() == expect) {
                bin.setExecutable(true, false);
                return true;
            }
            Context ctx = appContext();
            if (ctx == null) {
                CNLog.w(TAG, "拿不到 Application Context，无法解压 bsdtar");
                return false;
            }
            InputStream in = ctx.getAssets().open(asset);
            try {
                File tmp = new File(dir, "bsdtar.tmp");
                FileOutputStream fos = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                } finally {
                    try { fos.close(); } catch (Throwable ignore) {}
                }
                if (tmp.length() != expect) {
                    CNLog.w(TAG, "bsdtar 资产尺寸不符 expected=" + expect + " actual=" + tmp.length());
                    deleteQuietly(tmp);
                    return false;
                }
                if (!tmp.renameTo(bin)) {
                    CNLog.w(TAG, "bsdtar 改名失败: " + tmp);
                    deleteQuietly(tmp);
                    return false;
                }
                bin.setExecutable(true, false);
                return true;
            } finally {
                try { in.close(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "bsdtar 提取异常: " + t);
            return false;
        }
    }

    private static String primaryAbi() {
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                return Build.SUPPORTED_ABIS[0];
            }
            return Build.CPU_ABI;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Context appContext() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object thread = cls.getMethod("currentActivityThread").invoke(null);
            return (Context) cls.getMethod("getApplication").invoke(thread);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try { f.delete(); } catch (Throwable ignore) {}
        }
    }
}
