package io.kamihama.magianative;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * 统一日志。
 *
 * <p>安装器（{@link CNDownloaderFix}）、线路目录（{@link CNMirrors}）与分片下载
 * （{@link CNChunkedDownload}）都往这里写，{@link CNCNDownloadUI} 的 LOG 面板直接
 * 渲染同一份缓冲区——这样玩家在设备上看到的内容和 logcat 是同一套，出问题时不必
 * 接电脑抓日志。
 *
 * <p>三个去向，任何一个失败都不影响其它两个：
 * <ul>
 *   <li><b>logcat</b>：按级别转发到 {@link Log}，tag 为各模块原本的 tag；</li>
 *   <li><b>内存环形缓冲</b>：最多 {@link #BUFFER_MAX} 条，供 LOG 面板与「复制全部」；</li>
 *   <li><b>文件</b>：{@code <files>/cnv_installer.log}，进程被杀也留得下来。</li>
 * </ul>
 *
 * <p>本类在 UI 出现之前就可能被调用（安装器先于浮层启动），所以所有方法都必须
 * 在未 {@link #init} 的状态下安全工作：此时只是不写文件，缓冲与 logcat 照常。
 */
public final class CNLog {

    /** 内存缓冲保留的最大条数。 */
    public static final int BUFFER_MAX = 1000;

    /** 日志文件名。 */
    private static final String LOG_FILE = "cnv_installer.log";

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private static final ArrayDeque<String> BUFFER = new ArrayDeque<String>();
    private static final Object FILE_LOCK = new Object();

    private static BufferedWriter writer;
    /**
     * 本进程内是否已经打开过日志文件。
     *
     * <p>浮层在一次进程里会被建起来不止一次：安装器跑完 {@code hide()} 会关掉日志，
     * 随后的热更新又会 {@code show()} 一次。若每次都以截断模式打开，热更新一开始
     * 就会把安装阶段的日志冲掉——而那恰恰是出问题时最需要的部分。
     * 因此只有本进程第一次打开时截断，之后一律追加。
     */
    private static boolean openedOnce = false;
    /** 缓冲区有新内容时被调用（UI 用它刷新 LOG 面板）；可为 null。 */
    private static volatile Runnable listener;

    private CNLog() {}

    /**
     * 绑定日志文件目录。重复调用是安全的（会先关掉旧文件）。
     * 未调用时本类依然可用，只是不落盘。
     */
    public static void init(File dir) {
        synchronized (FILE_LOCK) {
            closeWriterLocked();
            if (dir == null) return;
            try {
                if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return;
                File f = new File(dir, LOG_FILE);
                boolean append = openedOnce;
                writer = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(f, append), "UTF-8"));
                writer.write((append ? "---- 日志继续（" : "==== 魔法纪录 资源安装器日志（开始于 ")
                        + TS.format(new Date())
                        + (append ? "） ----\n" : "） ====\n"));
                writer.flush();
                openedOnce = true;
            } catch (Throwable t) {
                writer = null;
                Log.w("CNLog", "日志文件打开失败: " + t);
            }
        }
    }

    /** 关闭日志文件。 */
    public static void close() {
        synchronized (FILE_LOCK) {
            closeWriterLocked();
        }
    }

    private static void closeWriterLocked() {
        if (writer != null) {
            try { writer.flush(); } catch (Throwable ignore) {}
            try { writer.close(); } catch (Throwable ignore) {}
            writer = null;
        }
    }

    /** 注册缓冲区变更回调；传 null 取消。 */
    public static void setListener(Runnable r) {
        listener = r;
    }

    // ---- 写入 ----

    public static void i(String component, String msg) { write(component, "INFO",  msg, null); }
    public static void w(String component, String msg) { write(component, "WARN",  msg, null); }
    public static void e(String component, String msg) { write(component, "ERROR", msg, null); }
    public static void w(String component, String msg, Throwable t) { write(component, "WARN",  msg, t); }
    public static void e(String component, String msg, Throwable t) { write(component, "ERROR", msg, t); }

    /**
     * 核心写入方法。格式：{@code ［yyyy-MM-dd HH:mm:ss］[组件][级别] 内容}
     * ——与复兴计划客户端 BootstrapActivity 的日志格式一致。
     */
    public static void write(String component, String level, String msg, Throwable t) {
        String comp = component == null ? "应用" : component;
        String lvl  = level     == null ? "INFO" : level;
        String text = msg == null ? "" : msg;
        if (t != null) text = text + " / " + t;

        // 1) logcat
        try {
            if ("ERROR".equals(lvl) || "FATAL".equals(lvl)) {
                if (t != null) Log.e(comp, text, t); else Log.e(comp, text);
            } else if ("WARN".equals(lvl)) {
                if (t != null) Log.w(comp, text, t); else Log.w(comp, text);
            } else {
                Log.i(comp, text);
            }
        } catch (Throwable ignore) {}

        // 2) 内存缓冲
        String line;
        synchronized (TS) {
            line = "［" + TS.format(new Date()) + "］[" + comp + "][" + lvl + "] " + text;
        }
        synchronized (BUFFER) {
            BUFFER.addLast(line);
            while (BUFFER.size() > BUFFER_MAX) BUFFER.removeFirst();
        }

        // 3) 文件
        synchronized (FILE_LOCK) {
            if (writer != null) {
                try {
                    writer.write(line);
                    writer.write('\n');
                    writer.flush();
                } catch (Throwable ignore) {
                    // 落盘失败不影响前两个去向
                }
            }
        }

        Runnable r = listener;
        if (r != null) {
            try { r.run(); } catch (Throwable ignore) {}
        }
    }

    // ---- 读取 ----

    /** 当前缓冲区的全部内容（每行一条）。 */
    public static String snapshot() {
        StringBuilder sb = new StringBuilder();
        synchronized (BUFFER) {
            Iterator<String> it = BUFFER.iterator();
            while (it.hasNext()) sb.append(it.next()).append('\n');
        }
        return sb.toString();
    }

    /** 当前缓冲区条数。 */
    public static int size() {
        synchronized (BUFFER) {
            return BUFFER.size();
        }
    }
}
