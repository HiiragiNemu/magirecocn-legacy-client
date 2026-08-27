package io.kamihama.magianative;

import android.content.Context;
import android.os.Build;

import java.io.InputStream;
import java.net.InetAddress;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

/**
 * 「引擎能不能跟我们自建的服务端说话」的**整机自证**：服务端和客户端都在这台手机里。
 *
 * <h3>为什么要有它</h3>
 *
 * 这是自建服务端路线唯一的技术死穴。2026-08-22 从 {@code libmadomagi_native.so}
 * 挖出来的静态结论是「引擎压根不验证服务端证书」：
 *
 * <pre>
 *   SSL_CTX_set_verify ·················· 全库 0 次调用
 *   SSL_CTX_load_verify_locations ······· 0 次
 *   SSL_CTX_set_cert_verify_callback ···· 0 次
 *   内置 CA bundle ······················ 没有
 *   OPENSSLDIR ·························· 打包机路径，设备上不存在
 * </pre>
 *
 * 而界面上那个 336142563（=0x140920E3，{@code SSL3_GET_SERVER_HELLO /
 * PARSE_TLSEXT}）是 OpenSSL 1.0.2s 听不懂现代 TLS 栈的扩展，属于**代差不是信任**。
 *
 * <p>静态论证再密也是论证。这个类把它变成一次真实握手。
 *
 * <h3>为什么两端都放在手机里</h3>
 *
 * 维护者手上不一定有电脑。凡是要 {@code adb}、要「另一台机器起服务端」的方案，
 * 对只有手机的人等于不存在。所以：
 *
 * <ul>
 *   <li><b>服务端</b>：本类用 Android 自带的 TLS 栈（Conscrypt）在
 *       {@code 127.0.0.1} 上起一个 {@code SSLServerSocket}，随机端口，自签名证书；</li>
 *   <li><b>客户端</b>：native 侧 {@code nativeTlsProbe} 用 {@code dlsym(RTLD_DEFAULT)}
 *       拿到<b>引擎自己那份 OpenSSL 1.0.2s</b>（引擎 so 早就在本进程里），
 *       逐行复刻 {@code http2::Http2SessionManager::run} 的调用序列去连它。</li>
 * </ul>
 *
 * <p>用 Conscrypt 当服务端不是将就，是**刻意**：它就是一个现代 TLS 栈，和当初把
 * api/chat 打过去、结果报 336142563 的那个代理同类。所以这一次握手同时回答两件事
 * ——① 引擎认不认自签名（信任问题）；② 现代栈按 1.2 收紧之后，1.0.2 能不能听懂
 * （代差问题）。
 *
 * <h3>收紧到什么程度是从二进制量出来的</h3>
 *
 * <pre>
 *   协议    仅 TLSv1.2      引擎无 TLS1.3 痕迹，且 ctx 明确建成 tlsv12（method=0xf）
 *   曲线    P-256/384/521   引擎里 X25519 符号数为 0（那是 1.1.0 才有的）
 *   cipher  见 CIPHERS      取 1.0.2s 与 Android 都有的交集
 *   ALPN    h2              引擎走 nghttp2；API 29+ 才设得了，低版本只是不设
 * </pre>
 *
 * <h3>零风险保证</h3>
 *
 * 整个类只在调试开关 {@code tlsProbe} 打开时才被调用一次，且全程吞异常。
 * 关着的时候一行都不执行，服务端也不会起——它不在任何常规启动路径上。
 */
public final class CNTlsProbe {

    private static final String TAG = "TLS探针";

    /** 自签名证书（PKCS#12）。随包走，不联网、不生成。 */
    private static final String P12_ASSET = "magia/tlsprobe.p12";
    /** 测试证书的口令。写在这里没有安全含义：它保护的是一张**故意**谁都能用的自签名证书。 */
    private static final char[] P12_PASS = "magiaprobe".toCharArray();

