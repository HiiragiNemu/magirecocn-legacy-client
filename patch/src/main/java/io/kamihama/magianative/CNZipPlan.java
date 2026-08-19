package io.kamihama.magianative;

import java.io.IOException;

/**
 * 下载前先算出「这个包解压后要占多少」。
 *
 * <p><b>为什么需要它。</b>安装一个包的磁盘峰值是 <em>ZIP + 解压后</em>——ZIP 要
 * 留到解压成功才删（进程被杀时好接着装）。而这两个数的比例，各个包差得很远：
 *
 * <pre>
 *   cn_base_03.zip     1.32 GiB → 2.79 GiB   膨胀 2.11x   峰值 4.11 GiB
 *   cn_voice_01.zip    1.85 GiB → 2.15 GiB   膨胀 1.16x   峰值 4.00 GiB
 *   movie.zip          1.33 GiB → 1.35 GiB   膨胀 1.02x   峰值 2.68 GiB
 *   cn_base_02.zip     0.89 GiB → 0.94 GiB   膨胀 1.06x   峰值 1.83 GiB
 * </pre>
 *
 * <p>03 是<b>唯一</b>真正会膨胀的包（2.11x，其余全在 1.02–1.16x 之间），也因此
 * 拥有 15 个包里最高的安装峰值。玩家看到的却是「1.3 GB」——他按这个数去清理空间，
 * 然后在解压阶段翻车。「其他包都下完了，就 03 下不下来」正是这么来的：03 早先
 * 失败一次后被安装器排到最后，那时磁盘已经被前面十几个包填满，4.11 GiB 再也腾
 * 不出来。
 *
 * <p><b>怎么做到的。</b>ZIP 的中央目录在文件末尾，且每条记录都写着该条目的
 * 未压缩长度。两次 Range 请求就能读到：先取尾部找 EOCD，再取中央目录本身
 * （03 的中央目录 1.7 MB）。花两兆搞清楚要不要下 1.3 GB，很划算。
 *
 * <p>取不到就返回 {@link #UNKNOWN}，调用方按「不知道」处理并放行——这是个
 * <b>提前量</b>，不是关卡；真正兜底的是解压前那道精确检查（见
 * {@code CNArchiveInstallTx}）。
 */
public final class CNZipPlan {
    private static final String TAG = "MagiaCNZipPlan";

    /** 算不出来。调用方必须按「不知道」处理，不能当成 0。 */
    public static final long UNKNOWN = -1L;

    /** EOCD 最多往前找这么多字节（22 字节定长 + 最长 64KiB 注释 + 余量）。 */
    private static final int TAIL_BYTES = 66 * 1024;
    /** 中央目录再大就不读了——正常包不会有这么大，读它本身就成了负担。 */
    private static final long MAX_CD_BYTES = 32L * 1024L * 1024L;

    private CNZipPlan() {}

    /** 取一段字节（含首含尾）。抽出来是为了让回归测试不必起 HTTP 服务器。 */
    public interface Ranges {
        byte[] get(long start, long endInclusive) throws IOException;
    }

    /**
     * 解压后的总字节数；任何一步不对劲都返回 {@link #UNKNOWN}，不抛。
     *
     * <p>刻意只读中央目录、不校验 CRC：这里要的是一个<b>用来判断空间够不够</b>
     * 的量级，不是完整性结论。完整性由分块指纹和解压本身负责。
     */
    public static long extractedBytes(Ranges src, long total) {
        try {
            if (src == null || total <= 0) return UNKNOWN;
            int tailLen = (int) Math.min((long) TAIL_BYTES, total);
            byte[] tail = src.get(total - tailLen, total - 1);
            if (tail == null || tail.length < 22) return UNKNOWN;

            int e = findEocd(tail);
            if (e < 0) return UNKNOWN;
            long cdSize = u32(tail, e + 12);
            long cdOff = u32(tail, e + 16);

            // Zip64：EOCD 里放不下的字段写成全 1，真值在 Zip64 EOCD 记录里。
            // 03 带着一份 Zip64 记录（15 个包里唯一），虽然它的值其实都放得下。
            // F-056：真 Zip64 记录在 EOCD 的 locator（20 字节）之前——记录末 + 20
            // 必须 ≤ EOCD 位置；伪签名（在注释/数据里）不满足这个相对布局。
            if (cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
                int z = lastIndexOf(tail, SIG_ZIP64_EOCD);
                if (z < 0 || z + 56 > tail.length || z + 76 > e) return UNKNOWN;
                cdSize = u64(tail, z + 40);
                cdOff = u64(tail, z + 48);
            }
            if (cdSize <= 0 || cdSize > MAX_CD_BYTES) return UNKNOWN;
            if (cdOff < 0 || cdOff + cdSize > total) return UNKNOWN;

            byte[] cd = src.get(cdOff, cdOff + cdSize - 1);
            if (cd == null || cd.length != cdSize) return UNKNOWN;
            return sumUncompressed(cd);
        } catch (Throwable t) {
            CNLog.i(TAG, "算不出解压后大小（按未知处理，不拦下载）: " + t);
            return UNKNOWN;
        }
    }

