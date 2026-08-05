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
 * （{@code CNDownloaderFix.RESOURCE_BASE_URL + 文件名}——那是身份标识，不是
 * 实际下载地址），所以换线/换默认线路不会让既有安装失效、也不会导致
 * 已装好的文件被重新下载。
 */
public final class CNMirrors {

    private static final String TAG = "MagiaCNMirrors";

    /** 线路列表地址。 */
    public static final String MIRRORS_URL = "https://api.example.test/legacy/config.json";

    /** 内置兜底线路：拉不到线路表时的默认可用下载路径。 */
    public static final String DEFAULT_BASE = "https://r2.assets.example.test/";

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
    // ---- 反限速 ----
    /** 跌到基准速度的这个比例以下即视为疑似被限速。 */
    private static volatile int  cfgThrottleRatioPct   = 60;
    /** 基准速度的取样窗口（秒）：下载开始后 [from, to) 这一段的平均速度。 */
    private static volatile int  cfgBaselineFromS      = 10;
    private static volatile int  cfgBaselineToS        = 30;
    /** 连续低于阈值多久才认定为限速（秒），避免被瞬时抖动误伤。 */
    private static volatile int  cfgThrottleGraceS     = 15;
    /** 另一条线路的基准要高出当前速度这么多倍，才值得换过去（百分比）。 */
    private static volatile int  cfgSwitchGainPct      = 125;
    /** 被判定限速后降低优先级的时长。 */
    private static volatile long cfgThrottleDemoteMs   = 120_000L;

    public static int  throttleRatioPct() { return cfgThrottleRatioPct; }
    public static int  baselineFromS()    { return cfgBaselineFromS; }
    public static int  baselineToS()      { return cfgBaselineToS; }
    public static int  throttleGraceS()   { return cfgThrottleGraceS; }

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