    /**
     * 1.0.2s 与 Android 都有的 cipher 交集。
     *
     * <p>⚠ 顺序即优先级，且不能只留 ECDHE：万一某设备的 Conscrypt 把这几条都禁了，
     * 留一条纯 RSA 密钥交换的兜底，至少能把「握手能不能成」和「cipher 谈不拢」
     * 两种失败分开。
     */
    private static final String[] CIPHERS = {
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_RSA_WITH_AES_128_GCM_SHA256",
    };

    private CNTlsProbe() {}

    /** native 侧实现，见 MagiaLegacy.cpp 的 tlsprobe 命名空间。 */
    public static native String nativeTlsProbe(String host, int port);

    /** 一个进程只探一次。两个调用点都会叫它，见下面 runAsync 的注释。 */
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 等 native 库加载的上限。超时就照常试一次，让失败自己说话。 */
    private static final long NATIVE_WAIT_MS = 30000L;
    private static final long NATIVE_POLL_MS = 250L;

    /**
     * 跑一次。<b>只在调试开关打开时调用</b>；本身吞掉所有异常。
     *
     * <p>自己起线程：要等一次完整握手，不能压在调用方的线程上（调用点在启动链上）。
     *
     * <h3>为什么有两个调用点</h3>
     *
     * 本来只挂在 {@code CNDownloaderFix.runInstaller} 开头，注释还写着「必定会
     * 执行的 native 入口」。<b>那是错的</b>：runInstaller 只在资源没装齐时才被
     * 叫起，而真机上资源早就装齐，走的是 {@code triggerInstaller} 里
     * {@code installed==true} 那一支。2026-08-27 两轮真机日志「runInstaller 被
     * 调用」都是 0 次——开关打开也一行输出都没有，看上去像探针坏了，其实是根本
     * 没被调到。现在 triggerInstaller 里也调一次（那条路装没装齐都会跑），
     * 用上面的哨兵保证只探一遍。
     */
    public static void runAsync() {
        try {
            if (!CNDebugFlags.isOn(CNDebugFlags.TLS_PROBE)) return;
            if (!STARTED.compareAndSet(false, true)) return;
            Thread t = new Thread(new Runner(), "cnv-tls-probe");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            CNLog.w(TAG, "探针线程起不来: " + e);
        }
    }

