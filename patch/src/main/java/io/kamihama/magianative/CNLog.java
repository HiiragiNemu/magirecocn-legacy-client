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

    /**
     * 内存缓冲保留的最大条数。
     * 接入 logcat 后条目量会明显上升，所以放宽到 3000——诊断时宁可多留一些。
     */
    public static final int BUFFER_MAX = 3000;

    /** 本模块自己的 tag：从 logcat 回收时要跳过，否则每条都会重复一遍。 */
    private static final String[] OWN_TAGS = {
        "MagiaCNDownloader", "MagiaCNChunk", "MagiaCNMirrors",
        "MagiaCNHotUpdate", "CNLog", "界面"
    };

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
    /** writeRaw 的落盘计数：logcat 量大，逐行 flush 会造成明显的 I/O 压力。 */
    private static int rawSinceFlush = 0;

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
        rawSinceFlush = 0;
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

    /**
     * 只写入缓冲与文件，**不**转发给 {@link Log}。
     * 供 logcat 回收线程使用：那些行本来就来自 logcat，再转发一次会形成回环。
     */
    public static void writeRaw(String line) {
        if (line == null || line.length() == 0) return;
        synchronized (BUFFER) {
            BUFFER.addLast(line);
            while (BUFFER.size() > BUFFER_MAX) BUFFER.removeFirst();
        }
        synchronized (FILE_LOCK) {
            if (writer != null) {
                try {
                    writer.write(line);
                    writer.write('\n');
                    // 每 50 行才落一次盘。逐行 flush 在 logcat 的量级下会把
                    // 磁盘打满，反过来拖慢整个进程。自己模块的日志（write）
                    // 仍然逐条 flush，那条路量小且更需要即时性。
                    if (++rawSinceFlush >= 50) {
                        writer.flush();
                        rawSinceFlush = 0;
                    }
                } catch (Throwable ignore) {}
            }
        }
        Runnable r = listener;
        if (r != null) {
            try { r.run(); } catch (Throwable ignore) {}
        }
    }

    // ---- logcat 回收 ----

    private static volatile Process logcatProc;
    private static volatile Thread  logcatThread;

    /**
     * 起一个后台线程读 logcat，把整机日志并入本缓冲区。
     *
     * <p>这样 LOG 面板里能直接看到 native hook（{@code MagiaClientJNI}）、引擎、
     * 以及任何 Java 异常栈——出问题时不必接电脑。重复调用是安全的。
     *
     * <p>只从「当前时刻」开始读（{@code -T 1}），不回灌历史，否则开局就会把
     * 缓冲区冲满。自己模块打的行会被跳过，避免与 {@link #write} 的记录重复。
     */
    public static synchronized void startLogcatCapture() {
        if (logcatThread != null) return;
        Thread t = new Thread(new LogcatReader(), "cnv-logcat");
        t.setDaemon(true);
        logcatThread = t;
        t.start();
    }

    /** 停止 logcat 回收。 */
    public static synchronized void stopLogcatCapture() {
        Process p = logcatProc;
        logcatProc = null;
        logcatThread = null;
        if (p != null) {
            try { p.destroy(); } catch (Throwable ignore) {}
        }
    }

    private static final class LogcatReader implements Runnable {
        @Override public void run() {
            java.io.BufferedReader br = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "logcat", "-v", "time", "-T", "1");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                logcatProc = p;
                write("日志", "INFO", "logcat 回收已启动", null);
                br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (logcatProc == null) break;
                    if (isOwnLine(line)) continue;   // 跳过自己打的，避免重复
                    writeRaw(line);
                }
            } catch (Throwable t) {
                try { write("日志", "WARN", "logcat 回收不可用: " + t, null); } catch (Throwable ignore) {}
            } finally {
                if (br != null) { try { br.close(); } catch (Throwable ignore) {} }
            }
        }

        /** logcat 的 time 格式里 tag 出现在冒号之前，用包含判断即可。 */
        private boolean isOwnLine(String line) {
            for (int i = 0; i < OWN_TAGS.length; i++) {
                if (line.contains(OWN_TAGS[i])) return true;
            }
            return false;
        }
    }

    /**
     * 只取最后 {@code n} 条。
     *
     * <p>面板渲染用这个而不是 {@link #snapshot()}：缓冲上限 3000 行拼出来约
     * 300KB，每次刷新都把这么大一坨塞进 TextView 会直接把主线程压垮。
     * 「复制全部」仍然走 {@link #snapshot()}，那是一次性操作。
     */
    public static String tail(int n) {
        StringBuilder sb = new StringBuilder();
        synchronized (BUFFER) {
            int skip = BUFFER.size() - n;
            Iterator<String> it = BUFFER.iterator();
            int i = 0;
            while (it.hasNext()) {
                String line = it.next();
                if (i++ < skip) continue;
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

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
