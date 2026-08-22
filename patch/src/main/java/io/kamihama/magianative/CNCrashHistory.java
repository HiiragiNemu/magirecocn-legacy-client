package io.kamihama.magianative;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 开机时把<b>上几次进程是怎么死的</b>记进日志。
 *
 * <h3>为什么需要它</h3>
 *
 * 「进战斗就闪退」这类问题最难的一步不是修，是**分辨死法**：原生崩溃、Java 未捕获
 * 异常、ANR、系统低内存杀进程，四者的下一步完全不同，而玩家看到的都是「游戏没了」。
 *
 * <p>{@link CNLog} 那两路 logcat 各自补了一块：
 *
 * <ul>
 *   <li>主回收带 {@code --pid}，看得见我们自己崩之前打的最后几行；</li>
 *   <li>崩溃流不带 {@code --pid}，指望 {@code -T} 回灌把上一个进程的墓碑捞回来。</li>
 * </ul>
 *
 * 但它们有一个共同的软肋：<b>都建立在「墓碑确实进了 logcat、而且我们读得到」这个
 * 假设上</b>。环形缓冲会被冲掉，厂商 ROM 会改 logd 策略，crash_dump 以哪个 UID 写
 * 日志也不是我们能左右的。假设不成立时，那两路一起失明，而且**失明得毫无迹象**
 * ——日志文件看上去完全正常。
 *
 * <p>这里用的是另一条完全独立的链路：{@code ActivityManager
 * .getHistoricalProcessExitReasons()}（API 30+）。它由系统维护，不经 logcat、不受
 * 环形缓冲影响、不需要任何权限，查的就是本应用自己的退出记录。两条链路同时哑掉的
 * 概率远低于任何一条——这正是它存在的意义，不是锦上添花。
 *
 * <h3>能拿到什么</h3>
 *
 * 每条记录都有确定的{@code reason}（原生崩溃 / Java 崩溃 / ANR / 低内存 / 自己退
 * 等）、时间戳、退出状态、当时的内存占用。<b>光是 reason 这一个字段就能把上面那
 * 四种死法一次分开</b>，而这恰恰是目前最缺的信息。
 *
 * <p>原生崩溃与 ANR 还带一份 trace（{@code getTraceInputStream()}）。⚠ 从
 * Android 12 起，原生崩溃那份是 <b>protobuf 格式的墓碑，不是文本</b>——直接往日志
 * 里倒会写进一堆二进制垃圾。本仓库的编译 classpath 只有 android.jar + OkHttp，没有
 * protobuf 运行时，也不该为这个引一个依赖。所以这里做的是「取可打印片段」：protobuf
 * 的字符串字段是长度前缀的原文，扫一遍就能把信号名、abi、so 路径、backtrace 的符号
 * 名捞出来——够人读，且完全不依赖 schema。是文本就原样按行记。
 *
 * <h3>绝不影响主流程</h3>
 *
 * 整个类吞掉所有异常，且在后台线程上跑：它挂在 {@link CNLog#initEarly()} 的收尾处，
 * 而那条路径出任何事都会让引擎停在半路。诊断设施把游戏搞挂，比它要诊断的毛病严重。
 */
public final class CNCrashHistory {

    private static final String TAG = "崩溃";

    /** 回看最近几次退出。5 次足够覆盖「崩了几次才来报」的典型场景。 */
    private static final int MAX_RECORDS = 5;
    /** 单条 trace 最多读多少字节。arm64 墓碑常在几十 KB，够用且封得住。 */
    private static final int MAX_TRACE_BYTES = 64 * 1024;
    /** 单条 trace 最多写多少行进日志，防一份异常巨大的墓碑把文件挤爆。 */
    private static final int MAX_TRACE_LINES = 400;
    /** 二进制里多长的可打印串才算「像人话」。太短会把 protobuf 的字段头也捞出来。 */
    private static final int MIN_STRING_RUN = 8;

    private static boolean done = false;

    private CNCrashHistory() {}

    /**
     * 记一次。重复调用只有第一次生效——退出记录是启动那一刻的既成事实，
     * 重复查只会把同样的内容再写一遍。
     *
     * <p>自己起后台线程：读 trace 要碰文件，而调用点在开机关键路径上。
     */
    public static synchronized void dumpRecentAsync() {
        if (done) return;
        done = true;
        try {
            Thread t = new Thread(new Dump(), "cnv-exit-reason");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            CNLog.w(TAG, "退出记录线程起不来: " + e);
        }
    }

    private static final class Dump implements Runnable {
        @Override public void run() {
            try {
                if (Build.VERSION.SDK_INT < 30) {
                    CNLog.i(TAG, "系统低于 Android 11，没有历史退出记录可查"
                            + "（只能靠 logcat 那两路）");
                    return;
                }
                Context ctx = CNRestClientActivity.appContext();
                if (ctx == null) {
                    CNLog.w(TAG, "取不到 Context，本次不查历史退出记录");
                    return;
                }
                Api30.dump(ctx);
            } catch (Throwable t) {
                CNLog.w(TAG, "查历史退出记录失败（不影响任何其它功能）: " + t);
            }
        }
    }

    /**
     * API 30+ 才有的部分单独放一个类。
     *
     * <p>不是风格问题：{@link ApplicationExitInfo} 在 API 30 以下不存在，而 ART 是
     * <b>按类</b>惰性校验的——把它关进一个只在 {@code SDK_INT >= 30} 时才被引用的类，
     * 老系统上这个类根本不会被加载，也就不会因为解析不到符号而报错。
     */
    private static final class Api30 {

        static void dump(Context ctx) {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                CNLog.w(TAG, "拿不到 ActivityManager，本次不查历史退出记录");
                return;
            }
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(
                    ctx.getPackageName(), 0, MAX_RECORDS);
            if (list == null || list.isEmpty()) {
                CNLog.i(TAG, "系统里没有本应用的历史退出记录（首次安装/刚清过数据）");
                return;
            }
            SimpleDateFormat fmt =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            CNLog.i(TAG, "历史退出记录 " + list.size() + " 条（新→旧）：");
            for (int i = 0; i < list.size(); i++) {
                ApplicationExitInfo info = list.get(i);
                try {
                    one(fmt, i, info);
                } catch (Throwable t) {
                    CNLog.w(TAG, "  #" + i + " 记录读不出来: " + t);
                }
            }
        }

        private static void one(SimpleDateFormat fmt, int idx, ApplicationExitInfo info) {
            int reason = info.getReason();
            StringBuilder sb = new StringBuilder();
            sb.append("  #").append(idx)
              .append(' ').append(fmt.format(new Date(info.getTimestamp())))
              .append("  ").append(reasonName(reason))
              .append("  pid=").append(info.getPid())
              .append(" status=").append(info.getStatus());
            String desc = info.getDescription();
            if (desc != null && desc.length() > 0) sb.append(" 描述=").append(desc);
            long rss = info.getRss();
            if (rss > 0) sb.append(" rss=").append(rss / 1024).append("MB");
            // 崩溃/ANR 用 ERROR 级：write() 那边 ERROR 会同步 flush，
            // 而这条恰恰是最不能因为「攒着还没落盘」而丢掉的。
            if (isBad(reason)) CNLog.e(TAG, sb.toString());
            else               CNLog.i(TAG, sb.toString());
            if (isBad(reason)) trace(idx, info);
        }

        /** 值得追 trace 的死法。其余（自己退、被用户停等）没有 trace，也不必看。 */
        private static boolean isBad(int reason) {
            return reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_SIGNALED;
        }

        private static void trace(int idx, ApplicationExitInfo info) {
            InputStream in = null;
            try {
                in = info.getTraceInputStream();
                if (in == null) {
                    CNLog.i(TAG, "  #" + idx + " 无 trace（系统没留）");
                    return;
                }
                byte[] buf = new byte[MAX_TRACE_BYTES];
                int n = 0;
                while (n < buf.length) {
                    int r = in.read(buf, n, buf.length - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n <= 0) {
                    CNLog.i(TAG, "  #" + idx + " trace 是空的");
                    return;
                }
                boolean text = looksLikeText(buf, n);
                CNLog.i(TAG, "  #" + idx + " trace " + n + " 字节（"
                        + (text ? "文本，原样记" : "二进制/protobuf，只取可打印片段")
                        + (n == buf.length ? "，已截到上限" : "") + "）：");
                int lines = text ? emitText(buf, n) : emitStrings(buf, n);
                if (lines >= MAX_TRACE_LINES) {
                    CNLog.i(TAG, "  #" + idx + " …trace 超过 " + MAX_TRACE_LINES
                            + " 行，其余略去");
                }
            } catch (Throwable t) {
                CNLog.w(TAG, "  #" + idx + " trace 读不出来: " + t);
            } finally {
                CNIo.closeQuietly(in);
            }
        }

        /**
         * 前 512 字节里可打印字符（含制表/换行）占比 ≥ 90% 就当文本。
         *
         * <p>ANR trace 是纯文本；Android 12 起原生崩溃的墓碑是 protobuf。
         * 两者开头差别极大，取样 512 字节足够分开，不必真去解析。
         */
        private static boolean looksLikeText(byte[] b, int n) {
            int look = Math.min(n, 512);
            if (look == 0) return false;
            int ok = 0;
            for (int i = 0; i < look; i++) {
                int c = b[i] & 0xFF;
                if (c == '\n' || c == '\r' || c == '\t' || (c >= 0x20 && c < 0x7F)) ok++;
            }
            return ok * 10 >= look * 9;
        }

        private static int emitText(byte[] b, int n) {
            String all = new String(b, 0, n, java.nio.charset.Charset.forName("UTF-8"));
            String[] rows = all.split("\n", -1);
            int lines = 0;
            for (int i = 0; i < rows.length && lines < MAX_TRACE_LINES; i++) {
                String row = rows[i].trim();
                if (row.length() == 0) continue;
                CNLog.writeRaw("    " + row, CNLog.SRC_NATIVE);
                lines++;
            }
            return lines;
        }

        /**
         * 从二进制里捞可打印片段。
         *
         * <p>protobuf 的字符串字段是「长度前缀 + 原文」，原文本身没有被编码打散，
         * 所以扫一遍连续可打印字节就能把信号名、abi、so 路径、backtrace 的符号名
         * 全捞出来——够人读懂崩在哪，而且完全不依赖 .proto schema。
         */
        private static int emitStrings(byte[] b, int n) {
            int lines = 0;
            int start = -1;
            for (int i = 0; i <= n && lines < MAX_TRACE_LINES; i++) {
                int c = (i < n) ? (b[i] & 0xFF) : 0;
                boolean printable = i < n && c >= 0x20 && c < 0x7F;
                if (printable) {
                    if (start < 0) start = i;
                    continue;
                }
                if (start >= 0 && i - start >= MIN_STRING_RUN) {
                    CNLog.writeRaw("    "
                            + new String(b, start, i - start,
                                         java.nio.charset.Charset.forName("US-ASCII")),
                            CNLog.SRC_NATIVE);
                    lines++;
                }
                start = -1;
            }
            return lines;
        }

        private static String reasonName(int reason) {
            switch (reason) {
                case ApplicationExitInfo.REASON_CRASH_NATIVE:
                    return "原生崩溃(REASON_CRASH_NATIVE)";
                case ApplicationExitInfo.REASON_CRASH:
                    return "Java 未捕获异常(REASON_CRASH)";
                case ApplicationExitInfo.REASON_ANR:
                    return "无响应(REASON_ANR)";
                case ApplicationExitInfo.REASON_SIGNALED:
                    return "被信号杀(REASON_SIGNALED)";
                case ApplicationExitInfo.REASON_LOW_MEMORY:
                    return "系统低内存杀进程(REASON_LOW_MEMORY)";
                case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                    return "资源占用超标被杀(REASON_EXCESSIVE_RESOURCE_USAGE)";
                case ApplicationExitInfo.REASON_EXIT_SELF:
                    return "自己退出(REASON_EXIT_SELF)";
                case ApplicationExitInfo.REASON_USER_REQUESTED:
                    return "用户要求(REASON_USER_REQUESTED)";
                case ApplicationExitInfo.REASON_USER_STOPPED:
                    return "用户停止(REASON_USER_STOPPED)";
                case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                    return "依赖进程死亡(REASON_DEPENDENCY_DIED)";
                case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                    return "初始化失败(REASON_INITIALIZATION_FAILURE)";
                case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                    return "权限变更(REASON_PERMISSION_CHANGE)";
                case ApplicationExitInfo.REASON_OTHER:
                    return "其它(REASON_OTHER)";
                default:
                    return "未知(reason=" + reason + ")";
            }
        }
    }
}
