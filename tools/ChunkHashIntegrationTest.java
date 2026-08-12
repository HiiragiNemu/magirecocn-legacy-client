import io.kamihama.magianative.CNChunkedDownload;
import io.kamihama.magianative.CNChunkedDownload.ChunkHashes;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 分块事务下载集成测试。除“能发现坏块”外，重点钉住 03 事故所需的恢复语义：
 * 校验块数不等于线程数、坏块不得持久化为完成、非整除布局的已验证块可跨重启复用。
 */
public class ChunkHashIntegrationTest {
    static int pass = 0, fail = 0;

    static final class Sink implements CNChunkedDownload.Sink {
        long first = -1L;
        long last = 0L;
        long cancelAt = Long.MAX_VALUE;
        public void onTotal(long total) {}
        public void onProgress(long soFar, long total) {
            if (first < 0) first = soFar;
            last = soFar;
        }
        public void onSpeed(float mbps) {}
        public boolean isCancelled() { return last >= cancelAt; }
    }

    static void check(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  ✓ " + name + " — " + detail); }
        else      { fail++; System.out.println("  ✗ " + name + " — " + detail); }
    }

    static String fileMd5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) >= 0) if (n > 0) md.update(buf, 0, n);
        in.close();
        return hex(md.digest());
    }

    static List<String> chunkMd5s(File f, int chunkSize) throws Exception {
        List<String> out = new ArrayList<String>();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[1 << 16];
        long remainTotal = f.length();
        while (remainTotal > 0) {
            long blockLen = Math.min((long) chunkSize, remainTotal);
            MessageDigest md = MessageDigest.getInstance("MD5");
            long got = 0L;
            while (got < blockLen) {
                int want = (int) Math.min((long) buf.length, blockLen - got);
                int n = in.read(buf, 0, want);
                if (n < 0) throw new IOException("参考文件短读");
                if (n == 0) continue;
                md.update(buf, 0, n);
                got += n;
            }
            out.add(hex(md.digest()));
            remainTotal -= blockLen;
        }
        in.close();
        return out;
    }

    static void clean(File target) {
        target.delete();
        CNChunkedDownload.partFileFor(target).delete();
        CNChunkedDownload.metaFileFor(target).delete();
        File parent = target.getParentFile();
        File[] files = parent == null ? null : parent.listFiles();
        String prefix = target.getName() + ".cpart.block.";
        if (files != null) for (File f : files) if (f.getName().startsWith(prefix)) f.delete();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: ChunkHashIntegrationTest <url> <size> <chunkSize>");
            System.exit(1);
        }
        String url = args[0];
        long expectedSize = Long.parseLong(args[1]);
        int chunk = Integer.parseInt(args[2]);
        File dir = new File("work/chunk-tx");
        dir.mkdirs();

        CNChunkedDownload.Probe probe = CNChunkedDownload.probe(url, true);
        check("探测结果", probe.rangeSupported && probe.total == expectedSize,
                "total=" + probe.total + " range=" + probe.rangeSupported);

        File ref = new File(dir, "reference.bin");
        clean(ref);
        CNChunkedDownload.download(url, ref, 4, true, probe, new Sink(), null, null, false);
        List<String> realChunks = chunkMd5s(ref, chunk);
        ChunkHashes good = new ChunkHashes(chunk, ref.length(), realChunks);
        System.out.println("参考文件: " + ref.length() + " 字节, " + realChunks.size() + " 块");

        System.out.println("\n[1] 正确清单 + requested=85：块数与线程数解耦");
        File normal = new File(dir, "normal.bin");
        clean(normal);
        CNChunkedDownload.Result r = CNChunkedDownload.download(url, normal, 85, true,
                CNChunkedDownload.probe(url, true), new Sink(), null, null, false, good);
        check("下载成功", normal.isFile(), "target exists");
        check("内容一致", fileMd5(normal).equals(fileMd5(ref)), "md5 match");
        check("全部块已认证", r.chunkVerified, "chunkVerified=true");

        System.out.println("\n[2] 错误指纹不得写进主文件或完成状态");
        List<String> badList = new ArrayList<String>(realChunks);
        badList.set(0, "00000000000000000000000000000000");
        ChunkHashes bad = new ChunkHashes(chunk, ref.length(), badList);
        File poisoned = new File(dir, "poisoned.bin");
        clean(poisoned);
        boolean rejected = false;
        try {
            CNChunkedDownload.download(url, poisoned, 1, true,
                    CNChunkedDownload.probe(url, true), new Sink(), null, null, false, bad);
        } catch (IOException expected) {
            rejected = true;
            System.out.println("  期望异常: " + expected.getMessage());
        }
        check("错误块被拒绝", rejected, "hash mismatch");
        Sink afterBad = new Sink();
        CNChunkedDownload.download(url, poisoned, 1, true,
                CNChunkedDownload.probe(url, true), afterBad, null, null, false, good);
        check("错误块未被伪装成已完成", afterBad.first == 0L,
                "首次可信进度=" + afterBad.first);
        check("恢复后内容正确", fileMd5(poisoned).equals(fileMd5(ref)), "md5 match");

        System.out.println("\n[3] 非整除块布局跨重启复用（03 的 85 块同类结构）");
        File resumed = new File(dir, "resumed.bin");
        clean(resumed);
        Sink stop = new Sink();
        stop.cancelAt = Math.min((long) chunk, ref.length());
        boolean interrupted = false;
        try {
            CNChunkedDownload.download(url, resumed, 1, true,
                    CNChunkedDownload.probe(url, true), stop, null, null, false, good);
        } catch (IOException expected) {
            interrupted = true;
            System.out.println("  期望中断: " + expected.getMessage());
        }
        check("中断后保留事务断点", interrupted
                        && CNChunkedDownload.partFileFor(resumed).isFile()
                        && CNChunkedDownload.metaFileFor(resumed).isFile(),
                "part/meta retained");
        Sink continueSink = new Sink();
        CNChunkedDownload.download(url, resumed, 1, true,
                CNChunkedDownload.probe(url, true), continueSink,
                null, null, false, good);
        check("从已验证块继续而非归零",
                continueSink.first >= Math.min((long) chunk, ref.length())
                        && continueSink.first < ref.length(),
                "首次可信进度=" + continueSink.first);
        check("续传成品正确", fileMd5(resumed).equals(fileMd5(ref)), "md5 match");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
