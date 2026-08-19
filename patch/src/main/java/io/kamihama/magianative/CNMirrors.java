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
 * <p>内置那份如今<b>全部落在自有主域下</b>（2026-08-14 去掉了经公共代理转
 * 代码托管站的那两条）。代价是「我们的域名整个没了也还能装」这条最后兜底
 * 不复存在；补偿手段是线上线路表：它随时可改、下发即生效，而内置表只有发
 * 新版才动得了。
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

    /** 线路列表地址。真实主机名构建期注入，见 {@link CNEndpoints}。 */
    public static final String MIRRORS_URL = CNEndpoints.MIRRORS_URL;

    // 代理配置**刻意不做任何缓存**，始终以本次启动读到的 config.json 为准。
    //
    // 曾经有过一份磁盘缓存（cn_proxy_config.tsv，7d108b10），目的是让 native 在
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
     *
     * <p>真实主机名构建期注入，见 {@link CNEndpoints#EDGEONE_BASE}。
     */
    public static final String DEFAULT_BASE = CNEndpoints.EDGEONE_BASE;

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
     *       {@code https://<镜像>/https://<另一个域>/version_js.json}
     *       这种东西——**每条线路都失败，热更静默停摆**。</li>
     * </ul>
     *
     * <p>{@link CNHotUpdateCheck} 的 {@code PACKAGES} 表里只写<b>文件名</b>，
     * 完整地址由本常量当场拼出来——所以「表和前缀对不上」这件事在结构上就不
     * 可能发生了。早先那张表里写的是整条 URL，两边任何一边单独改动就会静默
     * 失效，正是本段警告的由来。
     *
     * <p><b>取值必须与 {@code CNDownloaderFix.RESOURCE_BASE_URL} 一致</b>——
     * 全仓库只该有一个规范前缀。安装器的完成标记、安装器文件表里的
     * {@code cn_scenario_update.zip} / {@code cn_js_update.zip}，用的都是它。
     *
     * <p>刻意<b>不</b>用任何具体 CDN 的域名（如 r2. / edge. 开头的那些）：
     * 这个串永远不会被真的请求——两处用它的地方都是「剥出文件名后逐条线路试」。
     * 拿某个 CDN 的域名当身份，那个 CDN 一停用，字符串就变成一句谎话。
     * 早先这里绑的是 object-storage 的自定义子域，而 object-storage 自定义域只在 CDN 接管 DNS
     * 时才生效——换 NS 之后那个子域就彻底废了。
     *
     * <p><b>它解析不了也没关系，而且不许改。</b>身份标识不需要能被访问；
     * 更要紧的是，同一个串已经写进每一台已安装设备的 15 个完成标记里
     * （见 {@code CNDownloaderFix.RESOURCE_BASE_URL} 的说明），改动会让所有
     * 老玩家重下几个 GB。看到它 DNS 不通是正常的，不要「顺手修好」。
     *
     * <p>真实主机名构建期注入（{@link CNEndpoints#ASSETS_BASE}）。注入值被
     * {@code tools/check-base-urls.py} 用<b>钉死的 sha256</b> 核对——钉哈希
     * 而不是钉明文，是为了不把地址写回仓库；而校验强度不打折：差一个字符就
     * 对不上，构建当场失败，不会产出让老玩家重下几个 GB 的包。
     */
    public static final String CANONICAL_BASE = CNEndpoints.ASSETS_BASE;

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS    = 3000;
    /** 线路列表响应体大小上限，防止异常内容撑爆内存。 */
    private static final int MAX_JSON_BYTES     = 256 * 1024;

    // ---- 可被线路列表覆盖的全局参数（含默认值） ----
    private static volatile int  cfgMaxDownloads     = 4;   // settings.max_downloads
    private static volatile int  cfgChunks            = 4;
    private static volatile long cfgMinChunkBytes     = 8L * 1024 * 1024;
    private static volatile int  cfgSwitchAfterFail   = 1;
    private static volatile int  cfgStallSeconds      = 25;
    private static volatile int  cfgMinSpeedKbps      = 32;
    private static volatile long cfgCooldownMs        = 60_000L;
    // 分片跨镜像并发：开（true）时同一文件的分片按轮转分给多条健康镜像，
    // 吞吐随线路数叠加；关（false）保持旧的「一次尝试只用一条线路」行为
    private static volatile boolean cfgChunksAcrossMirrors = false;
    // 强制启用 aria2 备用引擎（settings.force_aria2，默认关）：云端控制——
    // 自带引擎修不动时服务器一键切备用引擎，不用等客户端发版。
    private static volatile boolean cfgForceAria2 = false;
    // 强制单线程可靠下载（settings.force_single_thread，默认关）：某条 CDN
    // 对所有人都炸了时，服务端一改所有人生效，不必等发版。见 CNDownloadMode。
    private static volatile boolean cfgForceSingleThread = false;
    // 离线包静态站地址（settings.offline_url）：下载引擎不可靠时引导玩家手动取包。
    private static volatile String cfgOfflineUrl = "";
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

    /**
     * 安装器/下载池并发数（settings.max_downloads，默认 4）。
     * F-013：校验内联在下载任务里，池子需容纳「下载 + 校验 + 解压」的混合负载，
     * 云端可调大（如 6-8）避免校验占满全部线程拖慢剩余包的下载。
     */
    public static int maxDownloads() { return cfgMaxDownloads; }

    public static int  chunks()          { return cfgChunks; }
    public static long minChunkBytes()   { return cfgMinChunkBytes; }
    public static int  switchAfterFail() { return cfgSwitchAfterFail; }
    public static int  stallSeconds()    { return cfgStallSeconds; }
    public static int  minSpeedKbps()    { return cfgMinSpeedKbps; }
    /** 分片是否跨镜像并发（settings.chunks_across_mirrors，默认关）。 */
    public static boolean chunksAcrossMirrors() { return cfgChunksAcrossMirrors; }

    /** 是否强制启用 aria2 备用引擎（settings.force_aria2，默认关）。 */
    public static boolean forceAria2() { return cfgForceAria2; }

    /**
     * 是否强制单线程可靠下载（settings.force_single_thread，默认关）。
     *
     * <p>与 force_aria2 同一个位置、同一套语义：某条 CDN 对所有人都炸了的时候，
     * 服务端改一下所有人生效，不必等发版。开着时玩家在失败弹窗里关不掉——
     * 那个场合恰恰是「我们已经知道多线程在这里不行」。
     */
    public static boolean forceSingleThread() { return cfgForceSingleThread; }

    /** 离线包静态站地址（settings.offline_url，空串=未配置）。 */
    public static String offlineUrl() { return cfgOfflineUrl; }

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
         * 观测到的基准速度（字节/秒），用 EMA 平滑而非历史峰值，见 reportBaseline。
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
     * <p>原先只有一条 edge，问题是它和 config.json 所在的 api 子域
     * <b>同属一个主域</b>。「服务器没了」
     * 通常意味着整个域名一起没——2026-08-06 换 NS 那次，全域 24 小时无解析，就是
     * 一次实战演练。那种情况下这条兜底跟着一起死，等于没有兜底。
     *
     * <p>所以排成四条，按「越往后越不依赖我们」排列：
     *
     * <ol>
     *   <li>{@code edge.} / {@code esa.} —— 自有域名下的 CDN。config.json 只是
     *       一时抽风（网络抖动、api 短暂 502）时最快，覆盖绝大多数情况；</li>
     * </ol>
     *
     * <p><b>内置表里不再有任何指向代码托管站的线路</b>（2026-08-14 去掉）。
     * 代价是丢了「我们的域名与 CDN 全挂掉也还能装」那条最后兜底——现在内置表
     * 全部落在自有域名下，域名没了内置表就全灭。补偿手段是线上 config.json：
     * 它随时可改，新线路下发即生效，而内置表只有发新版才动得了。
     *
     * <p>地址与线上 {@code config.json} 的 mirrors 保持一致；那边改了这里也要跟。
     * 顺序即优先级，{@code CNMirrors} 会按权重从高到低试。
     */
    private static List<Mirror> defaultList() {
        List<Mirror> l = new ArrayList<Mirror>(2);
        // 权重只决定内置表内部的先后，config.json 到位后整张表会被替换。
        // 注入缺失时两条前缀都是空串——此时**一条都不加**，返回空表。空表比
        // 「以空串为前缀的线路」好：后者会去请求 "cn_base_02.zip" 这种相对
        // 地址，报出来的错莫名其妙；空表则直接走「没有可用线路」那条既有分支。
        if (!DEFAULT_BASE.isEmpty()) {
            l.add(new Mirror("内置兜底 • edge", DEFAULT_BASE, 100, 0, true));
        }
        if (!CNEndpoints.ESA_BASE.isEmpty()) {
            l.add(new Mirror("内置兜底 • 阿里ESA", CNEndpoints.ESA_BASE, 80, 0, true));
        }
        return l;
    }

    /**
     * 拉取并解析线路列表。失败时保留当前（或默认）线路表，不抛异常。
     *
     * @param direct true 时绕过系统代理直连
     */
    public static synchronized void refresh(boolean direct) {
        try {
            if (CNDebugFlags.isOn(CNDebugFlags.SKIP_MIRROR_CONFIG)) {
                CNLog.i(TAG, "调试开关 skipMirrorConfig 生效，不拉 config.json，沿用内置线路");
                return;
            }
            if (CNDebugFlags.isOn(CNDebugFlags.FAIL_CONFIG_FETCH)) {
                // 造一个与真实失败同形的异常：走的是同一条 catch，
                // 所以退避重试、以及退避跑完那个「再试一次 / 用内置线路」的
                // 询问框，都能照常验到。
                throw new java.io.IOException("[DEBUG] failConfigFetch 注入的失败");
            }
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
                String raw = o.optString("base", "").trim();
                String b = normalizeBase(raw);
                if (b.isEmpty()) {
                    // 静默跳过会让「配了却不生效」变成哑谜。宁可吵一行。
                    CNLog.w(TAG, "忽略代理线路（非 https 或格式不合法）: "
                                 + o.optString("name", "?") + " base=" + raw);
                    continue;
                }
                String n = o.optString("name", "").trim();
                if (n.isEmpty()) n = b;
                out.add(CNWebProxy.newLine(n, b, o.optInt("weight", 50),
                                           o.optBoolean("enabled", true)));
            }
        }
        if (out.isEmpty()) {
            if (arr != null && arr.length() > 0) {
                CNLog.w(TAG, "proxy.lines 配了 " + arr.length()
                             + " 条但没有一条合法，回落到 proxy.base");
            }
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

    /**
     * 退避表（毫秒）。总跨度约 2 分钟——网络就绪一般是秒级的事，拖太久没意义。
     *
     * <p>第一档 2 秒是有讲究的：收浮层前的 config 到位窗口只有 3 秒
     * （CNHotUpdateCheck.CONFIG_SETTLE_MS）。第一档若比窗口长，「首载失败→
     * 重试成功」这条路径永远赶不上窗口，署名区照样落空——2026-08-07 那次
     * 真机（开机 1 秒网络未就绪、约 8 秒就绪）要能被覆盖，第一档必须短于
     * 窗口。
     */
    private static final long[] RETRY_BACKOFF_MS = { 2000L, 15000L, 45000L, 90000L };

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

    /** 首载 + 退避重试的循环体。 */
    private static final class RetryLoader implements Runnable {
        @Override public void run() {
            // i=0 是**首载**，立即发请求；退避只加在失败后的重试之间。
            //
            // 原先循环无条件先 sleep(RETRY_BACKOFF_MS[0])（改动前的旧值）再发第一次
            // 请求——首载也被当成了重试。后果：热更「已是最新」这类秒退路径
            // 两三百毫秒查完版本、900ms 驻留后就拆浮层，而 config.json 要到
            // 第 5 秒才发请求，署名区永远停在「加载中」就随浮层一起消失了。
            // 退避防的是「开机网络未就绪时的背靠背轰炸」，不是把首载也推迟；
            // 网络没起来时首次失败，后面的退避重试照样兜得住。
            for (int i = 0; i <= RETRY_BACKOFF_MS.length; i++) {
                if (i > 0) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS[i - 1]);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    if (loaded) return;
                    CNLog.i(TAG, "线路表仍未加载，第 " + i + " 次重试");
                }
                try {
                // Remote config is optional. Use the Android/system route once; if it
                // fails, keep built-in mirrors and retry later in the background.
                refresh(false);
                } catch (Throwable ignore) {}
                if (loaded) {
                    CNLog.i(TAG, i == 0 ? "线路表首次拉取成功"
                                        : "线路表在第 " + i + " 次重试后加载成功");
                    return;
                }
                // refresh 的 catch 每次失败都把 configState 拨成 2（终态失败），
                // 但只要还有下一次重试，对外语义就仍是「加载中」：收浮层的到位
                // 窗口（awaitConfigSettled 只等 state==0）若在首次失败那一瞬
                // 就看到 2，会立刻放行、拆掉浮层——等退避后重试成功时
                // refreshCredits 已无人接收，署名还是落空。那正是本循环要
                // 覆盖的场景，所以把状态拨回 0，让窗口继续等满它的时间。
                if (i < RETRY_BACKOFF_MS.length) configState = 0;
            }
            // 退避表用完仍然没拉到。到这一步为止玩家什么都不知道——原先只有一行
            // WARN 进日志，而后果是全局的（整场会话跑内置线路、proxy 段从未下发）。
            // 这正是「静默失败」最讨厌的地方：出了事没人知道，只能靠事后翻日志。
            //
            // 注意这里问的**不是**「要不要继续等」。线路表从设计上就不在启动关键
            // 路径里（见 ensureLoadedAsync 的说明：内置默认线路从进程启动起就可用，
            // 线路表服务故障绝不能卡住启动），没有任何人在等它——问「继续等
            // 吗」是个假选择。真正的取舍是「再试一次，还是就用内置线路过日子」。
            askAfterExhausted();
        }
    }

    /**
     * 退避表用完之后问玩家一次：再试，还是用内置线路继续。
     *
     * <p>只在浮层还在时才问得成——浮层收了之后玩家已经在游戏里，这时候弹框既打扰
     * 又没意义，退回记一行日志。{@code askSlowNetwork} 自己会处理「浮层不在」和
     * 「被在 UI 线程上调用」两种情况并返回 SKIP，所以这里不必重复判断。
     *
     * <p>选「再试一次」就把退避表整轮重跑（RETRY_STARTED 复位后再起一趟）。
     * 不做无限循环：每一轮结束都会再问一次，要不要继续始终由玩家定。
     */
    private static void askAfterExhausted() {
        try {
            android.app.Activity act = RestClient.getCurrentActivity();
            int choice = CNCNDownloadUI.askSlowNetwork(act, "线路表",
                    "一直没能取到线路表（config.json）",
                    "再试一次", "用内置线路",
                    "重新按退避节奏试一轮。网络刚恢复时选这个。",
                    "本次启动用内置默认线路继续，下载可能慢一些；重启游戏会再试。",
                    0L);
            if (choice == CNCNDownloadUI.SLOW_WAIT) {
                CNLog.i(TAG, "玩家选择重试线路表，重跑一轮退避");
                RETRY_STARTED.set(false);
                ensureLoadedAsync();
            } else {
                CNLog.w(TAG, "线路表重试 " + RETRY_BACKOFF_MS.length
                             + " 次仍失败，本次启动沿用内置默认线路（代理配置也不会下发）");
            }
        } catch (Throwable t) {
            CNLog.w(TAG, "线路表失败询问出错，沿用内置默认线路: " + t);
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
            CNUserAgent.apply(c);
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

    /**
     * 配置加载状态：0=未见最终结果（拉取中，或失败但还有退避重试在路上）
     * 1=成功 2=最终失败（重试也用完，或没人会再试）。
     *
     * <p>「失败但还会重试」必须留在 0：收浮层前的到位窗口
     * （CNHotUpdateCheck.awaitConfigSettled）只等 0，2 会立刻放行。
     */
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
     * @param base    代理入口前缀，如 "https://<api 子域>/stream/"
     * @param domains 要代理的域名后缀白名单，如 {"magi-reco.com", "sisyphus.systems"}
     */
    private static native void nativeSetProxyConfig(String base, String[] domains);

    /** Java 侧代理入口前缀（供 SNAA 等 Java 网络请求改写）；未配置为 null。 */
    private static volatile String proxyBase;

    /** Java 侧代理入口前缀；未配置为 null。 */
    public static String proxyBase() { return proxyBase; }

    /** 非 JSON 响应体在异常信息里保留多少字符。够认出是谁返回的就行。 */
    private static final int BODY_PEEK_CHARS = 200;

    /**
     * 响应体不像 JSON 时抛出，<b>并把响应体开头带上</b>。
     *
     * <h3>为什么非做不可</h3>
     *
     * 原先直接 {@code new JSONObject(body)}，拿到 HTML 时抛的是
     * {@code JSONException: Value <html> of type java.lang.String cannot be
     * converted to JSONObject}——只说明「不是 JSON」，而<b>响应体被丢掉了</b>。
     *
     * <p>2026-08-08 真机上连续四次拿到 HTML，日志里就只有这一句。那页 HTML 本来
     * 会写明是谁拦的（系统代理/VPN 的错误页、WAF 挑战页、运营商门户、CDN 错误页
     * ……），是唯一能定位的线索，却被扔了，排查因此停在「不知道是什么东西返回的」。
     *
     * <p>这<b>不是</b>说服务端返回错了：本类与 {@code CNVersionCheck} 都刻意尊重
     * 系统代理（显式 {@code NO_PROXY} 会绕开用户已配好的代理链，那才是更早一次
     * 真机长超时的成因），所以中途多一跳是被允许的——正因如此，出问题时更要说清楚
     * 是哪一跳。
     */
    static void requireJsonBody(String body, String contentType) throws IOException {
        String s = (body == null) ? "" : body.trim();
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1).trim();   // BOM
        if (s.startsWith("{")) {
            // 「以 { 开头」不等于能解析：{<broken 这类截断/损坏体会放过首字符
            // 守卫，随后在调用方 new JSONObject 抛出没有证据的老式异常——
            // 证据保留就白做了。在这里先试解析一次，挂了同样落下面的证据异常。
            // （好路径会解析两遍：这里一遍、调用方一遍。config.json 就几 KB，
            //   换「坏体永远带证据」值这个价。）
            try {
                new JSONObject(s);
                return;
            } catch (Exception parseFail) {
                // 落下去：与「不是 JSON」同一处构造带证据的异常
            }
        }

        StringBuilder sb = new StringBuilder("config.json 不是 JSON");
        if (contentType != null && !contentType.isEmpty()) {
            sb.append("（Content-Type: ").append(contentType).append('）');
        }
        sb.append("。多半是中途有东西把请求拦了——系统代理/VPN 的错误页、WAF 挑战页、"
                + "运营商门户或 CDN 错误页。响应体开头：");
        if (s.isEmpty()) {
            sb.append("（空）");
        } else {
            // 压成一行：多行内容在 logcat 里会被拆开，抓下来就对不上了。
            // 连续空白合并成一个——HTML 错误页满是换行与缩进，逐个保留会把这
            // BODY_PEEK_CHARS 的预算全喂给空白，真正有信息的那几个字反而被截掉。
            int kept = 0;
            boolean lastWasSpace = false;
            for (int i = 0; i < s.length() && kept < BODY_PEEK_CHARS; i++) {
                char ch = s.charAt(i);
                boolean isSpace = (ch == '\n' || ch == '\r' || ch == '\t' || ch == ' ');
                if (isSpace) {
                    if (lastWasSpace) continue;
                    ch = ' ';
                }
                lastWasSpace = isSpace;
                sb.append(ch);
                kept++;
            }
            if (kept >= BODY_PEEK_CHARS) sb.append("…（共 ").append(s.length()).append(" 字符）");
        }
        throw new IOException(sb.toString());
    }

    private static String fetch(String url, boolean direct) throws IOException {
        URL u = new URL(url);
        HttpURLConnection c = (HttpURLConnection)
                (direct ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        CNUserAgent.apply(c);
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
            String body = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            requireJsonBody(body, c.getContentType());
            return body;
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
            //
            // ⚠ 必须显式挑最大权重，不能取 plines[0]：parseProxyLines 是按**配置
            // 顺序**返回的，排序发生在 CNWebProxy.usableOf 里（那是另一个数组）。
            // 取下标 0 的话，把低权重那条写在前面就会让 native 拿到错的那台。
            // 只在**启用**的线路里挑：全禁用时 pbase 保持为空，native 那边也不代理。
            // 否则会出现「Java 侧因为全禁用而 OFF，native 却还在往一条被明确关掉的
            // 线上改写」这种两边打架的状态。
            if (pbase.isEmpty() && plines != null) {
                CNWebProxy.Line top = null;
                for (int i = 0; i < plines.length; i++) {
                    if (!plines[i].enabled) continue;
                    if (top == null || plines[i].weight > top.weight) top = plines[i];
                }
                if (top != null) pbase = top.base;
            }
            if (!pbase.isEmpty() && pdom != null && pdom.length > 0) {
                proxyBase = pbase;   // Java 侧保留，供 SNAA 等 Java 网络请求改写
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
            // 传本次解析出的 pdom，而不是 proxyDomains 字段。
            //
            // refresh() 一个会话里会跑多次（预热、取版本、退避重试、安装器）。
            // 字段只在上面那个 if 分支里赋值，一旦某次的 domains 全被校验拒掉，
            // 字段仍留着上一次的值——拦截层就会拿着旧白名单继续跑。那与本类
            // 写明的「代理配置不做任何缓存，只认本次下发的那一份」直接矛盾。
            try {
                CNWebProxy.configure(plines, pdom, pwebMode);
            } catch (Throwable t) {
                CNLog.w(TAG, "拦截层代理配置下发失败（保持透传直连）: " + t);
            }
        }

        JSONObject st = root.optJSONObject("settings");
        if (st != null) {
            cfgChunks          = clampInt(st.optInt("chunks",             cfgChunks),          1, 16);
            cfgMaxDownloads    = clampInt(st.optInt("max_downloads",      cfgMaxDownloads),   4, 16);
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
            cfgForceAria2 = st.optBoolean("force_aria2", cfgForceAria2);
            cfgForceSingleThread =
                    st.optBoolean("force_single_thread", cfgForceSingleThread);
            cfgOfflineUrl = st.optString("offline_url", cfgOfflineUrl).trim();
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
        // F-046：defaultList 已在端点未注入时刻意返回空表——这里不得重新发明
        // 一个 base 为空的「默认线路」（会拼出相对文件名进 new URL）。空表由
        // 调用方（pick）fail-closed，绝不给一个不可用的空 base 线路。
        List<Mirror> any = new ArrayList<Mirror>(all.size());
        for (Mirror m : all) if (m.enabled) any.add(m);
        return any;
    }

    /**
     * 为第 {@code attempt} 次尝试挑一条线路（attempt 从 1 开始）。
     * 依次轮换，使每次重试都换到下一条线路。
     */
    public static Mirror pick(int attempt) {
        List<Mirror> ok = healthy();
        if (ok.isEmpty()) {
            // F-046：无可用线路 = 构建期端点未注入的 fail-closed 态。抛异常让
            // 上层走「无法下载」而非拼出空 base 的无效 URL 静默失败。
            throw new IllegalStateException("没有可用下载线路（构建期端点可能未注入）");
        }
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
        if (m.baselineBps <= 0) {
            m.baselineBps = bps;
            CNLog.i(TAG, "线路基准速度 " + m.name + " = " + (bps / 1024) + " KB/s");
            return;
        }
        // 指数移动平均（EMA）：不用历史峰值永久锁死基线。峰值会让一条线
        // 首轮突发测到的高速被永久记为基线，之后正常速度永远低于它、被
        // 误判「限速」反复换线（2026-08-11 cn_base_03 事故：基线 113010
        // kbps 来自瞬时峰值，稳定 2-3MB/s 被判低于 60% 阈值）。
        // EMA 平滑系数取 0.5：单次偶发低速不瞬间拉低基线，但持续观测会
        // 让基线收敛到该线真实可达速度，既防误伤也防永久虚高。
        long ema = (m.baselineBps + bps) / 2;
        m.baselineBps = ema > 0 ? ema : bps;
        CNLog.i(TAG, "线路基准速度(EMA) " + m.name + " = " + (m.baselineBps / 1024) + " KB/s");
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
