package io.kamihama.magianative;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/**
 * {@code RestClient.cnDownloadFileFull} 的下载实现。
 *
 * <p>原方法在 {@code RestClient} 内部用 OkHttp 单线程续传，且 URL 是写死的主线地址。
 * 现在把方法体换成对本类的一次委托，让**热更新的文件下载也走支线**：
 * 与首次安装同一套 {@link CNMirrors} 选线 + {@link CNChunkedDownload} 分片下载 +
 * 失败自动换线。
 *
 * <h3>只对主线资源换线</h3>
 * 传入的 URL 只有在确实指向 {@link CNMirrors#CANONICAL_BASE}（主线资源的规范前缀）、且其后
 * 只剩一段文件名时，才会被替换成支线地址；其余任何地址一律原样使用。
 * 这样做是因为第三个参数 {@code displayName} 只是显示名，不保证等于远端文件名，
 * 拿它拼支线 URL 会拼错；而从 URL 自身推导则不会。也保证了将来若有别处调用这个
 * 方法去取非主线的东西，不会被意外重定向。
 *
 * <p>「配置直连主线」的约定不受影响：{@code config.json}、{@code version_js.json}、
 * {@code version_scenario.json} 都不经过这里，它们由各自的调用点直连主线。
 * 本类只负责「分发文件」。
 */
public final class CNHotUpdate {

    private static final String TAG = "MagiaCNHotUpdate";

    private static final int MAX_ATTEMPTS      = 4;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 30000;
    // 低速看门狗：read timeout 管的是「完全没字节」，管不了「每秒几十 KB 的滴速」。
    // 窗口速度持续低于 MIN_OK_BPS 超过 SLOW_FAIL_NS 就抛异常走换线。
    private static final long MIN_OK_BPS  = 100L * 1024L;                       // 100 KB/s
    private static final long SLOW_FAIL_NS = TimeUnit.SECONDS.toNanos(15L);

    private CNHotUpdate() {}

    /**
     * 下载 {@code url} 指向的文件到 {@code destPath}。
     *
     * <p>签名与被替换掉的 {@code RestClient.cnDownloadFileFull} 完全一致，
     * 返回值语义也一致：成功 true，失败 false（不抛异常）。
     *
     * @param url         原始下载地址（通常是主线）
     * @param destPath    本地目标路径
     * @param displayName 显示名，仅用于日志
     * @param index       进度槽位下标（0..14）
     */
    public static boolean download(String url, String destPath,
                                   String displayName, int index) {
        if (url == null || destPath == null) {
            CNLog.e(TAG, "参数为空，放弃下载 url=" + url + " dest=" + destPath);
            return false;
        }
        if (CNDebugFlags.isOn(CNDebugFlags.FAIL_DOWNLOAD)) {
            // 返回 false 而不是抛：调用方本来就按「这一项失败」处理，
            // 走的是与真实下载失败完全相同的分支（换线、重试、上报）。
            CNLog.w(TAG, "[DEBUG] failDownload 注入：直接判本次下载失败 " + displayName);
            return false;
        }
        File dest = new File(destPath);

        // 与原实现一致：目标文件已经在了就直接算完成
        if (dest.isFile()) {
            CNLog.i(TAG, "目标已存在，跳过下载: " + destPath);
            CNCNDownloadUI.setFileSize(index, (float) (dest.length() / 1000000.0d));
            markDone(index);
            return true;
        }

        String remoteName = mainLineFileName(url);
        if (remoteName == null) {
            // 非主线资源地址：不换线，按原地址单线程续传
            CNLog.i(TAG, "非主线地址，直连下载: " + url);
            try {
                singleStream(url, dest, index, false);
                markDone(index);
                return true;
            } catch (Throwable t) {
                CNLog.e(TAG, "直连下载失败: " + url, t);
                return false;
            }
        }

        // 内置 fallback 可立即使用；远程线路表只做后台优化，服务器故障
        // 不能在真正下载更新包之前再同步卡两轮 15 秒。
        if (!CNMirrors.isLoaded()) CNMirrors.ensureLoadedAsync();

        CNLog.i(TAG, "开始下载 " + displayName + " file=" + remoteName
                + " 可用线路=" + CNMirrors.healthy().size());

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                CNLog.w(TAG, "线程被中断，放弃 " + remoteName);
                markFailed(index);
                return false;
            }
            CNMirrors.Mirror mirror = CNMirrors.pick(attempt);
            boolean direct = attempt % 2 == 0;
            String  tryUrl = mirror.urlFor(remoteName);
            CNCNDownloadUI.setDownloadSpeed(index, 0.0f);

