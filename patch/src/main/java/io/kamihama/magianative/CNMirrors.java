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
 * <p>线路列表从 {@link #MIRRORS_URL} 拉取；拉取失败或内容不可用时回退到
 * {@link #defaultList()} 那份**内置兜底线路表**，因此任何情况下都至少有一条
 * 可用线路。
 *
 * <p>内置那份的最后两条指向 GitHub Release 的公共代理，<b>与我们的域名、
 * 服务器、CDN 账号全都无关</b>——这是「服务器没了玩家也还能装、还能更新」这条
 * 路的落脚点。选它们不是因为快，而是因为它们不跟着我们一起死。
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

    // 代理配置**刻意不做任何缓存**，始终以本次启动读到的 config.json 为准。
    //
    // 曾经有过一份磁盘缓存（cn_proxy_config.tsv，8f6dba66），目的是让 native 在
    // JNI_OnLoad 就预读到代理配置，赶在引擎首个请求之前生效。但它带来一个更糟的
    // 失败模式：config.json 拉不到时，缓存既不更新也不删除，于是 native 每次启动
    // 都把请求重写到一个可能早已不存在的代理——而端点级重写**没有失败回退**
    // （改完就交给引擎连，native 根本不知道连没连上）。服务器一旦下线，玩家就
    // 永远连不上，而不是退回直连。
    //
    // 现在的语义是二值的、无中间状态：读到 config.json 且有 proxy 段就走代理，
    // 拉不到就直连；服务器回来时立刻恢复，也不存在「新旧两份配置互搏」。
    // 代价是首轮引擎请求不走代理（见下方 nativeSetProxyConfig 的调用点）。

    /**
     * 内置兜底线路：拉不到线路表时的默认可用下载路径。
     *
     * <p>这是「**从哪里取字节**」，可以随时换成任何一条可用线路，与文件的身份无关。
     *
     * <p>选 edge 是因为它在 2026-08-06 的真机竞速里是最快的一条
     * （861–984 KB/s，对香港 CDN 的 261–608 KB/s）。改这个常量**不需要**动
     * {@link #CANONICAL_BASE}，两者已经解耦——这正是它们分开的意义。
     */
    public static final String DEFAULT_BASE = "https://assets.example/";

    /**
     * 与我们的基础设施<b>完全无关</b>的兜底线路：公共 gh-proxy 转 GitHub Release。
     *
     * <p>域名过期、服务器关停、CDN 账号被封——只要 GitHub 与 gh-proxy 还在，
     * 玩家就还能装、还能更新。这是「服务器没了也能玩」这条路的最后一环。
     */
    static final String GITHUB_FALLBACK_V4 =
        "https://v4.gh-proxy.org/https://github.com/MagirecoCN-Revival-Project/"
        + "（外部发布渠道）/releases/download/latest/";
    static final String GITHUB_FALLBACK =
        "https://gh-proxy.org/https://github.com/MagirecoCN-Revival-Project/"
        + "（外部发布渠道）/releases/download/latest/";

    /**
     * 主线资源的**规范前缀**：判断「这是不是一条主线资源地址」、以及从地址里
     * 剥出文件名，都以它为准。
     *
     * <p><b>刻意与 {@link #DEFAULT_BASE} 分开，尽管两者当前取值相同。</b>
     * 它们是两个概念，混用会在换兜底线路时炸掉热更新：
     *
     * <ul>
     *   <li>{@link CNHotUpdate#download} 用它判断该不该换线。前缀对不上就返回
     *       null＝「非主线地址，直连下载」——热更包会**悄悄退化成不换线**。</li>
     *   <li>{@link CNHotUpdateCheck} 取版本 json 时用它剥文件名。前缀对不上，
     *       剥出来的就是整条 URL，再拼上镜像前缀会得到
     *       {@code https://<镜像>/https://r2.assets.example.test/version_js.json}
     *       这种东西——**每条线路都失败，热更静默停摆**。</li>
     * </ul>
     *
     * <p>热更包与版本 json 的地址硬编码在 {@link CNHotUpdateCheck} 的
     * {@code PACKAGES} 表里，改这个常量必须与那张表同步，否则前缀立刻对不上。
     *
     * <p><b>取值必须与 {@code CNDownloaderFix.RESOURCE_BASE_URL} 一致</b>——
     * 全仓库只该有一个规范前缀。安装器的完成标记、安装器文件表里的
     * {@code cn_scenario_update.zip} / {@code cn_js_update.zip}，用的都是它。
     *
     * <p>刻意<b>不</b>用任何具体 CDN 的域名（如 r2. / edge. 开头的那些）：
     * 这个串永远不会被真的请求——两处用它的地方都是「剥出文件名后逐条线路试」。
     * 拿某个 CDN 的域名当身份，那个 CDN 一停用，字符串就变成一句谎话。
     * 早先这里是 {@code r2.assets.example.test}，而 object-storage 自定义域只在 CDN
     * 接管 DNS 时才生效——换 NS 之后那个子域就彻底废了。
     *
     * <p><b>它解析不了也没关系，而且不许改。</b>身份标识不需要能被访问；
     * 更要紧的是，同一个串已经写进每一台已安装设备的 15 个完成标记里
     * （见 {@code CNDownloaderFix.RESOURCE_BASE_URL} 的说明），改动会让所有
     * 老玩家重下几个 GB。看到它 DNS 不通是正常的，不要「顺手修好」。
     */
    public static final String CANONICAL_BASE = "https://assets.example.test/";

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
    // 分片跨镜像并发：开（true）时同一文件的分片按轮转分给多条健康镜像，
    // 吞吐随线路数叠加；关（false）保持旧的「一次尝试只用一条线路」行为
    private static volatile boolean cfgChunksAcrossMirrors = false;
    // 首选镜像竞速：加载线路表后让前两条已启用镜像并发拉取同一探测文件的前
    // 若干字节，按**实际吞吐**定胜负（首字节延迟低≠下载快）。
    // 配置顺序写死的首选不一定是用户网络下最快的那条
    private static volatile boolean cfgMirrorRace = true;
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
    /** 分片是否跨镜像并发（settings.chunks_across_mirrors，默认关）。 */
    public static boolean chunksAcrossMirrors() { return cfgChunksAcrossMirrors; }

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

    /**
     * 内置兜底线路表：拉不到 {@code config.json} 时用的那份。
     *
     * <h3>为什么是一串而不是一条</h3>
     *
     * 这张表<b>唯一的用武之地就是降级场景</b>——config.json 一旦拉到，整张线路表
     * 就把它替换掉了。所以选线的标准不是「快」，而是<b>「我们的基础设施全没了，
     * 还能不能下到东西」</b>。
     *
     * <p>原先只有一条 {@code assets.example}，问题是它和 config.json
     * 所在的 {@code api.example.test} <b>是同一个域名下的子域</b>。「服务器没了」
     * 通常意味着整个域名一起没——2026-08-06 换 NS 那次，全域 24 小时无解析，就是
     * 一次实战演练。那种情况下这条兜底跟着一起死，等于没有兜底。
     *
     * <p>所以排成四条，按「越往后越不依赖我们」排列：
     *
     * <ol>
     *   <li>{@code edge.} / {@code esa.} —— 自有域名下的 CDN。config.json 只是
     *       一时抽风（网络抖动、api 短暂 502）时最快，覆盖绝大多数情况；</li>
     *   <li>{@code v4.gh-proxy.org} / {@code gh-proxy.org} —— 指向 GitHub Release
     *       的公共代理，<b>与我们的域名、服务器、CDN 账号全都无关</b>。哪怕域名
     *       过期、服务器关停，只要 GitHub 和 gh-proxy 还在，玩家就还能装、还能更新。</li>
     * </ol>
     *
     * <p>地址与线上 {@code config.json} 的 mirrors 保持一致；那边改了这里也要跟。
     * 顺序即优先级，{@code CNMirrors} 会按权重从高到低试。
     */
    private static List<Mirror> defaultList() {
        List<Mirror> l = new ArrayList<Mirror>(4);
        // 权重只决定内置表内部的先后，config.json 到位后整张表会被替换
        l.add(new Mirror("内置兜底 • edge", DEFAULT_BASE, 100, 0, true));
        l.add(new Mirror("内置兜底 • 阿里ESA",
                "https://esa.assets.example.test/", 80, 0, true));
        l.add(new Mirror("内置兜底 • gh-proxy v4", GITHUB_FALLBACK_V4, 60, 0, true));
        l.add(new Mirror("内置兜底 • gh-proxy", GITHUB_FALLBACK, 40, 0, true));
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
            configState = 1;
            StringBuilder sb = new StringBuilder();
            for (Mirror m : parsed) sb.append(' ').append(m.name).append('=').append(m.base);
            CNLog.i(TAG, "线路列表已加载 count=" + parsed.size() + sb);
            // 署名优先刷新：署名只依赖 config.json 的 ui_credits，与镜像竞速无关，
            // 先让用户看到（竞速只是优化首选顺序，不参与署名渲染）
            try {
                CNCNDownloadUI.refreshCredits(RestClient.getCurrentActivity());
            } catch (Throwable ignore) {}
            // 前两条镜像竞速放后台：同步执行时 join(3s) 会拖住署名刷新/线路加载
            // （可在 settings.mirror_race=false 关闭）
            if (cfgMirrorRace) {
                final Thread raceThread = new Thread(new Runnable() {
                    @Override public void run() { raceTopMirrors(); }
                }, "cnv-mirror-race");
                raceThread.setDaemon(true);
                raceThread.start();
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "拉取线路列表失败，沿用默认线路: " + t);
            // 拉取失败：通知浮层从「加载中」占位回落到内置默认署名
            configState = 2;
            try {
                CNCNDownloadUI.refreshCredits(RestClient.getCurrentActivity());
            } catch (Throwable ignore) {}
        }
    }

    /**
     * 解析 {@code proxy.lines}——WebView 拦截层代理的线路表。
     *
     * <p>缺省或全被过滤光时，从 {@code proxy.base} 合成一条（名字就叫「默认代理」），
     * 这样老配置一个字都不用改。
     *
     * <p>校验与 {@code mirrors} 同规格：必须 http(s) 开头，强制以 '/' 结尾——
     * 否则 {@code rewriteWith} 会拼出 {@code …/stream<host>/path} 这种坏 URL。
     *
     * <p><b>它和 {@code mirrors} 没有任何关系，也不会去读 mirrors。</b>
     * 那边是公共 CDN 分发静态文件，转发不了 API；见 {@link CNWebProxy.Line} 的注释。
     */
    private static CNWebProxy.Line[] parseProxyLines(JSONObject proxy, String fallbackBase) {
        java.util.ArrayList<CNWebProxy.Line> out = new java.util.ArrayList<CNWebProxy.Line>();
        JSONArray arr = proxy.optJSONArray("lines");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String b = normalizeBase(o.optString("base", "").trim());
                if (b.isEmpty()) continue;
                String n = o.optString("name", "").trim();
                if (n.isEmpty()) n = b;
                out.add(CNWebProxy.newLine(n, b, o.optInt("weight", 50),
                                           o.optBoolean("enabled", true)));
            }
        }
        if (out.isEmpty()) {
            String b = normalizeBase(fallbackBase);
            if (b.isEmpty()) return null;
            out.add(CNWebProxy.newLine("默认代理", b, 100, true));
        }
        return out.toArray(new CNWebProxy.Line[0]);
    }

    /**
     * 校验 scheme 并强制以 '/' 结尾；不合格返回空串。
     *
     * <h3>为什么只收 https，明文 http 一律拒绝</h3>
     *
     * 本项目的完整性前提是「DNSSEC + 完整 TLS 验证都开着，能在这种情况下劫持
     * 约等于服务器已被攻破」。而这个前提<b>本身就是 config.json 能关掉的</b>——
     * 只要往 {@code mirrors[].base} 或 {@code proxy.base} 里填一个 {@code http://}，
     * TLS 就整个不参与了，防线被它要防的东西一句话解除。
     *
     * <p>后果不只是「被人看见下了什么」。安装器那 15 个基础包<b>没有 md5/sha 校验</b>
     * （只有热更包有 {@code verifyZip}），完整性全押在 TLS 上；
     * 而 {@code extractChecked} 只验结构不验内容。于是：
     *
     * <pre>
     * 配置被改 → 明文线路 → 投毒 zip → 结构合法照单全收
     *   → 恶意 JS 落进 &lt;files&gt;/magica/js/
     *   → WebView 本地优先且热更只写不删，永久执行
     *   → androidCommand.jsCallback 进 native
     * </pre>
     *
     * <p>拒收 http 就把这条链在第一环切断，代价是零：线上六条线路本来全是 https。
     */
    private static String normalizeBase(String b) {
        if (b == null) return "";
        b = b.trim();
        if (b.isEmpty()) return "";
        // 控制字符（含换行）会让后续拼出的 URL 变成两条请求，直接拒
        for (int i = 0; i < b.length(); i++) {
            if (b.charAt(i) < 0x20 || b.charAt(i) == 0x7f) return "";
        }
        String lower = b.toLowerCase(java.util.Locale.US);
        if (!lower.startsWith("https://")) return "";
        if (lower.length() <= "https://".length()) return "";   // 只有 scheme
        return b.endsWith("/") ? b : (b + "/");
    }

    /**
     * 代理域名白名单的最小粒度校验。
     *
     * <p>{@code proxy.domains} 是<b>后缀</b>匹配（{@code magi-reco.com} 命中
     * {@code dorothy.magi-reco.com}）。没有下限的话，填一个 {@code "com"} 就能
     * 把玩家所有 {@code .com} 流量吸进代理——配置被改时这是个极便宜的全量劫持。
     *
     * <p>所以要求至少两段（含一个点），每段非空，且不是裸的公共后缀。
     * 这不能挡住所有情况（{@code co.uk} 这类多级公共后缀仍会通过），但把
     * 「一个词吸走整个顶级域」这种最省事的攻击拦掉了。
     */
    private static boolean isSaneProxyDomain(String d) {
        if (d == null) return false;
        d = d.trim().toLowerCase(java.util.Locale.US);
        if (d.isEmpty() || d.length() > 253) return false;
        if (d.charAt(0) == '.' || d.charAt(d.length() - 1) == '.') return false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
            if (!ok) return false;
        }
        String[] labels = d.split("\\.", -1);
        if (labels.length < 2) return false;              // 裸 TLD，例如 "com"
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].isEmpty()) return false;        // 连续的点
        }
        // 常见的两级公共后缀，直接点名拒掉
        if ("com.cn".equals(d) || "net.cn".equals(d) || "org.cn".equals(d)
                || "gov.cn".equals(d) || "co.uk".equals(d) || "co.jp".equals(d)
                || "com.au".equals(d) || "pages".equals(d)
                || "github.io".equals(d) || "vercel.app".equals(d)) {
            return false;
        }
        return true;
    }

    /** 是否成功加载过远端线路列表。 */
    public static boolean isLoaded() { return loaded; }

    /** 后台重试只跑一轮，重复调用无副作用。 */
    private static final java.util.concurrent.atomic.AtomicBoolean RETRY_STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 退避表（毫秒）。总跨度约 2 分钟——网络就绪一般是秒级的事，拖太久没意义。 */
    private static final long[] RETRY_BACKOFF_MS = { 3000L, 6000L, 12000L, 24000L, 48000L, 48000L };

    /**
     * 拉不到 config.json 时在后台带退避重试，直到成功或退避表用完。
     *
     * <h3>为什么需要它（2026-08-07 真机 0117）</h3>
     *
     * 那次启动里 6 次 {@code refresh} 全部以
     * {@code SSLHandshakeException: connection closed} 失败，而且<b>全挤在同一秒</b>
     * （开机后约 1 秒），{@code CNVersion} 也在同一刻挂掉。8 秒后 WebView 拉
     * {@code dorothy.magi-reco.com} 一切正常——**网络那会儿只是还没就绪**。
     *
     * <p>问题在于调用方那对「先代理、失败再直连」是背靠背发的，两次相隔几毫秒，
     * 网络没起来时必然一起失败；而 {@link #refresh} 本身没有任何重试。结果就是
     * 整场会话跑在内置默认线路上，`proxy` 段也从来没下发过——
     * {@link CNWebProxy} 拿不到配置，一直是 off。
     *
     * <p>失败是静默的（只有一行 WARN），后果却是全局的，所以值得单独补这一层。
     * 成功后 {@link #parse} 会照常调 {@code CNWebProxy.configure}，配置迟到但会到；
     * 拦截层读的是 volatile 的 mode，中途变更即时生效，不需要额外协调。
     */
    public static void ensureLoadedAsync() {
        try {
            if (loaded) return;
            if (!RETRY_STARTED.compareAndSet(false, true)) return;
            Thread t = new Thread(new RetryLoader(), "cnv-mirrors-retry");
            t.setDaemon(true);
            t.start();
        } catch (Throwable t) {
            CNLog.w(TAG, "线路表重试线程起不来（沿用默认线路）: " + t);
        }
    }

    /** 具名静态类而非匿名类：避开 d8 对嵌套匿名类的老毛病。 */
    private static final class RetryLoader implements Runnable {
        @Override public void run() {
            for (int i = 0; i < RETRY_BACKOFF_MS.length; i++) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS[i]);
                } catch (InterruptedException ie) {
                    return;
                }
                if (loaded) return;
                CNLog.i(TAG, "线路表仍未加载，第 " + (i + 1) + " 次重试");
                try {
                    refresh(false);
                    if (!loaded) refresh(true);
                } catch (Throwable ignore) {}
                if (loaded) {
                    CNLog.i(TAG, "线路表在第 " + (i + 1) + " 次重试后加载成功");
                    return;
                }
            }
            CNLog.w(TAG, "线路表重试 " + RETRY_BACKOFF_MS.length
                         + " 次仍失败，本次启动沿用内置默认线路（代理配置也不会下发）");
        }
    }

    /**
     * 首选镜像竞速：前两条已启用镜像并发拉取同一真实文件的前 {@link #RACE_BYTES}
     * 字节，按实测吞吐（bytes/sec）定胜负——首字节延迟只反映 RTT，
     * 反映不了 CDN 的限速/拥塞。
     * 全程 ~7 秒封顶，任何异常都静默——竞速只是优化，输了不影响可用性。
     */
    private static final String RACE_PROBE     = "cn_js_update.zip";  // 各镜像都有的小包
    private static final int    RACE_BYTES     = 256 * 1024;          // 评估吞吐的取样量
    private static final int    RACE_MIN_BYTES = 64 * 1024;           // 不足此量视为无效样本
    private static final int    RACE_CAP_MS    = 6000;                // 单条线路的取样上限

    private static void raceTopMirrors() {
        final List<Mirror> cur = mirrors;
        final java.util.List<Mirror> enabled = new ArrayList<Mirror>();
        for (Mirror m : cur) if (m.enabled) enabled.add(m);
        if (enabled.size() < 2) return;

        final Mirror a = enabled.get(0), b = enabled.get(1);
        final long[] speed = new long[]{-1L, -1L};
        Thread[] ts = new Thread[2];
        for (int k = 0; k < 2; k++) {
            final Mirror m = (k == 0) ? a : b;
            final int idx = k;
            ts[k] = new Thread(new Runnable() {
                @Override public void run() {
                    speed[idx] = measureMirrorSpeed(m);
                }
            }, "cnv-mirror-race-" + idx);
            ts[k].setDaemon(true);
            ts[k].start();
        }
        try {
            ts[0].join(RACE_CAP_MS + 1500L);
            ts[1].join(RACE_CAP_MS + 1500L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        CNLog.i(TAG, "镜像竞速(吞吐): " + a.name + "=" + (speed[0] / 1024) + "KB/s, "
                + b.name + "=" + (speed[1] / 1024) + "KB/s");
        // 平手或 a 更快都维持原顺序；只有 b 明确更快才换
        if (speed[1] > 0 && speed[1] > speed[0]) {
            List<Mirror> reordered = new ArrayList<Mirror>(cur.size());
            reordered.add(b);
            for (Mirror m : cur) if (m != b) reordered.add(m);
            mirrors = reordered;
            CNLog.i(TAG, "镜像竞速: " + b.name + " 吞吐更高，提为首选（原首选 " + a.name + "）");
        } else {
            CNLog.i(TAG, "镜像竞速: 首选 " + a.name + " 保持");
        }
    }

    /** 拉取探测文件的前 RACE_BYTES 字节并返回实测吞吐（bps）；失败/样本不足返回 -1。 */
    private static long measureMirrorSpeed(Mirror m) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(m.urlFor(RACE_PROBE)).openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setUseCaches(false);
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code / 100 != 2) return -1L;
            InputStream in = new java.io.BufferedInputStream(c.getInputStream(), 65536);
            byte[] buf = new byte[65536];
            long got = 0L;
            long t0 = System.nanoTime();
            while (got < RACE_BYTES) {
                int want = (int) Math.min(buf.length, RACE_BYTES - got);
                int n = in.read(buf, 0, want);
                if (n < 0) break;
                if (n == 0) continue;
                got += n;
                if (System.nanoTime() - t0 > RACE_CAP_MS * 1000000L) break;
            }
            try { in.close(); } catch (Throwable ignore) {}
            if (got < RACE_MIN_BYTES) return -1L;
            long dt = System.nanoTime() - t0;
            return (long) (got * 1.0E9d / dt);
        } catch (Throwable t) {
            return -1L;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /** 配置加载状态：0=拉取中（未见结果） 1=成功 2=失败（本轮拉取没拿到）。 */
    public static volatile int configState = 0;

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

    /** 远端 right_pill（右上角可变按钮）配置；未配置时 GitHub 胶囊保持默认。 */
    private static volatile JSONObject rightPill;

    /** 远端 right_pill；未配置时为 null。 */
    public static JSONObject rightPill() { return rightPill; }

    /**
     * 下发 Totentanz 代理配置到 native setURI hook（MagiaLegacy.cpp 实现）。
     *
     * @param base    代理入口前缀，如 "https://api.example.test/stream/"
     * @param domains 要代理的域名后缀白名单，如 {"magi-reco.com", "sisyphus.systems"}
     */
    private static native void nativeSetProxyConfig(String base, String[] domains);

    /** Java 侧代理入口前缀（供 SNAA 等 Java 网络请求改写）；未配置为 null。 */
    private static volatile String proxyBase;

    /** Java 侧域名后缀白名单（供 {@link CNWebProxy} 判断该不该改写）；未配置为 null。 */
    private static volatile String[] proxyDomains;

    /** Java 侧代理入口前缀；未配置为 null。 */
    public static String proxyBase() { return proxyBase; }

    /** Java 侧域名后缀白名单；未配置为 null。调用方不要改返回的数组。 */
    public static String[] proxyDomains() { return proxyDomains; }

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
        // 不写 Connection: close——保留 keep-alive 复用连接池，
        // 分片/重试接连不断时省掉每段一次的 TCP+TLS 握手
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

        // 右上角可变按钮（可选）：见 rightPill()。未配置时 GitHub 胶囊保持默认。
        rightPill = root.optJSONObject("right_pill");

        // Totentanz 代理配置（可选）：proxy.base 为代理入口前缀，
        // proxy.domains 为要代理的域名后缀白名单。下发到 native setURI hook；
        // 缺失或为空时客户端不代理、直连（兼容旧版）。
        JSONObject proxy = root.optJSONObject("proxy");
        if (proxy != null) {
            // 与 mirrors 同一套校验：只收 https、强制以 '/' 结尾（见 normalizeBase）。
            // 结尾的 '/' 是必需的，否则 C++ tryRewriteUrl 会拼出
            // "…/stream<host>/path" 这类坏 URL。
            String pbaseRaw = proxy.optString("base", "").trim();
            String pbase = normalizeBase(pbaseRaw);
            if (pbase.isEmpty() && !pbaseRaw.isEmpty()) {
                CNLog.w(TAG, "忽略 proxy.base（非 https 或格式不合法）: " + pbaseRaw);
            }
            JSONArray pdomains = proxy.optJSONArray("domains");
            String[] pdom = null;
            if (pdomains != null && pdomains.length() > 0) {
                java.util.ArrayList<String> list = new java.util.ArrayList<String>();
                for (int i = 0; i < pdomains.length(); i++) {
                    String d = pdomains.optString(i, "").trim();
                    if (d.isEmpty()) continue;
                    // 粒度太粗的后缀会把无关流量一起吸进代理，见 isSaneProxyDomain
                    if (!isSaneProxyDomain(d)) {
                        CNLog.w(TAG, "忽略 proxy.domains 条目（粒度过粗或格式不合法）: " + d);
                        continue;
                    }
                    list.add(d);
                }
                if (!list.isEmpty()) pdom = list.toArray(new String[0]);
            }
            // WebView 拦截层代理的模式（off / measure / on），缺省 off。
            // 端点级代理在真机上一次都没命中过（见 CNWebProxy 的类注释），
            // 拦截层是另一条独立的路，两者互不影响，各读各的开关。
            String pwebMode = proxy.optString("web_mode", "off").trim();

            // 代理线路表（可选）：proxy.lines。缺省时从 proxy.base 合成一条，
            // 老配置行为不变。
            //
            // ⚠ 这张表和 mirrors 是两回事，绝不能互相顶替：mirrors 里绝大多数是
            // 公共 CDN，只分发我们放上去的静态文件，根本不会转发 API 请求。
            // 详见 CNWebProxy.Line 的类注释。
            CNWebProxy.Line[] plines = parseProxyLines(proxy, pbase);
            // native 侧只吃单个 base（改它要动 .so，不值当）。没写 base 但配了 lines
            // 时，就把权重最高那条给它——两边至少指向同一台机器。
            if (pbase.isEmpty() && plines != null && plines.length > 0) {
                pbase = plines[0].base;
            }
            if (!pbase.isEmpty() && pdom != null && pdom.length > 0) {
                proxyBase = pbase;   // Java 侧保留，供 SNAA 等 Java 网络请求改写
                proxyDomains = pdom;
                try {
                    nativeSetProxyConfig(pbase, pdom);
                    CNLog.i(TAG, "代理配置已下发 base=" + pbase + " domains=" + pdom.length);
                } catch (Throwable t) {
                    CNLog.w(TAG, "nativeSetProxyConfig 调用失败（代理不生效）: " + t);
                }
            } else {
                // 云端拿掉了 proxy 配置 —— 什么都不用做：代理只在本次启动成功读到
                // config.json 且其中有 proxy 段时才生效，没有任何持久状态要清。
                CNLog.i(TAG, "config.json 未配置 proxy，本次启动直连");
            }
            try {
                CNWebProxy.configure(plines, proxyDomains, pwebMode);
            } catch (Throwable t) {
                CNLog.w(TAG, "拦截层代理配置下发失败（保持透传直连）: " + t);
            }
        }

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
            cfgChunksAcrossMirrors = st.optBoolean("chunks_across_mirrors", cfgChunksAcrossMirrors);
            cfgMirrorRace = st.optBoolean("mirror_race", cfgMirrorRace);
        }

        JSONArray arr = root.optJSONArray("mirrors");
        List<Mirror> out = new ArrayList<Mirror>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String base = normalizeBase(o.optString("base", "").trim());
            if (base.isEmpty()) {
                CNLog.w(TAG, "忽略线路（非 https 或格式不合法）: " + o.optString("base", ""));
                continue;
            }
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
