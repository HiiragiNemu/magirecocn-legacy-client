package io.kamihama.magianative;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.util.HashMap;
import java.util.Map;

/**
 * 16MB 分块哈希清单（manifest.json）的拉取与解析。
 *
 * <p>发布侧 {@code scripts/build_chunk_manifest.py} 生成，作为镜像根目录的
 * manifest.json 分发（与 version json 同套路）。结构：
 * <pre>
 * {
 *   "cn_base_03.zip": {
 *     "size": 1422289288,
 *     "chunk_size": 16777216,
 *     "chunks": ["md5hex0", "md5hex1", ...]
 *   },
 *   ...
 * }
 * </pre>
 *
 * <p>拉取走多线路重试（同 version json）：每条线路 {@code urlFor("manifest.json")}，
 * 失败只换线、不计入冷却——manifest 是几十 KB 冷对象，一次回源慢不代表线路
 * 不适合传大文件。
 */
public final class ChunkManifest {
    private static final String TAG = "ChunkManifest";
    /** 清单文件名，放在镜像根目录（与资源文件同前缀）。 */
    public static final String MANIFEST_NAME = "manifest.json";

    private static volatile Map<String, CNChunkedDownload.ChunkHashes> cached;
    private static volatile boolean attempted = false;

    private ChunkManifest() {}

    /**
     * 取某文件的分块清单；拉取失败/未找到返回 null（调用方退化为无分块校验）。
     * 结果缓存：一次启动内只拉一次。
     */
    public static CNChunkedDownload.ChunkHashes forFile(String fileName) {
        Map<String, CNChunkedDownload.ChunkHashes> m = cached;
        if (m == null && !attempted) {
            m = fetchOnce();
        }
        return m == null ? null : m.get(fileName);
    }

    /**
     * 作废缓存：分块校验失败时调用，下次 {@link #forFile} 重新拉取。
     *
     * <p>热更文件重新发布后，本进程里缓存的旧块哈希会和新文件对不上（manifest
     * 是「一次启动只拉一次」）。校验失败说明要么传输损坏、要么清单过期——前者
     * 重拉无妨，后者重拉即治（2026-08-12 真机日志：cn_scenario_update.zip 重发后
     * 客户端旧清单反复 ResetRequired）。
     */
    public static synchronized void invalidate() {
        cached = null;
        attempted = false;
    }

    /** 多线路重试拉取并解析一次（全失败静默，退化为无清单）。 */
    private static synchronized Map<String, CNChunkedDownload.ChunkHashes> fetchOnce() {
        if (cached != null || attempted) return cached;
        attempted = true;
        if (!CNMirrors.isLoaded()) CNMirrors.ensureLoadedAsync();
        Throwable last = null;
        for (CNMirrors.Mirror m : CNMirrors.healthy()) {
            try {
                Map<String, CNChunkedDownload.ChunkHashes> map =
                        fetchDirect(m.urlFor(MANIFEST_NAME));
                if (map != null) {
                    CNLog.i(TAG, "manifest 已加载: " + map.size() + " 个文件, 线路=" + m.name);
                    cached = map;
                    return map;
                }
            } catch (Throwable t) {
                CNLog.w(TAG, "manifest 线路失败（只换线，不计冷却） mirror=" + m.name + ": " + t);
                last = t;
            }
        }
        if (last != null) CNLog.w(TAG, "manifest 全部线路失败: " + last);
        return null;
    }

    /** 单线路拉取解析；HTTP 非 200 返回 null（不抛，换线）。 */
    private static Map<String, CNChunkedDownload.ChunkHashes> fetchDirect(String url)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new java.net.URL(url)
                .openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        CNUserAgent.apply(c);
        int code = c.getResponseCode();
        if (code != 200) {
            try { c.disconnect(); } catch (Throwable ignore) {}
            CNLog.w(TAG, "manifest 拉取失败 HTTP " + code + " url=" + url);
            return null;
        }
        InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[1 << 15];
        int n;
        while ((n = in.read(buf)) >= 0) sb.append(new String(buf, 0, n, "UTF-8"));
        try { in.close(); } catch (Throwable ignore) {}
        try { c.disconnect(); } catch (Throwable ignore) {}

        JSONObject root = new JSONObject(sb.toString());
        Map<String, CNChunkedDownload.ChunkHashes> map =
                new HashMap<String, CNChunkedDownload.ChunkHashes>();
        java.util.Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            JSONObject meta = root.optJSONObject(name);
            if (meta == null) continue;
            long size = meta.optLong("size", -1L);
            long cs   = meta.optLong("chunk_size", 0L);
            JSONArray chunks = meta.optJSONArray("chunks");
            if (cs <= 0 || chunks == null || size < 0) continue;
            java.util.List<String> list = new java.util.ArrayList<String>(chunks.length());
            for (int i = 0; i < chunks.length(); i++) list.add(chunks.optString(i));
            map.put(name, new CNChunkedDownload.ChunkHashes(cs, size, list));
        }
        return map;
    }
}
