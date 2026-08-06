package io.kamihama.magianative;


import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多线程分片下载（带断点续传）。
 *
 * <p>做法与复兴计划客户端的 {@code Net.downloadChunked} 一致：先探测服务端是否
 * 支持 {@code Range}，支持则把文件切成 N 段并行下载，段内进度写入
 * {@code .cpart.prog} 元数据，中断后可从断点继续。
 *
 * <p>分片下载使用**独立的临时文件名** {@code <目标>.cpart}，与单线程续传路径的
 * {@code <目标>.part} 完全隔离。这是刻意的：分片路径会把临时文件**预分配**到
 * 完整长度，而单线程路径是用「临时文件当前长度」当续传偏移的——两者共用同一个
 * 文件时，一个中断的分片下载会让单线程路径把偏移当成「已下完」，进而把一个中间
 * 全是空洞的文件当作完整文件提交。分开命名从根上避免这种误判。
 *
 * <h3>断点续传的可信前提</h3>
 * 元数据只有在**同时**满足下面所有条件时才被采信，任何一条不满足都退回从头下载
 * （宁可多下一遍，也不能把两份不同的内容拼在一起）：
 * <ul>
 *   <li>{@code .cpart} 存在，且长度恰好等于本次探测到的总长度——防止元数据还在、
 *       临时文件已被清掉时，把预分配出来的<b>全零文件</b>当成已下完直接提交；</li>
 *   <li>元数据记录的总长度与本次探测一致；</li>
 *   <li>元数据记录的 ETag 与本次探测一致——<b>仅当本次与上次是同一条线路时才比对</b>。
 *       各线路对同一文件给出的 ETag 格式互不相同（nginx 的 inode-mtime、CDN 的
 *       MD5、对象存储的版本号），跨线路照比必然不等，会让换线把续传成果全部作废；
 *       换线时改为只依赖总长度一致；</li>
 *   <li>各分片的已完成字节数都在 {@code [0, 分片长度]} 区间内。</li>
 * </ul>
 *
 * <p>分片布局（分片数）一旦写进元数据就**固定不变**，后续即使换到 {@code chunks}
 * 配置不同的线路也沿用原布局，这样换线不会让已下好的部分作废。
 *
 * <p>下载过程中若出现「长时间没有任何字节」或「持续低于最低速度」，会主动中断
 * 并抛出 {@link IOException}，由调用方换到下一条线路（见 {@link CNMirrors}）。
 */
public final class CNChunkedDownload {

    private static final String TAG = "MagiaCNChunk";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 30000;

    /** 断点元数据的格式标识；不匹配一律视作不可用。 */
    private static final String META_MAGIC = "CNVPROG3";

    private CNChunkedDownload() {}

    /** 进度回调。 */
    public interface Sink {
        /** 探测到文件总长度时回调一次。 */
        void onTotal(long total);
        /** 已下载字节数发生变化（绝对值，含断点续传的已有部分）。 */
        void onProgress(long soFar, long total);
        /** 瞬时速度，单位 MB/s。 */
        void onSpeed(float mbps);
        /** 返回 true 表示外部要求取消。 */
        boolean isCancelled();
    }

    /** 探测结果。 */
    public static final class Probe {
        public final long    total;
        public final String  etag;
        public final boolean rangeSupported;
        Probe(long total, String etag, boolean rangeSupported) {
            this.total          = total;
            this.etag           = etag == null ? "" : etag.trim();
            this.rangeSupported = rangeSupported;
        }
    }

    /** 下载结果。 */
    public static final class Result {
        public final long   totalBytes;
        public final String etag;
        Result(long totalBytes, String etag) {
            this.totalBytes = totalBytes;
            this.etag       = etag == null ? "" : etag;
        }
    }

    /** 从元数据文件读出的断点状态。 */
    private static final class Resume {
        long   total;
        int    chunks;
        String etag = "";
        /** 写下这份断点时所用的完整 URL；用于判断本次是否换了线路。 */
        String url  = "";
        long[] done;
    }

