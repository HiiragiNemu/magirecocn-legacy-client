package io.kamihama.magianative;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;

import io.kamihama.magianative.CNAria2Lib.Backend;

/**
 * aria2 双后端 dead-man's switch。
 *
 * <p>原理：原生崩溃（SIGSEGV/SIGABRT，如当年 TLS 路径 {@code br 0}）会整体杀死
 * 进程，Java 层没有任何 catch 机会。所以在「启动 aria2 之前」落盘一个 armed
 * 标记；只有 RPC 确认干净关停后才 disarm。下次启动若发现标记仍是 armed，
 * 说明上一轮死于非命——自动换另一个后端（openssl ↔ gnutls）。
 *
 * <p>两档故障分开处理：
 * <ul>
 *   <li><b>加载期失败（UnsatisfiedLinkError）</b>：可捕获，{@link #shouldFallbackOnLoadError}
 *       当场换后端，不等下次启动；</li>
 *   <li><b>原生崩溃</b>：靠 armed 标记跨进程生效——进程死了，finally 来不及跑，
 *       标记留在盘上，下次 {@link #pickBackend()} 读到就换。</li>
 * </ul>
 *
 * <p>防抖：两组 so 的 BT/HTTP 协议栈是同一份 aria2 代码，若崩溃由负载触发
 * （而非 TLS 后端），换组也会连环炸。故连续 armed-death 达到
 * {@value #MAX_CONSECUTIVE_DEATHS} 后 {@link #giveUp()} 返回 true——调用方
 * 应停切、回退自建引擎，避免乒乓。giveUp 后 aria2 在该设备持续禁用，直到
 * **客户端版本变更**（{@link CNUserAgent#clientVersion()} 与标记里的 gen 不符）
 * 自动整体重置——新版本可能修了后端，计数不该跨版本继承。
 *
 * <p>标记是<b>文件</b>（{@code <filesDir>/madomagi/magica/aria2_failover}），
 * 不用 SharedPreferences——本层刻意不依赖 Context（补丁层在 Context 就绪前
 * 就要能跑，见 {@link CNPaths} 的说明）。写盘走「临时文件 + rename」保证原子。
 *
 * <p>用法（下载线程，首次用 aria2 之前）：
 * <pre>
 *   Backend b = Aria2EngineFailover.pickBackend();          // 读标记，决定本进程用谁
 *   try {
 *       CNAria2Lib.load(b);
 *   } catch (UnsatisfiedLinkError e) {
 *       b = Aria2EngineFailover.shouldFallbackOnLoadError(b); // 可能已 giveUp
 *       CNAria2Lib.load(b);
 *   }
 *   Aria2EngineFailover.arm();                               // 在 nativeStart 之前
 *   CNAria2Lib.start(args);
 *   ... RPC 驱动 ...
 *   // RPC aria2.shutdown 且 waitStopped 返回后：
 *   Aria2EngineFailover.disarm();                            // 干净关停，清除标记
 * </pre>
 */
public final class Aria2EngineFailover {
    private static final String TAG = "Aria2EngineFailover";

    /** 连续 armed-death 上限：两组都炸过就停切（4 个进程生命周期 = 每组 2 次机会）。 */
    private static final int MAX_CONSECUTIVE_DEATHS = 4;

    private Aria2EngineFailover() {}

    // ---- 盘面：backend=… / armed=… / deaths=… / gen=… 四行 ----
    // gen = 写入时的客户端版本；读出来不一致说明发了新版本 → 整体重置。
    // 标记目录默认 <filesDir>/madomagi/magica；测试可用系统属性
    // "aria2.failover.dir" 覆盖（Android 上该属性不存在，生产路径不受影响）。

    private static String markerDir() {
        String over = System.getProperty("aria2.failover.dir");
        return over != null ? over : CNPaths.filesDir() + "/madomagi/magica";
    }

    private static File marker() { return new File(markerDir(), "aria2_failover"); }
    private static File tmp()    { return new File(markerDir(), "aria2_failover.tmp"); }

    private static final class State {
        Backend backend = Backend.OSSL;
        boolean armed;
        int deaths;
        String gen = "";
    }

