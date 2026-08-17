package io.kamihama.magianative;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * WebView 本地资源拦截的<b>全部判据</b>。
 *
 * <h3>它在哪里被调用</h3>
 *
 * 基础客户端的 {@code jp.f4samurai.web.WebViewImpl$WebViewClientImpl.smali} 经
 * baseline 补丁改写后，{@code shouldInterceptRequest(WebView, String)} 的方法体
 * 只剩一行：调用本方法；返回非 null 即作为页面响应，返回 null 则回落 super
 * （恒为 null），之后轮到 {@link CNWebProxy} 的代理层。把判据收进 Java 补丁类
 * 而不是留在 smali 里，是因为这里的每一行都是<b>安全边界</b>：它要能被
 * javac/d8 流水线编译检查、能被单测覆盖、能在 code review 里被逐行评审——
 * 而 smali 三者都难。{@code tools/check-webview-interceptor.py} 同时守着两侧
 * 的形状，任何一侧被悄悄改动都会让构建失败。
 *
 * <h3>安全模型（为什么旧实现必须换）</h3>
 *
 * 旧 smali 实现的判据是「URL 字符串任意位置 {@code contains("/magica/")}，
 * 取其首次出现之后的子串、只剥 {@code ?} 查询串，拼到 {@code <files>/magica/}
 * 下，存在即回注」。两个洞：
 *
 * <ol>
 *   <li><b>路径穿越（F-E-01）</b>：URL 的查询串不做点段归一化，把载荷放进
 *       查询串即可带着字面 {@code ..} 到达文件系统——
 *       {@code https://<游戏域>/x?q=/magica/../../shared_prefs/x.xml} 会读出
 *       应用私有目录的文件，且请求与页面同源、响应可被页面 JS 全读，等于把
 *       「Web 层妥协」直接升级成「私有数据外泄」。
 *   <li><b>无 scheme/host/路径锚定（F-E-02）</b>：全串 contains 与 scheme、
 *       host、path 前缀完全无关，任意源页面都能借
 *       {@code ?x=/magica/js/foo.js} 读走 {@code <files>/magica/} 下的本地
 *       供给文件，并把 {@code File.exists()} 当存在性预言机用。
 * </ol>
 *
 * 本方法用四道闸替代字符串 contains：
 *
 * <ol>
 *   <li><b>scheme</b>：只认 http/https（大小写不敏感）；
 *   <li><b>host</b>：必须属于游戏域——{@link #GAME_HOST_SUFFIXES} 后缀
 *       白名单（页面 origin 是 {@code dorothy.magi-reco.com}，API 引导地址
 *       totentanz-9b 也在 magi-reco.com 系下），或项目自有主域
 *       {@link CNEndpoints#ROOT_DOMAIN} 及其子域。大小写不敏感，恒生效——
 *       这是「任意源页面能否借 /magica/ 读本地文件」的屏障；
 *   <li><b>path 前缀</b>：只对 {@link Uri#getPath()}（不含查询串/fragment，
 *       且已做一次百分号解码）做 {@code /magica/} 前缀匹配——查询串里的
 *       {@code /magica/} 不再命中，F-E-01 的载荷通道就此关闭；
 *   <li><b>相对路径净化 + canonical 复核</b>：相对路径逐段拒绝 {@code "."}/
 *       {@code ".."}（防直接穿越与 {@code %2e%2e} 编码穿越），拒绝反斜杠
 *       （防跨平台混淆），最后 {@link File#getCanonicalPath()} 必须落在
 *       {@code <files>/magica/} 的 canonical 前缀之内（连符号链接逃逸也算
 *       在内），否则一律按「不接管」处理。
 * </ol>
 *
 * <h3>有意保留的旧语义</h3>
 *
 * <ul>
 *   <li>{@code api/} 前缀不接管：那是真后端接口，必须落到网络（CNWebProxy
 *       的代理层正是接在「拦截器返回 null」之后）；</li>
 *   <li>查询串不参与文件名：{@code ?<md5>} 版本号会被丢掉，本地文件一旦
 *       存在就不再看服务端版本——这是 README 里记录在案的「CSS 冻结」
 *       取舍，本次只修安全判据，不改资源版本语义；</li>
 *   <li>本地不存在的文件一律返回 null，回落网络，与旧实现逐点一致。</li>
 * </ul>
 *
 * <h3>日志纪律（顺带修 F-E-03）</h3>
 *
 * 旧实现对 WebView 的<b>每一个</b>子资源请求全量 logcat 打完整 URL（含
 * {@code ?uid=…&token=…} 查询参数）。本方法：命中时打相对路径（不含
 * 查询串）；穿越尝试打一条 WARN（相对路径截断 120 字符，防日志塞爆与
 * 控制符注入）；其余拒绝路径完全静默——攻击者刷探测不应在日志里留下
 * 每秒数次的高频噪声。
 */
public final class CNWebLocalFiles {

    private static final String TAG = "CNWebLocalFiles";

    /** 拦截只认这个 path 前缀。 */
    private static final String MAGIC_PREFIX = "/magica/";

    /** 真后端接口前缀，照旧不接管（落到 CNWebProxy / 直连）。 */
    private static final String API_PREFIX = "api/";

    /** 日志里相对路径的最大长度，防日志塞爆与控制符注入。 */
    private static final int LOG_REL_MAX = 120;

    /**
     * {@code <files>/magica} 的 canonical 前缀缓存。filesDir 进程内不变，
     * 每个请求省一次符号链接解析。volatile 双检足够：重复算无副作用。
     */
    private static volatile String cachedBaseCanon;

    /** 游戏真实供给域后缀白名单：/magica/ 前端与页面 origin 都在这些域系下。 */
    private static final String[] GAME_HOST_SUFFIXES = {
            "magi-reco.com", "f4samurai.com", "sisyphus.systems"
    };

    private CNWebLocalFiles() {}

    /**
     * 拦截入口。返回非 null = 用本地文件作答；返回 null = 不接管（回落
     * super → null → CNWebProxy）。本方法在 WebView 的 IO 线程上被高频调用，
     * 全程不允许抛异常、不允许阻塞。
     */
    public static WebResourceResponse intercept(String url) {
        try {
            if (url == null || url.isEmpty()) return null;
            final Uri uri;
            try {
                uri = Uri.parse(url);
            } catch (Throwable t) {
                return null;   // 畸形 URL：不接管，交回网络栈自己报错
            }

            // ── 闸 1：scheme ────────────────────────────────────────
            String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                return null;
            }

            // ── 闸 2：host 属于游戏域或项目自有域 ─────────────────────
            // 游戏的 /magica/ 前端与页面 origin 在 magi-reco.com 系下
            // （dorothy.magi-reco.com / totentanz-9b.magi-reco.com），另有
            // f4samurai.com 与 sisyphus.systems。原实现只认项目自有主域
            // ROOT_DOMAIN（api./assets. 那套基础设施域），与游戏页面 origin
            // 毫无从属关系——生产构建会把所有 dorothy.magi-reco.com 的
            // /magica/ 请求拦在门外，本地热更供给整闸静默失效。这里同时
            // 接受游戏域后缀与 ROOT_DOMAIN，恒生效（不因开发构建 ROOT_DOMAIN
            // 为空而跳过）——这是「任意源页面能否借 /magica/ 读本地文件」
            // 的屏障。
            String host = uri.getHost();
            if (host == null) return null;
            String h = host.toLowerCase(Locale.US);
            boolean gameHost = false;
            for (String suf : GAME_HOST_SUFFIXES) {
                if (h.equals(suf) || h.endsWith("." + suf)) { gameHost = true; break; }
            }
            if (!gameHost) {
                String root = CNEndpoints.ROOT_DOMAIN;
                boolean rootHost = !root.isEmpty()
                        && (h.equals(root) || h.endsWith("." + root));
                if (!rootHost) return null;
            }

            // ── 闸 3：只对 path 分量做前缀匹配 ──────────────────────
            // getPath() 不含 ?/#，查询串里的 /magica/ 不再命中（F-E-01 关闭）；
            // 且已做一次百分号解码，%2e%2e 会在下面的分段校验里现出原形。
            String path = uri.getPath();
            if (path == null || !path.startsWith(MAGIC_PREFIX)) return null;
            String rel = path.substring(MAGIC_PREFIX.length());

            // ── 闸 4：相对路径净化 + canonical 复核 ─────────────────
            if (rel.isEmpty() || rel.endsWith("/")) return null;   // 目录不供给
            if (rel.startsWith(API_PREFIX)) return null;           // 真后端接口
            if (rel.indexOf('\\') >= 0) return null;               // 反斜杠混淆
            if (rel.indexOf('%') >= 0) return null;   // 残余编码：不做二次解码，直接拒
            String[] segs = rel.split("/");
            for (int i = 0; i < segs.length; i++) {
                String s = segs[i];
                if (s.equals("..") || s.equals(".")) {
                    CNLog.w(TAG, "[本地资源] 拒绝带点段的相对路径：" + abbrev(rel));
                    return null;
                }
                if (s.isEmpty()) return null;        // "//" 叠分隔符，不规范即拒
            }

            File base = new File(CNPaths.filesDir(), "magica");
            File target = new File(base, rel);
            final String baseCanon;
            try {
                String cached = cachedBaseCanon;
                if (cached == null) {
                    cached = base.getCanonicalPath();
                    cachedBaseCanon = cached;
                }
                baseCanon = cached;
                String targetCanon = target.getCanonicalPath();
                if (!targetCanon.startsWith(baseCanon + File.separator)) {
                    // 分段校验之后还能逃出去，只剩符号链接一条路——同样拒。
                    CNLog.w(TAG, "[本地资源] 拒绝逃出供给根的路径：" + abbrev(rel));
                    return null;
                }
            } catch (IOException ioe) {
                return null;   // canonical 解析失败按不接管处理，别猜
            }

            if (!target.isFile()) return null;   // 不存在/目录/特殊文件：回落网络

            // ── 命中：按扩展名给 MIME，其余 octet-stream（与旧实现同表）──
            String mime = mimeFor(rel);
            FileInputStream in;
            try {
                in = new FileInputStream(target);
            } catch (Throwable t) {
                return null;   // 检查到打开之间的删除竞态：回落网络
            }
            CNLog.i(TAG, "[本地资源] 供给：" + abbrev(rel));
            return new WebResourceResponse(mime, "utf-8", in);
        } catch (Throwable t) {
            // 拦截器崩在 WebView 的 IO 线程上会把整次加载变成原生层异常——
            // 宁可这一次不接管，也不能让页面加载流程炸掉。
            CNLog.e(TAG, "[本地资源] 拦截判据异常，按不接管处理", t);
            return null;
        }
    }

    /** 日志用的截断缩写，顺带把换行/回车压掉，防日志注入。 */
    private static String abbrev(String rel) {
        String s = rel.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= LOG_REL_MAX ? s : s.substring(0, LOG_REL_MAX) + "…";
    }

    /** 与旧 smali 实现逐条相同的 MIME 表。 */
    private static String mimeFor(String rel) {
        if (rel.endsWith(".png"))  return "image/png";
        if (rel.endsWith(".jpg"))  return "image/jpeg";
        if (rel.endsWith(".jpeg")) return "image/jpeg";
        if (rel.endsWith(".json")) return "application/json";
        if (rel.endsWith(".js"))   return "application/javascript";
        if (rel.endsWith(".css"))  return "text/css";
        if (rel.endsWith(".html")) return "text/html";
        return "application/octet-stream";
    }
}
