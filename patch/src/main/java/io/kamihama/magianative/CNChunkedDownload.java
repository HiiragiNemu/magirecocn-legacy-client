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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 多连接 Range 下载器。
 *
 * <p>存在分块清单时，清单块是完整性事务的最小单位：每块先下载到独立临时文件，
 * MD5 通过后才写入主临时文件并持久化“已验证”状态。哈希块数量与网络并发度完全
 * 分离；例如 cn_base_03 有 85 个 16MiB 校验块，但同时只运行有限数量的连接。
 * 坏块不会污染主文件，也不会让已验证的其他块倒退或整包重下。
 *
 * <p>没有清单时保留传统的分段断点续传，但禁止跨镜像拼装未经内容认证的文件。
 */
public final class CNChunkedDownload {
    private static final String TAG = "MagiaCNChunk";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    /**
     * 大包的校验块可以很多，网络连接数不能随块数膨胀。
     *
     * <p>这两个是<b>正常模式</b>的上限。实际用的一律走 {@link #maxWorkers()} /
     * {@link #maxSegments()}——单线程可靠模式下它们是 1（见 {@link CNDownloadMode}）。
     * 直接引用常量会漏掉那个模式，而漏掉的表现是「明明选了单线程，还是开了 8 条
     * 连接」，且只有看日志才发现得了。
     */
    private static final int MAX_NETWORK_WORKERS = 8;
    private static final int MAX_BYTE_SEGMENTS = 16;

    /** 当前允许的分片工作线程数。 */
    private static int maxWorkers() { return CNDownloadMode.cap(MAX_NETWORK_WORKERS); }
    /** 当前允许的字节分段数。 */
    private static int maxSegments() { return CNDownloadMode.cap(MAX_BYTE_SEGMENTS); }
    private static final int MAX_MIRROR_CANDIDATES = 6;
    private static final long META_SAVE_INTERVAL_NS = 2_000_000_000L;

    private static final String META_HASH = "CNVTX5-HASH";
    private static final String META_BYTE = "CNVTX5-BYTE";

    private CNChunkedDownload() {}

    public interface Sink {
        void onTotal(long total);
        void onProgress(long soFar, long total);
        void onSpeed(float mbps);
        boolean isCancelled();
    }

    public static final class Probe {
        public final long total;
        public final String etag;
        public final boolean rangeSupported;

        Probe(long total, String etag, boolean rangeSupported) {
            this.total = total;
            this.etag = etag == null ? "" : etag.trim();
            this.rangeSupported = rangeSupported;
        }
    }

    public static final class Result {
        public final long totalBytes;
        public final String etag;
        public final boolean chunkVerified;

        Result(long totalBytes, String etag) {
            this(totalBytes, etag, false);
        }

        Result(long totalBytes, String etag, boolean chunkVerified) {
            this.totalBytes = totalBytes;
            this.etag = etag == null ? "" : etag;
            this.chunkVerified = chunkVerified;
        }
    }

    public static final class ChunkHashes {
        public final long chunkSize;
        public final long total;
        public final String[] chunks;
        public final int count;

        public ChunkHashes(long chunkSize, long total, List<String> chunks) {
            this.chunkSize = chunkSize;
            this.total = total;
            this.chunks = chunks == null ? new String[0] : chunks.toArray(new String[0]);
            this.count = this.chunks.length;
        }

        public String hashFor(long start, long end) {
            if (count == 0 || chunkSize <= 0 || start < 0 || end <= start) return null;
            int b0 = (int) (start / chunkSize);
            int b1 = (int) ((end - 1) / chunkSize);
            if (b0 < 0 || b1 >= count || b0 != b1) return null;
            return chunks[b0];
        }
    }

    private static HttpURLConnection open(String url, boolean direct) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        CNUserAgent.apply(c);
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    /**
     * 先 HEAD，再用 bytes=0-0 实测 Range。HEAD 有长度但没 Accept-Ranges 时也不能
     * 直接判死：不少 CDN 的 HEAD 不声明该头，而 GET Range 实际可用。
     */
    public static Probe probe(String url, boolean direct) {
        long headTotal = -1L;
        String headEtag = "";
        HttpURLConnection c = null;
        try {
            c = open(url, direct);
            c.setRequestMethod("HEAD");
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                headTotal = parseLong(c.getHeaderField("Content-Length"), -1L);
                headEtag = trim(c.getHeaderField("ETag"));
                String ar = c.getHeaderField("Accept-Ranges");
                if (headTotal > 0 && ar != null
                        && ar.toLowerCase(Locale.US).contains("bytes")) {
                    return new Probe(headTotal, headEtag, true);
                }
            }
        } catch (Throwable ignore) {
        } finally {
            disconnect(c);
        }