    private static synchronized State read() {
        State s = new State();
        File m = marker();
        if (m.isFile()) {
            BufferedReader in = null;
            try {
                in = new BufferedReader(new InputStreamReader(
                        new FileInputStream(m), Charset.forName("UTF-8")));
                String line;
                while ((line = in.readLine()) != null) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    if (k.equals("backend")) {
                        try { s.backend = Backend.valueOf(v); } catch (Throwable ignore) {}
                    } else if (k.equals("armed")) {
                        s.armed = "1".equals(v);
                    } else if (k.equals("deaths")) {
                        try { s.deaths = Math.max(0, Integer.parseInt(v)); } catch (Throwable ignore) {}
                    } else if (k.equals("gen")) {
                        s.gen = v;
                    }
                }
            } catch (Throwable t) {
                CNLog.w(TAG, "读 failover 标记失败（按默认处理）: " + t);
            } finally {
                if (in != null) { try { in.close(); } catch (Throwable ignore) {} }
            }
        }
        // 版本变更即重置：新版本可能修了后端/换了构建，giveUp 计数不该跨版本继承。
        // 重置成全新状态（默认 OSSL、未 armed、deaths=0）并把版本号烙进标记。
        if (!CNUserAgent.clientVersion().equals(s.gen)) {
            s = new State();
            s.gen = CNUserAgent.clientVersion();
            write(s.backend, s.armed, s.deaths);
        }
        return s;
    }

    private static synchronized void write(Backend backend, boolean armed, int deaths) {
        try {
            File dir = new File(markerDir());
            if (!dir.isDirectory() && !dir.mkdirs()) {
                CNLog.w(TAG, "创建标记目录失败: " + dir);
                return;
            }
            FileOutputStream out = new FileOutputStream(tmp());
            try {
                StringBuilder sb = new StringBuilder(64);
                sb.append("backend=").append(backend.name()).append('\n');
                sb.append("armed=").append(armed ? "1" : "0").append('\n');
                sb.append("deaths=").append(deaths).append('\n');
                sb.append("gen=").append(CNUserAgent.clientVersion()).append('\n');
                out.write(sb.toString().getBytes(Charset.forName("UTF-8")));
                out.flush();
                out.getFD().sync();
            } finally {
                try { out.close(); } catch (Throwable ignore) {}
            }
            // 同目录 rename 成功时由文件系统原子替换。失败时绝不能先删旧标记：
            // 旧的 armed/deaths 即便稍旧，也比“没有任何状态”更可信。
            File m = marker();
            if (!tmp().renameTo(m)) {
                CNLog.w(TAG, "写入 failover 标记失败（rename）；保留旧标记: " + m);
                try { tmp().delete(); } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "写 failover 标记失败: " + t);
        }
    }

    // ---- 对外接口 ----

    /**
     * 首次用 aria2 之前调用：决定本进程用哪个后端。
     * 若上一轮 armed（死于非命），自动切到另一组并累加死亡计数。
     */
    public static synchronized Backend pickBackend() {
        State s = read();
        if (!s.armed) return s.backend;   // 上轮干净（或首次）：沿用
        int deaths = s.deaths + 1;
        Backend next = (s.backend == Backend.OSSL) ? Backend.GNUTLS : Backend.OSSL;
        write(next, false, deaths);       // 选择结果必须先于任何 native 代码上盘
        CNLog.w(TAG, "previous run died armed (deaths=" + deaths + "), switch "
                + s.backend + " -> " + next);
        return next;
    }

    /**
     * 加载期失败（UnsatisfiedLinkError）的当场换组。与 {@link #pickBackend()}
     * 同级地累加计数并换组；调用方随后 {@code CNAria2Lib.load(返回值)}。
     */
    public static synchronized Backend shouldFallbackOnLoadError(Backend failed) {
        State s = read();
        Backend next = (failed == Backend.OSSL) ? Backend.GNUTLS : Backend.OSSL;
        int deaths = s.deaths + 1;
        write(next, false, deaths);
        CNLog.w(TAG, failed + " UnsatisfiedLinkError, switch -> " + next
                + " (deaths=" + deaths + ")");
        return next;
    }

    /** 在 {@code CNAria2Lib.start()} 之前调用：立「生死状」。 */
    public static synchronized void arm() {
        State s = read();
        write(s.backend, true, s.deaths);
    }

    /**
     * RPC aria2.shutdown 完成、waitStopped 返回后调用：干净关停，清除标记并
     * 清零死亡计数（一次成功运行证明当前后端在本机可用）。
     */
    public static synchronized void disarm() {
        State s = read();
        write(s.backend, false, 0);
    }

    /** 是否放弃双后端（连续 armed-death 达上限）。调用方应回退自建引擎。 */
    public static synchronized boolean giveUp() {
        return read().deaths >= MAX_CONSECUTIVE_DEATHS;
    }

    /** 当前记录的后端与死亡计数（诊断/遥测用）。 */
    public static synchronized String dump() {
        State s = read();
        return "backend=" + s.backend + " armed=" + s.armed + " deaths=" + s.deaths;
    }
}
