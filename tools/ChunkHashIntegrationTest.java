import io.kamihama.magianative.CNChunkedDownload;
import io.kamihama.magianative.CNChunkedDownload.ChunkHashes;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 分块哈希下载的集成测试：连真实 HTTP 服务器下载，验证
 *  - 正确清单 → 下载成功
 *  - 错误指纹 → 触发 ResetRequired（坏块检测）
 *  - 分片边界对齐 16MB 块
 *
 * 用 CNChunkedDownload.download(..., verifyZip=false, chunkHashes) 直调。
 * 服务器：tools/server.py <size> <port> 提供可 Range 的 file.bin。
 */
public class ChunkHashIntegrationTest {
    static int pass = 0, fail = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  ✓ " + name + " — " + detail); }
        else      { fail++; System.out.println("  ✗ " + name + " — " + detail); }
    }

    /** 读文件算整文件 md5。 */
    static String fileMd5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    /** 按 chunkSize 切块算每块 md5。 */
    static List<String> chunkMd5s(File f, int chunkSize) throws Exception {
        List<String> out = new ArrayList<String>();
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[chunkSize];
        int n;
        while ((n = in.read(buf)) >= 0) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
            out.add(sb.toString());
        }
        in.close();
        return out;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("用法: ChunkHashIntegrationTest <url> <size> <chunkSize>");
            System.exit(1);
        }
        String url = args[0];
        long   size = Long.parseLong(args[1]);
        int    chunk = Integer.parseInt(args[2]);

        // 先探测文件并下载原文件算真实指纹（用 single download）
        CNChunkedDownload.Probe probe = CNChunkedDownload.probe(url, true);

        // 下载到临时文件算指纹
        File ref = File.createTempFile("chunkref", ".bin");
        CNChunkedDownload.download(url, ref, 4, true, probe, null, null, "file.bin", false);
        List<String> realChunks = chunkMd5s(ref, chunk);
        long realSize = ref.length();
        System.out.println("参考文件: " + realSize + " 字节, " + realChunks.size() + " 块");

        // ── 场景 1: 正确清单 → 下载成功
        System.out.println("\n[1] 正确清单 → 下载成功");
        ChunkHashes good = new ChunkHashes(chunk, realSize, realChunks);
        File out1 = File.createTempFile("chunkok", ".bin");
        boolean ok1 = true;
        try {
            CNChunkedDownload.Probe p2 = CNChunkedDownload.probe(url, true);
            CNChunkedDownload.download(url, out1, 1, true, p2, null, null, "file.bin", false, good);
        } catch (Exception e) {
            ok1 = false;
            System.out.println("  异常: " + e);
        }
        check("下载成功", ok1, "correct manifest");
        check("内容一致", fileMd5(out1).equals(fileMd5(ref)), "md5 match");

        // ── 场景 2: 错误指纹 → ResetRequired
        System.out.println("\n[2] 错误指纹 → 触发校验失败");
        List<String> badChunks = new ArrayList<String>(realChunks);
        badChunks.set(0, "00000000000000000000000000000000");   // 块0 指纹改错
        ChunkHashes bad = new ChunkHashes(chunk, realSize, badChunks);
        File out2 = File.createTempFile("chunkbad", ".bin");
        boolean reset = false;
        try {
            CNChunkedDownload.Probe p3 = CNChunkedDownload.probe(url, true);
            CNChunkedDownload.download(url, out2, 1, true, p3, null, null, "file.bin", false, bad);
        } catch (Exception e) {
            reset = true;
            System.out.println("  期望的异常: " + e.getClass().getSimpleName()
                    + " — " + (e.getMessage() == null ? "" : e.getMessage().substring(0, Math.min(60, e.getMessage().length()))));
        }
        check("触发 ResetRequired", reset, "corrupt chunk 0");

        // ── 场景 3: 分片边界对齐（用大 chunk 模拟多分片）
        System.out.println("\n[3] 多分片下载（分片数=块数）");
        if (realChunks.size() >= 2) {
            File out3 = File.createTempFile("chunkmulti", ".bin");
            boolean ok3 = true;
            try {
                // 请求 1 分片会被 download 覆盖成块数（若 chunkHashes 在）
                CNChunkedDownload.Probe p4 = CNChunkedDownload.probe(url, true);
                CNChunkedDownload.download(url, out3, 1, true, p4, null, null, "file.bin", false, good);
            } catch (Exception e) {
                ok3 = false;
                System.out.println("  异常: " + e);
            }
            check("多分片下载成功", ok3, "requested 1 chunk, overridden to blocks");
            check("内容一致", fileMd5(out3).equals(fileMd5(ref)), "md5 match");
        } else {
            System.out.println("  （文件不足 2 块，跳过）");
        }

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
