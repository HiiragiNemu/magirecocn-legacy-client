import io.kamihama.magianative.CNZipPlan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 「下载前先算出解压后要占多少」的回归测试。
 *
 * <p>不造假数据：每条都用 {@link ZipOutputStream} 生成<b>真的 ZIP</b>，再让被测
 * 代码按 Range 去读它的中央目录。手写的字节序列只能证明解析器与我的想象一致，
 * 证明不了它读得懂真实产物——而这里唯一要证明的就是后者。
 *
 * <p>钉的重点是<b>失败时必须说「不知道」</b>。这个值只用来决定「要不要拦下一次
 * 1.3 GB 的下载」，把残缺数据当成一个小数字，等于把玩家放进去然后在解压时翻车；
 * 当成一个大数字，则是把装得下的人挡在门外。两头都比「不知道」差。
 */
public class ZipPlanTest {
    private static int pass;
    private static int fail;

    private static void check(String name, boolean ok) {
        if (ok) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name); }
    }

    /** 把整个 ZIP 放在内存里，按 Range 供给被测代码。 */
    private static final class Mem implements CNZipPlan.Ranges {
        private final byte[] data;
        int calls;
        long maxSpan;
        Mem(byte[] d) { this.data = d; }
        long dataLen() { return data.length; }
        @Override public byte[] get(long start, long end) throws IOException {
            if (start < 0 || end < start || end >= data.length) {
                throw new IOException("越界 " + start + "-" + end + " / " + data.length);
            }
            calls++;
            maxSpan = Math.max(maxSpan, end - start + 1L);
            byte[] out = new byte[(int) (end - start + 1L)];
            System.arraycopy(data, (int) start, out, 0, out.length);
            return out;
        }
    }

    /** 造一个真 ZIP：n 个条目，每个 size 字节；compress 决定存不存得下去。 */
    private static byte[] zip(int n, int size, boolean compress, String comment)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        if (comment != null) zos.setComment(comment);
        // 可压缩的内容用重复字节，不可压缩的用随机字节——后者能造出
        // 「膨胀比约等于 1」的包，正是 movie.zip 那一类。
        Random rnd = new Random(42);
        for (int i = 0; i < n; i++) {
            ZipEntry e = new ZipEntry("dir/entry" + i + ".bin");
            zos.putNextEntry(e);
            byte[] buf = new byte[size];
            if (!compress) rnd.nextBytes(buf);
            zos.write(buf);
            zos.closeEntry();
        }
        zos.close();
        return bos.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        // ── [1] 真 ZIP：算出来的就是各条目未压缩长度之和 ────────────
        byte[] z = zip(50, 4096, true, null);
        Mem m = new Mem(z);
        long got = CNZipPlan.extractedBytes(m, z.length);
        check("[1a] 高压缩包算得出解压后大小", got == 50L * 4096L);
        check("[1b] 只发两次 Range 请求", m.calls == 2);
        check("[1c] 高压缩包确实比 ZIP 大得多（这正是 03 那一类）",
                got > z.length * 2L);

        // 不可压缩的内容：解压后≈ZIP 大小，就是 movie.zip 那一类。
        byte[] z2 = zip(20, 8192, false, null);
        long got2 = CNZipPlan.extractedBytes(new Mem(z2), z2.length);
        check("[1d] 不可压缩包解压后≈ZIP 大小", got2 == 20L * 8192L
                && Math.abs(got2 - z2.length) < z2.length / 10);

        // ── [2] 带注释的 EOCD 也要找得到 ───────────────────────────
        // EOCD 是变长的：末尾可以跟最多 64KiB 注释，倒着找签名必须扛得住。
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append("注释");
        byte[] z3 = zip(10, 1024, true, sb.toString());
        check("[2a] EOCD 后面挂着长注释仍算得出",
                CNZipPlan.extractedBytes(new Mem(z3), z3.length) == 10L * 1024L);

        // ── [3] 空包与退化输入 ─────────────────────────────────────
        byte[] z4 = zip(0, 0, true, null);
        check("[3a] 没有条目的 ZIP 报「不知道」而不是 0",
                CNZipPlan.extractedBytes(new Mem(z4), z4.length) == CNZipPlan.UNKNOWN);
        check("[3b] total<=0 报不知道",
                CNZipPlan.extractedBytes(new Mem(z), 0) == CNZipPlan.UNKNOWN);
        check("[3c] 取字节器为 null 报不知道",
                CNZipPlan.extractedBytes(null, 100) == CNZipPlan.UNKNOWN);

        // ── [4] 坏数据一律「不知道」，绝不猜 ───────────────────────
        byte[] junk = new byte[70000];
        new Random(7).nextBytes(junk);
        check("[4a] 随机字节报不知道",
                CNZipPlan.extractedBytes(new Mem(junk), junk.length) == CNZipPlan.UNKNOWN);

        // 中央目录被截断：解析会在半路停下，剩下尾巴——必须说不知道，
        // 而不是把「已经加到一半的和」当答案（那会是个偏小的数，最危险）。
        byte[] cut = new byte[z.length];
        System.arraycopy(z, 0, cut, 0, z.length);
        // 把中央目录中间某条记录的长度字段改坏
        int cd = indexOf(cut, new byte[] { 0x50, 0x4b, 0x01, 0x02 });
        check("[4b] 测试样本里确实有中央目录", cd > 0);
        cut[cd + 28] = (byte) 0xff;         // 文件名长度改成 0xff??
        cut[cd + 29] = (byte) 0x7f;
        check("[4c] 中央目录被改坏报不知道",
                CNZipPlan.extractedBytes(new Mem(cut), cut.length) == CNZipPlan.UNKNOWN);

        // 取字节器抛异常（网络断了）：不能把异常抛给调用方，下载不该因为
        // 一个「提前量」问不到就整个失败。
        check("[4d] 取字节器抛异常时报不知道而不是抛出",
                CNZipPlan.extractedBytes(new Throwing(), 100000) == CNZipPlan.UNKNOWN);

        // ── [5] 读取量要小 ─────────────────────────────────────────
        // 这套东西的整个价值在于「花两兆搞清楚要不要下 1.3 GB」。要是它自己
        // 就得先读一大截，那还不如直接下。
        Mem big = new Mem(zip(2000, 512, true, null));
        CNZipPlan.extractedBytes(big, big.dataLen());
        check("[5a] 单次读取不超过尾部窗口 + 中央目录", big.maxSpan <= 66 * 1024L + 512 * 1024L);

        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static final class Throwing implements CNZipPlan.Ranges {
        @Override public byte[] get(long a, long b) throws IOException {
            throw new IOException("网络断了");
        }
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