    /**
     * 等到 native 库真的加载完。
     *
     * <p>两个调用点都在启动链很靠前的位置，那时 {@code libMagiaLegacy.so} 往往
     * 还没 load——真机日志里 CNDebugBridge 在同一毫秒就吃过
     * {@code UnsatisfiedLinkError}，500ms 后才就绪。直接调
     * {@link #nativeTlsProbe} 会当场 UnsatisfiedLinkError，日志里看起来像「探针
     * 失败」，而真正的原因只是早了半秒。
     *
     * <p>判据借 {@link CNDebugBridge#overlayGate()} 的三态：{@code null} =
     * 库还没加载。那是本仓库里现成的、写过教训的「native 好了没有」信号
     * （见它的类注释里 2026-08-13 那次相差一毫秒的失败），不必再造一个。
     *
     * @return 真的等到了返回 true；超时返回 false（调用方仍会试一次）
     */
    private static boolean awaitNative() {
        long deadline = android.os.SystemClock.elapsedRealtime() + NATIVE_WAIT_MS;
        boolean waited = false;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            try {
                if (CNDebugBridge.overlayGate() != null) {
                    if (waited) CNLog.i(TAG, "native 库已就绪，开始探测");
                    return true;
                }
            } catch (Throwable ignore) {}
            waited = true;
            try { Thread.sleep(NATIVE_POLL_MS); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        CNLog.w(TAG, "等了 " + (NATIVE_WAIT_MS / 1000) + " 秒仍拿不到 native 就绪信号，照常试一次");
        return false;
    }

    private static final class Runner implements Runnable {
        @Override public void run() {
            SSLServerSocket server = null;
            try {
                CNLog.i(TAG, "==== TLS 探针开始（服务端与客户端都在本机）====");
                awaitNative();
                server = startServer();
                int port = server.getLocalPort();
                CNLog.i(TAG, "本机 TLS1.2 服务端已起：127.0.0.1:" + port
                        + "（自签名，Android 自带 TLS 栈）");

                final SSLServerSocket srv = server;
                Thread acceptor = new Thread(new Acceptor(srv), "cnv-tls-probe-accept");
                acceptor.setDaemon(true);
                acceptor.start();

                String report = nativeTlsProbe("127.0.0.1", port);
                for (String line : (report == null ? "" : report).split("\n")) {
                    if (line.length() > 0) CNLog.i(TAG, line);
                }
                CNLog.i(TAG, "==== TLS 探针结束 ====");
            } catch (Throwable e) {
                CNLog.e(TAG, "探针失败（不影响其它功能）", e);
            } finally {
                CNIo.closeQuietly(server);
            }
        }
    }

    /** 收一条连接、把握手做完就算数——本探针要的是握手，不是 HTTP。 */
    private static final class Acceptor implements Runnable {
        private final SSLServerSocket srv;
        Acceptor(SSLServerSocket srv) { this.srv = srv; }
        @Override public void run() {
            try {
                SSLSocket s = (SSLSocket) srv.accept();
                s.setSoTimeout(15000);
                s.startHandshake();
                CNLog.i(TAG, "服务端侧握手完成："
                        + s.getSession().getProtocol() + " / "
                        + s.getSession().getCipherSuite());
                CNIo.closeQuietly(s);
            } catch (Throwable e) {
                // 客户端失败时这里通常也会抛，属于同一件事的另一面，记一条就够
                CNLog.w(TAG, "服务端侧: " + e);
            }
        }
    }

    private static SSLServerSocket startServer() throws Exception {
        Context ctx = CNRestClientActivity.appContext();
        if (ctx == null) throw new IllegalStateException("取不到 Context");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream in = null;
        try {
            in = ctx.getAssets().open(P12_ASSET);
            ks.load(in, P12_PASS);
        } finally {
            CNIo.closeQuietly(in);
        }
        KeyManagerFactory kmf =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, P12_PASS);

        SSLContext sc = SSLContext.getInstance("TLSv1.2");
        sc.init(kmf.getKeyManagers(), null, null);

        SSLServerSocketFactory f = sc.getServerSocketFactory();
        SSLServerSocket srv = (SSLServerSocket) f.createServerSocket(
                0, 1, InetAddress.getByName("127.0.0.1"));
        // 只说 TLS 1.2。这是整张清单里最要紧的一条：谈到 1.3 的话，ServerHello
        // 里那些扩展正是 1.0.2 解析不动、报 PARSE_TLSEXT 的东西。
        srv.setEnabledProtocols(new String[] { "TLSv1.2" });

        // cipher 取交集；设备上没有的就跳过，别因为一条不支持就整个 setEnabled 抛异常
        List<String> want = new ArrayList<String>();
        List<String> have = java.util.Arrays.asList(srv.getSupportedCipherSuites());
        for (int i = 0; i < CIPHERS.length; i++) {
            if (have.contains(CIPHERS[i])) want.add(CIPHERS[i]);
        }
        if (!want.isEmpty()) {
            srv.setEnabledCipherSuites(want.toArray(new String[want.size()]));
        }
        CNLog.i(TAG, "服务端启用 cipher " + want.size() + " 条：" + want);

        // ALPN 只有 API 29+ 设得了。设不了不是失败——引擎那边 ALPN 谈不出来时
        // nghttp2 仍会走 h2（它是直接按 h2 发的），只是少一条佐证。
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                SSLParameters p = srv.getSSLParameters();
                p.setApplicationProtocols(new String[] { "h2" });
                srv.setSSLParameters(p);
                CNLog.i(TAG, "服务端已设 ALPN=h2");
            } catch (Throwable e) {
                CNLog.w(TAG, "设 ALPN 失败（不致命）: " + e);
            }
        } else {
            CNLog.i(TAG, "系统低于 Android 10，服务端设不了 ALPN（不致命）");
        }
        return srv;
    }
}
