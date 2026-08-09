import io.kamihama.magianative.CNMirrors;
import java.lang.reflect.Method;

/**
 * 验证 {@code config.json} 里两类「云端可控字符串」的准入判据。
 *
 * <h3>为什么这两条必须钉死</h3>
 *
 * 本项目的完整性前提是「DNSSEC + 完整 TLS 验证都开着」。问题在于这个前提
 * <b>本身就是 config.json 能关掉的</b>：
 *
 * <ul>
 *   <li>{@code mirrors[].base} / {@code proxy.base} 一旦允许 {@code http://}，
 *       TLS 就整个不参与了。而安装器那 15 个基础包<b>没有 md5/sha 校验</b>，
 *       完整性全押在 TLS 上，{@code extractChecked} 又只验结构不验内容——
 *       投毒 zip 里的 JS 会落进 {@code <files>/magica/js/}，被 WebView 永久执行。</li>
 *   <li>{@code proxy.domains} 是<b>后缀</b>匹配，没有下限的话填个 {@code "com"}
 *       就能把玩家所有 {@code .com} 流量吸进代理。</li>
 * </ul>
 *
 * <p>两个判据都是私有静态方法，这里用反射直接打，避免为了测试把它们放开。
 *
 * <pre>
 *   javac -nowarn -source 8 -target 8 -encoding UTF-8 \
 *         -cp .cache/deps/android.jar -d .build-test \
 *         $(find patch/src/main/java -name '*.java') tools/ConfigGuardTest.java
 *   java -cp .build-test:.cache/deps/android.jar ConfigGuardTest
 * </pre>
 */
public class ConfigGuardTest {

    static int pass = 0, fail = 0;
    static Method normalizeBase, isSaneProxyDomain, requireJsonBody;

    static {
        try {
            normalizeBase = CNMirrors.class.getDeclaredMethod("normalizeBase", String.class);
            normalizeBase.setAccessible(true);
            isSaneProxyDomain = CNMirrors.class.getDeclaredMethod("isSaneProxyDomain", String.class);
            isSaneProxyDomain.setAccessible(true);
            requireJsonBody = CNMirrors.class.getDeclaredMethod(
                    "requireJsonBody", String.class, String.class);
            requireJsonBody.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("取不到待测方法（改名了？）: " + e, e);
        }
    }

    static String base(String s) throws Exception { return (String) normalizeBase.invoke(null, s); }
    static boolean dom(String s) throws Exception { return (Boolean) isSaneProxyDomain.invoke(null, s); }

    /** 期望 base 被接受，并归一化成 expect。 */
    static void okBase(String in, String expect) throws Exception {
        String got = base(in);
        if (expect.equals(got)) { pass++; System.out.println("  ✅ 收下  " + in + "  → " + got); }
        else { fail++; System.out.println("  ❌ " + in + "\n           期望 " + expect + "\n           实得 " + got); }
    }

    /** 期望 base 被拒（返回空串）。 */
    static void noBase(String in, String note) throws Exception {
        String got = base(in);
        if ("".equals(got)) { pass++; System.out.println("  ✅ 拒收  " + note); }
        else { fail++; System.out.println("  ❌ 本该拒收却收下了  " + note + "  → " + got); }
    }

    static void okDom(String d) throws Exception {
        if (dom(d)) { pass++; System.out.println("  ✅ 放行  " + d); }
        else { fail++; System.out.println("  ❌ 本该放行却拦了  " + d); }
    }

    static void noDom(String d, String note) throws Exception {
        if (!dom(d)) { pass++; System.out.println("  ✅ 拦下  " + note); }
        else { fail++; System.out.println("  ❌ 本该拦下却放行了  " + note + "  （" + d + "）"); }
    }

    static String cause(Exception e) {
        Throwable t = (e.getCause() != null) ? e.getCause() : e;
        return t.getMessage();
    }

    /** 期望被当成 JSON 放行。 */
    static void okJson(String body, String ct, String why) throws Exception {
        try {
            requireJsonBody.invoke(null, body, ct);
            pass++; System.out.println("  ✅ 放行  " + why);
        } catch (Exception e) {
            fail++; System.out.println("  ❌ 本该放行却抛了  " + why + "  → " + cause(e));
        }
    }

