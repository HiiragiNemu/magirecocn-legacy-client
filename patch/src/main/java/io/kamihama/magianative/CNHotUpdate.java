package io.kamihama.magianative;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 热更新 ZIP 的镜像下载实现。
 *
 * <p>动态热更新与静态基础包的身份模型不同：scenario/js 的权威身份来自本轮
 * version JSON 的 version + size + whole-file MD5，不能再套用可能滞后的全局
 * {@code manifest.json} 块哈希。下载仍可 Range 并发，但只做同 URL 字节续传；
 * 每条镜像完工后必须通过整包 size/MD5/ZIP 校验，失败即清除该镜像断点并换线。
 */
public final class CNHotUpdate {
    private static final String TAG = "MagiaCNHotUpdate";
    private static final int MAX_ATTEMPTS = 4;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final long MIN_OK_BPS = 100L * 1024L;
    private static final long SLOW_FAIL_NS = TimeUnit.SECONDS.toNanos(15L);

    private CNHotUpdate() {}

    /** 保留旧签名；没有 version meta 的调用仍走动态包的无 manifest 下载。 */
    public static boolean download(String url, String destPath,
                                   String displayName, int index) {
        return download(url, destPath, displayName, index, null);
    }

    /**
     * 下载并按 version JSON 的权威 size/MD5 校验。成功 true，失败 false。
     */
    public static boolean download(String url, String destPath,
                                   String displayName, int index,
                                   CNHotUpdateValidate.VerMeta expected) {
        CNDownloadRestart.register(index);
        try {
            return downloadRegistered(url, destPath, displayName, index, expected);
        } finally {
            CNDownloadRestart.unregister(index);
        }
    }

