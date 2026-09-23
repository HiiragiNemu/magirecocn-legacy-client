import io.kamihama.magianative.CNSafeLink;

/**
 * 验证 {@link CNSafeLink#reject} 的放行/拦截判据。
 *
 * <p>不碰 Android API，直接在 JVM 上跑：
 *
 * <p><b>先注入占位端点再编译</b>——源码里的 {@code CNEndpoints} 常量恒为空串，
 * 不注入的话白名单是空的，[1] 那一组会全军覆没：
 *
 * <pre>
 *   python3 tools/inject-endpoints.py --test
 *   javac -nowarn -source 8 -target 8 -encoding UTF-8 \
 *         -cp .cache/deps/android.jar -d .build-test \
 *         $(find patch/src/main/java -name '*.java') tools/SafeLinkTest.java
 *   java -cp .build-test SafeLinkTest
 * </pre>
 */
public class SafeLinkTest {

    static int pass = 0, fail = 0;

    /** 期望放行。 */
    static void ok(String url) {
        String why = CNSafeLink.reject(url);
        if (why == null) { pass++; System.out.println("  ✅ 放行  " + url); }
        else { fail++; System.out.println("  ❌ 本该放行却被拦  " + url + "  — " + why); }
    }

    /** 期望拦截。 */
    static void no(String url, String note) {
        String why = CNSafeLink.reject(url);
        if (why != null) { pass++; System.out.println("  ✅ 拦下  " + note + "  — " + why); }
        else { fail++; System.out.println("  ❌ 本该拦下却放行了  " + note + "  " + url); }
    }

    public static void main(String[] args) {
        // 域名一律用注入的**占位值**（tools/inject-endpoints.py --test）：
        // example.test / *.pages.example 都是 RFC 2606 保留域，永远解析不到。
        // 这里验的是 CNSafeLink 的**判据**，与线上用什么域名无关——真实域名
        // 不进仓库，测试也就不该依赖它。
        final String ROOT  = "example.test";          // = CNEndpoints.ROOT_DOMAIN
        final String WWW   = "https://www." + ROOT;
        final String P1    = "https://reader.pages.example";      // = pagesHosts()[0]
        final String P2    = "https://live2d.pages.example";      // = pagesHosts()[1]
        final String P3    = "https://callsearch.pages.example";  // = pagesHosts()[2]

        System.out.println("\n[1] 实际在用的地址必须全部放行");
        // 署名区内置的那一批
        ok("https://b23.tv/aNjcz1p");
        ok("https://b23.tv/ovvbrNw");
        ok(P1);
        ok(P2);
        ok(P3);
        ok(WWW);
        ok("https://www.bilibili.com/video/BV1faRiBBExk");
        // 云端配置里出现过的
        ok("https://assets." + ROOT + "/client.apk");
        ok("https://api." + ROOT + "/legacy/config.json");
        ok("https://assets." + ROOT + "/version_js.json");
        ok("https://edge.assets." + ROOT + "/cn_js_update.zip");
        ok("https://docs." + ROOT + "/client/bootstrap");
        // 深层子域也必须放行：自有域下的加速镜像随时可能新开一个前缀
        ok("https://cdn1.assets." + ROOT + "/g/m/pkg/client.apk");
        ok("https://cdn1.assets." + ROOT + "/version_js.json");
        // ui_credits 的 github_url 走 github.com，它在白名单里
        ok("https://github.com/example-org/example-repo");
        // right_pill 的「支持我们」跳爱发电；两个域名是同一个站
        ok("https://afdian.com/a/example");
        ok("https://afdian.com/a/madeinmagius");
        ok("https://afdian.com/a/cybernova");
        ok("https://ifdian.net/a/example");

        // 2026-08-14：下载线路不再经任何公共 GitHub 代理，白名单里也拿掉了。
        // 反向钉住——这条要是又被放回来，多半是有人为了「让强制更新能跑」
        // 顺手加的，而那正是当初把它加进来的理由。
        no("https://gh-proxy.org/https://github.com/example/repo/releases/download/latest/x.apk",
           "公共 GitHub 代理");
        no("https://v4.gh-proxy.org/https://github.com/example/repo/releases/download/latest/x.apk",
           "公共 GitHub 代理");

        for (String host : new String[] {"magireader.pages.dev", "magireco-call-search-cn.pages.dev", "magius3dviewer.pages.dev", "magiaexedralive2dviewer.pages.dev", "madeinmagius-site.pages.dev"}) {
            ok("https://"+host+"/");
            no("https://evil."+host+"/", "未批准的子域");
            no("https://"+host+".evil.example/", "相似域名");
            no("https://"+host+"@evil.example/", "用户名伪装");
        }
        no("https://someone-else.pages.dev/", "同平台的其他站点");

        System.out.println("\n[2] 协议：只放行 https");
        no("http://www." + ROOT, "明文 http");
        no("intent://evil/#Intent;scheme=x;end", "intent scheme（可拉起任意组件）");
        no("file:///data/data/io.kamihama.totentanz/files/", "file scheme（私有目录）");
        no("javascript:alert(1)", "javascript scheme");
        no("content://com.example/x", "content scheme");
        no("HTTP://www." + ROOT, "大写的 http 也是 http");
        no("www." + ROOT, "缺少协议");

        System.out.println("\n[3] authority 伪装");
        no(WWW + "@evil.example/", "userinfo 伪装成自家域名");
        no("https://evil.example/?x=" + WWW, "自家域名只出现在查询串里");
        no("https://evil" + ROOT + "/", "后缀拼接（endsWith 会误放）");
        no("https://" + ROOT + ".evil.example/", "把自家域名放在左边当子域");
        no("https://", "没有主机名");

        System.out.println("\n[4] 不在允许列表内的域名");
        no("https://pages.example/", "那个静态站平台的公共后缀本身");
        no("https://someone-else.pages.example/", "同一平台上别人的站");
        no("https://example.com/", "无关域名");
        no("https://afdian.net/a/x", "爱发电的旧域名已停止解析，不在列表里");
        no("https://afdian.com.evil.example/", "把爱发电放在左边当子域");

        System.out.println("\n[5] 空白与控制字符");
        no("  " + WWW, "首尾空白");
        no(WWW + "\n", "尾部换行");
        no("https://www.exam\nple.test", "中间换行");
        no(WWW + "\u0000" + "/x", "内嵌 NUL 字符");
        no(null, "null");
        no("", "空串");

        System.out.println("\n[6] 大小写与末尾点归一化后仍应放行");
        ok("https://WWW.Example.TEST/");
        ok(WWW + "./x");

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
