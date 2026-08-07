import android.webkit.WebResourceResponse;
import io.kamihama.magianative.CNWebProxy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 {@code CNWebProxy.fetchViaProxy} 真跑一遍——用一个本地 HTTP 服务器喂它各种
 * 响应形态。
 *
 * <h3>为什么要有它</h3>
 *
 * 这条路径此前是**零运行时覆盖**的，全靠读代码来验。而读代码漏掉过一个很典型的
 * 问题：上游回 304 时响应体是空的，旧判据却把它当成功转交，WebView 拿到的是一个
 * 空的 JS/CSS——文件"存在"，只是没内容，坏得极难查。那种 bug 一跑就能抓到。
 *
 * <p>覆盖的形态：200 明文 / 200 gzip / 条件请求 304 / 跨协议 301 / 上游 5xx /
 * 上游 4xx / 分块传输 / 缺 Content-Type / 请求头转发白名单。
 *
 * <h3>跑法</h3>
 *
 * <pre>
 *   python3 tools/proxy-test-server.py 8791 &amp;
 *   # 注意 teststubs 要排在 android.jar **前面**——里头那个能用的
 *   # WebResourceResponse 要盖掉 android.jar 的桩
 *   javac -nowarn -source 8 -target 8 -encoding UTF-8 \
 *         -cp .cache/deps/android.jar -d .build-test \
 *         $(find patch/src/main/java -name '*.java') \
 *         tools/teststubs/android/webkit/WebResourceResponse.java \
 *         tools/ProxyFetchTest.java
 *   java -cp .build-test:.cache/deps/android.jar ProxyFetchTest 8791
 * </pre>
 *
 * <p><b>注意本测试用的是 http 的代理入口</b>（本地服务器）。生产里 http 是被
 * {@code CNMirrors.normalizeBase} 一律拒掉的（见 {@code ConfigGuardTest}）；
 * 这里绕过那道闸只是为了能起一个本地明文服务器，不代表 http 被允许。
 */
public class ProxyFetchTest {

    static int pass = 0, fail = 0;
    static String base;          // http://127.0.0.1:PORT/
    static Method fetchViaProxy;
    static Field cooldownField;

