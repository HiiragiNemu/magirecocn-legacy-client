import io.kamihama.magianative.CNWebProxy;
import java.lang.reflect.Field;

/**
 * 验证 {@link CNWebProxy#rewrite} 的改写判据。
 *
 * <p>这条判据是<b>安全边界</b>：它决定哪些主机的流量会被送到我们的代理入口。
 * 判松了等于把玩家的请求转给白名单外的域名，判紧了代理直接不生效。所以后缀匹配、
 * 排除自身、端口处理这几处都得钉住。
 *
 * <p>不碰 Android API，直接在 JVM 上跑：
 *
 * <pre>
 *   javac -nowarn -source 8 -target 8 -encoding UTF-8 \
 *         -cp .cache/deps/android.jar -d .build-test \
 *         $(find patch/src/main/java -name '*.java') tools/WebProxyTest.java
 *   java -cp .build-test WebProxyTest
 * </pre>
 */
public class WebProxyTest {

    static int pass = 0, fail = 0;

    /** 期望改写成 expect。 */
    static void rw(String url, String expect) {
        String got = CNWebProxy.rewrite(url);
        if (expect.equals(got)) { pass++; System.out.println("  ✅ 改写  " + url + "\n           → " + got); }
        else { fail++; System.out.println("  ❌ 改写结果不对  " + url + "\n           期望 " + expect + "\n           实得 " + got); }
    }

    /** 期望不改写（透传直连）。 */
    static void no(String url, String note) {
        String got = CNWebProxy.rewrite(url);
        if (got == null) { pass++; System.out.println("  ✅ 不改写  " + note); }
        else { fail++; System.out.println("  ❌ 本该透传却改写了  " + note + "\n           " + url + " → " + got); }
    }

    /** 绕开 configure()——它会写日志，而日志在 JVM 上没有落点。直接塞字段。 */
    static void setConfig(String base, String[] domains, int mode) throws Exception {
        Field b = CNWebProxy.class.getDeclaredField("base");
        b.setAccessible(true); b.set(null, base);
        Field d = CNWebProxy.class.getDeclaredField("domains");
        d.setAccessible(true); d.set(null, domains);
        Field m = CNWebProxy.class.getDeclaredField("mode");
        m.setAccessible(true); m.setInt(null, mode);
    }

    public static void main(String[] args) throws Exception {
        final String BASE = "https://api.example.test/stream/";
        final String[] DOM = { "magi-reco.com", "sisyphus.systems", "magica.f4samurai.com" };
        setConfig(BASE, DOM, CNWebProxy.MODE_ON);

        System.out.println("[1] 白名单内的主机应当改写");
        rw("https://dorothy.magi-reco.com/magica/api/page/TopPage?value=user&timeStamp=1",
           BASE + "dorothy.magi-reco.com/magica/api/page/TopPage?value=user&timeStamp=1");
        rw("https://ttzstrg9b.sisyphus.systems/en/magica/resource/x.json",
           BASE + "ttzstrg9b.sisyphus.systems/en/magica/resource/x.json");
        rw("https://magi-reco.com/x", BASE + "magi-reco.com/x");
        rw("https://magica.f4samurai.com/a/b", BASE + "magica.f4samurai.com/a/b");

        System.out.println("\n[2] 没有路径时补一个 '/'——不补会拼成 stream<host>");
        rw("https://dorothy.magi-reco.com", BASE + "dorothy.magi-reco.com/");
        rw("https://dorothy.magi-reco.com/", BASE + "dorothy.magi-reco.com/");

        System.out.println("\n[3] 查询串与片段要原样带上");
        rw("https://dorothy.magi-reco.com?a=1", BASE + "dorothy.magi-reco.com?a=1");
        rw("https://dorothy.magi-reco.com/magica/index.html#/TopPage",
           BASE + "dorothy.magi-reco.com/magica/index.html#/TopPage");

        System.out.println("\n[4] 后缀匹配必须卡在点上，不能是裸的 endsWith");
        no("https://evilmagi-reco.com/x", "把白名单拼在右边当后缀");
        no("https://magi-reco.com.evil.example/x", "把白名单放在左边当子域");
        no("https://notsisyphus.systems/x", "同理，sisyphus.systems");

        System.out.println("\n[5] 排除自身：改写它会打成死循环");
        no("https://api.example.test/legacy/config.json", "config.json 所在");
        no("https://example.test/x", "裸的 example.test");
        no("https://assets.example/cn_js_update.zip", "线路表里的资源域");

        System.out.println("\n[6] 只改 https");
        no("http://dorothy.magi-reco.com/x", "明文 http");
        no("ftp://dorothy.magi-reco.com/x", "非 http 协议");
        no("//dorothy.magi-reco.com/x", "协议相对");
        no("/magica/api/x", "站内相对路径");
        no(null, "null");
        no("", "空串");
        no("https://", "只有 scheme");
        no("https:///x", "主机名为空");

        System.out.println("\n[7] 端口：判白名单时要摘掉，改写时要留着");
        rw("https://dorothy.magi-reco.com:8443/x", BASE + "dorothy.magi-reco.com:8443/x");
        no("https://evil.example:443/x", "带端口的白名单外主机");

        System.out.println("\n[8] 配置不全就一律透传");
        setConfig(null, DOM, CNWebProxy.MODE_ON);
        no("https://dorothy.magi-reco.com/x", "base 为 null");
        setConfig(BASE, null, CNWebProxy.MODE_ON);
        no("https://dorothy.magi-reco.com/x", "白名单为 null");
        setConfig(BASE, new String[0], CNWebProxy.MODE_ON);
        no("https://dorothy.magi-reco.com/x", "白名单为空");
        setConfig("https://api.example.test/stream", DOM, CNWebProxy.MODE_ON);
        no("https://dorothy.magi-reco.com/x", "base 没有以 '/' 结尾（会拼出坏 URL）");

        System.out.println("\n[9] 白名单里的空串不能变成「匹配一切」");
        setConfig(BASE, new String[] { "" }, CNWebProxy.MODE_ON);
        no("https://anything.example/x", "白名单只有一个空串");
        setConfig(BASE, new String[] { "", "magi-reco.com" }, CNWebProxy.MODE_ON);
        rw("https://dorothy.magi-reco.com/x", BASE + "dorothy.magi-reco.com/x");
        no("https://anything.example/x", "空串混在有效项里也不该放行");

        System.out.println("\n通过 " + pass + " 项，失败 " + fail + " 项");
        if (fail > 0) System.exit(1);
    }
}