    private static HttpURLConnection open(String url, boolean direct) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept-Encoding", "identity");
        // 不写 Connection: close——保留 keep-alive 复用连接池，
        // 分片/重试接连不断时省掉每段一次的 TCP+TLS 握手
        return c;
    }

    /**
     * 探测总长度与 Range 支持情况。先试 HEAD；某些 CDN 对 HEAD 不返回
     * {@code Accept-Ranges}，因此 HEAD 结果不可用时再用一次
     * {@code Range: bytes=0-0} 的 GET 兜底。任何异常都当作「不支持」。
     */
    public static Probe probe(String url, boolean direct) {
        HttpURLConnection c = null;
        try {
            c = open(url, direct);
            c.setRequestMethod("HEAD");
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                long total  = parseLong(c.getHeaderField("Content-Length"), -1L);
                String ar   = c.getHeaderField("Accept-Ranges");
                String etag = c.getHeaderField("ETag");
                boolean ok  = ar != null && ar.toLowerCase(Locale.US).contains("bytes");
                if (total > 0 && ok) return new Probe(total, etag, true);
                if (total > 0)       return new Probe(total, etag, false);
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignore) {} }
        }

        c = null;
        try {
            c = open(url, direct);
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            String etag = c.getHeaderField("ETag");
            if (code == 206) {
                long total = totalFromContentRange(c.getHeaderField("Content-Range"));
                if (total > 0) return new Probe(total, etag, true);
            } else if (code >= 200 && code < 300) {
                long total = parseLong(c.getHeaderField("Content-Length"), -1L);
                if (total > 0) return new Probe(total, etag, false);
            }
        } catch (Throwable ignore) {
        } finally {
            if (c != null) { try { c.disconnect(); } catch (Throwable ignore) {} }
        }
        return new Probe(-1L, "", false);
    }

    /** 分片下载使用的临时文件。 */
    public static File partFileFor(File target) {
        return new File(target.getPath() + ".cpart");
    }

    /** 分片进度元数据文件。 */
    public static File metaFileFor(File target) {
        return new File(target.getPath() + ".cpart.prog");
    }

    /**
     * 分片下载 {@code url} 到 {@code target}（成功后 target 即为完整文件）。
     *
     * @param requestedChunks 本线路建议的分片数；若已有可用断点，则沿用断点里的
     *                        分片布局，忽略此值
     * @throws IOException 网络错误、停滞、过慢、短读或校验失败
     */
    public static Result download(String url, File target, int requestedChunks,
                                  boolean direct, Probe probe, Sink sink)
            throws IOException {
        return download(url, target, requestedChunks, direct, probe, sink, null);
    }

    /**
     * 同上，额外传入本次使用的线路，用于反限速判定（可为 null 表示不判定）。
     */
    public static Result download(String url, File target, int requestedChunks,
                                  boolean direct, Probe probe, Sink sink,
                                  CNMirrors.Mirror mirror)
            throws IOException {
        return download(url, target, requestedChunks, direct, probe, sink, mirror, null);
    }

    /**
     * 同上，再额外传入文件名（主线资源根下的单段文件名，可为 null）。
     *
     * <p>当 {@code settings.chunks_across_mirrors=true} 且健康镜像 ≥2 时，
     * 各分片按轮转派给多条镜像同时下载，吞吐随线路数叠加。
     * 跨镜像时 {@code If-Range} 只在主线路那条分片上带——各家 ETag 格式
     * 互不相同，跨线带校验必然 200 整份重发。文件级一致性由调用方的
     * size/md5 完工校验兜住（ETag 从来都不是完整性的依据）。
     */
    public static Result download(String url, File target, int requestedChunks,
                                  boolean direct, Probe probe, Sink sink,
                                  CNMirrors.Mirror mirror, String remoteName)
            throws IOException {

        final long total = probe.total;
        if (total <= 0) throw new IOException("未知的文件长度");

        final File part = partFileFor(target);
        final File meta = metaFileFor(target);

        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("无法创建下载目录: " + parent);
        }

        // ── 判定断点是否可信 ──
        int    chunks   = requestedChunks < 1 ? 1 : requestedChunks;
        long[] resumed  = null;
        Resume st = readResume(meta);
        if (st != null) {
            String why = resumeRejectReason(st, total, probe.etag, url, part);
            if (why == null) {
                // 沿用元数据里的分片布局，保证换线也能接着下
                chunks  = st.chunks;
                resumed = st.done;
                long have = 0L;
                for (int i = 0; i < resumed.length; i++) have += resumed[i];
                CNLog.i(TAG, "resume-accept file=" + target.getName()
                        + " chunks=" + chunks + " have=" + have + "/" + total);
            } else {
                CNLog.w(TAG, "resume-reject file=" + target.getName() + " reason=" + why);
                deleteQuietly(meta);
            }
        }

        final long chunkSize = (total + chunks - 1) / chunks;
        final long[] starts  = new long[chunks];
        final long[] ends    = new long[chunks];
        final AtomicLongArray done = new AtomicLongArray(chunks);
        for (int i = 0; i < chunks; i++) {
            starts[i] = i * chunkSize;
            ends[i]   = Math.min(starts[i] + chunkSize - 1, total - 1);
            done.set(i, resumed != null ? resumed[i] : 0L);
        }

        // 预分配到完整长度（分片要按偏移随机写入）。
        // 注意顺序：断点可信度已在上面判完，这里再拉长文件就不会影响判定。
        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        try {
            if (raf.length() != total) raf.setLength(total);
        } finally {
            try { raf.close(); } catch (Throwable ignore) {}
        }
        saveMeta(meta, total, probe.etag, url, done);

        final AtomicLong totalDone = new AtomicLong(0L);
        for (int i = 0; i < chunks; i++) totalDone.addAndGet(done.get(i));

        if (sink != null) {
            sink.onTotal(total);
            sink.onProgress(totalDone.get(), total);
        }

        // 元数据显示已全部完成：此时 .cpart 的存在与长度已在 resumeRejectReason
        // 里验过，可以直接提交
        if (totalDone.get() >= total) {
            promote(part, target);
            deleteQuietly(meta);
            if (sink != null) sink.onProgress(total, total);
            CNLog.i(TAG, "resume-complete file=" + target.getName() + " 无需再下载");
            return new Result(total, probe.etag);
        }

        final AtomicReference<IOException> firstErr = new AtomicReference<IOException>(null);
        final AtomicBoolean abort        = new AtomicBoolean(false);
        /** 任一分片收到 HTTP 200（Range 被忽略）时置位。 */
        final AtomicBoolean rangeIgnored = new AtomicBoolean(false);
        final AtomicLong    lastMoveNs  = new AtomicLong(System.nanoTime());
        final AtomicLong    windowStart = new AtomicLong(System.nanoTime());
        final AtomicLong    windowBytes = new AtomicLong(0L);

        // 分片跨镜像并发：开关打开且有多条健康镜像时，分片轮转派到各线路
        final String[] chunkUrls;
        java.util.List<CNMirrors.Mirror> spread = null;
        if (remoteName != null && CNMirrors.chunksAcrossMirrors()) {
            java.util.List<CNMirrors.Mirror> h = CNMirrors.healthy();
            if (h.size() >= 2) spread = h;
        }
        if (spread != null) {
            chunkUrls = new String[chunks];
            for (int i = 0; i < chunks; i++) {
                chunkUrls[i] = spread.get(i % spread.size()).urlFor(remoteName);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(chunks, spread.size()); i++)
                sb.append(' ').append(spread.get(i).name);
            CNLog.i(TAG, "分片跨镜像并发 file=" + target.getName()
                    + " chunks=" + chunks + " 线路:" + sb);
        } else {
            chunkUrls = null;
        }

        ExecutorService pool = Executors.newFixedThreadPool(chunks, new ChunkThreadFactory());
        final CountDownLatch latch = new CountDownLatch(chunks);

        for (int i = 0; i < chunks; i++) {
            ChunkTask task = new ChunkTask();
            task.url = chunkUrls != null ? chunkUrls[i] : url;
            task.part = part;
            task.start = starts[i]; task.end = ends[i];
            task.done = done;       task.idx = i;
            task.direct = direct;   task.meta = meta;
            task.total = total;
            // If-Range 只在主线路的分片上带：跨镜像 ETag 格式互不相同
            task.etag = (chunkUrls == null || chunkUrls[i].equals(url)) ? probe.etag : null;
            task.totalDone = totalDone;
            task.windowStart = windowStart; task.windowBytes = windowBytes;
            task.lastMoveNs = lastMoveNs;   task.abort = abort;
            task.rangeIgnored = rangeIgnored;
            task.sink = sink;       task.firstErr = firstErr;
            task.latch = latch;
            pool.submit(task);
        }

        // 监控：停滞 / 过慢 / 外部取消 —— 命中即中断本次尝试，交给上层换线
        final long stallNs = TimeUnit.SECONDS.toNanos(CNMirrors.stallSeconds());
        // 字段名是 min_speed_kbps —— kbps 按惯例是「千比特每秒」，所以要
        // 除以 8 换成字节。之前按 KiB/s 解释，线上配置的 800 会变成
        // 800 KiB/s ≈ 6.5 Mbit/s 的下限，任何慢于此的用户每条线都会在 10 秒
        // 后被判「过慢」，4 次尝试耗尽后整包安装失败。
        final long minBps  = (long) CNMirrors.minSpeedKbps() * 1000L / 8L;
        long checkStartNs  = System.nanoTime();
        long bytesAtCheck  = totalDone.get();
        // 反限速（相对自身基线）：仅在传入 mirror 时启用。逐 3 秒窗口测实际吞吐；
        // 持续低于该线历史峰值 × throttle_ratio_pct% 达到 throttle_grace_s 秒，
        // 判为被限速 → 降级该线 → 若有更快的线则中断本次尝试交给上层换线。
        final long rateIntervalNs = TimeUnit.SECONDS.toNanos(3L);
        long rateCheckNs  = System.nanoTime();
        long rateBytesAtCheck = totalDone.get();
        int  slowTicks    = 0;
        final long graceTicks = Math.max(1L,
                (long) CNMirrors.throttleGraceS() * 1000L / 3000L);
        try {
            while (!latch.await(1, TimeUnit.SECONDS)) {
                long now = System.nanoTime();
                if (sink != null && sink.isCancelled()) {
                    abort.set(true);
                    firstErr.compareAndSet(null, new IOException("已取消"));
                    break;
                }
                if (now - lastMoveNs.get() > stallNs) {
                    abort.set(true);
                    firstErr.compareAndSet(null, new IOException(
                            "线路停滞：" + CNMirrors.stallSeconds() + " 秒内没有任何数据"));
                    break;
                }
                long elapsed = now - checkStartNs;
                if (minBps > 0 && elapsed >= TimeUnit.SECONDS.toNanos(10)) {
                    long moved = totalDone.get() - bytesAtCheck;
                    long bps   = (long) (moved / (elapsed / 1_000_000_000.0));
                    if (bps < minBps) {
                        abort.set(true);
                        firstErr.compareAndSet(null, new IOException(
                                "线路过慢：" + (bps * 8 / 1000) + " kbps < "
                                + CNMirrors.minSpeedKbps() + " kbps"));
                        break;
                    }
                    checkStartNs = now;
                    bytesAtCheck = totalDone.get();
                }
                // 相对基线反限速：记录历史峰值，持续走低则降级该线，有更快线就换
                long rateElapsed = now - rateCheckNs;
                if (mirror != null && rateElapsed >= rateIntervalNs) {
                    long moved = totalDone.get() - rateBytesAtCheck;
                    long bps   = (long) (moved / (rateElapsed / 1_000_000_000.0));
                    rateCheckNs = now;
                    rateBytesAtCheck = totalDone.get();
                    if (bps > 0) {
                        CNMirrors.reportBaseline(mirror, bps);
                        long bl = mirror.baselineBps;
                        if (bl > 0 && bps < bl * CNMirrors.throttleRatioPct() / 100L) {
                            if (++slowTicks >= graceTicks) {
                                CNMirrors.reportThrottled(mirror);
                                slowTicks = 0;
                                if (CNMirrors.worthSwitching(mirror, bps)) {
                                    abort.set(true);
                                    firstErr.compareAndSet(null, new IOException(
                                            "线路疑似被限速，换线: " + (bps * 8 / 1000)
                                            + "kbps < 基线 " + (bl * 8 / 1000) + "kbps"));
                                    break;
                                }
                            }
                        } else {
                            slowTicks = 0;
                        }
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            abort.set(true);
            firstErr.compareAndSet(null, new IOException("已取消"));
        }

        pool.shutdownNow();
        try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
        // 无论成败都落盘：保住这一轮已经下到的进度
        saveMeta(meta, total, probe.etag, url, done);

        IOException err = firstErr.get();
        if (err != null) {
            if (rangeIgnored.get()) {
                // 断点在这条线路上用不了：清干净，下一次尝试整份重下。
                // 不清的话每次尝试都会重复撞上同一个 200。
                CNLog.w(TAG, "服务端忽略 Range，清除断点后整份重下: " + target.getName());
                deleteQuietly(meta);
                deleteQuietly(part);
            }
            throw err;   // 否则保留 .cpart 与元数据，下次可续
        }

        // 完工校验：.cpart 是预分配的，长度永远等于 total，所以**不能**拿长度当
        // 完成依据——必须核对各分片累计的已写字节数。少了就是短读，绝不提交。
        long written = 0L;
        for (int i = 0; i < chunks; i++) written += done.get(i);
        if (written != total) {
            throw new IOException("下载不完整: 已写 " + written + " / " + total);
        }
        long actual = part.length();
        if (actual != total) {
            throw new IOException("临时文件大小异常: " + actual + " / " + total);
        }

        promote(part, target);
        deleteQuietly(meta);
        if (sink != null) {
            sink.onProgress(total, total);
            sink.onSpeed(0f);
        }
        CNLog.i(TAG, "分片下载完成 file=" + target.getName() + " bytes=" + total
                + " chunks=" + chunks);
        return new Result(total, probe.etag);
    }

    /**
     * 判断已有断点是否可用。返回 {@code null} 表示可用，否则返回不可用的原因。
     */
    private static String resumeRejectReason(Resume st, long total, String etag,
                                             String url, File part) {
        if (st.total != total) {
            return "总长度不符 " + st.total + " != " + total;
        }
        if (st.chunks < 1 || st.done == null || st.done.length != st.chunks) {
            return "分片信息损坏";
        }
        // 临时文件必须在、且长度正确。否则元数据可能对应一个已被删除的文件，
        // 预分配会造出一个全零文件并被误判成「已下完」。
        if (!part.isFile()) {
            return "临时文件不存在";
        }
        if (part.length() != total) {
            return "临时文件长度不符 " + part.length() + " != " + total;
        }
        // ETag 只在**同一条线路**上才有可比性。
        // 实测三条线路对同一个文件给出的 ETag 格式互不相同（nginx 的
        // inode-mtime、CDN 的 MD5、对象存储的版本号），跨线路比对必然不等，
        // 若照比就会让「自动换线」把「断点续传」的成果全部作废——两个功能
        // 互相抵消。换线时改为只依赖总长度一致（镜像提供的是同一份文件）。
        boolean sameLine = st.url.length() > 0 && st.url.equals(url);
        if (sameLine && st.etag.length() > 0 && etag != null && etag.length() > 0
                && !st.etag.equals(etag)) {
            return "ETag 已变化";
        }
        long chunkSize = (total + st.chunks - 1) / st.chunks;
        for (int i = 0; i < st.chunks; i++) {
            long start = i * chunkSize;
            long end   = Math.min(start + chunkSize - 1, total - 1);
            long len   = end - start + 1;
            if (st.done[i] < 0 || st.done[i] > len) {
                return "分片 " + i + " 进度越界 " + st.done[i] + " / " + len;
            }
        }
        return null;
    }

    /** 分片线程工厂：守护线程，进程退出不被卡住。 */
    private static final class ChunkThreadFactory implements ThreadFactory {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cnv-chunk");
            t.setDaemon(true);
            return t;
        }
    }

    /** 单个分片的下载任务。 */
    private static final class ChunkTask implements Runnable {
        String url;
        File   part;
        long   start;
        long   end;
        AtomicLongArray done;
        int    idx;
        boolean direct;
        File   meta;
        long   total;
        String etag;
        AtomicLong totalDone;
        AtomicLong windowStart;
        AtomicLong windowBytes;
        AtomicLong lastMoveNs;
        AtomicBoolean abort;
        AtomicBoolean rangeIgnored;
        Sink   sink;
        AtomicReference<IOException> firstErr;
        CountDownLatch latch;

        @Override public void run() {
            try {
                oneChunk(url, part, start, end, done, idx, direct, meta, total, etag,
                         totalDone, windowStart, windowBytes, lastMoveNs, abort,
                         rangeIgnored, sink);
            } catch (Throwable t) {
                firstErr.compareAndSet(null,
                        t instanceof IOException ? (IOException) t
                                                 : new IOException(String.valueOf(t.getMessage()), t));
                abort.set(true);
            } finally {
                latch.countDown();
            }
        }
    }

    private static void oneChunk(String url, File part,
                                 long chunkStart, long chunkEnd,
                                 AtomicLongArray done, int idx, boolean direct,
                                 File meta, long total, String etag,
                                 AtomicLong totalDone,
                                 AtomicLong windowStart, AtomicLong windowBytes,
                                 AtomicLong lastMoveNs,
                                 AtomicBoolean abort, AtomicBoolean rangeIgnored,
                                 Sink sink) throws IOException {

        final long chunkLen = chunkEnd - chunkStart + 1;
        long already = done.get(idx);
        if (already >= chunkLen) return;

        final long startByte = chunkStart + already;
        HttpURLConnection c = open(url, direct);
        c.setRequestMethod("GET");
        c.setRequestProperty("Range", "bytes=" + startByte + "-" + chunkEnd);
        if (etag != null && etag.length() > 0) {
            // 续传途中服务端换了文件时，让它直接拒绝而不是给回另一版本的字节
            c.setRequestProperty("If-Range", etag);
        }
        c.connect();
        int code = c.getResponseCode();
        if (code != 206) {
            try { c.disconnect(); } catch (Throwable ignore) {}
            if (code == 200) {
                // 服务端忽略了 Range，或 If-Range 的校验值不匹配而整份重发。
                // 这种响应对分片下载不可用，但**不是**线路故障：若只当普通失败
                // 处理，四次尝试会全部撞在同一堵墙上，最终整个压缩包失败、
                // 安装器提前返回——而安装器一返回，native hook 就会放行引擎
                // 自带的下载场景。所以这里单独标记，让上层清掉断点后重来。
                rangeIgnored.set(true);
                throw new IOException("分片 " + idx + " 的 Range 被服务端忽略（HTTP 200）");
            }
            throw new IOException("分片 " + idx + " 期望 206，实得 HTTP " + code);
        }
        // 回验服务端给的确实是我们要的区间，避免中间设备返回错位数据后
        // 被按偏移写进文件
        long got = rangeStart(c.getHeaderField("Content-Range"));
        if (got >= 0 && got != startByte) {
            try { c.disconnect(); } catch (Throwable ignore) {}
            throw new IOException("分片 " + idx + " Content-Range 起点不符: "
                    + got + " != " + startByte);
        }

        InputStream is = null;
        RandomAccessFile raf = null;
        try {
            is  = new BufferedInputStream(c.getInputStream(), 1 << 16);
            raf = new RandomAccessFile(part, "rw");
            raf.seek(startByte);
            byte[] buf = new byte[1 << 15];
            long lastSaveNs = System.nanoTime();
            int n;
            while ((n = is.read(buf)) != -1) {
                if (n == 0) continue;
                if (abort.get()) throw new IOException("已中断");
                if (sink != null && sink.isCancelled()) throw new IOException("已取消");

                // 夹到分片边界：服务端多发的字节直接丢弃，否则会踩坏下一片的区域
                long remain = chunkLen - done.get(idx);
                int  wr     = (int) Math.min((long) n, remain);
                if (wr <= 0) break;
                raf.write(buf, 0, wr);

                long cur      = done.addAndGet(idx, wr);
                long sumSoFar = totalDone.addAndGet(wr);
                long now = System.nanoTime();
                lastMoveNs.set(now);

                long wb = windowBytes.addAndGet(wr);
                long ws = windowStart.get();
                long elapsedMs = (now - ws) / 1_000_000L;
                if (elapsedMs >= 500 && windowStart.compareAndSet(ws, now)) {
                    windowBytes.set(0L);
                    if (sink != null) {
                        sink.onProgress(sumSoFar, total);
                        float mbps = (float) ((wb * 1000.0 / elapsedMs) / 1_000_000.0);
                        sink.onSpeed(mbps);
                    }
                }
                if (now - lastSaveNs > 2_000_000_000L) {
                    saveMeta(meta, total, etag, url, done);
                    lastSaveNs = now;
                }
                if (cur >= chunkLen) break;
            }
            // 服务端提前断流时 read() 会正常返回 -1，不抛异常。这里必须显式
            // 检查，否则这一片会带着缺口被当成「下完了」。
            long finished = done.get(idx);
            if (finished < chunkLen) {
                throw new IOException("分片 " + idx + " 短读: " + finished + " / " + chunkLen);
            }
        } finally {
            saveMeta(meta, total, etag, url, done);
            if (raf != null) { try { raf.close(); } catch (Throwable ignore) {} }
            if (is  != null) { try { is.close();  } catch (Throwable ignore) {} }
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    // ---- 断点元数据 ----
    //
    // 格式（UTF-8 文本）：
    //   第 1 行 CNVPROG2
    //   第 2 行 <总长度> <分片数>
    //   第 3 行 <ETag>（可为空行）
    //   第 4 行 <写下这份断点时所用的完整 URL>（可为空行）
    //   其后每行一个分片的已完成字节数

    private static synchronized void saveMeta(File meta, long total,
                                              String etag, String url,
                                              AtomicLongArray done) {
        File tmp = new File(meta.getAbsolutePath() + ".tmp");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp, false), "UTF-8");
            StringBuilder sb = new StringBuilder();
            sb.append(META_MAGIC).append('\n');
            sb.append(total).append(' ').append(done.length()).append('\n');
            sb.append(sanitize(etag)).append('\n');
            sb.append(sanitize(url)).append('\n');
            for (int i = 0; i < done.length(); i++) sb.append(done.get(i)).append('\n');
            w.write(sb.toString());
            w.flush();
        } catch (Throwable t) {
            if (w != null) { try { w.close(); } catch (Throwable ignore) {} w = null; }
            deleteQuietly(tmp);
            return;
        } finally {
            if (w != null) { try { w.close(); } catch (Throwable ignore) {} }
        }
        if (!tmp.renameTo(meta)) {
            deleteQuietly(meta);
            if (!tmp.renameTo(meta)) deleteQuietly(tmp);
        }
    }

    private static synchronized Resume readResume(File meta) {
        if (!meta.isFile() || meta.length() > 1 << 20) return null;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(
                    new FileInputStream(meta), "UTF-8"));
            String magic = br.readLine();
            if (!META_MAGIC.equals(magic)) return null;
            String head = br.readLine();
            if (head == null) return null;
            String[] tk = head.trim().split("\\s+");
            if (tk.length < 2) return null;

            Resume st = new Resume();
            st.total  = Long.parseLong(tk[0]);
            st.chunks = Integer.parseInt(tk[1]);
            if (st.total <= 0 || st.chunks < 1 || st.chunks > 64) return null;

            String e = br.readLine();
            st.etag = e == null ? "" : e.trim();
            String u = br.readLine();
            st.url = u == null ? "" : u.trim();

            st.done = new long[st.chunks];
            for (int i = 0; i < st.chunks; i++) {
                String line = br.readLine();
                if (line == null) return null;   // 行数不够 = 元数据被截断，整体作废
                st.done[i] = Long.parseLong(line.trim());
            }
            return st;
        } catch (Throwable t) {
            return null;
        } finally {
            if (br != null) { try { br.close(); } catch (Throwable ignore) {} }
        }
    }

    // ---- 小工具 ----

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static void promote(File part, File target) throws IOException {
        if (target.exists() && !target.delete()) {
            throw new IOException("无法替换目标文件 " + target);
        }
        if (!part.renameTo(target)) {
            throw new IOException("无法重命名 " + part + " -> " + target);
        }
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) {
            CNLog.w(TAG, "无法删除 " + f);
        }
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

    /** 从 {@code Content-Range: bytes 100-199/12345} 解析出起始偏移。 */
    private static long rangeStart(String v) {
        if (v == null) return -1L;
        String s = v.trim().toLowerCase(Locale.US);
        if (!s.startsWith("bytes ")) return -1L;
        int dash = s.indexOf('-', 6);
        if (dash < 0) return -1L;
        return parseLong(s.substring(6, dash), -1L);
    }

    /** 从 {@code Content-Range: bytes 0-0/12345} 解析出总长度。 */
    private static long totalFromContentRange(String v) {
        if (v == null) return -1L;
        String s = v.trim().toLowerCase(Locale.US);
        int slash = s.indexOf('/');
        if (slash < 0) return -1L;
        return parseLong(s.substring(slash + 1), -1L);
    }
}