    /** 期望抛出，且异常信息里带上指定的证据片段。 */
    static void badJson(String body, String ct, String why, String[] mustContain) throws Exception {
        try {
            requireJsonBody.invoke(null, body, ct);
            fail++; System.out.println("  ❌ 本该抛出却放行了  " + why);
            return;
        } catch (Exception e) {
            String msg = String.valueOf(cause(e));
            StringBuilder missing = new StringBuilder();
            for (int i = 0; i < mustContain.length; i++) {
                if (!msg.contains(mustContain[i])) missing.append(" [").append(mustContain[i]).append(']');
            }
            if (missing.length() == 0) { pass++; System.out.println("  ✅ 抛出且带证据  " + why); }
            else { fail++; System.out.println("  ❌ " + why + "  信息里缺:" + missing + "\n           实际: " + msg); }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("[1] base：只收 https，且强制以 '/' 结尾");
        okBase("https://api.example.test/stream/", "https://api.example.test/stream/");
        okBase("https://api.example.test/stream",  "https://api.example.test/stream/");
        okBase("https://（已下线线路）/g/m/releases/download/latest",
               "https://（已下线线路）/g/m/releases/download/latest/");
        okBase("  https://x.example/s/  ", "https://x.example/s/");
        okBase("HTTPS://X.EXAMPLE/s/", "HTTPS://X.EXAMPLE/s/");   // 大小写不影响判定

        System.out.println("\n[2] base：明文 http 一律拒——这是整条信任链的第一环");
        noBase("http://api.example.test/stream/", "明文 http");
        noBase("HTTP://api.example.test/stream/", "明文 http（大写）");
        noBase("hTtP://api.example.test/", "明文 http（混合大小写）");

        System.out.println("\n[3] base：其余畸形输入");
        noBase("ftp://x.example/", "非 http(s) 协议");
        noBase("//x.example/", "协议相对");
        noBase("x.example/", "没有协议");
        noBase("https://", "只有 scheme");
        noBase("", "空串");
        noBase(null, "null");
        noBase("javascript:alert(1)", "伪协议");
        noBase("https://x.example/\n/evil", "内嵌换行（会被拆成两条请求）");
        noBase("https://x.example/\r\nHost: evil", "CRLF 注入");
        noBase("https://x.example/" + (char) 0 + "/evil", "内嵌 NUL");
        noBase("https://x.example/" + (char) 7 + "/evil", "内嵌控制字符（响铃）");
        okBase("https://x.example/s/  ", "https://x.example/s/");   // 尾随空白 trim 掉即可，不算畸形

        System.out.println("\n[4] proxy.domains：正常粒度放行");
        okDom("magi-reco.com");
        okDom("sisyphus.systems");
        okDom("magica.f4samurai.com");
        okDom("dorothy.magi-reco.com");
        okDom("a.b.c.example");

        System.out.println("\n[5] proxy.domains：裸顶级域必须拦——一个词吸走整个 TLD");
        noDom("com", "裸 TLD com");
        noDom("cn", "裸 TLD cn");
        noDom("top", "裸 TLD top");
        noDom("systems", "裸 TLD systems");

        System.out.println("\n[6] proxy.domains：常见两级公共后缀");
        noDom("com.cn", "com.cn");
        noDom("co.uk", "co.uk");
        noDom("pages", "pages（CDN Pages，谁都能开）");
        noDom("github.io", "github.io");

        System.out.println("\n[7] proxy.domains：畸形输入");
        noDom("", "空串");
        noDom(null, "null");
        noDom(".com", "前导点");
        noDom("example.", "尾随点");
        noDom("a..b.com", "连续的点");
        noDom("ex ample.com", "内嵌空格");
        noDom("example.com/", "带路径分隔符");
        noDom("*.example.com", "通配符");
        noDom("例子.com", "非 ASCII（应走 punycode）");
        noDom("https://example.com", "整个 URL 而非域名");

        System.out.println("\n[8] 响应体不是 JSON 时，必须把「是谁返回的」留下来");
        // 2026-08-08：真机连续四次拿到 HTML，日志里只有一句
        // 「Value <html> … cannot be converted to JSONObject」——响应体被丢了，
        // 而那页 HTML 恰恰写着是谁拦的。这几条钉住「证据不许再被扔掉」。
        okJson("{\"a\":1}",       null,               "正常 JSON");
        okJson("  \n {\"a\":1} ", "application/json", "前导空白");
        okJson("\uFEFF{\"a\":1}", "application/json", "带 UTF-8 BOM");

        badJson("<html><head><title>403 Forbidden</title></head><body>blocked by X</body></html>",
                "text/html", "HTML 错误页",
                new String[] { "text/html", "403 Forbidden", "blocked by X" });
        badJson("",     "text/html",        "空响应体",   new String[] { "（空）" });
        badJson(null,   null,               "null 响应体", new String[] { "（空）" });
        badJson("[1,2]", "application/json", "顶层是数组不是对象", new String[] { "[1,2]" });
        // 以 { 开头但体已损坏：首字符守卫放过它，真解析必须接住
        badJson("{\"a\":", "application/json", "截断的 JSON",
                new String[] { "{\"a\":" });
        // 多行必须压成一行：否则 logcat 会把它拆开，抓下来对不上
        badJson("<html>\r\na\tb\n</html>", "text/html", "多行压成一行",
                new String[] { "<html> a b </html>" });

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
