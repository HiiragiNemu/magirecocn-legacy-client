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

    /**
     * 日志目录（相对各基准目录）。每次启动一个新文件，命名为
     * {@code <四位启动序号>_<yyyyMMdd-HHmmss>.log}，例如
     * {@code 0007_20260802-014530.log}。
     *
     * <p>为什么不是单文件轮转：这类问题常常「第 N 次启动才复现」，需要同时看到
     * 出问题那次**和它之前几次**的记录。按启动次数分文件，排序即时间顺序，
     * 一眼就能定位到第几次启动出的问题。
     */
    private static final String LOG_DIR   = "log";
    /** 启动序号计数器文件（放在日志目录内）。 */
    private static final String SEQ_FILE  = ".seq";
    /** 最多保留多少个历史日志文件，超出的按序号从旧到新删除。 */
    private static final int    KEEP_LOGS = 30;

    private static final SimpleDateFormat FILE_TS =
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);

    /** 本次启动的序号与文件名，供日志头与「复制全部」标注。 */
    private static volatile int    launchSeq  = 0;
    private static volatile String logName    = "";

    /** 本次启动写出的日志文件路径（用于在界面上告诉玩家去哪儿找）。 */
    public static String currentLogPath() {
        return PRIV_DIR + "/" + LOG_DIR + "/" + logName;
    }
    /** 日志目录（供界面提示玩家去哪儿找）。 */
    public static String logDirPath() {
        return PRIV_DIR + "/" + LOG_DIR;
    }
    public static int launchSeq() { return launchSeq; }

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    // ---- 日志来源。分开标记是为了让面板上的三个开关能**即时**生效：
    //      若在采集时就按开关丢弃，事后再打开开关也补不回来。
    /** 本补丁自己打的日志。 */
    public static final int SRC_APP    = 0;
    /** logcat 里其它进程 / 框架的日志。 */
    public static final int SRC_LOGCAT = 1;
    /** native / 引擎的日志（MagiaClientJNI、Cocos2dx 等）。 */
    public static final int SRC_NATIVE = 2;

    /** 一条日志。 */
    private static final class Entry {
        final int    src;
        final String line;
        Entry(int src, String line) { this.src = src; this.line = line; }
    }

    /** native / 引擎日志的判定关键字。命中即归入 {@link #SRC_NATIVE}。 */
    private static final String[] NATIVE_HINTS = {
        "MagiaClientJNI", "MagiaCNDownloader", "Cocos2dx", "cocos2d",
        "DownloadScene", "AssetLoad", "magia", "Magia", "MagiaCN_Legacy",
        "madomagi", "f4samurai"
    };

    private static final ArrayDeque<Entry> BUFFER = new ArrayDeque<Entry>();

    /** 面板显示开关（由 UI 侧从 SharedPreferences 载入后写入）。 */
    private static volatile boolean showLogcat = true;
    private static volatile boolean showNative = true;

    public static void setShowLogcat(boolean v) { showLogcat = v; }
    public static void setShowNative(boolean v) { showNative = v; }
    public static boolean isShowLogcat() { return showLogcat; }
    public static boolean isShowNative() { return showNative; }

    /** 该来源当前是否应显示。本补丁自己的日志永远显示。 */
    private static boolean visible(int src) {
        if (src == SRC_NATIVE) return showNative;
        if (src == SRC_LOGCAT) return showLogcat;
        return true;
    }
    private static final Object FILE_LOCK = new Object();

    /**
     * 应用数据目录（写死：早期初始化时拿不到 Context）。
     *
     * <p>日志落在 {@code <数据目录>/log/} 下。曾经额外往
     * {@code /sdcard/Android/data/<包名>/files/} 也写一份「方便取出」，已移除：
     * Android 10+ 的分区存储下，用写死路径去 mkdirs 这个应用专属外部目录基本
     * 是失败的（该目录须由框架经 getExternalFilesDir() 创建），写了也白写，
     * 反而让人误以为那里会有东西。
     */
    private static final String PRIV_DIR = "/data/data/io.kamihama.totentanz";

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
    /** 上次把 writeRaw 的内容落盘的时刻。 */
    private static long lastFlushMs = 0L;
    /** 落盘滞后上限：超过这个时间就强制 flush 一次，保证取出来的日志不缺尾巴。 */
    private static final long FLUSH_INTERVAL_MS = 1000L;

    private CNLog() {}

    /**
     * 绑定日志文件目录。重复调用是安全的（会先关掉旧文件）。
     * 未调用时本类依然可用，只是不落盘。
     */
    /**
     * 早期初始化：不需要 Context，直接用写死的应用目录。
     *
     * <p>为什么必须有这个：原先只有 {@link #init(File)} 一条路，而它是在浮层
     * 创建成功之后才被调用的。一旦「浮层没建起来」或「安装器根本没被调用」——
     * 也就是最需要日志的那两种情况——反而一个字节都不会落盘。
     *
     * <p>因此所有 native 入口（{@code runInstaller}、{@code getEndpoint}）都在
     * 方法体第一行调用本方法，保证日志从最早的时刻就开始记。重复调用无副作用。
     */
    public static synchronized void initEarly() {
        // 本方法由 native 入口在最外层调用，**绝不允许抛出**：调用方所在的
        // JNI 边界一旦拿到挂起异常，就会放行引擎自带的下载场景。
        // 日志起不来是可以接受的降级，把游戏搞坏不是。
        try {
            initEarlyInner();
        } catch (Throwable t) {
            try { Log.e("CNLog", "日志初始化失败（已忽略，不影响主流程）", t); }
            catch (Throwable ignore) {}
        }
    }

    private static void initEarlyInner() {
        if (openedOnce) return;
        init(new File(PRIV_DIR));
        startLogcatCapture();
        startFlusher();
        installCrashHandler();
        write("日志", "INFO", "日志已启动（第 " + launchSeq + " 次启动）"
                + " 文件=" + currentLogPath()
                + " 保留最近 " + KEEP_LOGS + " 次", null);
    }

    /**
     * 挂全局未捕获异常处理器：崩在哪个线程都要留下现场，然后再交回系统默认处理
     * （不吞掉，否则会改变进程原本的崩溃行为）。
     */
    private static void installCrashHandler() {
        try {
            final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (prev instanceof CrashHandler) return;
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(prev));
        } catch (Throwable t) {
            Log.w("CNLog", "无法安装崩溃处理器: " + t);
        }
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler prev;
        CrashHandler(Thread.UncaughtExceptionHandler prev) { this.prev = prev; }
        @Override public void uncaughtException(Thread t, Throwable e) {
            try {
                write("崩溃", "ERROR", "线程 " + t.getName() + " 未捕获异常", e);
                StackTraceElement[] st = e.getStackTrace();
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < st.length && i < 40; i++) {
                    sb.append("\n    at ").append(st[i]);
                }
                writeRaw("  调用栈:" + sb);
                synchronized (FILE_LOCK) {
                    if (writer != null) try { writer.flush(); } catch (Throwable ignore) {}
                }
            } catch (Throwable ignore) {}
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    public static void init(File dir) {
        synchronized (FILE_LOCK) {
            closeWriterLocked();
            if (dir == null) return;
            boolean append = openedOnce;
            if (!append) {
                // 本进程第一次开：分配启动序号并定下文件名，两个目录用同一个名字
                Date now = new Date();
                launchSeq = nextSeq(new File(dir, LOG_DIR));
                logName   = String.format(Locale.US, "%04d_%s.log",
                                          launchSeq, FILE_TS.format(now));
            }
            String head = (append ? "---- 日志继续（" : "==== 魔法纪录 资源安装器日志（第 "
                        + launchSeq + " 次启动，开始于 ")
                    + TS.format(new Date())
                    + (append ? "） ----\n" : "） ====\n");
            writer = openOne(new File(dir, LOG_DIR), append, head);
            if (writer != null) openedOnce = true;
            else Log.w("CNLog", "日志目录打不开: " + new File(dir, LOG_DIR));
        }
    }

    /**
     * 打开一份日志文件。首次打开时把上一次的内容轮转成 {@code .prev.log}——
     * 这个 bug 的表现是「第二次启动才出问题」，若直接截断，恰恰会把第一次
     * （也就是出问题前那一次）的记录冲掉。
     */
    private static BufferedWriter openOne(File dir, boolean append, String head) {
        try {
            if (dir == null) return null;
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return null;
            if (!append) pruneOldLogs(dir);
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(dir, logName), append), "UTF-8"));
            w.write(head);
            w.flush();
            return w;
        } catch (Throwable t) {
            Log.w("CNLog", "日志文件打开失败 " + dir + ": " + t);
            return null;
        }
    }

    /**
     * 取本次启动序号：读 {@code log/.seq} 加一再写回。
     *
     * <p>读不到（首次运行、文件损坏）时退化为「已有日志文件数 + 1」，
     * 这样即便计数器丢了，序号也不会倒退回去覆盖旧文件名。
     */
    private static int nextSeq(File logDir) {
        int seq = 0;
        File f = new File(logDir, SEQ_FILE);
        try {
            if (!logDir.isDirectory()) logDir.mkdirs();
            if (f.isFile()) {
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                try {
                    String line = br.readLine();
                    if (line != null) seq = Integer.parseInt(line.trim());
                } finally { try { br.close(); } catch (Throwable ignore) {} }
            }
        } catch (Throwable ignore) {}
        if (seq <= 0) {
            String[] names = logDir.list();
            seq = names == null ? 0 : names.length;
        }
        seq++;
        try {
            java.io.Writer w = new OutputStreamWriter(new FileOutputStream(f, false), "UTF-8");
            try { w.write(Integer.toString(seq)); w.flush(); }
            finally { try { w.close(); } catch (Throwable ignore) {} }
        } catch (Throwable ignore) {}
        return seq;
    }

    /** 只保留最近 {@link #KEEP_LOGS} 个日志文件，多余的按文件名（含序号）从旧删起。 */
    private static void pruneOldLogs(File logDir) {
        try {
            String[] names = logDir.list();
            if (names == null || names.length <= KEEP_LOGS) return;
            java.util.ArrayList<String> logs = new java.util.ArrayList<String>();
            for (int i = 0; i < names.length; i++) {
                if (names[i].endsWith(".log")) logs.add(names[i]);
            }
            if (logs.size() <= KEEP_LOGS) return;
            java.util.Collections.sort(logs);   // 名字以四位序号开头，字典序即时间序
            int remove = logs.size() - KEEP_LOGS;
            for (int i = 0; i < remove; i++) {
                try { new File(logDir, logs.get(i)).delete(); } catch (Throwable ignore) {}
            }
            Log.i("CNLog", "清理了 " + remove + " 个旧日志");
        } catch (Throwable ignore) {}
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
            BUFFER.addLast(new Entry(SRC_APP, line));
            while (BUFFER.size() > BUFFER_MAX) BUFFER.removeFirst();
        }

        // 3) 文件
        synchronized (FILE_LOCK) {
            writeFileLocked(line, true);
        }

        Runnable r = listener;
        if (r != null) {
            try { r.run(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 强制把攒着的内容落盘。定时线程与「玩家点复制日志」都会调用。
     *
     * <p>writeRaw 的行数阈值只在**还有新行进来**时才会被跨过。如果 logcat 恰好
     * 安静下来（或 logcat 回收被关掉），最后那几行会一直留在 BufferedWriter 里
     * 出不去——玩家此刻把文件取走，看到的就是一份缺尾巴的日志。
     */
    public static void flushNow() {
        synchronized (FILE_LOCK) {
            if (writer == null) return;
            if (rawSinceFlush == 0) return;   // 没有欠账，不做无谓的 I/O
            try { writer.flush(); } catch (Throwable ignore) {}
            rawSinceFlush = 0;
            lastFlushMs = System.currentTimeMillis();
        }
    }

    private static volatile Thread flusherThread;

    /** 起定时 flush 线程；重复调用安全。 */
    private static synchronized void startFlusher() {
        if (flusherThread != null) return;
        Thread t = new Thread(new Flusher(), "cnv-log-flush");
        t.setDaemon(true);
        flusherThread = t;
        t.start();
    }

    private static final class Flusher implements Runnable {
        @Override public void run() {
            while (true) {
                try { Thread.sleep(FLUSH_INTERVAL_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                try { flushNow(); } catch (Throwable ignore) {}
            }
        }
    }

    /** 往日志文件写一行。调用方必须持有 FILE_LOCK。 */
    private static void writeFileLocked(String line, boolean flush) {
        if (writer != null) {
            try {
                writer.write(line); writer.write('\n');
                if (flush) writer.flush();
            } catch (Throwable ignore) {}
        }
    }

    // ---- 读取 ----

    /**
     * 只写入缓冲与文件，**不**转发给 {@link Log}。
     * 供 logcat 回收线程使用：那些行本来就来自 logcat，再转发一次会形成回环。
     */
    public static void writeRaw(String line) {
        writeRaw(line, SRC_LOGCAT);
    }

    /** 同上，指定来源。 */
    public static void writeRaw(String line, int src) {
        if (line == null || line.length() == 0) return;
        synchronized (BUFFER) {
            BUFFER.addLast(new Entry(src, line));
            while (BUFFER.size() > BUFFER_MAX) BUFFER.removeFirst();
        }
        synchronized (FILE_LOCK) {
            // 逐行 flush 在 logcat 的量级下 I/O 压力太大，会反过来拖慢整个进程，
            // 所以要攒。但**只按行数攒是错的**：上一版「每 50 行落一次盘」的结果是
            // 玩家把日志文件取出来时，末尾不足 50 行永远留在 BufferedWriter 里，
            // 文件正好断在 50 的整数倍上——而断掉的那一截恰恰是出问题的现场。
            // 现在改成「满 50 行 **或** 距上次落盘超过 1 秒」，再配合下面的定时
            // flush 线程兜住「日志突然安静下来」的情况：落盘滞后不超过约 1 秒。
            long now = System.currentTimeMillis();
            boolean doFlush = (++rawSinceFlush >= 50)
                    || (now - lastFlushMs >= FLUSH_INTERVAL_MS);
            if (doFlush) { rawSinceFlush = 0; lastFlushMs = now; }
            writeFileLocked(line, doFlush);
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
                // 回灌最近 1000 行，而不是只从当前时刻开始。
                //
                // 进程启动最早的一段（各 native 库的 JNI_OnLoad）恰恰是最需要看的：
                // libMagiaLegacy 就在那里装 hook 并打出「[Hook] ✓ / ✗ X」
                // 「Unable to hook X」。而 CNLog 要等 native 调进 Java 才起得来，
                // 用 -T 1 的话这些行早就过去了，永远抓不到。
                //
                // 1000 行相对 BUFFER_MAX=3000 是可接受的开销，且文件里本来就全都有。
                ProcessBuilder pb = new ProcessBuilder(
                        "logcat", "-v", "time", "-T", "1000");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                logcatProc = p;
                write("日志", "INFO", "logcat 回收已启动", null);
                br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (logcatProc == null) break;
                    if (isOwnLine(line)) continue;   // 跳过自己打的，避免与 write() 重复
                    writeRaw(line, classify(line));
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
        java.util.ArrayList<String> keep = new java.util.ArrayList<String>();
        synchronized (BUFFER) {
            Iterator<Entry> it = BUFFER.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (visible(e.src)) keep.add(e.line);
            }
        }
        StringBuilder sb = new StringBuilder();
        int skip = keep.size() - n;
        for (int i = 0; i < keep.size(); i++) {
            if (i < skip) continue;
            sb.append(keep.get(i)).append('\n');
        }
        return sb.toString();
    }

    /** 当前开关下可见的条数（面板上「共 N 行」用）。 */
    public static int visibleSize() {
        int n = 0;
        synchronized (BUFFER) {
            Iterator<Entry> it = BUFFER.iterator();
            while (it.hasNext()) if (visible(it.next().src)) n++;
        }
        return n;
    }

    /** 当前缓冲区的全部内容（每行一条）。 */
    public static String snapshot() {
        StringBuilder sb = new StringBuilder();
        synchronized (BUFFER) {
            Iterator<Entry> it = BUFFER.iterator();
            while (it.hasNext()) {
                Entry e = it.next();
                if (visible(e.src)) sb.append(e.line).append('\n');
            }
        }
        return sb.toString();
    }

    /** 当前缓冲区条数。 */
    public static int size() {
        synchronized (BUFFER) {
            return BUFFER.size();
        }
    }

    /** 判定一行 logcat 是否来自 native / 引擎。 */
    static int classify(String line) {
        for (int i = 0; i < NATIVE_HINTS.length; i++) {
            if (line.contains(NATIVE_HINTS[i])) return SRC_NATIVE;
        }
        return SRC_LOGCAT;
    }
}
