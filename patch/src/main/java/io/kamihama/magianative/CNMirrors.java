package io.kamihama.magianative;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 资源下载线路（镜像）目录。
 *
 * <p>线路列表从 {@link #MIRRORS_URL} 拉取；拉取失败或内容不可用时回退到内置的
 * 默认线路 {@link #DEFAULT_BASE}，因此**任何情况下都至少有一条可用线路**，
 * 行为不会比改版前更差。
 *
 * <p>线路按 {@code weight} 由大到小排序（权重越大越优先）。下载失败会给该线路
 * 记一次失败并进入冷却，冷却期内不再被选中；{@code CNDownloaderFix} 的每一次
 * 重试都会换到下一条健康线路，从而实现自动换线。
 *
 * <p>线路只影响「从哪里取字节」。安装完成标记（marker）里记录的始终是规范 URL
 * （{@code DEFAULT_BASE + 文件名}），所以换线不会让既有安装失效、也不会导致
 * 已装好的文件被重新下载。
 */
public final class CNMirrors {

    private static final String TAG = "MagiaCNMirrors";

    /** 线路列表地址。 */
    public static final String MIRRORS_URL = "https://assets.example.test/mirrors.json";

    /** 内置兜底线路：与改版前使用的地址一致。 */
    public static final String DEFAULT_BASE = "https://assets.example.test/";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS    = 30000;
    /** 线路列表响应体大小上限，防止异常内容撑爆内存。 */
    private static final int MAX_JSON_BYTES     = 256 * 1024;

    // ---- 可被线路列表覆盖的全局参数（含默认值） ----
    private static volatile int  cfgChunks            = 4;
    private static volatile long cfgMinChunkBytes     = 8L * 1024 * 1024;
    private static volatile int  cfgSwitchAfterFail   = 1;
    private static volatile int  cfgStallSeconds      = 25;
    private static volatile int  cfgMinSpeedKbps      = 32;
    private static volatile long cfgCooldownMs        = 60_000L;

    public static int  chunks()          { return cfgChunks; }
    public static long minChunkBytes()   { return cfgMinChunkBytes; }
    public static int  switchAfterFail() { return cfgSwitchAfterFail; }
    public static int  stallSeconds()    { return cfgStallSeconds; }
    public static int  minSpeedKbps()    { return cfgMinSpeedKbps; }

    /** 一条线路。 */
    public static final class Mirror {
        public final String name;
        /** 已保证以 '/' 结尾。 */
        public final String base;
        public final int    weight;
        /** 该线路的分片数；<=0 表示用全局默认。 */
        public final int    chunks;
        public final boolean enabled;

        final AtomicInteger failures = new AtomicInteger(0);
        /** 冷却截止时刻（{@link System#nanoTime()} 基准）；0 表示不在冷却中。 */
        volatile long cooldownUntilNs = 0L;

        Mirror(String name, String base, int weight, int chunks, boolean enabled) {
            this.name    = name;
            this.base    = base;
            this.weight  = weight;
            this.chunks  = chunks;
            this.enabled = enabled;
        }

        /** 该线路上某个文件的完整 URL。 */
        public String urlFor(String fileName) {
            return base + fileName;
        }

        /** 本线路应使用的分片数。 */
        public int effectiveChunks() {
            return chunks > 0 ? chunks : cfgChunks;
        }

        @Override public String toString() {
            return name + " <" + base + ">";
        }
    }

    /** 当前线路表；构造后不再原地修改，整体替换。 */
    private static volatile List<Mirror> mirrors = defaultList();
    private static volatile boolean loaded = false;

    private CNMirrors() {}

    private static List<Mirror> defaultList() {
        List<Mirror> l = new ArrayList<Mirror>(1);
        l.add(new Mirror("默认线路", DEFAULT_BASE, 100, 0, true));
        return l;
    }

    /**
     * 拉取并解析线路列表。失败时保留当前（或默认）线路表，不抛异常。
     *
     * @param direct true 时绕过系统代理直连
     */
    public static synchronized void refresh(boolean direct) {
        try {
            String body = fetch(MIRRORS_URL, direct);
            List<Mirror> parsed = parse(body);
            if (parsed.isEmpty()) {
                CNLog.w(TAG, "mirrors.json 未包含任何可用线路，沿用默认线路");
                return;
            }
            mirrors = parsed;
            loaded  = true;
            StringBuilder sb = new StringBuilder();
            for (Mirror m : parsed) sb.append(' ').append(m.name).append('=').append(m.base);
            CNLog.i(TAG, "线路列表已加载 count=" + parsed.size() + sb);
        } catch (Throwable t) {
            CNLog.w(TAG, "拉取线路列表失败，沿用默认线路: " + t);
        }
    }

    /** 是否成功加载过远端线路列表。 */
    public static boolean isLoaded() { return loaded; }

    private static String fetch(String url, boolean direct) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Connection", "close");
        InputStream is = null;
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("mirrors.json HTTP " + code);
            }
            is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0, n;
            while ((n = is.read(buf)) >= 0) {
                total += n;
                if (total > MAX_JSON_BYTES) throw new IOException("mirrors.json 过大");
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            if (is != null) { try { is.close(); } catch (IOException ignore) {} }
            c.disconnect();
        }
    }

    private static List<Mirror> parse(String body) throws Exception {
        JSONObject root = new JSONObject(body);

        JSONObject st = root.optJSONObject("settings");
        if (st != null) {
            cfgChunks          = clampInt(st.optInt("chunks",             cfgChunks),          1, 16);
            cfgMinChunkBytes   = Math.max(1L << 20,
                                 st.optLong("min_chunk_bytes",            cfgMinChunkBytes));
            cfgSwitchAfterFail = clampInt(st.optInt("switch_after_failures", cfgSwitchAfterFail), 1, 10);
            cfgStallSeconds    = clampInt(st.optInt("stall_seconds",      cfgStallSeconds),    5, 300);
            cfgMinSpeedKbps    = clampInt(st.optInt("min_speed_kbps",     cfgMinSpeedKbps),    0, 1000000);
            cfgCooldownMs      = Math.max(1000L, st.optLong("cooldown_ms", cfgCooldownMs));
        }

        JSONArray arr = root.optJSONArray("mirrors");
        List<Mirror> out = new ArrayList<Mirror>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String base = o.optString("base", "").trim();
            if (base.isEmpty()) continue;
            // 只接受 http/https，避免线路列表被塞进奇怪的 scheme
            String lower = base.toLowerCase(java.util.Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                CNLog.w(TAG, "忽略非 http(s) 线路: " + base);
                continue;
            }
            if (!base.endsWith("/")) base = base + "/";
            String name    = o.optString("name", base);
            int    weight  = o.optInt("weight", 0);
            int    chunks  = o.optInt("chunks", 0);
            boolean enable = o.optBoolean("enabled", true);
            out.add(new Mirror(name, base, weight, chunks, enable));
        }
        sortByWeightDesc(out);
        return out;
    }

    /**
     * 按权重从大到小排序（稳定的插入排序）。
     *
     * <p>不用 {@code Collections.sort(list, Comparator)}：线路条目通常只有个位数，
     * 插入排序足够，且省掉一个比较器类型。
     */
    private static void sortByWeightDesc(List<Mirror> list) {
        for (int i = 1; i < list.size(); i++) {
            Mirror cur = list.get(i);
            int j = i - 1;
            while (j >= 0 && list.get(j).weight < cur.weight) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, cur);
        }
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 返回当前可用（已启用且不在冷却期）的线路，优先级从高到低。
     * 若全部处于冷却期，则忽略冷却返回全部已启用线路——宁可重试也不要无线路可用。
     */
    public static List<Mirror> healthy() {
        List<Mirror> all = mirrors;
        long now = System.nanoTime();
        List<Mirror> ok = new ArrayList<Mirror>(all.size());
        for (Mirror m : all) {
            if (!m.enabled) continue;
            if (m.cooldownUntilNs > now) continue;
            ok.add(m);
        }
        if (!ok.isEmpty()) return ok;
        List<Mirror> any = new ArrayList<Mirror>(all.size());
        for (Mirror m : all) if (m.enabled) any.add(m);
        if (any.isEmpty()) any.add(new Mirror("默认线路", DEFAULT_BASE, 0, 0, true));
        return any;
    }

    /**
     * 为第 {@code attempt} 次尝试挑一条线路（attempt 从 1 开始）。
     * 依次轮换，使每次重试都换到下一条线路。
     */
    public static Mirror pick(int attempt) {
        List<Mirror> ok = healthy();
        int idx = (attempt - 1) % ok.size();
        if (idx < 0) idx = 0;
        return ok.get(idx);
    }

    /** 记一次失败：累计计数，达到阈值后让该线路进入冷却。 */
    public static void reportFailure(Mirror m, String reason) {
        if (m == null) return;
        int f = m.failures.incrementAndGet();
        if (f >= cfgSwitchAfterFail) {
            m.cooldownUntilNs = System.nanoTime() + cfgCooldownMs * 1_000_000L;
            m.failures.set(0);
            CNLog.w(TAG, "线路进入冷却 mirror=" + m.name + " reason=" + reason
                    + " cooldown_ms=" + cfgCooldownMs);
        } else {
            CNLog.w(TAG, "线路失败 mirror=" + m.name + " reason=" + reason + " count=" + f);
        }
    }

    /** 记一次成功：清空失败计数并解除冷却。 */
    public static void reportSuccess(Mirror m) {
        if (m == null) return;
        m.failures.set(0);
        m.cooldownUntilNs = 0L;
    }
}