        c = null;
        try {
            c = open(url, direct);
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=0-0");
            int code = c.getResponseCode();
            String etag = trim(c.getHeaderField("ETag"));
            if (code == 206) {
                RangeInfo r = parseContentRange(c.getHeaderField("Content-Range"));
                if (r != null && r.start == 0L && r.end == 0L && r.total > 0L) {
                    return new Probe(r.total, etag.length() > 0 ? etag : headEtag, true);
                }
            } else if (code >= 200 && code < 300) {
                long total = parseLong(c.getHeaderField("Content-Length"), headTotal);
                if (total > 0) return new Probe(total, etag, false);
            }
        } catch (Throwable ignore) {
        } finally {
            disconnect(c);
        }
        return new Probe(headTotal, headEtag, false);
    }

    public static File partFileFor(File target) {
        return new File(target.getPath() + ".cpart");
    }

    public static File metaFileFor(File target) {
        return new File(target.getPath() + ".cpart.prog");
    }

    public static Result download(String url, File target, int requestedChunks,
                                  boolean direct, Probe probe, Sink sink) throws IOException {
        return download(url, target, requestedChunks, direct, probe, sink, null);
    }

    public static Result download(String url, File target, int requestedChunks,
                                  boolean direct, Probe probe, Sink sink,
                                  CNMirrors.Mirror mirror) throws IOException {
        return download(url, target, requestedChunks, direct, probe, sink, mirror, null);
    }

    public static Result download(String url, File target, int requestedChunks,
                                  boolean direct, Probe probe, Sink sink,
                                  CNMirrors.Mirror mirror, String remoteName) throws IOException {
        return download(url, target, requestedChunks, direct, probe, sink,
                mirror, remoteName, false);
    }

    public static Result download(String url, File target, int requestedChunks,
                                  boolean direct, Probe probe, Sink sink,
                                  CNMirrors.Mirror mirror, String remoteName,
                                  boolean verifyZip) throws IOException {
        return download(url, target, requestedChunks, direct, probe, sink,
                mirror, remoteName, verifyZip, null);
    }

    public static Result download(String url, File target, int requestedChunks,
                                  boolean direct, Probe probe, Sink sink,
                                  CNMirrors.Mirror mirror, String remoteName,
                                  boolean verifyZip, ChunkHashes hashes) throws IOException {
        try {
            CNDownloadUiAssist.ensureInstalled();
        } catch (Throwable t) {
            try { CNLog.w(TAG, "下载界面辅助控件初始化失败（不影响下载）: " + t); }
            catch (Throwable ignore) {}
        }

        if (url == null || target == null || probe == null) {
            throw new IOException("下载参数为空");
        }
        if (probe.total <= 0) throw new IOException("未知的文件长度");
        ensureParent(target);

        ChunkHashes valid = validateManifest(hashes, probe.total);
        if (hashes != null && valid == null) {
            // 已经拿到清单却与本次文件身份不符，说明镜像/清单传播不同步。此时
            // 绝不能悄悄关掉完整性校验继续拼装；交给上层换线并重新拉清单。
            throw new IOException("分块清单与文件不一致 file=" + target.getName()
                    + " manifestTotal=" + hashes.total + " probeTotal=" + probe.total);
        }
        if (valid != null) {
            return downloadVerified(url, target, requestedChunks, direct, probe,
                    sink, remoteName, verifyZip, valid);
        }
        return downloadByteSegments(url, target, requestedChunks, direct, probe,
                sink, verifyZip);
    }

    // -----------------------------------------------------------------
    // 有清单：块级事务下载
    // -----------------------------------------------------------------

    private static Result downloadVerified(String url, File target, int requestedWorkers,
                                           boolean direct, Probe probe, Sink sink,
                                           String remoteName, boolean verifyZip,
                                           ChunkHashes hashes) throws IOException {
        final File part = partFileFor(target);
        final File meta = metaFileFor(target);
        cleanBlockTemps(part);

        final String manifestId = manifestFingerprint(hashes);
        HashResume resume = readHashResume(meta);
        AtomicIntegerArray verified = new AtomicIntegerArray(hashes.count);
        // 已验证块的文件身份只由完整 manifest 指纹决定。ETag 是 CDN/缓存节点的
        // 响应元数据，同一内容在同一 URL 上也可能因节点、回源或重签而变化；继续
        // 把它当身份会让 03 已验证的几十个块无故归零。块内容已由 manifest MD5
        // 认证，因此 URL/ETag 变化不影响这些 verified 位的可信度。
        boolean accepted = resume != null
                && resume.total == probe.total
                && resume.blockSize == hashes.chunkSize
                && resume.count == hashes.count
                && manifestId.equals(resume.manifestId)
                && part.isFile() && part.length() == probe.total;
        if (accepted) {
            for (int i = 0; i < hashes.count; i++) verified.set(i, resume.verified[i]);
            CNLog.i(TAG, "verified-resume-accept file=" + target.getName()
                    + " blocks=" + countSet(verified) + "/" + hashes.count);
        } else {
            if (resume != null) {
                CNLog.w(TAG, "verified-resume-reject file=" + target.getName()
                        + "（文件身份/清单/布局已变化）");
            }
            deleteQuietly(meta);
            deleteQuietly(part);
        }

        preallocate(part, probe.total);
        final Object commitLock = new Object();
        final AtomicLong committed = new AtomicLong(verifiedBytes(verified, hashes));
        final AtomicLong networkBytes = new AtomicLong(0L);
        final AtomicLong lastMoveNs = new AtomicLong(System.nanoTime());
        final AtomicReference<IOException> firstErr = new AtomicReference<IOException>();
        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicBoolean open = new AtomicBoolean(true);
        final int[] pending = pendingBlocks(verified);

        if (sink != null) {
            sink.onTotal(probe.total);
            sink.onProgress(committed.get(), probe.total);
        }

        if (pending.length > 0) {
            String[] candidates = candidateUrls(url, remoteName);
            int workers = clampWorkers(requestedWorkers, pending.length);
            CNLog.i(TAG, "事务分块下载 file=" + target.getName()
                    + " blocks=" + hashes.count + " pending=" + pending.length
                    + " workers=" + workers + " mirrors=" + candidates.length);

            HashContext ctx = new HashContext();
            ctx.target = target;
            ctx.part = part;
            ctx.meta = meta;
            ctx.total = probe.total;
            ctx.etag = probe.etag;
            ctx.primaryUrl = url;
            ctx.direct = direct;
            ctx.hashes = hashes;
            ctx.manifestId = manifestId;
            ctx.verified = verified;
            ctx.pending = pending;
            ctx.next = new AtomicInteger(0);
            ctx.committed = committed;
            ctx.networkBytes = networkBytes;
            ctx.lastMoveNs = lastMoveNs;
            ctx.firstErr = firstErr;
            ctx.abort = abort;
            ctx.open = open;
            ctx.commitLock = commitLock;
            ctx.sink = sink;
            ctx.candidates = candidates;

            ExecutorService pool = Executors.newFixedThreadPool(workers, new DownloadThreadFactory());
            CountDownLatch latch = new CountDownLatch(workers);
            for (int i = 0; i < workers; i++) {
                pool.submit(new HashWorker(ctx, latch));
            }
            monitor(latch, pool, abort, open, firstErr, lastMoveNs, networkBytes,
                    committed, probe.total, sink);
            IOException err = firstErr.get();
            if (err != null) throw err;
        }

        if (countSet(verified) != hashes.count || committed.get() != probe.total) {
            throw new IOException("分块下载未完成: verified=" + countSet(verified)
                    + "/" + hashes.count + " bytes=" + committed.get() + "/" + probe.total);
        }
        // F-027：resume 恢复的 verified 位只能证明「用的是哪份清单」，不能证明
        // .cpart 中对应字节自上次验证后未变（存储损坏/错误恢复/调试工具/另一路径
        // 误写）。resume 被接受时对 .cpart 做一次全量分块重验——兜住 resume 块的
        // 内容漂移。全新下载（无 resume）各块已按清单校验，不额外重读。
        // ⚠ 重验对象是 .cpart（此刻所有块都已提交进它、拼装已完成）；target 要到
        //   finish() 才由 .cpart 落成，此刻验 target 只会命中「文件不存在」而误报
        //   （2026-08-19 修：resume 恢复路径因此恒红，test [2] 的恢复场景实锤）。
        if (accepted) {
            try {
                if (!CNArchiveValidate.verifyChunks(part, hashes)) {
                    throw new IOException("分块身份校验失败（resume 后全量重验）: "
                            + target.getName());
                }
            } catch (IOException e) {
                throw e;
            } catch (Throwable t) {
                throw new IOException("分块身份校验异常: " + target.getName(), t);
            }
            CNLog.i(TAG, "resume 后全量分块重验通过 file=" + target.getName());
        }
        finish(part, meta, target, verifyZip, sink, probe.total);
        CNLog.i(TAG, "事务分块下载完成 file=" + target.getName()
                + " bytes=" + probe.total + " blocks=" + hashes.count);
        return new Result(probe.total, probe.etag, true);
    }

    private static final class HashContext {
        File target;
        File part;
        File meta;
        long total;
        String etag;
        String primaryUrl;
        boolean direct;
        ChunkHashes hashes;
        String manifestId;
        AtomicIntegerArray verified;
        int[] pending;
        AtomicInteger next;
        AtomicLong committed;
        AtomicLong networkBytes;
        AtomicLong lastMoveNs;
        AtomicReference<IOException> firstErr;
        AtomicBoolean abort;
        AtomicBoolean open;
        Object commitLock;
        Sink sink;
        String[] candidates;
    }

    private static final class HashWorker implements Runnable {
        private final HashContext ctx;
        private final CountDownLatch latch;

        HashWorker(HashContext ctx, CountDownLatch latch) {
            this.ctx = ctx;
            this.latch = latch;
        }

        @Override public void run() {
            try {
                while (!ctx.abort.get() && ctx.open.get()) {
                    int p = ctx.next.getAndIncrement();
                    if (p >= ctx.pending.length) break;
                    int block = ctx.pending[p];
                    downloadVerifiedBlock(ctx, block);
                }
            } catch (IOException e) {
                ctx.firstErr.compareAndSet(null, e);
                ctx.abort.set(true);
            } catch (Throwable t) {
                ctx.firstErr.compareAndSet(null,
                        new IOException("分块工作线程异常: " + t, t));
                ctx.abort.set(true);
            } finally {
                latch.countDown();
            }
        }
    }

    private static void downloadVerifiedBlock(HashContext ctx, int block) throws IOException {
        long start = block * ctx.hashes.chunkSize;
        long end = Math.min(start + ctx.hashes.chunkSize, ctx.total) - 1L;
        String expected = ctx.hashes.hashFor(start, end + 1L);
        if (expected == null) throw new IOException("清单缺少块 " + block + " 的指纹");

        IOException last = null;
        for (int i = 0; i < ctx.candidates.length && !ctx.abort.get(); i++) {
            String u = ctx.candidates[i];
            File temp = blockTemp(ctx.part, block);
            deleteQuietly(temp);
            try {
                String got = fetchRangeToTemp(u, ctx.direct,
                        start, end, ctx.total, temp, ctx.abort, ctx.open, ctx.sink,
                        ctx.networkBytes, ctx.lastMoveNs);
                if (!expected.equalsIgnoreCase(got)) {
                    throw new IOException("分块校验失败 block=" + block + " offset=" + start
                            + " 期望=" + expected + " 实得=" + got + " url=" + u);
                }
                if (ctx.abort.get() || !ctx.open.get()) throw new IOException("已中断");
                synchronized (ctx.commitLock) {
                    if (ctx.abort.get() || !ctx.open.get()) throw new IOException("已中断");
                    if (ctx.verified.get(block) == 0) {
                        commitTempBlock(temp, ctx.part, start, end - start + 1L);
                        ctx.verified.set(block, 1);
                        long now = ctx.committed.addAndGet(end - start + 1L);
                        saveHashMeta(ctx.meta, ctx.total, ctx.hashes.chunkSize,
                                ctx.hashes.count, ctx.manifestId, ctx.etag,
                                ctx.primaryUrl, ctx.verified);
                        if (ctx.sink != null) ctx.sink.onProgress(now, ctx.total);
                    }
                }
                deleteQuietly(temp);
                return;
            } catch (IOException e) {
                last = e;
                deleteQuietly(temp);
                CNLog.w(TAG, "块重试 file=" + ctx.target.getName() + " block=" + block
                        + " candidate=" + (i + 1) + "/" + ctx.candidates.length
                        + " reason=" + e.getMessage());
            }
        }
        throw last == null ? new IOException("块 " + block + " 下载失败") : last;
    }

    private static String fetchRangeToTemp(String url, boolean direct,
                                           long start, long end, long total,
                                           File temp, AtomicBoolean abort, AtomicBoolean open,
                                           Sink sink, AtomicLong networkBytes,
                                           AtomicLong lastMoveNs) throws IOException {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        CNDownloadConcurrency.Lease lease = null;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IOException("MD5 不可用", e);
        }
        try {
            lease = CNDownloadConcurrency.acquire(
                    "verified-range:" + temp.getName(), lastMoveNs);
            c = open(url, direct);
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=" + start + "-" + end);
            // 事务块始终从块头完整请求，并由 manifest MD5 验证，不是字节级续传。
            // 不发送 If-Range：CDN 的 ETag 变化会把本来合法的 Range 降成 HTTP 200，
            // 造成频繁“分块失败”；内容若真的变了，MD5 会在提交前将其拒绝。
            int code = c.getResponseCode();
            if (code != 206) {
                throw new IOException("Range 请求期望 206，实得 HTTP " + code + " url=" + url);
            }
            RangeInfo r = parseContentRange(c.getHeaderField("Content-Range"));
            if (r == null || r.start != start || r.end != end || r.total != total) {
                throw new IOException("Content-Range 不符: " + c.getHeaderField("Content-Range")
                        + " 期望 bytes " + start + "-" + end + "/" + total);
            }
            long expected = end - start + 1L;
            long contentLength = parseLong(c.getHeaderField("Content-Length"), -1L);
            if (contentLength >= 0 && contentLength != expected) {
                throw new IOException("Content-Length 不符: " + contentLength + " != " + expected);
            }

            in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            out = new FileOutputStream(temp, false);
            byte[] buf = new byte[1 << 16];
            long written = 0L;
            while (true) {
                if (abort.get() || !open.get()) throw new IOException("已中断");
                if (sink != null && sink.isCancelled()) throw new IOException("已取消");
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                if (written + n > expected) {
                    throw new IOException("Range 响应越界: " + (written + n) + " > " + expected);
                }
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                written += n;
                networkBytes.addAndGet(n);
                lastMoveNs.set(System.nanoTime());
            }
            out.flush();
            if (written != expected) {
                throw new IOException("Range 短读: " + written + " / " + expected);
            }
            return hex(md.digest());
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            disconnect(c);
            if (lease != null) lease.close();
        }
    }

    private static void commitTempBlock(File temp, File part, long start, long length)
            throws IOException {
        if (!temp.isFile() || temp.length() != length) {
            throw new IOException("待提交块大小异常: " + temp.length() + " / " + length);
        }
        FileInputStream in = null;
        RandomAccessFile raf = null;
        try {
            in = new FileInputStream(temp);
            raf = new RandomAccessFile(part, "rw");
            raf.seek(start);
            byte[] buf = new byte[1 << 16];
            long copied = 0L;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                raf.write(buf, 0, n);
                copied += n;
            }
            if (copied != length) throw new IOException("块提交短写: " + copied + " / " + length);
            // 元数据只有在主文件数据落稳后才会标记该块已验证。
            raf.getFD().sync();
        } finally {
            closeQuietly(in);
            closeQuietly(raf);
        }
    }

    // -----------------------------------------------------------------
    // 无清单：传统分段断点续传（单镜像）
    // -----------------------------------------------------------------

    private static Result downloadByteSegments(String url, File target, int requestedSegments,
                                               boolean direct, Probe probe, Sink sink,
                                               boolean verifyZip) throws IOException {
        final File part = partFileFor(target);
        final File meta = metaFileFor(target);
        ByteResume resume = readByteResume(meta);
        int segments = Math.max(1, Math.min(maxSegments(), requestedSegments));
        if (probe.total < segments) segments = (int) Math.max(1L, probe.total);
        long segmentSize = ceilDiv(probe.total, segments);
        // 无 manifest 时没有内容指纹，断点只能在**同一完整 URL**上复用。
        // 旧逻辑只在同 URL 比 ETag、换 URL 时无条件接受，等于允许把不同镜像的
        // 未认证字节继续拼进同一文件，正是 03 历史 corrupt-zip 的入口。
        boolean accepted = resume != null
                && resume.total == probe.total
                && resume.segments >= 1 && resume.segments <= MAX_BYTE_SEGMENTS
                && resume.segmentSize > 0
                && part.isFile() && part.length() == probe.total
                && url.equals(resume.url)
                && etagCompatible(resume.url, resume.etag, url, probe.etag)
                && byteResumeBoundsValid(resume);
        long[] resumed = null;
        if (accepted) {
            // ⚠ 这里刻意用 resume.segments 覆盖上面按当前模式算出的 segments，
            // 而且 accepted 的判据用的是 MAX_BYTE_SEGMENTS（绝对上限）而不是
            // maxSegments()（当前模式上限）。两处都不是笔误：
            //
            // 分段数决定的是**磁盘上那个半成品的字节布局**。中途改了，已下好的
            // 区间记录就对不上，等于把断点作废、整包重下——而玩家恰恰是在「下到
            // 一半失败」的当口切到单线程的，那个时刻把进度清零是最不该发生的事。
            //
            // 并发降到 1 靠的是下面的 workers = min(maxWorkers(), …)：还是这
            // 16 段布局，但同时只有一条连接在推进。这才是「单线程」该有的样子。
            segments = resume.segments;
            segmentSize = resume.segmentSize;
            resumed = resume.done;
            CNLog.i(TAG, "byte-resume-accept file=" + target.getName()
                    + " bytes=" + sum(resumed) + "/" + probe.total);
        } else {
            deleteQuietly(meta);
            deleteQuietly(part);
        }
        preallocate(part, probe.total);

        final AtomicLongArray done = new AtomicLongArray(segments);
        final long[] starts = new long[segments];
        final long[] ends = new long[segments];
        for (int i = 0; i < segments; i++) {
            starts[i] = i * segmentSize;
            ends[i] = Math.min(starts[i] + segmentSize, probe.total) - 1L;
            if (resumed != null) done.set(i, resumed[i]);
        }
        final AtomicLong totalDone = new AtomicLong(sum(done));
        final AtomicLong networkBytes = new AtomicLong(0L);
        final AtomicLong lastMoveNs = new AtomicLong(System.nanoTime());
        final AtomicReference<IOException> firstErr = new AtomicReference<IOException>();
        final AtomicBoolean abort = new AtomicBoolean(false);
        final AtomicBoolean open = new AtomicBoolean(true);
        final AtomicBoolean rangeIgnored = new AtomicBoolean(false);

        if (sink != null) {
            sink.onTotal(probe.total);
            sink.onProgress(totalDone.get(), probe.total);
        }

        int incomplete = 0;
        for (int i = 0; i < segments; i++) {
            if (done.get(i) < ends[i] - starts[i] + 1L) incomplete++;
        }
        if (incomplete > 0) {
            ByteContext ctx = new ByteContext();
            ctx.url = url;
            ctx.part = part;
            ctx.meta = meta;
            ctx.total = probe.total;
            ctx.etag = probe.etag;
            ctx.direct = direct;
            ctx.starts = starts;
            ctx.ends = ends;
            ctx.done = done;
            ctx.totalDone = totalDone;
            ctx.networkBytes = networkBytes;
            ctx.lastMoveNs = lastMoveNs;
            ctx.firstErr = firstErr;
            ctx.abort = abort;
            ctx.open = open;
            ctx.rangeIgnored = rangeIgnored;
            ctx.sink = sink;
            ctx.segmentSize = segmentSize;
            ctx.next = new AtomicInteger(0);

            int workers = Math.min(maxWorkers(), Math.min(segments, incomplete));
            ExecutorService pool = Executors.newFixedThreadPool(workers, new DownloadThreadFactory());
            CountDownLatch latch = new CountDownLatch(workers);
            for (int i = 0; i < workers; i++) pool.submit(new ByteWorker(ctx, latch));
            monitor(latch, pool, abort, open, firstErr, lastMoveNs, networkBytes,
                    totalDone, probe.total, sink);
            IOException err = firstErr.get();
            if (err != null) {
                if (rangeIgnored.get()) {
                    deleteQuietly(meta);
                    deleteQuietly(part);
                }
                throw err;
            }
        }

        if (sum(done) != probe.total) {
            throw new IOException("下载不完整: " + sum(done) + " / " + probe.total);
        }
        finish(part, meta, target, verifyZip, sink, probe.total);
        CNLog.i(TAG, "分段下载完成 file=" + target.getName() + " bytes=" + probe.total
                + " segments=" + segments);
        return new Result(probe.total, probe.etag, false);
    }

    private static final class ByteContext {
        String url;
        File part;
        File meta;
        long total;
        String etag;
        boolean direct;
        long[] starts;
        long[] ends;
        AtomicLongArray done;
        AtomicLong totalDone;
        AtomicLong networkBytes;
        AtomicLong lastMoveNs;
        AtomicReference<IOException> firstErr;
        AtomicBoolean abort;
        AtomicBoolean open;
        AtomicBoolean rangeIgnored;
        Sink sink;
        long segmentSize;
        AtomicInteger next;
    }

    private static final class ByteWorker implements Runnable {
        private final ByteContext ctx;
        private final CountDownLatch latch;

        ByteWorker(ByteContext ctx, CountDownLatch latch) {
            this.ctx = ctx;
            this.latch = latch;
        }

        @Override public void run() {
            try {
                while (!ctx.abort.get() && ctx.open.get()) {
                    int i = ctx.next.getAndIncrement();
                    if (i >= ctx.starts.length) break;
                    long len = ctx.ends[i] - ctx.starts[i] + 1L;
                    if (ctx.done.get(i) >= len) continue;
                    downloadByteSegment(ctx, i);
                }
            } catch (IOException e) {
                ctx.firstErr.compareAndSet(null, e);
                ctx.abort.set(true);
            } catch (Throwable t) {
                ctx.firstErr.compareAndSet(null, new IOException("分段工作线程异常: " + t, t));
                ctx.abort.set(true);
            } finally {
                latch.countDown();
            }
        }
    }

    private static void downloadByteSegment(ByteContext ctx, int index) throws IOException {
        long segmentStart = ctx.starts[index];
        long segmentEnd = ctx.ends[index];
        long start = segmentStart + ctx.done.get(index);
        HttpURLConnection c = null;
        InputStream in = null;
        RandomAccessFile raf = null;
        CNDownloadConcurrency.Lease lease = null;
        long lastSave = System.nanoTime();
        try {
            lease = CNDownloadConcurrency.acquire(
                    "byte-range:" + ctx.part.getName(), ctx.lastMoveNs);
            c = open(ctx.url, ctx.direct);
            c.setRequestMethod("GET");
            c.setRequestProperty("Range", "bytes=" + start + "-" + segmentEnd);
            if (ctx.etag != null && ctx.etag.length() > 0) c.setRequestProperty("If-Range", ctx.etag);
            int code = c.getResponseCode();
            if (code != 206) {
                if (code == 200) ctx.rangeIgnored.set(true);
                throw new IOException("分段 " + index + " 期望 206，实得 HTTP " + code);
            }
            RangeInfo r = parseContentRange(c.getHeaderField("Content-Range"));
            // 无清单兼容路径仍严格校验响应头身份；只有在头部准确声明请求区间、
            // 中间设备却额外多写正文时，下面的读取循环才会安全裁掉尾部。
            if (r == null) {
                throw new IOException("分段 " + index + " Content-Range 格式非法: "
                        + c.getHeaderField("Content-Range"));
            }
            if (r.start != start) {
                throw new IOException("分段 " + index + " Content-Range 起点不符: "
                        + r.start + " != " + start);
            }
            if (r.total != ctx.total) {
                throw new IOException("分段 " + index + " Content-Range 总长不符: "
                        + r.total + " != " + ctx.total);
            }
            if (r.end != segmentEnd) {
                throw new IOException("分段 " + index + " Content-Range 终点不符: "
                        + r.end + " != " + segmentEnd);
            }
            long expected = segmentEnd - start + 1L;
            long cl = parseLong(c.getHeaderField("Content-Length"), -1L);
            if (cl >= 0 && cl != expected) {
                throw new IOException("分段 Content-Length 不符: " + cl + " != " + expected);
            }

            in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            raf = new RandomAccessFile(ctx.part, "rw");
            raf.seek(start);
            byte[] buf = new byte[1 << 16];
            long received = 0L;
            while (true) {
                if (ctx.abort.get() || !ctx.open.get()) throw new IOException("已中断");
                if (ctx.sink != null && ctx.sink.isCancelled()) throw new IOException("已取消");
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                // 无清单兼容路径：响应头已经严格回验为请求区间时，若中间设备仍在
                // 正文尾部多发字节，只接收声明区间内的部分并在下一轮退出。绝不能
                // 把越界正文写进相邻分段；有清单的事务块路径仍对任何越界严格拒绝。
                if (received + n > expected) {
                    n = (int) (expected - received);
                    if (n <= 0) break;
                }
                raf.write(buf, 0, n);
                received += n;
                ctx.done.addAndGet(index, n);
                long totalNow = ctx.totalDone.addAndGet(n);
                ctx.networkBytes.addAndGet(n);
                long now = System.nanoTime();
                ctx.lastMoveNs.set(now);
                if (ctx.sink != null) ctx.sink.onProgress(totalNow, ctx.total);
                if (now - lastSave >= META_SAVE_INTERVAL_NS) {
                    // F-057：meta 声称「这些字节 done」之前，先把数据同步落盘——
                    // 否则掉电重排可能 meta 存活而页缓存丢失，resume 从更靠后的
                    // offset 继续，跳过未落盘区间。每 2s 一次 fsync，代价可控。
                    try { raf.getFD().sync(); } catch (IOException e) {
                        throw new IOException("分段数据同步失败: " + index, e);
                    }
                    saveByteMeta(ctx.meta, ctx.total, ctx.starts.length, ctx.segmentSize,
                            ctx.etag, ctx.url, ctx.done);
                    lastSave = now;
                }
            }
            if (received != expected) {
                throw new IOException("分段 " + index + " 短读: " + received + " / " + expected);
            }
            raf.getFD().sync();
        } finally {
            if (ctx.open.get()) {
                // F-057：finally 里的断点保存同样先同步数据（取消/失败路径要把
                // 已写字节做成持久化检查点）。raf 可能因早退为 null——null 说明
                // 还没写过任何字节，无数据可丢，跳过同步即可。
                if (raf != null) {
                    try { raf.getFD().sync(); } catch (Throwable ignore) {}
                }
                saveByteMeta(ctx.meta, ctx.total, ctx.starts.length, ctx.segmentSize,
                        ctx.etag, ctx.url, ctx.done);
            }
            closeQuietly(raf);
            closeQuietly(in);
            disconnect(c);
            if (lease != null) lease.close();
        }
    }

    // -----------------------------------------------------------------
    // 监控、状态与工具
    // -----------------------------------------------------------------

    private static void monitor(CountDownLatch latch, ExecutorService pool,
                                AtomicBoolean abort, AtomicBoolean open,
                                AtomicReference<IOException> firstErr,
                                AtomicLong lastMoveNs, AtomicLong networkBytes,
                                AtomicLong usefulBytes, long total, Sink sink) {
        // Display speed is based on monotonic useful progress (verified bytes for manifest
        // blocks, persisted bytes for byte segments), never on raw wire bytes. Failed block
        // attempts and mirror retries therefore cannot be counted twice.
        long lastSpeedNs = System.nanoTime();
        long lastSpeedBytes = usefulBytes.get();
        double smoothedMbps = 0.0d;
        long lowWindowNs = System.nanoTime();
        long lowWindowBytes = networkBytes.get();
        long stallNs = TimeUnit.SECONDS.toNanos(Math.max(1, CNMirrors.stallSeconds()));
        long minBps = Math.max(0L, (long) CNMirrors.minSpeedKbps()) * 1000L / 8L;
        try {
            while (!latch.await(1L, TimeUnit.SECONDS)) {
                long now = System.nanoTime();
                if (sink != null && sink.isCancelled()) {
                    firstErr.compareAndSet(null, new IOException("已取消"));
                    abort.set(true);
                    break;
                }
                if (firstErr.get() != null) {
                    abort.set(true);
                    break;
                }
                if (now - lastMoveNs.get() > stallNs) {
                    firstErr.compareAndSet(null, new IOException("线路停滞："
                            + CNMirrors.stallSeconds() + " 秒内没有数据"));
                    abort.set(true);
                    break;
                }
                long speedDt = now - lastSpeedNs;
                if (speedDt >= TimeUnit.SECONDS.toNanos(3L)) {
                    long currentUseful = usefulBytes.get();
                    long moved = Math.max(0L, currentUseful - lastSpeedBytes);
                    double instant = (moved * 1.0E9d / speedDt) / 1_000_000.0d;
                    smoothedMbps = smoothedMbps <= 0.0d
                            ? instant : smoothedMbps * 0.70d + instant * 0.30d;
                    if (sink != null) sink.onSpeed((float) smoothedMbps);
                    lastSpeedNs = now;
                    lastSpeedBytes = currentUseful;
                }
                long lowDt = now - lowWindowNs;
                if (minBps > 0 && lowDt >= TimeUnit.SECONDS.toNanos(10L)) {
                    long moved = networkBytes.get() - lowWindowBytes;
                    // 其它 ZIP 占用全局连接时，本文件的 worker 会在公平信号量排队。
                    // 排队不是线路低速，不能把它误判成镜像失败。
                    if (moved == 0L && CNDownloadConcurrency.hasQueuedWaiters()) {
                        lowWindowNs = now;
                        lowWindowBytes = networkBytes.get();
                        continue;
                    }
                    long bps = (long) (moved / (lowDt / 1.0E9d));
                    if (bps < minBps) {
                        firstErr.compareAndSet(null, new IOException("线路过慢："
                                + (bps * 8L / 1000L) + " kbps < "
                                + CNMirrors.minSpeedKbps() + " kbps"));
                        abort.set(true);
                        break;
                    }
                    lowWindowNs = now;
                    lowWindowBytes = networkBytes.get();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            firstErr.compareAndSet(null, new IOException("已取消"));
            abort.set(true);
        } finally {
            pool.shutdownNow();
            try { pool.awaitTermination(5L, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            // 读超时后才醒来的线程必须看到封口，禁止再提交块或覆盖元数据。
            open.set(false);
            if (sink != null) sink.onSpeed(0f);
        }
    }

    private static ChunkHashes validateManifest(ChunkHashes h, long total) {
        if (h == null || h.chunkSize <= 0 || h.total != total || h.count <= 0) return null;
        long expectedCount = ceilDiv(total, h.chunkSize);
        if (expectedCount != h.count || expectedCount > 100000L) return null;
        for (int i = 0; i < h.count; i++) {
            String s = h.chunks[i];
            if (s == null || s.trim().length() != 32) return null;
            s = s.trim();
            for (int j = 0; j < s.length(); j++) {
                char c = Character.toLowerCase(s.charAt(j));
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return null;
            }
        }
        return h;
    }

    private static String manifestFingerprint(ChunkHashes h) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            updateUtf8(md, String.valueOf(h.total));
            updateUtf8(md, ":");
            updateUtf8(md, String.valueOf(h.chunkSize));
            for (int i = 0; i < h.count; i++) {
                updateUtf8(md, ":");
                updateUtf8(md, h.chunks[i].toLowerCase(Locale.US));
            }
            return hex(md.digest());
        } catch (Exception e) {
            throw new IOException("无法计算清单身份", e);
        }
    }

    private static void updateUtf8(MessageDigest md, String s) throws Exception {
        md.update(s.getBytes("UTF-8"));
    }

    private static int clampWorkers(int requested, int pending) {
        int n = requested < 1 ? 1 : requested;
        n = Math.min(maxWorkers(), n);
        return Math.max(1, Math.min(n, pending));
    }

    private static String[] candidateUrls(String primary, String remoteName) {
        ArrayList<String> out = new ArrayList<String>();
        addUnique(out, primary);
        if (remoteName != null && remoteName.length() > 0) {
            try {
                List<CNMirrors.Mirror> healthy = CNMirrors.healthy();
                for (int i = 0; healthy != null && i < healthy.size()
                        && out.size() < MAX_MIRROR_CANDIDATES; i++) {
                    CNMirrors.Mirror m = healthy.get(i);
                    if (m != null) addUnique(out, m.urlFor(remoteName));
                }
            } catch (Throwable t) {
                CNLog.w(TAG, "读取备用镜像失败，块重试只用主线路: " + t);
            }
        }
        return out.toArray(new String[0]);
    }

    private static void addUnique(ArrayList<String> out, String value) {
        if (value == null || value.length() == 0) return;
        for (int i = 0; i < out.size(); i++) if (value.equals(out.get(i))) return;
        out.add(value);
    }

    private static int[] pendingBlocks(AtomicIntegerArray verified) {
        int count = 0;
        for (int i = 0; i < verified.length(); i++) if (verified.get(i) == 0) count++;
        int[] out = new int[count];
        int p = 0;
        for (int i = 0; i < verified.length(); i++) if (verified.get(i) == 0) out[p++] = i;
        return out;
    }

    private static long verifiedBytes(AtomicIntegerArray verified, ChunkHashes h) {
        long n = 0L;
        for (int i = 0; i < verified.length(); i++) {
            if (verified.get(i) != 0) {
                long start = i * h.chunkSize;
                n += Math.min(h.chunkSize, h.total - start);
            }
        }
        return n;
    }

    private static int countSet(AtomicIntegerArray a) {
        int n = 0;
        for (int i = 0; i < a.length(); i++) if (a.get(i) != 0) n++;
        return n;
    }

    private static File blockTemp(File part, int block) {
        return new File(part.getPath() + ".block." + block + "."
                + Thread.currentThread().getId());
    }

    private static void cleanBlockTemps(File part) {
        File parent = part.getParentFile();
        File[] files = parent == null ? null : parent.listFiles();
        if (files == null) return;
        String prefix = part.getName() + ".block.";
        for (int i = 0; i < files.length; i++) {
            if (files[i].getName().startsWith(prefix)) deleteQuietly(files[i]);
        }
    }

    private static void finish(File part, File meta, File target, boolean verifyZip,
                               Sink sink, long total) throws IOException {
        if (!part.isFile() || part.length() != total) {
            throw new IOException("临时文件大小异常: " + part.length() + " / " + total);
        }
        if (verifyZip && !CNArchiveValidate.isZipStructurallyValid(part)) {
            deleteQuietly(part);
            deleteQuietly(meta);
            throw new IOException("完工校验失败: zip 结构非法");
        }
        promote(part, target);
        deleteQuietly(meta);
        if (sink != null) {
            sink.onProgress(total, total);
            sink.onSpeed(0f);
        }
    }

    private static void ensureParent(File target) throws IOException {
        File p = target.getParentFile();
        if (p != null && !p.isDirectory() && !p.mkdirs() && !p.isDirectory()) {
            throw new IOException("无法创建下载目录: " + p);
        }
    }

    private static void preallocate(File f, long total) throws IOException {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            if (raf.length() != total) raf.setLength(total);
        } finally {
            closeQuietly(raf);
        }
    }

    private static boolean etagCompatible(String oldUrl, String oldEtag,
                                          String newUrl, String newEtag) {
        if (oldUrl == null || !oldUrl.equals(newUrl)) return true;
        if (oldEtag == null || oldEtag.length() == 0 || newEtag == null || newEtag.length() == 0) {
            return true;
        }
        return oldEtag.equals(newEtag);
    }

    private static synchronized void saveHashMeta(File meta, long total, long blockSize,
                                                  int count, String manifestId,
                                                  String etag, String url,
                                                  AtomicIntegerArray verified) {
        Writer w = null;
        File tmp = new File(meta.getPath() + ".tmp");
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp, false), "UTF-8");
            w.write(META_HASH); w.write('\n');
            w.write(total + " " + blockSize + " " + count + "\n");
            w.write(sanitize(manifestId)); w.write('\n');
            w.write(sanitize(etag)); w.write('\n');
            w.write(sanitize(url)); w.write('\n');
            for (int i = 0; i < count; i++) {
                w.write(verified.get(i) == 0 ? "0\n" : "1\n");
            }
            w.flush();
            closeQuietly(w); w = null;
            replace(tmp, meta);
        } catch (Throwable t) {
            CNLog.w(TAG, "保存分块断点失败: " + t);
        } finally {
            closeQuietly(w);
            deleteQuietly(tmp);
        }
    }

    private static synchronized void saveByteMeta(File meta, long total, int segments,
                                                  long segmentSize, String etag, String url,
                                                  AtomicLongArray done) {
        Writer w = null;
        File tmp = new File(meta.getPath() + ".tmp");
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp, false), "UTF-8");
            w.write(META_BYTE); w.write('\n');
            w.write(total + " " + segments + " " + segmentSize + "\n");
            w.write(sanitize(etag)); w.write('\n');
            w.write(sanitize(url)); w.write('\n');
            for (int i = 0; i < segments; i++) w.write(done.get(i) + "\n");
            w.flush();
            closeQuietly(w); w = null;
            replace(tmp, meta);
        } catch (Throwable t) {
            CNLog.w(TAG, "保存分段断点失败: " + t);
        } finally {
            closeQuietly(w);
            deleteQuietly(tmp);
        }
    }

    private static HashResume readHashResume(File meta) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(meta), "UTF-8"));
            if (!META_HASH.equals(br.readLine())) return null;
            String[] h = split(br.readLine(), 3);
            HashResume r = new HashResume();
            r.total = Long.parseLong(h[0]);
            r.blockSize = Long.parseLong(h[1]);
            r.count = Integer.parseInt(h[2]);
            if (r.total <= 0 || r.blockSize <= 0 || r.count <= 0 || r.count > 100000) return null;
            r.manifestId = line(br);
            r.etag = line(br);
            r.url = line(br);
            r.verified = new int[r.count];
            for (int i = 0; i < r.count; i++) {
                String s = br.readLine();
                if (!"0".equals(s) && !"1".equals(s)) return null;
                r.verified[i] = "1".equals(s) ? 1 : 0;
            }
            return r;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(br);
        }
    }

    private static ByteResume readByteResume(File meta) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(new FileInputStream(meta), "UTF-8"));
            if (!META_BYTE.equals(br.readLine())) return null;
            String[] h = split(br.readLine(), 3);
            ByteResume r = new ByteResume();
            r.total = Long.parseLong(h[0]);
            r.segments = Integer.parseInt(h[1]);
            r.segmentSize = Long.parseLong(h[2]);
            r.etag = line(br);
            r.url = line(br);
            if (r.total <= 0 || r.segments <= 0 || r.segments > MAX_BYTE_SEGMENTS
                    || r.segmentSize <= 0) return null;
            r.done = new long[r.segments];
            for (int i = 0; i < r.segments; i++) {
                String s = br.readLine();
                if (s == null) return null;
                r.done[i] = Long.parseLong(s.trim());
            }
            return r;
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(br);
        }
    }

    private static final class HashResume {
        long total;
        long blockSize;
        int count;
        String manifestId;
        String etag;
        String url;
        int[] verified;
    }

    private static final class ByteResume {
        long total;
        int segments;
        long segmentSize;
        String etag;
        String url;
        long[] done;
    }

    private static boolean byteResumeBoundsValid(ByteResume r) {
        if (r.done == null || r.done.length != r.segments) return false;
        for (int i = 0; i < r.segments; i++) {
            long start = i * r.segmentSize;
            long end = Math.min(start + r.segmentSize, r.total);
            long len = Math.max(0L, end - start);
            if (len <= 0 || r.done[i] < 0 || r.done[i] > len) return false;
        }
        return true;
    }

    private static String[] split(String s, int n) {
        if (s == null) throw new IllegalArgumentException("缺少元数据头");
        String[] a = s.trim().split("\\s+");
        if (a.length != n) throw new IllegalArgumentException("元数据头字段数错误");
        return a;
    }

    private static String line(BufferedReader br) throws IOException {
        String s = br.readLine();
        return s == null ? "" : s.trim();
    }

    private static void replace(File tmp, File dst) throws IOException {
        if (dst.exists() && !dst.delete()) throw new IOException("无法替换 " + dst);
        if (!tmp.renameTo(dst)) throw new IOException("无法重命名 " + tmp + " -> " + dst);
    }

    private static void promote(File part, File target) throws IOException {
        if (target.exists() && !target.delete()) throw new IOException("无法替换目标文件 " + target);
        if (!part.renameTo(target)) throw new IOException("无法重命名 " + part + " -> " + target);
    }

    private static final class RangeInfo {
        final long start;
        final long end;
        final long total;
        RangeInfo(long start, long end, long total) {
            this.start = start;
            this.end = end;
            this.total = total;
        }
    }

    private static RangeInfo parseContentRange(String value) {
        if (value == null) return null;
        String s = value.trim().toLowerCase(Locale.US);
        if (!s.startsWith("bytes ")) return null;
        int dash = s.indexOf('-', 6);
        int slash = s.indexOf('/', dash + 1);
        if (dash < 0 || slash < 0) return null;
        long start = parseLong(s.substring(6, dash), -1L);
        long end = parseLong(s.substring(dash + 1, slash), -1L);
        long total = parseLong(s.substring(slash + 1), -1L);
        if (start < 0 || end < start || total <= end) return null;
        return new RangeInfo(start, end, total);
    }

    private static long ceilDiv(long a, long b) {
        return a / b + (a % b == 0 ? 0 : 1);
    }

    private static long sum(long[] a) {
        long n = 0L;
        if (a != null) for (int i = 0; i < a.length; i++) n += a[i];
        return n;
    }

    private static long sum(AtomicLongArray a) {
        long n = 0L;
        for (int i = 0; i < a.length(); i++) n += a.get(i);
        return n;
    }

    private static long parseLong(String s, long dflt) {
        if (s == null) return dflt;
        try { return Long.parseLong(s.trim()); }
        catch (Throwable t) { return dflt; }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (int i = 0; i < b.length; i++) sb.append(String.format(Locale.US, "%02x", b[i] & 0xff));
        return sb.toString();
    }

    private static void disconnect(HttpURLConnection c) {
        if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists() && !f.delete()) CNLog.w(TAG, "无法删除 " + f);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Throwable ignore) {}
    }

    private static final class DownloadThreadFactory implements ThreadFactory {
        private final AtomicInteger ids = new AtomicInteger(0);
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "cnv-range-" + ids.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