    static void ok(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("  ✅ " + name + (detail.isEmpty() ? "" : "  — " + detail)); }
        else { fail++; System.out.println("  ❌ " + name + "  — " + detail); }
    }

    static {
        try {
            for (Method m : CNWebProxy.class.getDeclaredMethods()) {
                if (m.getName().equals("fetchViaProxy")) { fetchViaProxy = m; break; }
            }
            if (fetchViaProxy == null) throw new RuntimeException("找不到 fetchViaProxy（改名了？）");
            fetchViaProxy.setAccessible(true);
            cooldownField = CNWebProxy.Line.class.getDeclaredField("cooldownUntil");
            cooldownField.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("测试初始化失败: " + e, e);
        }
    }

    static CNWebProxy.Line line(String name) {
        return CNWebProxy.newLine(name, base, 100, true);
    }

    static long cooldown(CNWebProxy.Line l) throws Exception {
        return cooldownField.getLong(l);
    }

    static WebResourceResponse fetch(String route, Map<String, String> headers,
                                     CNWebProxy.Line l) throws Exception {
        String orig  = "https://dorothy.magi-reco.com" + route;
        String proxy = base + route.substring(1);
        return (WebResourceResponse) fetchViaProxy.invoke(null, orig, proxy, headers, l);
    }

    static String body(WebResourceResponse r) throws Exception {
        InputStream in = r.getData();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8791;
        base = "http://127.0.0.1:" + port + "/";

        System.out.println("[1] 200 明文：接管，mime/编码/正文都对");
        CNWebProxy.Line l1 = line("线1");
        WebResourceResponse r = fetch("/ok", null, l1);
        ok("接管了", r != null, r == null ? "返回 null" : "");
        if (r != null) {
            ok("状态码 200", r.getStatusCode() == 200, "实得 " + r.getStatusCode());
            ok("mime 取自 Content-Type", "application/javascript".equals(r.getMimeType()),
               "实得 " + r.getMimeType());
            ok("编码取自 charset", "utf-8".equals(r.getEncoding()), "实得 " + r.getEncoding());
            String b = body(r);
            ok("正文完整", b.startsWith("console.log") && b.length() > 1000,
               "长度 " + b.length());
            Map<String, String> h = r.getResponseHeaders();
            ok("Content-Type 未重复放进头里", !h.containsKey("Content-Type"), h.keySet().toString());
            ok("Content-Length 已剥离", !h.containsKey("Content-Length"), h.keySet().toString());
            ok("自定义头保留", "plain".equals(h.get("X-Marker")), String.valueOf(h.get("X-Marker")));
        }

        // ⚠ 这一节在桌面 JVM 上**验不到 gzip 协商本身**。
        //
        // Android 的 HttpURLConnection 底层是 OkHttp：它会自己加
        // Accept-Encoding: gzip 并透明解压——这正是 isHopByHopRequestHeader 不转发
        // WebView 那个 Accept-Encoding 的理由。而桌面 JDK 的实现**不会**自动加，
        // 所以本地服务器收到的请求里根本没有 accept-encoding（见 [8] 的 echo 输出），
        // 会走明文回落分支。
        //
        // 能验的仍然有价值：无论走哪条，交给 WebView 的正文必须是可用的明文，
        // 且响应头里不能留下过时的 Content-Encoding。
        System.out.println("\n[2] 200 gzip：正文必须是可用明文，且不留 Content-Encoding");
        WebResourceResponse rg = fetch("/gzip", null, line("线g"));
        ok("接管了", rg != null, rg == null ? "返回 null" : "");
        if (rg != null) {
            String b = body(rg);
            ok("正文是可用明文", b.startsWith("console.log") && b.length() > 1000,
               "长度 " + b.length() + " 首 20 字节=" + b.substring(0, Math.min(20, b.length())));
            ok("Content-Encoding 已剥离",
               !rg.getResponseHeaders().containsKey("Content-Encoding"),
               rg.getResponseHeaders().keySet().toString());
            String marker = rg.getResponseHeaders().get("X-Marker");
            if ("gzipped".equals(marker)) {
                ok("走的是 gzip 分支且已解压", true, "本运行时会自动协商 gzip");
            } else {
                System.out.println("  ⏭  gzip 协商未发生（桌面 JDK 不自动加 "
                                   + "Accept-Encoding；Android/OkHttp 会）——该分支只能在真机验");
            }
        }

        System.out.println("\n[3] 条件请求 304：绝不能接管（否则 WebView 拿到空文件）");
        Map<String, String> cond = new LinkedHashMap<String, String>();
        cond.put("If-None-Match", "\"deadbeef\"");
        WebResourceResponse r304 = fetch("/conditional", cond, line("线c"));
        ok("没有接管", r304 == null, r304 == null ? "" : "居然接管了，正文长度 " + body(r304).length());

        WebResourceResponse r200 = fetch("/conditional", null, line("线c2"));
        ok("无条件头时正常接管", r200 != null && r200.getStatusCode() == 200,
           r200 == null ? "返回 null" : "状态 " + r200.getStatusCode());

        System.out.println("\n[4] 跨协议 301：不接管（手上只有跳转页）");
        WebResourceResponse r301 = fetch("/redirect", null, line("线r"));
        ok("没有接管", r301 == null, r301 == null ? "" : "状态 " + r301.getStatusCode());

        System.out.println("\n[5] 上游 5xx：不接管，且这条线进冷却");
        CNWebProxy.Line l5 = line("线5");
        ok("冷却初值为 0", cooldown(l5) == 0, "实得 " + cooldown(l5));
        WebResourceResponse r5 = fetch("/err500", null, l5);
        ok("没有接管", r5 == null, r5 == null ? "" : "状态 " + r5.getStatusCode());
        ok("已进冷却", cooldown(l5) > System.currentTimeMillis(), "cooldownUntil=" + cooldown(l5));

        System.out.println("\n[6] 上游 4xx：不接管，但**不该**怪到线路头上");
        CNWebProxy.Line l4 = line("线4");
        WebResourceResponse r4 = fetch("/err404", null, l4);
        ok("没有接管", r4 == null, r4 == null ? "" : "状态 " + r4.getStatusCode());
        ok("没有进冷却", cooldown(l4) == 0, "cooldownUntil=" + cooldown(l4));

        System.out.println("\n[7] 分块传输（无 Content-Length）：正文要能完整读出");
        WebResourceResponse rc = fetch("/chunked", null, line("线ch"));
        ok("接管了", rc != null, rc == null ? "返回 null" : "");
        if (rc != null) {
            String b = body(rc);
            ok("四个分块都在", b.contains("chunk 0") && b.contains("chunk 3"), b.replace("\n", "|"));
            ok("Transfer-Encoding 已剥离",
               !rc.getResponseHeaders().containsKey("Transfer-Encoding"),
               rc.getResponseHeaders().keySet().toString());
        }

        System.out.println("\n[8] 请求头转发：逐跳头与 Accept-Encoding 不得外传");
        Map<String, String> req = new LinkedHashMap<String, String>();
        req.put("Accept-Encoding", "identity");     // 若被转发，gzip 那条路就废了
        req.put("Host", "evil.example");
        req.put("Connection", "close");
        req.put("X-Custom", "keep-me");
        req.put("Referer", "https://dorothy.magi-reco.com/magica/index.html");
        WebResourceResponse re = fetch("/echo", req, line("线e"));
        ok("接管了", re != null, re == null ? "返回 null" : "");
        if (re != null) {
            String got = body(re);
            ok("X-Custom 有转发", got.contains("\"x-custom\": \"keep-me\""), got);
            ok("Referer 有转发", got.contains("\"referer\""), got);
            ok("Accept-Encoding 未被我们覆盖成 identity",
               !got.contains("\"accept-encoding\": \"identity\""), got);
            ok("Host 是真实目标而非 evil.example",
               !got.contains("evil.example"), got);
        }

        System.out.println("\n[9] 缺 Content-Type：按扩展名兜底");
        String orig = "https://dorothy.magi-reco.com/magica/js/foo.js";
        String proxy = base + "noctype";
        WebResourceResponse rn =
                (WebResourceResponse) fetchViaProxy.invoke(null, orig, proxy, null, line("线n"));
        ok("接管了", rn != null, rn == null ? "返回 null" : "");
        if (rn != null) {
            ok("按 .js 兜底成 application/javascript",
               "application/javascript".equals(rn.getMimeType()), "实得 " + rn.getMimeType());
            ok("编码兜底 utf-8", "utf-8".equals(rn.getEncoding()), "实得 " + rn.getEncoding());
        }

        System.out.println("\n[10] 连不上：不接管，且这条线进冷却");
        CNWebProxy.Line ld = CNWebProxy.newLine("死线", "http://127.0.0.1:1/", 100, true);
        WebResourceResponse rd = (WebResourceResponse) fetchViaProxy.invoke(
                null, "https://dorothy.magi-reco.com/ok", "http://127.0.0.1:1/ok",
                new HashMap<String, String>(), ld);
        ok("没有接管", rd == null, rd == null ? "" : "居然接管了");
        ok("已进冷却", cooldown(ld) > System.currentTimeMillis(), "cooldownUntil=" + cooldown(ld));

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
