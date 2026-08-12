package io.kamihama.magianative;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** 资源包固定块哈希清单的拉取与解析。 */
public final class ChunkManifest {
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
     */
    public static synchronized CNChunkedDownload.ChunkHashes forFile(String fileName) {
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
            c = (HttpURLConnection) new java.net.URL(url).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
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
