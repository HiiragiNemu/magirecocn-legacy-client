package io.kamihama.magianative;

import java.io.File;
import java.util.Locale;

/**
 * 存储空间的判据与话术。
 *
 * <p><b>为什么单列一个类。</b>「设备没空间了」在这套下载器里一直伪装成网络故障：
 * 写 {@code .cpart} 时 ENOSPC 抛的是 {@code IOException}，和超时、断流、坏块走的是
 * 同一个 catch。后果有三层，一层比一层难看：
 *
 * <ol>
 *   <li>{@code CNMirrors.reportFailure} 把这条线路记一次失败。线上
 *       {@code switch_after_failures=1}，于是它<b>立刻</b>进 60 秒冷却——而它什么
 *       错都没犯；</li>
 *   <li>四次重试逐条线路轮过去，四条线全被冷却，白白烧掉整个重试窗口；</li>
 *   <li>玩家看到的是红条 + 「重试 / 换备用引擎 / 单线程 / 离线包」四个选项，
 *       没有一个能解决磁盘满。他会一直点重试。</li>
 * </ol>
 *
 * <p>这件事最容易发生在 {@code cn_base_03.zip} 上，而且和「03 有什么毛病」无关：
 * 它 1.42 GB，是队列里第一个真正的大包，前面五个装完已经占掉几个 GB——峰值就落在
 * 它这里。玩家的感受是「03 老是下不了」，实际是「空间在 03 用尽」。
 *
 * <p>纯静态工具类，不碰 Android API（{@link File#getUsableSpace()} 是 JDK 的），
 * 因此可以在 JVM 上直接测。
 */
public final class CNDiskSpace {
    private static final String TAG = "MagiaCNDiskSpace";

    /**
     * 余量。除了要写的字节本身，还得给文件系统元数据、日志、以及系统自己留一点；
     * 贴着零算会在最后几 MB 上翻车，而那时已经下了一个多 G。
     */
    public static final long SAFETY_BYTES = 256L * 1024L * 1024L;

    private CNDiskSpace() {}

    /** 空间不够。单列一类，好让调用方<b>不</b>去怪线路、也不浪费重试次数。 */
    public static final class NotEnoughSpace extends java.io.IOException {
        private static final long serialVersionUID = 1L;
        public final long needBytes;
        public final long freeBytes;
        NotEnoughSpace(String message, long needBytes, long freeBytes) {
            super(message);
            this.needBytes = needBytes;
            this.freeBytes = freeBytes;
        }
    }

