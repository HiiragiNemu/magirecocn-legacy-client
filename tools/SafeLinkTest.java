import io.kamihama.magianative.CNSafeLink;

/**
 * 验证 {@link CNSafeLink#reject} 的放行/拦截判据。
 *
 * <p>不碰 Android API，直接在 JVM 上跑：
 *
 * <pre>
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
        System.out.println("\n[1] 实际在用的地址必须全部放行");
        // 署名区内置的那一批
        ok("https://b23.tv/aNjcz1p");
        ok("https://b23.tv/ovvbrNw");
        ok("https://reader.pages.example");
        ok("https://live2d.pages.example");
        ok("https://callsearch.pages.example");
        ok("https://www.example.test");
        ok("https://www.bilibili.com/video/BV1faRiBBExk");
        // 云端配置里出现过的
        ok("https://assets.example.test/legacy-client.apk");
        ok("https://api.example.test/legacy/config.json");
        ok("https://assets.example.test/version_js.json");
        ok("https://assets.example/cn_js_update.zip");
        ok("https://docs.example.test/client/bootstrap");
        // 深层子域也必须放行：（已下线线路） 是公测包与热更的加速镜像，
        // 强制更新/署名条目里的下载链接会落在它上面
        ok("https://（已下线线路）/g/m/releases/download/latest/legacy-client.apk");
        ok("https://（已下线线路）/version_js.json");
        ok("https://github.com/magirecocn-revival-project/legacy-client");
        // right_pill 的「支持我们」跳爱发电；两个域名是同一个站
        ok("https://afdian.com/a/magireco");
        ok("https://ifdian.net/a/magireco");

        System.out.println("\n[2] 协议：只放行 https");
        no("http://www.example.test", "明文 http");
        no("intent://evil/#Intent;scheme=x;end", "intent scheme（可拉起任意组件）");
        no("file:///data/data/io.kamihama.totentanz/files/", "file scheme（私有目录）");
        no("javascript:alert(1)", "javascript scheme");
        no("content://com.example/x", "content scheme");
        no("HTTP://www.example.test", "大写的 http 也是 http");
        no("www.example.test", "缺少协议");

        System.out.println("\n[3] authority 伪装");
        no("https://www.example.test@evil.example/", "userinfo 伪装成自家域名");
        no("https://evil.example/?x=https://www.example.test", "自家域名只出现在查询串里");
        no("https://evilexample.test/", "后缀拼接（endsWith 会误放）");
        no("https://example.test.evil.example/", "把自家域名放在左边当子域");
        no("https://", "没有主机名");

        System.out.println("\n[4] 不在允许列表内的域名");
        no("https://pages/", "pages 本身是公共后缀");
        no("https://someone-else.pages/", "别人的 CDN Pages 站");
        no("https://example.com/", "无关域名");
        no("https://afdian.net/a/x", "爱发电的旧域名已停止解析，不在列表里");
        no("https://afdian.com.evil.example/", "把爱发电放在左边当子域");

        System.out.println("\n[5] 空白与控制字符");
        no("  https://www.example.test", "首尾空白");
        no("https://www.example.test\n", "尾部换行");
        no("https://www.magi\nreco.top", "中间换行");
        no("https://www.example.test" + "\u0000" + "/x", "内嵌 NUL 字符");
        no(null, "null");
        no("", "空串");

        System.out.println("\n[6] 大小写与末尾点归一化后仍应放行");
        ok("https://WWW.Example.TEST/");
        ok("https://www.example.test./x");

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
