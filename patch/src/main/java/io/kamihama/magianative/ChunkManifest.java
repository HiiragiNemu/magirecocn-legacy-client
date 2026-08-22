package io.kamihama.magianative;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 资源包固定块哈希清单的拉取与解析。 */
public final class ChunkManifest {

    /** 与分片下载同口径：清单是下载链的一部分，超时不该比它紧。 */
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 30000;
    private static final String TAG = "ChunkManifest";
    public static final String MANIFEST_NAME = "manifest.json";
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    /** 每次从下一条健康线路起步，避免永远被第一条线路的旧 CDN 缓存钉死。 */
    private static final AtomicInteger NEXT_START = new AtomicInteger(0);
    private static final AtomicInteger NONCE = new AtomicInteger(0);

    private ChunkManifest() {}

    /**
     * 每次调用现拉，不做进程缓存。请求同时带 cache-busting 查询参数和 no-cache
     * 头；某条线路的清单缺少目标文件时继续检查下一条，而不是把“旧清单缺项”
     * 错当成“服务端没有清单”并关闭完整性校验。
     *
     * <h3>并发约定（P-03 修复）：本方法<b>故意不加 synchronized</b></h3>
     *
     * 旧实现是 {@code static synchronized}——持<b>类锁</b>做网络 I/O。每条健康
     * 线路最坏 connect 15s + read 30s（见 {@link #fetchDirect}），多条线路全挂时
     * 一次调用可烧约 3 分钟。首装期间 4 路 ArchiveTask 并行开工、每个文件各调
     * 一次本方法，类锁把这些调用<b>完全串行化</b>：线路故障时文件 B/C/D 的下载
     * 线程全堵在类锁上，等文件 A 把全部线路的超时挨个烧完——「4 路并行池」在
     * 网络故障场景退化回 1 路，与下载池的并发设计直接相悖。更糟的是
     * CNDownloaderFix 的调用点在 ARCHIVE_LOCKS[index] 临界区内等这把类锁，玩家
     * 对同一槽位点「重下」或「离线包即时安装」（同一把槽位锁）会被一并堵住，
     * 最坏分钟级无响应。
     *
     * <p>这把锁换不来任何一致性收益，去掉是安全的，依据有三：
     * <ol>
     * <li><b>方法内无可变共享状态</b>：NEXT_START/NONCE 是 AtomicInteger；健康
     *     线路表由 CNMirrors 自己保证并发可见（mirrors 字段为 volatile，
     *     healthy()/pick() 本就供多个下载线程并发调用，ensureLoadedAsync 用
     *     AtomicBoolean CAS 做内部单飞）；解析出的 map 是每次新建的局部变量；
     *     返回的 ChunkHashes 字段全 final（不可变），各调用方持有独立实例。</li>
     * <li><b>没有结果缓存就没有需要持锁的临界区</b>：「每次现拉」是本方法的有意
     *     设计（对抗旧 CDN 缓存把清单钉死，见上段）。因此双重检查、
     *     ConcurrentHashMap 缓存或 computeIfAbsent 单飞在这里没有保护对象——
     *     引入它们反而会把「同 key 现拉」变成「同 key 复用他人结果」，改变
     *     既有语义。同 key 并发调用极罕见（同一文件被同时重下/导入），各拉
     *     一次与旧锁下「排队各拉一次」结果等价，且互不阻塞只会更快；线路
     *     故障时独立拉取还能借 NEXT_START 的原子轮转天然错开起步线路，比
     *     单飞（后来者干等先来者烧完全部超时）延迟更低。</li>
     * <li><b>失败降级行为不变</b>：全部线路失败仍返回 null；单条线路抛异常仍
     *     只换线、不计冷却（线路冷却由 CNMirrors.reportFailure 负责，本方法
     *     从不调用它）。</li>
     * </ol>
     *
     * <p>并发语义小结：不同 key（不同文件）的调用<b>真并行</b>；同 key 并发调用
     * 各拉各的、互不排队，语义与旧实现等价——去锁不牺牲任何正确性，
     * 只移除人为串行点。
     */
    public static CNChunkedDownload.ChunkHashes forFile(String fileName) {
        if (!CNMirrors.isLoaded()) CNMirrors.ensureLoadedAsync();
        List<CNMirrors.Mirror> healthy = CNMirrors.healthy();
        if (healthy == null || healthy.isEmpty()) return null;
        int n = healthy.size();
        int start = positiveMod(NEXT_START.getAndIncrement(), n);
        Throwable last = null;
        for (int i = 0; i < n; i++) {
            CNMirrors.Mirror m = healthy.get((start + i) % n);
            try {
                String base = m.urlFor(MANIFEST_NAME);
                String sep = base.indexOf('?') >= 0 ? "&" : "?";
                String url = base + sep + "cnv_manifest=" + System.currentTimeMillis()
                        + "-" + NONCE.incrementAndGet();
                Map<String, CNChunkedDownload.ChunkHashes> map = fetchDirect(url);
                if (map == null) continue;
                CNChunkedDownload.ChunkHashes h = map.get(fileName);
                if (h != null) {
                    CNLog.i(TAG, "manifest 已加载: " + map.size() + " 个文件, 线路="
                            + m.name + " target=" + fileName);
                    return h;
                }
                CNLog.w(TAG, "manifest 缺少目标文件，继续换线: " + fileName
                        + " mirror=" + m.name);
            } catch (Throwable t) {
                last = t;
                CNLog.w(TAG, "manifest 线路失败（只换线，不计冷却） mirror="
                        + m.name + ": " + t);
            }
        }
        if (last != null) CNLog.w(TAG, "manifest 全部线路失败: " + last);
        return null;
    }