    private static boolean downloadRegistered(String url, String destPath,
                                   String displayName, int index,
                                   CNHotUpdateValidate.VerMeta expected) {
        if (url == null || destPath == null) {
            CNLog.e(TAG, "参数为空，放弃下载 url=" + url + " dest=" + destPath);
            return false;
        }
        if (CNDebugFlags.isOn(CNDebugFlags.FAIL_DOWNLOAD)) {
            CNLog.w(TAG, "[DEBUG] failDownload 注入：直接判本次下载失败 " + displayName);
            markFailed(index);
            return false;
        }

        File dest = new File(destPath);
        if (dest.isFile()) {
            String bad = expected == null ? null : CNHotUpdateValidate.verifyZip(dest, expected);
            if (bad == null) {
                CNLog.i(TAG, "目标已存在且身份有效，跳过下载: " + destPath);
                CNCNDownloadUI.setFileSize(index, (float) (dest.length() / 1000000.0d));
                CNCNDownloadUI.setFileDownloaded(index, (float) (dest.length() / 1000000.0d));
                markDone(index);
                return true;
            }
            CNLog.w(TAG, "目标已存在但不符合本轮 version JSON，清理重下: " + bad);
            cleanupDownloadArtifacts(dest);
        }

        String remoteName = mainLineFileName(url);
        if (remoteName == null) {
            CNLog.i(TAG, "非主线地址，按原地址下载: " + url);
            try {
                singleStream(withIdentity(url, expected), dest, index, false, expected,
                        CNDownloadRestart.generation(index));
                String bad = expected == null ? null : CNHotUpdateValidate.verifyZip(dest, expected);
                if (bad != null) throw new IOException("热更新完工校验失败: " + bad);
                markDone(index);
                return true;
            } catch (Throwable t) {
                // F-B-08：磁盘满不是下载失败——不清理断点（.part 保留，腾出
                // 空间后重试即续传），走与镜像循环同一个空间不足出口。
                if (CNDiskSpace.isOutOfSpace(t)) {
                    reportNoSpace(index, displayName, dest);
                    return false;
                }
                cleanupDownloadArtifacts(dest);
                markFailed(index);
                CNLog.e(TAG, "直连下载失败: " + url, t);
                return false;
            }
        }

        if (!CNMirrors.isLoaded()) CNMirrors.ensureLoadedAsync();
        CNLog.i(TAG, "开始下载 " + displayName + " file=" + remoteName
                + " identity=" + hotIdentity(expected)
                + " 可用线路=" + CNMirrors.healthy().size());

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                markFailed(index);
                return false;
            }
            final int restartToken = CNDownloadRestart.generation(index);
            // F-072：pick() 与随后的 urlFor()/身份拼接原先都在 try 之外，空线路表
            // 的 fail-closed 异常会越过包级失败出口——markFailed、镜像诊断、
            // 「保留旧内容与旧版本号」这些既定合同一条都不落地。
            CNMirrors.Mirror mirror;
            String tryUrl;
            try {
                mirror = CNMirrors.pick(attempt);
                tryUrl = withIdentity(mirror.urlFor(remoteName), expected);
            } catch (IllegalStateException noMirror) {
                CNLog.e(TAG, "no-mirror file=" + remoteName + " attempt=" + attempt, noMirror);
                markFailed(index);
                return false;
            }
            boolean direct = true;
            CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
            try {
                fetch(tryUrl, dest, index, direct, mirror, remoteName, expected,
                        restartToken);
                String bad = expected == null ? null : CNHotUpdateValidate.verifyZip(dest, expected);
                if (bad != null) {
                    cleanupDownloadArtifacts(dest);
                    throw new IOException("镜像内容不符合 version JSON: " + bad);
                }
                CNMirrors.reportSuccess(mirror);
                markDone(index);
                CNLog.i(TAG, "下载完成 " + remoteName + " attempt=" + attempt
                        + " mirror=" + mirror.name + " identity=" + hotIdentity(expected));
                return true;
            } catch (Throwable t) {
                if (CNDownloadRestart.changed(index, restartToken)) {
                    CNLog.i(TAG, "manual-restart-active file=" + remoteName
                            + " attempt=" + attempt + "：清除该文件断点并从头重下");
                    CNDownloadRestart.clearInterrupt();
                    cleanupDownloadArtifacts(dest);
                    CNCNDownloadUI.resetFileProgress(index);
                    attempt = 0;
                    continue;
                }
                // F-B-08：磁盘满不是线路故障。安装器主引擎/aria2 两条路都已
                // 用 CNDiskSpace 分流（CNDownloaderFix 的 NotEnoughSpace /
                // isOutOfSpace 分支），热更原先漏接——out.write 在磁盘满时抛的
                // 是普通 IOException，落到下面的 reportFailure 就是「线路故障」：
                // 线上 switch_after_failures=1，一次就把无辜线路打进 60 秒冷却，
                // 四次重试把四条线各烧一遍，每次从头写、每次 ENOSPC。这里照同
                // 一套模式分流：不 reportFailure、不冷却、**保留断点**（.part/
                // .cpart/meta 不清，腾出空间后重试即续传）、不再换线白试——
                // 空间不会因为多试四次就长出来。
                if (CNDiskSpace.isOutOfSpace(t)) {
                    reportNoSpace(index, displayName, dest);
                    return false;
                }
                CNMirrors.reportFailure(mirror, String.valueOf(t.getMessage()));
                CNLog.w(TAG, "下载失败 " + remoteName + " attempt=" + attempt
                        + " mirror=" + mirror.name, t);
                // 不同镜像的未认证字节绝不复用。无论成品是否已经 rename 出来，
                // 都清掉该镜像留下的 .part/.cpart/meta，再换下一条线路。
                cleanupDownloadArtifacts(dest);
                if (attempt < MAX_ATTEMPTS) {
                    long delay = 2000L << (attempt - 1);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        markFailed(index);
                        return false;
                    }
                }
            }
        }
        cleanupDownloadArtifacts(dest);
        markFailed(index);
        CNLog.e(TAG, "全部线路均失败: " + remoteName);
        return false;
    }

    /** Range 可用时走无 manifest 字节分段；进度按实际接收字节即时推进。 */
    private static void fetch(String url, File dest, int index,
                              boolean direct, CNMirrors.Mirror mirror,
                              String remoteName,
                              CNHotUpdateValidate.VerMeta expected,
                              int restartToken) throws IOException {
        // 单线程可靠模式下热更新也只开一条连接——热更是「进游戏前的最后一关」，
        // 卡在这里的玩家进不去游戏，所以它必须和基础包走同一个模式判据。
        int wanted = CNDownloadMode.cap(mirror.effectiveChunks());
        if (wanted > 1) {
            CNChunkedDownload.Probe probe = CNChunkedDownload.probe(url, direct);
            if (expected != null && expected.size > 0 && probe.total != expected.size) {
                throw new IOException("镜像总长与 version JSON 不符: "
                        + probe.total + " != " + expected.size);
            }
            if (probe.rangeSupported && probe.total > 0) {
                int chunks = wanted;
                long minChunk = CNMirrors.minChunkBytes();
                if (minChunk > 0) {
                    long fit = probe.total / minChunk;
                    if (fit < chunks) chunks = (int) Math.max(1L, fit);
                }
                if (chunks > 1) {
                    CNLog.i(TAG, "动态热更字节分段 " + dest.getName() + " segments=" + chunks
                            + " bytes=" + probe.total + " mirror=" + mirror.name
                            + "（不读取基础包 manifest）");
                    CNCNDownloadUI.setFileSize(index, (float) (probe.total / 1000000.0d));
                    // hashes 必须为 null：动态包由 version JSON 的 whole-file MD5 认证。
                    CNChunkedDownload.download(url, dest, chunks, direct, probe,
                            new HotSink(index, restartToken), mirror, remoteName, true, null);
                    return;
                }
            }
            CNLog.i(TAG, "不支持 Range 或文件过小，改用单线程: " + dest.getName());
        }
        singleStream(url, dest, index, direct, expected, restartToken);
    }

    private static final class HotSink implements CNChunkedDownload.Sink {
        private final int index;
        private final int restartToken;
        HotSink(int index, int restartToken) {
            this.index = index;
            this.restartToken = restartToken;
        }
        @Override public void onTotal(long total) {
            CNCNDownloadUI.setFileSize(index, (float) (total / 1000000.0d));
        }
        @Override public void onProgress(long soFar, long total) {
            CNCNDownloadUI.setFileDownloaded(index, (float) (soFar / 1000000.0d));
            int pct = total > 0
                    ? (int) Math.min(100L, Math.max(0L, (soFar * 100) / total)) : 0;
            CNCNDownloadUI.updateFileProgress(index, pct);
        }
        @Override public void onSpeed(float mbps) {
            CNCNDownloadUI.setDownloadSpeed(index, mbps);
        }
        @Override public boolean isCancelled() {
            return CNDownloadRestart.cancelled(index, restartToken);
        }
    }

    /** Range 不可用时的单连接续传，同样受全局 8 连接闸门约束。 */
    private static void singleStream(String url, File dest, int index, boolean direct,
                                     CNHotUpdateValidate.VerMeta expected,
                                     int restartToken) throws IOException {
        File part = new File(dest.getPath() + ".part");
        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException("无法创建下载目录: " + parent);
        }
        long offset = part.isFile() ? part.length() : 0L;
        if (expected != null && expected.size > 0 && offset > expected.size) {
            if (!part.delete() && part.exists()) throw new IOException("无法清理超长残片");
            offset = 0L;
        }

        CNDownloadConcurrency.Lease lease = CNDownloadConcurrency.acquire(
                "hot-single:" + dest.getName());
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection)
                    (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            CNUserAgent.apply(c);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");

            int code = c.getResponseCode();
            boolean append;
            long total;
            if (offset > 0 && code == 206) {
                // F-068：206 必须严格解析 Content-Range——证明正文确实从 offset 开始、
                // end 是文件尾、总长与 version JSON 一致。只信 206 + Content-Length
                // 会让中间设备/错误响应把任意字节序列当作续传正文（CNChunkedDownload
                // 的分段路径早已这么做）。畸形/不符立即失败，不写一个字节。
                long[] r = parseContentRange(c.getHeaderField("Content-Range"));
                long len = parseLong(c.getHeaderField("Content-Length"), -1L);
                if (r == null || r[0] != offset) {
                    throw new IOException("续传 Content-Range 缺失或起点不符: "
                            + c.getHeaderField("Content-Range") + " != " + offset);
                }
                if (r[1] >= 0 && r[2] >= 0 && r[1] != r[2] - 1L) {
                    throw new IOException("续传 Content-Range 未到文件尾（open-ended 请求应 end=total-1）: "
                            + c.getHeaderField("Content-Range"));
                }
                if (len >= 0 && r[1] >= 0 && len != r[1] - r[0] + 1L) {
                    throw new IOException("续传 Content-Length 与 Content-Range 不符: "
                            + len + " != " + (r[1] - r[0] + 1L));
                }
                append = true;
                total = r[2] >= 0 ? r[2] : (len >= 0 ? offset + len : -1L);
            } else if (code == 200) {
                append = false;
                offset = 0L;
                total = parseLong(c.getHeaderField("Content-Length"), -1L);
            } else {
                throw new IOException("HTTP " + code + " offset=" + offset + " url=" + url);
            }
            if (expected != null && expected.size > 0 && total != expected.size) {
                throw new IOException("响应总长与 version JSON 不符: "
                        + total + " != " + expected.size);
            }
            if (total > 0) CNCNDownloadUI.setFileSize(index, (float) (total / 1000000.0d));

            in = new BufferedInputStream(c.getInputStream(), 65536);
            out = new FileOutputStream(part, append);
            byte[] buf = new byte[65536];
            long written = 0L;
            long speedBase = 0L;
            long windowStart = System.nanoTime();
            long slowSinceNs = 0L;
            int n;
            double smoothedMbps = 0.0d;
            while ((n = in.read(buf)) != -1) {
                if (CNDownloadRestart.cancelled(index, restartToken)) {
                    throw new IOException("manual restart during hot-update download");
                }
                if (n == 0) continue;
                out.write(buf, 0, n);
                written += n;
                long soFar = offset + written;
                CNCNDownloadUI.setFileDownloaded(index, (float) (soFar / 1000000.0d));
                if (total > 0) {
                    int pct = (int) Math.min(100L, Math.max(0L, (soFar * 100) / total));
                    CNCNDownloadUI.updateFileProgress(index, pct);
                }
                long now = System.nanoTime();
                long dt = now - windowStart;
                if (dt >= TimeUnit.SECONDS.toNanos(3L)) {
                    long windowBytes = written - speedBase;
                    double instant = (windowBytes * 1.0E9d / dt) / 1000000.0d;
                    smoothedMbps = smoothedMbps <= 0.0d
                            ? instant : smoothedMbps * 0.70d + instant * 0.30d;
                    CNCNDownloadUI.setDownloadSpeed(index, (float) smoothedMbps);
                    if (windowBytes * 1000000000L / dt < MIN_OK_BPS) {
                        if (slowSinceNs == 0L) slowSinceNs = now;
                        else if (now - slowSinceNs >= SLOW_FAIL_NS) {
                            throw new IOException("镜像速度过慢（持续低于 "
                                    + (MIN_OK_BPS / 1024) + "KB/s），换线");
                        }
                    } else slowSinceNs = 0L;
                    speedBase = written;
                    windowStart = now;
                }
            }
            out.flush();
            out.getFD().sync();
            closeQuietly(out); out = null;
            closeQuietly(in); in = null;
            if (total > 0 && part.length() != total) {
                throw new IOException("下载不完整: " + part.length() + " / " + total);
            }
            // F-073：不预删 dest。先删再改名，两步之间被杀就连「上一次下好的包」
            // 一起没了；rename(2) 同目录替换本来就是原子的。
            CNAtomicReplace.commit(part, dest);
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
            lease.close();
        }
    }

    /** Request a from-zero restart of the currently active hot package. */
    static boolean requestActiveRestart(int index) {
        return CNDownloadRestart.request(index);
    }

    /** 清理某一动态包的全部下载态，不触碰已经事务应用的活动资源。 */
    static void cleanupDownloadArtifacts(File dest) {
        if (dest == null) return;
        deleteQuietly(dest);
        deleteQuietly(new File(dest.getPath() + ".part"));
        File sidecar = new File(dest.getPath() + ".part.meta");
        deleteQuietly(sidecar);
        // F-073：候选名不再固定为 <目标>.tmp，清残留一律走 sweep。
        CNAtomicReplace.sweep(sidecar);
        File cpart = CNChunkedDownload.partFileFor(dest);
        File meta = CNChunkedDownload.metaFileFor(dest);
        deleteQuietly(cpart);
        deleteQuietly(meta);
        CNAtomicReplace.sweep(meta);
        File parent = cpart.getParentFile();
        File[] files = parent == null ? null : parent.listFiles();
        String prefix = cpart.getName() + ".block.";
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File f = files[i];
                if (f != null && f.getName().startsWith(prefix)) deleteQuietly(f);
            }
        }
    }

    /**
     * 给 URL 挂上本轮身份，把 CDN 上可能存在的旧副本隔开。
     *
     * <p>包内可见而不是 private：首次安装器取热更两包时要用<b>同一个</b>格式。
     * 两边各写一份迟早会漂，而漂了的表现是「安装器下到的和热更轮下到的不是同
     * 一个东西」——最难查的那种。
     */
    static String withIdentity(String url, CNHotUpdateValidate.VerMeta meta) {
        if (meta == null) return url;
        String sep = url.indexOf('?') >= 0 ? "&" : "?";
        return url + sep + "cnv_hot=" + hotIdentity(meta);
    }

    private static String hotIdentity(CNHotUpdateValidate.VerMeta meta) {
        if (meta == null) return "legacy";
        String md5 = meta.md5 == null ? "nomd5" : meta.md5.trim().toLowerCase(Locale.US);
        if (md5.length() > 12) md5 = md5.substring(0, 12);
        return meta.version + "-" + meta.size + "-" + md5;
    }

    private static String mainLineFileName(String url) {
        String base = CNMirrors.CANONICAL_BASE;
        if (!url.startsWith(base)) return null;
        String rest = url.substring(base.length());
        if (rest.length() == 0) return null;
        if (rest.indexOf('/') >= 0 || rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0) return null;
        return rest;
    }

    private static void markDone(int index) {
        CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
        CNCNDownloadUI.markFileDone(index);
    }

    private static void markFailed(int index) {
        CNCNDownloadUI.setDownloadSpeed(index, 0.0f);
        if (CNCNDownloadUI.fileStatus != null
                && index >= 0 && index < CNCNDownloadUI.fileStatus.length) {
            CNCNDownloadUI.fileStatus[index] = CNCNDownloadUI.ST_ERROR;
        }
        CNCNDownloadUI.throttledUpdate();
    }

    /**
     * 「存储空间不足」出口（F-B-08），与 {@code CNDownloaderFix.reportNoSpace}
     * 同语：话里要有数字（还差多少），而不是「失败了，请重试」——这个失败
     * 点重试没意义，先去腾空间。断点与半成品一律保留：腾出空间后重试是
     * 续传，不是从头来。
     */
    private static void reportNoSpace(int index, String displayName, File dest) {
        String msg = CNDiskSpace.shortfall(displayName, 0L, CNDiskSpace.usableBytes(dest));
        CNLog.e(TAG, "no-space file=" + displayName + " " + msg
                + "（下载断点保留，不记线路冷却，不重试）");
        try {
            CNCNDownloadUI.updateSimple("存储空间不足",
                    msg + "。请清理后点「重试」，已下好的部分会保留。", 0);
        } catch (Throwable ignore) {}
        markFailed(index);
    }

    private static long parseLong(String s, long dflt) {
        if (s == null) return dflt;
        try {
            long v = Long.parseLong(s.trim());
            return v >= 0 ? v : dflt;
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    /**
     * 解析 {@code Content-Range}：返回 {@code [start, end, total]}，total 为
     * {@code *} 时记 -1；不是 {@code bytes start-end/total} 的完整形式返回 null。
     * F-068：单连接续传只信 206 + Content-Length，会被中间设备/错误响应喂任意
     * 字节序列——必须证明正文确实从请求 offset 开始。
     */
    private static long[] parseContentRange(String cr) {
        if (cr == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "bytes (\\d+)-(\\d+)/(\\d+|\\*)").matcher(cr.trim());
        if (!m.matches()) return null;
        long start = Long.parseLong(m.group(1));
        long end = Long.parseLong(m.group(2));
        long total = "*".equals(m.group(3)) ? -1L : Long.parseLong(m.group(3));
        return new long[]{start, end, total};
    }

    private static void deleteQuietly(File f) {
        if (f == null || !f.exists()) return;
        try { if (!f.delete() && f.exists()) CNLog.w(TAG, "无法删除 " + f); }
        catch (Throwable ignore) {}
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Throwable ignore) {}
    }

    // ---- JVM 合同测试入口 ----
    public static String hotIdentityForTest(CNHotUpdateValidate.VerMeta meta) {
        return hotIdentity(meta);
    }
    public static boolean usesChunkManifestForHotUpdateForTest() { return false; }
}
