import io.kamihama.magianative.CNChunkedDownload;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;

/** 断点续传行为验证（跑在 JVM 上，打真实 HTTP 服务器）。 */
public class ResumeTest {

    static int passed = 0, failed = 0;
    static String base;
    static String expectSha;
    static long   totalSize;

    static class Sink implements CNChunkedDownload.Sink {
        long last = -1;
        /** 首次进度回调的值 = 本次开工前已经具备的字节数（断点复用量）。 */
        long first = -1;
        public void onTotal(long t) {}
        public void onProgress(long s, long t) { if (first < 0) first = s; last = s; }
        public void onSpeed(float m) {}
        public boolean isCancelled() { return false; }
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  ✅ " + name + (detail.isEmpty() ? "" : "  — " + detail)); }
        else    { failed++; System.out.println("  ❌ " + name + "  — " + detail); }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) >= 0) md.update(b, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    static void clean(File target) {
        target.delete();
        CNChunkedDownload.partFileFor(target).delete();
        CNChunkedDownload.metaFileFor(target).delete();
    }

    // 7. 换线续传：ETag 变了但确实是同一份文件时，必须复用断点而不是从零重下
    static void test7_crossMirrorResume(File dir) throws Exception {
        System.out.println("\n[7] 换线续传（不同线路 ETag 不同）");
        File t = new File(dir, "f.bin");
        clean(t);
        // 线路 A：ETag = v1，只发一部分制造残局
        try {
            CNChunkedDownload.Probe p0 = CNChunkedDownload.probe(base + "?etag=v1", false);
            ctl("/settruncate?v=" + (totalSize/16));
            CNChunkedDownload.download(base + "?etag=v1", t, 4, false, p0, new Sink());
            ctl("/settruncate?v=0");
        } catch (IOException ignore) {}
        ctl("/settruncate?v=0");
        File part = CNChunkedDownload.partFileFor(t);
        check("残局已产生", part.exists() && CNChunkedDownload.metaFileFor(t).exists(), "");
        // 线路 B：同一份文件，但 ETag 完全不同（模拟 CDN 与源站 ETag 格式不一致）
        String urlB = base + "?etag=" + java.net.URLEncoder.encode("\"0xDEADBEEF\"", "UTF-8");
        CNChunkedDownload.Probe p = CNChunkedDownload.probe(urlB, false);
        Sink s = new Sink();
        CNChunkedDownload.Result r = CNChunkedDownload.download(urlB, t, 4, false, p, s);
        check("换线后内容正确", sha256(t).equals(expectSha), "sha=" + sha256(t).substring(0,12));
        check("复用了断点而非从零重下", s.first > 0,
              "换线后首次回调=" + s.first + "（0 表示进度被作废）");
    }

    public static void main(String[] args) throws Exception {
        base      = args[0];
        expectSha = args[1];
        totalSize = Long.parseLong(args[2]);
        File dir  = new File("work");
        dir.mkdirs();

        test1_full(dir);
        test2_shortRead(dir);
        test3_resumeAfterShortRead(dir);
        test4_missingPartFile(dir);
        test5_etagChanged(dir);
        test6_overSend(dir);
        test7_crossMirrorResume(dir);
        test8_rangeIgnored(dir);

        System.out.println();
        System.out.println("通过 " + passed + " / 失败 " + failed);
        if (failed > 0) System.exit(1);
    }

    // 1. 正常的完整分片下载
    static void test1_full(File dir) throws Exception {
        System.out.println("\n[1] 完整分片下载");
        File t = new File(dir, "a.bin");
        clean(t);
        CNChunkedDownload.Probe p = CNChunkedDownload.probe(base, false);
        check("探测到 Range 支持", p.rangeSupported && p.total == totalSize,
              "total=" + p.total + " range=" + p.rangeSupported);
        CNChunkedDownload.Result r = CNChunkedDownload.download(base, t, 4, false, p, new Sink());
        check("内容正确", sha256(t).equals(expectSha), "sha=" + sha256(t).substring(0, 12));
        check("大小正确", r.totalBytes == totalSize, "" + r.totalBytes);
        check("元数据已清理", !CNChunkedDownload.metaFileFor(t).exists(), "");
        check("临时文件已清理", !CNChunkedDownload.partFileFor(t).exists(), "");
    }

    // 2. 服务端提前断流：必须报错，绝不能当成功
    static void test2_shortRead(File dir) throws Exception {
        System.out.println("\n[2] 服务端提前断流（每片只发 1/4）");
        File t = new File(dir, "b.bin");
        clean(t);
        long per = totalSize / 4;
        // 用服务端开关截断，保持 URL 与后续续传完全一致（否则会被判成换线）
        ctl("/settruncate?v=" + (per / 4));
        CNChunkedDownload.Probe p = CNChunkedDownload.probe(base, false);
        boolean threw = false;
        String msg = "";
        try {
            CNChunkedDownload.download(base, t, 4, false, p, new Sink());
        } catch (IOException e) {
            threw = true; msg = String.valueOf(e.getMessage());
        }
        check("抛出异常而非静默成功", threw, msg);
        check("未生成成品文件", !t.exists(), "");
        check("保留断点元数据供续传", CNChunkedDownload.metaFileFor(t).exists(), "");
        check("保留临时文件", CNChunkedDownload.partFileFor(t).exists(), "");
    }

    // 3. 断流之后用正常线路续传，必须补齐且内容正确
    static void test3_resumeAfterShortRead(File dir) throws Exception {
        System.out.println("\n[3] 断点续传补齐（承接上一步的残局）");
        File t = new File(dir, "b.bin");
        File part = CNChunkedDownload.partFileFor(t);
        long before = 0;
        if (part.exists()) before = part.length();
        ctl("/settruncate?v=0");
        CNChunkedDownload.Probe p = CNChunkedDownload.probe(base, false);
        Sink s = new Sink();
        CNChunkedDownload.Result r = CNChunkedDownload.download(base, t, 4, false, p, s);
        check("续传后内容正确", sha256(t).equals(expectSha), "sha=" + sha256(t).substring(0, 12));
        // 上一步每片只拿到 1/4，总共 totalSize/4；续传必须从这个量接着下，
        // 而不是从 0 重来
        check("确实复用了断点（首次回调 = 已下量，非 0）",
              s.first > 0 && s.first == totalSize / 4,
              "首次回调=" + s.first + " 期望=" + (totalSize / 4));
        check("预分配长度一致", before == totalSize, "" + before);
        check("大小正确", r.totalBytes == totalSize, "" + r.totalBytes);
    }

    // 4. 元数据在但临时文件没了：绝不能把预分配出来的全零文件当成品
    static void test4_missingPartFile(File dir) throws Exception {
        System.out.println("\n[4] 元数据显示已下完，但临时文件被删");
        File t = new File(dir, "c.bin");
        clean(t);
        // 先正常下完一次，拿到一份「全部完成」的元数据
        CNChunkedDownload.Probe p = CNChunkedDownload.probe(base, false);
        CNChunkedDownload.download(base, t, 4, false, p, new Sink());
        // 手工伪造：成品删掉，写一份「全下完」的元数据，但不给临时文件
        t.delete();
        File meta = CNChunkedDownload.metaFileFor(t);
        long per = (totalSize + 3) / 4;
        StringBuilder sb = new StringBuilder();
        sb.append("CNVPROG2\n").append(totalSize).append(" 4\n").append("\n");
        for (int i = 0; i < 4; i++) {
            long st = i * per, en = Math.min(st + per - 1, totalSize - 1);
            sb.append(en - st + 1).append('\n');
        }
        java.io.FileWriter fw = new java.io.FileWriter(meta);
        fw.write(sb.toString());
        fw.close();
        CNChunkedDownload.partFileFor(t).delete();

        CNChunkedDownload.Result r = CNChunkedDownload.download(base, t, 4, false, p, new Sink());
        check("没有提交全零文件，内容正确", sha256(t).equals(expectSha),
              "sha=" + sha256(t).substring(0, 12));
        check("大小正确", r.totalBytes == totalSize, "" + r.totalBytes);
    }

    // 5. 同一条线路上服务端换了文件（URL 不变、ETag 变化）：必须放弃断点重下
    static void test5_etagChanged(File dir) throws Exception {
        System.out.println("\n[5] 同一线路上 ETag 变化 -> 拒绝复用断点");
        File t = new File(dir, "d.bin");
        clean(t);
        // 用**同一个 URL**制造残局
        // 先把服务端 ETag 重置到已知值——否则若上一轮测试已把它改成目标值，
        // 本用例里的「变化」根本没发生，断言就失去意义（测试隔离）
        ctl("/setetag?v=" + java.net.URLEncoder.encode("\"v1-fresh\"", "UTF-8"));
        ctl("/settruncate?v=1024");
        try {
            CNChunkedDownload.Probe p0 = CNChunkedDownload.probe(base, false);
            CNChunkedDownload.download(base, t, 4, false, p0, new Sink());
        } catch (IOException ignore) {}
        ctl("/settruncate?v=0");
        File part = CNChunkedDownload.partFileFor(t);
        check("残局已产生", part.exists() && CNChunkedDownload.metaFileFor(t).exists(), "");
        // 往残片里写脏数据：若断点被错误复用，最终 sha 必然对不上
        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        raf.seek(0);
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) 0xEE;
        raf.write(junk);
        raf.close();

        // 服务端把 ETag 换掉（URL 不变 —— 这才是「文件被换了」而非「换线」）
        ctl("/setetag?v=" + java.net.URLEncoder.encode("\"v2-changed\"", "UTF-8"));

        CNChunkedDownload.Probe p = CNChunkedDownload.probe(base, false);
        Sink s = new Sink();
        CNChunkedDownload.download(base, t, 4, false, p, s);
        check("断点被丢弃，从零重下", s.first == 0, "首次回调=" + s.first + "（应为 0）");
        check("脏数据未被保留", sha256(t).equals(expectSha), "sha=" + sha256(t).substring(0,12));
    }

    // 8. 有断点时服务端忽略 Range 返回 200：必须清掉断点，而不是反复撞墙
    static void test8_rangeIgnored(File dir) throws Exception {
        System.out.println("\n[8] 有断点时服务端忽略 Range（返回 200）");
        File t = new File(dir, "g.bin");
        clean(t);
        // 先制造断点
        ctl("/settruncate?v=" + (totalSize/8));
        try {
            CNChunkedDownload.Probe p0 = CNChunkedDownload.probe(base, false);
            CNChunkedDownload.download(base, t, 4, false, p0, new Sink());
        } catch (IOException ignore) {}
        ctl("/settruncate?v=0");
        check("断点已产生", CNChunkedDownload.metaFileFor(t).exists(), "");

        // 服务端开始无视 Range（norange=1 -> 整份 200）
        String urlNR = base + "?norange=1";
        CNChunkedDownload.Probe p = CNChunkedDownload.probe(base, false);
        boolean threw = false; String msg = "";
        try {
            CNChunkedDownload.download(urlNR, t, 4, false, p, new Sink());
        } catch (IOException e) { threw = true; msg = String.valueOf(e.getMessage()); }
        check("报错而非静默成功", threw, msg);
        check("断点已被清除（下次整份重下）",
              !CNChunkedDownload.metaFileFor(t).exists()
              && !CNChunkedDownload.partFileFor(t).exists(),
              "meta存在=" + CNChunkedDownload.metaFileFor(t).exists()
              + " part存在=" + CNChunkedDownload.partFileFor(t).exists());

        // 恢复正常后应当能一次下完
        CNChunkedDownload.Probe p2 = CNChunkedDownload.probe(base, false);
        CNChunkedDownload.download(base, t, 4, false, p2, new Sink());
        check("恢复后下载成功", sha256(t).equals(expectSha), "sha=" + sha256(t).substring(0,12));
    }

    /** 调服务端控制端点（保持请求 URL 不变，只改服务端行为）。 */
    static void ctl(String path) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL(baseRoot() + path).openConnection();
        c.getResponseCode(); c.disconnect();
    }

    /** 从 base 推出服务器根地址，用于调控制端点。 */
    static String baseRoot() throws Exception {
        java.net.URL u = new java.net.URL(base);
        return u.getProtocol() + "://" + u.getHost() + ":" + u.getPort();
    }

    // 6. 服务端多发字节：必须夹到分片边界，不能踩坏相邻分片
    static void test6_overSend(File dir) throws Exception {
        System.out.println("\n[6] 服务端越界多发字节");
        File t = new File(dir, "e.bin");
        clean(t);
        CNChunkedDownload.Probe p = CNChunkedDownload.probe(base, false);
        CNChunkedDownload.Result r =
                CNChunkedDownload.download(base + "?over=4096", t, 4, false, p, new Sink());
        check("内容仍然正确", sha256(t).equals(expectSha), "sha=" + sha256(t).substring(0, 12));
        check("大小正确", r.totalBytes == totalSize, "" + r.totalBytes);
    }
}
