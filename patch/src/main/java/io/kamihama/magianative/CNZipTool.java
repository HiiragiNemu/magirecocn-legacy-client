package io.kamihama.magianative;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置 bsdtar（libarchive 3.7.4，全静态）的包装：用子进程解压 zip，替代
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
 * <h3>为什么不是 ZIP64 → ZipFile</h3>
 *
 * 与其修 Android ZipFile，不如绕开它。子进程解压把 zip 解析完全交给经过验证的
 * bsdtar，不依赖系统实现，也不受「CI 里某个 zip 依赖悄悄升级」这类回归影响——
 * 这正是当初选内置二进制的理由。
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

    /** bsdtar 是否可用（二进制已解压、可执行）。 */
    public static boolean isAvailable() {
        if (initDone) return available;
        synchronized (INIT_LOCK) {
            if (initDone) return available;
            boolean ok = false;
            try {
                ok = ensureBinary();
            } catch (Throwable t) {
                CNLog.w(TAG, "bsdtar 初始化异常: " + t);
                ok = false;
            }
            available = ok;
            initDone = true;
            return available;
        }
    }

    /**
     * 校验 zip 结构是否合法。替代 {@link CNArchiveValidate#isZipStructurallyValid}：
     * 用 {@code bsdtar -tf} 列出条目，成功即视为可解析。
     */
    public static boolean isValid(File zip) {
        if (zip == null || !zip.isFile() || !isAvailable()) return false;
        try {
            File bin = binFile();
            if (bin == null) return false;
            Process p = new ProcessBuilder(bin.getAbsolutePath(), "-tf", zip.getAbsolutePath())
                    .redirectErrorStream(true).start();
            drain(p);
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Throwable t) {
            CNLog.w(TAG, "bsdtar 结构校验失败: " + t);
            return false;
        }
    }

    /**
     * 解压 zip 到目标目录。用 {@code bsdtar -xf} 整包解压。
     *
     * <p>替代 {@code java.util.zip.ZipFile} 的逐条目解压。调用方需自行预检磁盘
     * 空间与解压比例（{@code CNArchiveInstallTx} 保留那两道防线）。
     *
     * @param zip  源 zip
     * @param dest 解压目标目录（需已存在）
     * @return 是否成功
     */
    public static boolean extract(File zip, File dest) {
        if (zip == null || dest == null || !zip.isFile() || !dest.isDirectory()) {
            return false;
        }
        if (!isAvailable()) return false;
        if (!busy.compareAndSet(false, true)) {
            CNLog.w(TAG, "已有解压在进行，拒绝并发: " + zip.getName());
            return false;
        }
        try {
            File bin = binFile();
            if (bin == null) return false;
            Process p = new ProcessBuilder(bin.getAbsolutePath(), "-xf", zip.getAbsolutePath(),
                    "-C", dest.getAbsolutePath())
                    .redirectErrorStream(true).start();
            drain(p);
            return p.waitFor(60 * 60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Throwable t) {
            CNLog.w(TAG, "bsdtar 解压失败: " + t);
            return false;
        } finally {
            busy.set(false);
        }
    }

    // -----------------------------------------------------------------
    // 二进制提取（复用 CNAria2 的模式）
    // -----------------------------------------------------------------

    private static File binFile() {
        String abi = primaryAbi();
        if (abi == null) return null;
        File dir = new File(CNPaths.filesDir(), "bsdtar");
        return new File(dir, "bsdtar");
    }

    private static boolean ensureBinary() {
        String abi = primaryAbi();
        String asset = null;
        long expect = -1;
        for (String[] row : ASSETS) {
            if (abi.startsWith(row[0])) {
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

    private static void drain(Process p) {
        try {
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            while (in.read(buf) >= 0) {
                // 排空管道，避免子进程写满缓冲区阻塞。
            }
        } catch (Throwable ignore) {}
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