    /**
     * 这个异常（或它的任何一层 cause）是不是「没空间了」。
     *
     * <p>只能按<b>文本</b>认：ART 抛的是 {@code ErrnoException}，包在
     * {@code IOException} 里，errno 常量在编译 classpath 上够不着（只有
     * android.jar + OkHttp）。文本判据看着糙，但这几句话是 libcore 与 bionic
     * 里写死的，比「引一个只为读一个常量的依赖」稳。
     */
    public static boolean isOutOfSpace(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof NotEnoughSpace) return true;
            String m = c.getMessage();
            if (m == null) continue;
            String s = m.toLowerCase(Locale.US);
            if (s.indexOf("enospc") >= 0
                    || s.indexOf("no space left") >= 0
                    || s.indexOf("not enough space") >= 0
                    || s.indexOf("disk full") >= 0) {
                return true;
            }
            if (c == c.getCause()) break;   // 自环的 cause，别绕死
        }
        return false;
    }

    /**
     * 目标目录所在分区的可用字节；<b>取不到</b>返回 -1（此时一律放行，不猜）。
     *
     * <h3>F-078：0 不是「取不到」</h3>
     *
     * 原先写的是 {@code return free > 0 ? free : -1L;}，把「真的一个字节都不剩」
     * 折叠进了「测量失败」这个哨兵，而 {@link #require} 对 -1 的定义是<b>放行</b>。
     * 结果最确定的一种「装不下」——磁盘已经彻底满了——反而是唯一一种预检不生效的
     * 情况：下载照跑，直到写 {@code .part} / {@code .cpart} / 解压临时文件 / marker
     * 时才收到 ENOSPC，白烧网络与重试窗口，玩家还看到「当前可用未知」。
     *
     * <p>{@code File.getUsableSpace()} 没有独立错误码，它对不存在的路径返回 0——
     * 所以「测不出来」只能由<b>目录有效性</b>和<b>异常分支</b>去承担，不能借 0 表示。
     * 先确认目录确实存在，之后 0 就只可能是真的 0。
     */
    public static long usableBytes(File target) {
        try {
            File dir = target == null ? null
                    : (target.isDirectory() ? target : target.getParentFile());
            if (dir == null || !dir.exists()) return -1L;
            long free = dir.getUsableSpace();
            return free >= 0L ? free : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * 饱和加法（F-077）：结果夹在 {@code [0, Long.MAX_VALUE]}，永不回绕。
     *
     * <p>空间预算的每一个操作数都来自<b>外部</b>——远端 Content-Length、ZIP 中央
     * 目录声明的解压后大小、以及它们的累加。有符号加法一旦回绕成负数，
     * {@code free >= want} 对任何非负 {@code free} 都成立，一个本该 fail-closed
     * 的损坏元数据状态就变成了<b>无条件放行</b>。
     *
     * <p>这不是在假设真有 8 EiB 的文件；防御点是「不拿溢出后的值做控制流」。
     * 负操作数（未知/无意义）按 0 计，不当成「巨大的可用空间」。
     */
    public static long saturatedAdd(long a, long b) {
        if (a <= 0L) return Math.max(0L, b);
        if (b <= 0L) return Math.max(0L, a);
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }

    /** 饱和减法（F-077）：结果夹在 {@code [0, Long.MAX_VALUE]}，不出现负容量。 */
    public static long saturatedSub(long a, long b) {
        if (a <= 0L) return 0L;
        if (b <= 0L) return a;
        return a < b ? 0L : a - b;
    }

    /**
     * 写 {@code needBytes} 字节之前先看一眼够不够，不够就抛。
     *
     * <p>取不到可用空间（{@code -1}）时<b>放行</b>：宁可照旧在写的时候失败，
     * 也不能因为读不到一个数字就把玩家挡在门外。
     */
    public static void require(File target, long needBytes, String what)
            throws NotEnoughSpace {
        if (needBytes <= 0) return;
        long free = usableBytes(target);
        if (free < 0) return;
        // F-077：饱和相加。needBytes 接近 Long.MAX_VALUE 时，裸加法会回绕成负数，
        // 于是下一行的 free >= want 恒成立——预检从「拦住」变成「一律放行」。
        long want = saturatedAdd(needBytes, SAFETY_BYTES);
        if (free >= want) return;
        String msg = shortfall(what, needBytes, free);
        CNLog.w(TAG, msg);
        throw new NotEnoughSpace(msg, want, free);
    }

    /** 给玩家看的话：说清还差多少，而不是「失败了，请重试」。 */
    public static String shortfall(String what, long needBytes, long freeBytes) {
        long want = saturatedAdd(needBytes, SAFETY_BYTES);
        long lack = saturatedSub(want, freeBytes);
        return "存储空间不足：" + (what == null ? "本次安装" : what)
                + " 还需要约 " + human(want) + "，当前可用 " + human(freeBytes)
                + "，还差 " + human(lack);
    }

    /** 1 位小数的 KB/MB/GB。只用来说人话，不参与任何判定。 */
    public static String human(long bytes) {
        if (bytes < 0) return "未知";
        if (bytes < 1024L) return bytes + " B";
        double v = bytes;
        String[] unit = { "KB", "MB", "GB", "TB" };
        int i = -1;
        while (v >= 1024.0 && i + 1 < unit.length) { v /= 1024.0; i++; }
        return String.format(Locale.US, "%.1f %s", v, unit[i]);
    }
}
