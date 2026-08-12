import io.kamihama.magianative.CNChunkedDownload;
import io.kamihama.magianative.CNChunkedDownload.ChunkHashes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 分块事务下载集成测试。钉住 03 事故真正需要的恢复语义：
 * 85 个校验块不等于 85 个并发线程；同一 manifest 下网络坏块不得提交；
 * 非整除布局的已验证块可跨重启复用；无 manifest 时不得跨镜像拼接未认证字节。
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
        String root = rootOf(url);
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
        check("测试模型确实为 85 块", realChunks.size() == 85,
                "blocks=" + realChunks.size() + " chunk=" + chunk);
        check("最后一块为非整除余数", ref.length() % chunk != 0,
                "remainder=" + (ref.length() % chunk));

        System.out.println("\n[1] 正确清单 + requested=85：块数与网络并发解耦");
        File normal = new File(dir, "normal.bin");
        clean(normal);
        httpGet(root + "/resetstats");
        String slowUrl = addQuery(url, "slow_others_ms=75");
        CNChunkedDownload.Result r = CNChunkedDownload.download(slowUrl, normal, 85, true,
                CNChunkedDownload.probe(slowUrl, true), new Sink(), null, null, false, good);
        String stats = httpGet(root + "/stats");
        int requests = count(stats, "\"start\"");
        int maxActive = intField(stats, "max_active");
        check("下载成功", normal.isFile(), "target exists");
        check("内容一致", fileMd5(normal).equals(fileMd5(ref)), "md5 match");
        check("全部块已认证", r.chunkVerified, "chunkVerified=true");
        check("85 块各请求一次", requests == 85, "requests=" + requests);
        check("并发被限制在 8 内", maxActive >= 2 && maxActive <= 8,
                "max_active=" + maxActive);

        System.out.println("\n[2] 同一清单下网络坏块不得写进主文件或 verified 状态");
        File poisoned = new File(dir, "poisoned.bin");
        clean(poisoned);
        int badBlock = realChunks.size() - 1;
        String badUrl = addQuery(url, "corrupt_block=" + badBlock
                + "&block_size=" + chunk);
        boolean rejected = false;
        Sink badSink = new Sink();
        try {
            // 单 worker 让前 84 块先确定提交，最后一块再触发真实网络损坏。
            CNChunkedDownload.download(badUrl, poisoned, 1, true,
                    CNChunkedDownload.probe(badUrl, true), badSink,
                    null, null, false, good);
        } catch (IOException expected) {
            rejected = true;
            System.out.println("  期望异常: " + expected.getMessage());
        }
        long verifiedBeforeTail = (long) badBlock * chunk;
        check("错误块被拒绝", rejected, "hash mismatch");
        check("进度只到前 84 个可信块", badSink.last == verifiedBeforeTail,
                "last=" + badSink.last + " expected=" + verifiedBeforeTail);
        httpGet(root + "/resetstats");
        Sink afterBad = new Sink();
        CNChunkedDownload.download(url, poisoned, 1, true,
                CNChunkedDownload.probe(url, true), afterBad, null, null, false, good);
        String retryStats = httpGet(root + "/stats");
        check("恢复沿用前 84 个 verified 块", afterBad.first == verifiedBeforeTail,
                "首次可信进度=" + afterBad.first);
        check("恢复只请求坏的最后一块", count(retryStats, "\"start\"") == 1,
                retryStats);
        check("恢复后内容正确", fileMd5(poisoned).equals(fileMd5(ref)), "md5 match");

        System.out.println("\n[3] 非整除 85 块布局跨重启复用");
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
        check("从已验证块继续而非归零", continueSink.first == chunk,
                "首次可信进度=" + continueSink.first);
        check("续传成品正确", fileMd5(resumed).equals(fileMd5(ref)), "md5 match");

        System.out.println("\n[4] 无 manifest：不同 URL 不得复用未认证断点");
        File unverified = new File(dir, "unverified.bin");
        clean(unverified);
        String urlA = addQuery(url, "mirror=A&truncate=1024");
        try {
            CNChunkedDownload.download(urlA, unverified, 4, true,
                    CNChunkedDownload.probe(urlA, true), new Sink(), null, null, false);
        } catch (IOException expected) {
            System.out.println("  期望制造残局: " + expected.getMessage());
        }
        check("无清单残局已产生", CNChunkedDownload.partFileFor(unverified).isFile()
                        && CNChunkedDownload.metaFileFor(unverified).isFile(),
                "part/meta retained");
        String urlB = addQuery(url, "mirror=B");
        Sink switched = new Sink();
        CNChunkedDownload.download(urlB, unverified, 4, true,
                CNChunkedDownload.probe(urlB, true), switched, null, null, false);
        check("换 URL 后未认证断点从零重建", switched.first == 0L,
                "first=" + switched.first);
        check("重建后内容正确", fileMd5(unverified).equals(fileMd5(ref)), "md5 match");

        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    static String addQuery(String url, String query) {
        return url + (url.indexOf('?') >= 0 ? "&" : "?") + query;
    }

    static String rootOf(String url) throws Exception {
        URL u = new URL(url);
        return u.getProtocol() + "://" + u.getHost()
                + (u.getPort() >= 0 ? ":" + u.getPort() : "");
    }

    static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(5000);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
        in.close();
        c.disconnect();
        return new String(out.toByteArray(), "UTF-8");
    }

    static int count(String text, String needle) {
        int n = 0, p = 0;
        while ((p = text.indexOf(needle, p)) >= 0) { n++; p += needle.length(); }
        return n;
    }

    static int intField(String json, String key) {
        int p = json.indexOf("\"" + key + "\"");
        if (p < 0 || (p = json.indexOf(':', p)) < 0) return -1;
        p++;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        int e = p;
        while (e < json.length() && Character.isDigit(json.charAt(e))) e++;
        return e > p ? Integer.parseInt(json.substring(p, e)) : -1;
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
