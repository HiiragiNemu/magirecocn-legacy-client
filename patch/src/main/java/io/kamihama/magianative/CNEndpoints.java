package io.kamihama.magianative;

/**
 * 全部对外主机名的<b>唯一来源</b>。源码里只留结构，真实取值构建期注入。
 *
 * <h3>为什么要有这一层</h3>
 *
 * 主机名是<b>部署参数</b>，不是代码。以前它们散在 {@code CNMirrors}、
 * {@code CNSafeLink}、{@code CNHotUpdateCheck}、{@code CNCNDownloadUI} 等
 * 七八个文件里：换域名、换架构、切测试环境都要满仓库找，而且漏一处就是一条
 * 半死不活的链路——查起来还特别难，因为其余几处都是对的。
 *
 * <h3>注入了什么</h3>
 *
 * 只有两个值，其余全部由它们推导——推导关系是<b>结构</b>，跟着代码走；
 * 取值是<b>部署参数</b>，跟着环境走。两者分开，换环境时只动后者：
 *
 * <ul>
 *   <li>{@link #ROOT_DOMAIN} —— 自有主域。官网 / api / assets / 各 CDN 子域
 *       都挂在它下面；{@code CNSafeLink} 的按域放行、{@code CNWebProxy} 的
 *       「自身域不重写」判据也用它。</li>
 *   <li>{@link #PAGES_HOSTS} —— 署名区那三个站的<b>精确主机名</b>，逗号分隔。
 *       它们挂在公共后缀下，推导不出来，只能整串给。</li>
 * </ul>
 *
 * <h3>注入失败会怎样</h3>
 *
 * <b>fail-closed。</b>两个值留空时：放行列表里不含自有域与那三个站
 * （＝所有外链都不放行）、线路表为空、热更地址前缀为空串而拼不出合法 URL。
 * 客户端退化成「什么都下不了」，而不是「退到某个不受控的默认值」。这与
 * 仓库里其它信任锚的取向一致：宁可不工作，不可工作在错误的锚上。
 *
 * <p>所以 {@code build-apk.yml} 在注入前先预检 Secret 是否存在，缺了直接
 * 让构建失败，不会产出一个「装上去什么都不会发生」的包。
 *
 * <h3>{@link #ASSETS_BASE} 特别注意</h3>
 *
 * 它就是 {@code CNMirrors.CANONICAL_BASE}——<b>身份标识</b>，已经写进每一台
 * 已安装设备的 15 个完成标记里。注入值哪怕差一个字符，所有老玩家都会被判定
 * 「没装过」而重下几个 GB。因此 {@code tools/check-base-urls.py} 用<b>钉死的
 * sha256</b> 核对注入结果——钉哈希而不是钉明文，才能既锁死取值、又让它继续
 * 由部署参数给。
 */
public final class CNEndpoints {

    /**
     * 自有主域，如 {@code example.tld}（不含协议、不含子域、不含结尾的点）。
     *
     * <p><b>构建期由 CI 从 Secret 注入，源码里必须保持空串。</b>
     * 提交前的守卫会检查这一点。
     */
    public static final String ROOT_DOMAIN = "";

    /**
     * 署名区外链的精确主机名，逗号分隔（如 {@code a.example,b.example}）。
     *
     * <p>这几个站挂在公共后缀下，<b>只认全名不认子域</b>——按域放行等于把
     * 任何人在同一后缀下开的站都放进来。同样构建期注入，源码里留空串。
     */
    public static final String PAGES_HOSTS = "";

    // 编译必须先经过 tools/inject-endpoints.py。生成类不在源码库中；若有人
    // 绕过唯一注入入口直接 javac，符号解析会在构建期失败，而不是产出
    // ROOT_DOMAIN/MIRRORS_URL 为空的可安装包。该常量不参与运行时分支。
    @SuppressWarnings("unused")
    private static final String BUILD_INJECTION_GUARD =
            CNEndpointInjectionGuard.INJECTED_MARKER;

    private CNEndpoints() {}

    /** 官网（署名区「项目官网」那条）。 */
    public static final String HOME_HOST = ROOT_DOMAIN.isEmpty() ? "" : "www." + ROOT_DOMAIN;

    /** 官网完整地址。 */
    public static final String HOME_URL = HOME_HOST.isEmpty() ? "" : "https://" + HOME_HOST;

    /** API 前缀，线路表与代理入口都在它下面。 */
    public static final String API_BASE = ROOT_DOMAIN.isEmpty() ? "" : "https://api." + ROOT_DOMAIN + "/";

    /**
     * 主线资源的<b>规范前缀</b>，即 {@code CNMirrors.CANONICAL_BASE}。
     *
     * <p>刻意不绑定任何具体 CDN：它永远不会被真的请求，只作身份标识。
     * 详见 {@code CNMirrors#CANONICAL_BASE} 与 {@code tools/check-base-urls.py}。
     */
    public static final String ASSETS_BASE = ROOT_DOMAIN.isEmpty() ? "" : "https://assets." + ROOT_DOMAIN + "/";

    /** 线路列表（config.json）地址。路径部分是固定约定，只有主机名随部署变。 */
    public static final String MIRRORS_URL = API_BASE.isEmpty() ? "" : API_BASE + "legacy/config.json";

    /** 内置兜底线路之一：自有域下的 EdgeOne。 */
    public static final String EDGEONE_BASE = ROOT_DOMAIN.isEmpty() ? "" : "https://edgeone.assets." + ROOT_DOMAIN + "/";

    /** 内置兜底线路之二：自有域下的阿里 ESA。 */
    public static final String ESA_BASE = ROOT_DOMAIN.isEmpty() ? "" : "https://esa.assets." + ROOT_DOMAIN + "/";

    /**
     * 署名区那三个站的主机名数组；注入缺失时返回<b>空数组</b>（＝一个都不放行）。
     *
     * <p>每次调用都重新切分。这条路径一次启动只走个位数次，没必要为它加缓存，
     * 也就不必操心「谁先初始化」那类静态初始化顺序问题。
     */
    public static String[] pagesHosts() {
        if (PAGES_HOSTS.isEmpty()) return new String[0];
        String[] raw = PAGES_HOSTS.split(",", -1);
        int n = 0;
        for (int i = 0; i < raw.length; i++) {
            raw[i] = raw[i].trim();
            if (!raw[i].isEmpty()) n++;
        }
        if (n == raw.length) return raw;
        String[] out = new String[n];
        int k = 0;
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isEmpty()) out[k++] = raw[i];
        }
        return out;
    }

    /**
     * 取第 {@code idx} 个署名区站点的主机名；注入缺失或越界时返回空串。
     *
     * <p>署名区那三行是<b>并行数组</b>（文案 / 链接 / 着色片段各一张表），
     * 越界会直接崩在启动路径上。所以这里吞掉越界返回空串——空串在三张表里
     * 的含义都是「这条不可点、不着色」，是既有的合法状态。
     */
    public static String site(int idx) {
        String[] h = pagesHosts();
        return (idx >= 0 && idx < h.length) ? h[idx] : "";
    }

    /** 同 {@link #site(int)}，但给出带协议的完整地址；空串仍表示「没有」。 */
    public static String siteUrl(int idx) {
        String h = site(idx);
        return h.isEmpty() ? "" : "https://" + h;
    }

    /** 注入是否到位。UI 与日志用它给出「这是个没注入的包」的明确提示。 */
    public static boolean injected() {
        return !ROOT_DOMAIN.isEmpty() && !PAGES_HOSTS.isEmpty();
    }
}