            try {
                fetch(tryUrl, dest, index, direct, mirror, remoteName);
                CNMirrors.reportSuccess(mirror);
                markDone(index);
                CNLog.i(TAG, "下载完成 " + remoteName + " attempt=" + attempt
                        + " mirror=" + mirror.name);
                return true;
            } catch (Throwable t) {
                CNMirrors.reportFailure(mirror, String.valueOf(t.getMessage()));
                CNLog.w(TAG, "下载失败 " + remoteName + " attempt=" + attempt
                        + " mirror=" + mirror.name, t);
                if (attempt < MAX_ATTEMPTS) {
                    long delay = 2000L << (attempt - 1);
                    CNLog.i(TAG, "等待 " + delay + "ms 后换线重试");
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
        CNLog.e(TAG, "全部线路均失败: " + remoteName);
        markFailed(index);
        return false;
    }

    /** 优先分片，条件不满足时退回单线程续传。 */
    private static void fetch(String url, File dest, int index,
                              boolean direct, CNMirrors.Mirror mirror,
                              String remoteName) throws IOException {
        int wanted = mirror.effectiveChunks();
        if (wanted > 1) {
            CNChunkedDownload.Probe probe = CNChunkedDownload.probe(url, direct);
            if (probe.rangeSupported && probe.total > 0) {
                int chunks = wanted;
                long minChunk = CNMirrors.minChunkBytes();
                if (minChunk > 0) {
                    long fit = probe.total / minChunk;
                    if (fit < chunks) chunks = (int) Math.max(1L, fit);
                }
                if (chunks > 1) {
                    CNLog.i(TAG, "分片下载 " + dest.getName() + " chunks=" + chunks
                            + " bytes=" + probe.total + " mirror=" + mirror.name);
                    CNCNDownloadUI.setFileSize(index, (float) (probe.total / 1000000.0d));
                    CNChunkedDownload.download(url, dest, chunks, direct, probe,
                            new HotSink(index), mirror, remoteName);
                    return;
                }
            }
            CNLog.i(TAG, "不支持 Range 或文件过小，改用单线程: " + dest.getName());
        }
        singleStream(url, dest, index, direct);
    }

    /** 进度回调桥接到既有的下载 UI 槽位。 */
    private static final class HotSink implements CNChunkedDownload.Sink {
        private final int index;
        HotSink(int index) { this.index = index; }
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
            return Thread.currentThread().isInterrupted();
        }
    }

    /**
     * 单线程断点续传：沿用原实现的 {@code <目标>.part} 约定，
     * 已有残片时用 {@code Range} 接着下。
     */
    private static void singleStream(String url, File dest, int index, boolean direct)
            throws IOException {
        File part = new File(dest.getPath() + ".part");
        File parent = part.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                && !parent.isDirectory()) {
            throw new IOException("无法创建下载目录: " + parent);
        }
        long offset = part.isFile() ? part.length() : 0L;

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
        if (offset > 0) c.setRequestProperty("Range", "bytes=" + offset + "-");

        InputStream  in  = null;
        FileOutputStream out = null;
        try {
            int code = c.getResponseCode();
            boolean append;
            long total;
            if (offset > 0 && code == 206) {
                append = true;
                long len = parseLong(c.getHeaderField("Content-Length"), -1L);
                total = len >= 0 ? offset + len : -1L;
            } else if (code == 200) {
                // 服务端忽略了 Range：残片作废，从头来
                append = false;
                offset = 0L;
                total  = parseLong(c.getHeaderField("Content-Length"), -1L);
            } else {
                throw new IOException("HTTP " + code + " offset=" + offset + " url=" + url);
            }
            if (total > 0) {
                CNCNDownloadUI.setFileSize(index, (float) (total / 1000000.0d));
            }

            in  = new BufferedInputStream(c.getInputStream(), 65536);
            out = new FileOutputStream(part, append);
            byte[] buf = new byte[65536];
            long written = 0L, speedBase = 0L;
            long windowStart = System.nanoTime();
            long slowSinceNs = 0L;  // 低速看门狗：半死镜像滴速下载时主动换线
            int n;
            while ((n = in.read(buf)) != -1) {
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
                long dt  = now - windowStart;
                if (dt >= TimeUnit.MILLISECONDS.toNanos(500L)) {
                    long windowBytes = written - speedBase;
                    CNCNDownloadUI.setDownloadSpeed(index,
                            (float) ((windowBytes * 1.0E9d / dt) / 1000000.0d));
                    // 持续低速（<100KB/s 超过 15s）视为镜像半死：
                    // read timeout 只在完全无字节时触发，滴速线路会永远卡在这里
                    if (windowBytes * 1000000000L / dt < MIN_OK_BPS) {
                        if (slowSinceNs == 0L) slowSinceNs = now;
                        else if (now - slowSinceNs >= SLOW_FAIL_NS) {
                            throw new IOException("镜像速度过慢（持续低于 "
                                    + (MIN_OK_BPS / 1024) + "KB/s），换线");
                        }
                    } else {
                        slowSinceNs = 0L;
                    }
                    speedBase   = written;
                    windowStart = now;
                }
            }
            out.flush();
            out.getFD().sync();
            closeQuietly(out); out = null;
            closeQuietly(in);  in  = null;

            // 声明了总长度就必须对得上，避免把短读当成功
            if (total > 0 && part.length() != total) {
                throw new IOException("下载不完整: " + part.length() + " / " + total);
            }
            if (dest.exists() && !dest.delete()) {
                throw new IOException("无法替换目标文件 " + dest);
            }
            if (!part.renameTo(dest)) {
                throw new IOException("无法重命名 " + part + " -> " + dest);
            }
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    // ---- 小工具 ----

    /**
     * 若 {@code url} 指向主线资源根下的单个文件，返回该文件名；否则返回 null
     * （表示不适用换线）。
     */
    private static String mainLineFileName(String url) {
        // 用规范前缀而不是兜底线路：兜底线路是「从哪里取字节」，可以随时换；
        // 这里要判断的是「这条地址是不是主线资源」，属于身份问题。
        String base = CNMirrors.CANONICAL_BASE;
        if (!url.startsWith(base)) return null;
        String rest = url.substring(base.length());
        if (rest.length() == 0) return null;
        // 只接受「根下单段文件名」，带子路径或查询串的一律不动
        if (rest.indexOf('/') >= 0 || rest.indexOf('?') >= 0 || rest.indexOf('#') >= 0) {
            return null;
        }
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
            CNCNDownloadUI.fileStatus[index] = 3;
        }
        CNCNDownloadUI.throttledUpdate();
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

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignore) {}
        }
    }
}