    private static Map<String, CNChunkedDownload.ChunkHashes> fetchDirect(String url)
            throws Exception {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = CNHttp.open(new java.net.URL(url), true,
                    CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
            c.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0");
            c.setRequestProperty("Pragma", "no-cache");
            c.setRequestProperty("Accept-Encoding", "identity");
            CNUserAgent.apply(c);
            int code = c.getResponseCode();
            if (code != 200) {
                CNLog.w(TAG, "manifest 拉取失败 HTTP " + code + " url=" + url);
                return null;
            }
            int declared = c.getContentLength();
            if (declared > MAX_BYTES) throw new Exception("manifest 过大: " + declared);
            in = new BufferedInputStream(c.getInputStream(), 1 << 16);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    declared > 0 ? Math.min(declared, MAX_BYTES) : 65536);
            byte[] buf = new byte[1 << 15];
            int total = 0;
            int got;
            while ((got = in.read(buf)) >= 0) {
                if (got == 0) continue;
                total += got;
                if (total > MAX_BYTES) throw new Exception("manifest 响应超过上限");
                out.write(buf, 0, got);
            }
            JSONObject root = new JSONObject(new String(out.toByteArray(), "UTF-8"));
            Map<String, CNChunkedDownload.ChunkHashes> map =
                    new HashMap<String, CNChunkedDownload.ChunkHashes>();
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                JSONObject meta = root.optJSONObject(name);
                if (meta == null) continue;
                long size = meta.optLong("size", -1L);
                long chunkSize = meta.optLong("chunk_size", 0L);
                JSONArray chunks = meta.optJSONArray("chunks");
                if (size < 0 || chunkSize <= 0 || chunks == null || chunks.length() == 0) continue;
                java.util.ArrayList<String> list =
                        new java.util.ArrayList<String>(chunks.length());
                boolean valid = true;
                for (int i = 0; i < chunks.length(); i++) {
                    String hash = chunks.optString(i, "").trim().toLowerCase(java.util.Locale.US);
                    if (!isMd5(hash)) { valid = false; break; }
                    list.add(hash);
                }
                if (valid) {
                    map.put(name, new CNChunkedDownload.ChunkHashes(chunkSize, size, list));
                }
            }
            return map;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    private static boolean isMd5(String s) {
        if (s == null || s.length() != 32) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }

    private static int positiveMod(int value, int mod) {
        int r = value % mod;
        return r < 0 ? r + mod : r;
    }
}