    /** 遍历中央目录累加未压缩长度。结构不对就返回 {@link #UNKNOWN}。 */
    static long sumUncompressed(byte[] cd) {
        long sum = 0L;
        int i = 0;
        int seen = 0;
        while (i + 46 <= cd.length && startsWith(cd, i, SIG_CD)) {
            long uSize = u32(cd, i + 24);
            int nLen = u16(cd, i + 28);
            int eLen = u16(cd, i + 30);
            int cLen = u16(cd, i + 32);
            if (nLen < 0 || eLen < 0 || cLen < 0) return UNKNOWN;
            if (uSize == 0xFFFFFFFFL) {
                long z = zip64Uncompressed(cd, i + 46 + nLen, eLen);
                if (z < 0) return UNKNOWN;
                uSize = z;
            }
            // F-056：sum 累加可能溢出 long（合法 Zip64 声明值可以很大）。规划器只是
            // 提前量，不是关卡——溢出让总和绕回负数/小值会让它给出荒谬的空间结论，
            // 宁可返回 UNKNOWN 放行（解压前还有精确检查兜底）。
            if (uSize > Long.MAX_VALUE - sum) return UNKNOWN;
            sum += uSize;
            seen++;
            i += 46 + nLen + eLen + cLen;
        }
        if (seen == 0) return UNKNOWN;
        // 走完整个中央目录才算数：剩下尾巴说明解析跑偏了，宁可说不知道。
        return i == cd.length ? sum : UNKNOWN;
    }

    /** 从 extra 字段里取 Zip64 的未压缩长度（header id 0x0001 的第一个 8 字节）。 */
    private static long zip64Uncompressed(byte[] cd, int off, int len) {
        int j = off;
        int end = off + len;
        while (j + 4 <= end && j + 4 <= cd.length) {
            int id = u16(cd, j);
            int sz = u16(cd, j + 2);
            if (id == 0x0001) {
                if (j + 4 + 8 > cd.length || sz < 8) return -1L;
                return u64(cd, j + 4);
            }
            j += 4 + sz;
        }
        return -1L;
    }

    private static final byte[] SIG_EOCD       = { 0x50, 0x4b, 0x05, 0x06 };
    private static final byte[] SIG_ZIP64_EOCD = { 0x50, 0x4b, 0x06, 0x06 };
    private static final byte[] SIG_CD         = { 0x50, 0x4b, 0x01, 0x02 };

    private static boolean startsWith(byte[] b, int at, byte[] sig) {
        if (at < 0 || at + sig.length > b.length) return false;
        for (int i = 0; i < sig.length; i++) if (b[at + i] != sig[i]) return false;
        return true;
    }

    private static int lastIndexOf(byte[] b, byte[] sig) {
        for (int i = b.length - sig.length; i >= 0; i--) {
            if (startsWith(b, i, sig)) return i;
        }
        return -1;
    }

    /**
     * 找「comment 恰好填到文件尾」的 EOCD。F-056：真 EOCD 是文件**最后**的结构，
     * 它的 comment 一定延伸到 EOF（{@code i + 22 + commentLen == 缓冲区长度}，
     * tail 就是文件末尾 min(TAIL_BYTES, total) 字节）。注释/数据里的伪签名要么
     * 注释长度对不上 EOF，要么压根不是结构化的 EOCD——从后往前扫，只认满足等式
     * 的。找不着返回 -1。
     */
    private static int findEocd(byte[] tail) {
        for (int i = tail.length - SIG_EOCD.length; i >= 0; i--) {
            if (!startsWith(tail, i, SIG_EOCD)) continue;
            if (i + 22 > tail.length) continue;
            int commentLen = u16(tail, i + 20);
            if (commentLen < 0) continue;
            if (i + 22 + commentLen == tail.length) return i;
        }
        return -1;
    }

    private static int u16(byte[] b, int i) {
        if (i + 2 > b.length) return -1;
        return (b[i] & 0xff) | ((b[i + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int i) {
        if (i + 4 > b.length) return -1L;
        return (b[i] & 0xffL) | ((b[i + 1] & 0xffL) << 8)
                | ((b[i + 2] & 0xffL) << 16) | ((b[i + 3] & 0xffL) << 24);
    }

    private static long u64(byte[] b, int i) {
        if (i + 8 > b.length) return -1L;
        long v = 0L;
        for (int k = 7; k >= 0; k--) v = (v << 8) | (b[i + k] & 0xffL);
        return v;
    }
}