        /**
         * 观测到的基准速度（字节/秒），取历次观测的最大值。
         *
         * <p>用最大值而不是平均：我们要的是「这条线路没被限速时能跑多快」，
         * 一旦某次跑出过高速，后面掉下去就说明是被限了，而不是它本来就慢。
         */
        volatile long baselineBps = 0L;
        /** 判定为限速后的降级截止时刻（nanoTime 基准）；降级只降优先级，不禁用。 */
        volatile long demoteUntilNs = 0L;

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
                CNLog.w(TAG, "config.json 未包含任何可用线路，沿用默认线路");
                return;
            }
            mirrors = parsed;
            loaded  = true;
            StringBuilder sb = new StringBuilder();
            for (Mirror m : parsed) sb.append(' ').append(m.name).append('=').append(m.base);
            CNLog.i(TAG, "线路列表已加载 count=" + parsed.size() + sb);
            // 浮层可能在配置加载完成前就已用内置署名建成——配置到位后刷新一次署名
            try {
                CNCNDownloadUI.refreshCredits(RestClient.getCurrentActivity());
            } catch (Throwable ignore) {}
        } catch (Throwable t) {
            CNLog.w(TAG, "拉取线路列表失败，沿用默认线路: " + t);
        }
    }

    /** 是否成功加载过远端线路列表。 */
    public static boolean isLoaded() { return loaded; }

    // ---- 浮层署名配置 ----
    //
    // config.json 里的可选字段，控制下载浮层左侧署名列表、底部滚动署名与
    // GitHub 胶囊地址；缺省（null）时浮层用代码里的内置默认值。格式：
    //
    //   "ui_credits": {
    //     "list": [
    //       {"type":"title","text":"…"},
    //       {"type":"head","text":"…"},
    //       {"type":"item","text":"…","url":"https://…","span":"高亮片段"},
    //       {"type":"sub","text":"…"}
    //     ],
    //     "footer": "底部滚动署名",
    //     "github_url": "https://github.com/…"
    //   }
    //
    // type 缺省为 item；url/span 可省。人名条与网站条在同一 list 里任意混排。
    private static volatile JSONObject uiCredits;

    /** 远端 ui_credits 原文；未配置时为 null。 */
    public static JSONObject uiCredits() { return uiCredits; }

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
                throw new IOException("config.json HTTP " + code);
            }
            is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int total = 0, n;
            while ((n = is.read(buf)) >= 0) {
                total += n;
                if (total > MAX_JSON_BYTES) throw new IOException("config.json 过大");
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

        // 浮层署名配置（可选）：见 uiCredits()
        uiCredits = root.optJSONObject("ui_credits");

        JSONObject st = root.optJSONObject("settings");
        if (st != null) {
            cfgChunks          = clampInt(st.optInt("chunks",             cfgChunks),          1, 16);
            cfgMinChunkBytes   = Math.max(1L << 20,
                                 st.optLong("min_chunk_bytes",            cfgMinChunkBytes));
            cfgSwitchAfterFail = clampInt(st.optInt("switch_after_failures", cfgSwitchAfterFail), 1, 10);
            cfgStallSeconds    = clampInt(st.optInt("stall_seconds",      cfgStallSeconds),    5, 300);
            cfgMinSpeedKbps    = clampInt(st.optInt("min_speed_kbps",     cfgMinSpeedKbps),    0, 1000000);
            cfgCooldownMs      = Math.max(1000L, st.optLong("cooldown_ms", cfgCooldownMs));
            cfgThrottleRatioPct = clampInt(st.optInt("throttle_ratio_pct",   cfgThrottleRatioPct), 10, 100);
            cfgBaselineFromS    = clampInt(st.optInt("baseline_from_s",      cfgBaselineFromS),     1, 600);
            cfgBaselineToS      = clampInt(st.optInt("baseline_to_s",        cfgBaselineToS),       2, 1200);
            if (cfgBaselineToS <= cfgBaselineFromS) cfgBaselineToS = cfgBaselineFromS + 10;
            cfgThrottleGraceS   = clampInt(st.optInt("throttle_grace_s",     cfgThrottleGraceS),    1, 600);
            cfgSwitchGainPct    = clampInt(st.optInt("switch_gain_pct",      cfgSwitchGainPct),   100, 1000);
            cfgThrottleDemoteMs = Math.max(1000L, st.optLong("throttle_demote_ms", cfgThrottleDemoteMs));
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
        if (!ok.isEmpty()) {
            // 被判定限速的线路不排除，只挪到末尾——它仍然可用，只是不优先
            List<Mirror> normal = new ArrayList<Mirror>(ok.size());
            List<Mirror> demoted = new ArrayList<Mirror>();
            for (Mirror m : ok) {
                if (m.demoteUntilNs > now) demoted.add(m); else normal.add(m);
            }
            if (!normal.isEmpty()) {
                normal.addAll(demoted);
                return normal;
            }
            return ok;
        }
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

    /** 记录一次基准速度观测。 */
    public static void reportBaseline(Mirror m, long bps) {
        if (m == null || bps <= 0) return;
        if (bps > m.baselineBps) {
            m.baselineBps = bps;
            CNLog.i(TAG, "线路基准速度 " + m.name + " = " + (bps / 1024) + " KB/s");
        }
    }

    /** 判定为限速：降低其优先级一段时间，但不禁用（它仍然可用，只是不优先）。 */
    public static void reportThrottled(Mirror m) {
        if (m == null) return;
        m.demoteUntilNs = System.nanoTime() + cfgThrottleDemoteMs * 1_000_000L;
        CNLog.w(TAG, "线路疑似被限速，降级 " + cfgThrottleDemoteMs + "ms: " + m.name);
    }

    /**
     * 当前线路已跌到 {@code currentBps}，换一条是否可能更好？
     *
     * <p>这是「反限速」最容易做错的地方：只看「相对自己掉了多少」就换线，会把
     * 一条被限到 5MB/s 的快线，换成一条本来就只有 1MB/s 的慢线——越换越慢。
     * 所以这里要求**换过去有实际收益**才放行：
     * <ul>
     *   <li>存在还没测过基准的线路 → 值得试一次（未知即机会）；</li>
     *   <li>或存在基准速度高于当前速度 {@code switch_gain_pct}% 的线路。</li>
     * </ul>
     * 两者都不满足时留在原地——被限速也好过换到更慢的线。
     */
    public static boolean worthSwitching(Mirror from, long currentBps) {
        List<Mirror> ok = healthy();
        for (Mirror m : ok) {
            if (m == from) continue;
            if (m.baselineBps <= 0) {
                CNLog.i(TAG, "存在未测速线路 " + m.name + "，值得一试");
                return true;
            }
            if (m.baselineBps > currentBps * cfgSwitchGainPct / 100L) {
                CNLog.i(TAG, "线路 " + m.name + " 基准 " + (m.baselineBps / 1024)
                        + " KB/s 明显快于当前 " + (currentBps / 1024) + " KB/s，值得换");
                return true;
            }
        }
        CNLog.i(TAG, "没有更快的线路可换（当前 " + (currentBps / 1024) + " KB/s），留在原地");
        return false;
    }

    /** 记一次成功：清空失败计数并解除冷却。 */
    public static void reportSuccess(Mirror m) {
        if (m == null) return;
        m.failures.set(0);
        m.cooldownUntilNs = 0L;
    }
}
