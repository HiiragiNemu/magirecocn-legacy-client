package io.kamihama.magianative;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

/**
 * 开一条按本仓库统一口径加固过的 {@link HttpURLConnection}。
 *
 * <h3>为什么要有这个入口</h3>
 *
 * 在此之前，同一段建连样板在六个类里各写了一遍，而它们已经**漂移**了：
 *
 * <pre>
 *   缺 setInstanceFollowRedirects(true)：CNMirrors 的测速探针、CNDownloaderFix 的
 *                                        续传取回与 POST
 *   缺 setUseCaches(false)            ：CNVersionCheck
 * </pre>
 *
 * 这两处今天都不是活的故障——JVM 的 {@code followRedirects} 默认本来就是 true，
 * Android 也默认不装 HTTP 响应缓存，所以省略与显式设置等价。但「靠平台默认值恰好
 * 和我们想要的一致」不是一种保证：任何一处将来调了
 * {@code HttpURLConnection.setFollowRedirects(false)} 或装上
 * {@code HttpResponseCache}，这些省略就会同时变成真故障，而且各家表现还不一样。
 * 收进一处之后，「我们要什么」只有一个地方写着。
 *
 * <h3>这里只收无争议的部分</h3>
 *
 * 超时按调用方传——它们的差异是**有意的**，不该统一：批量下载 15s/30s 要能等，
 * 控制面 1.8s/2.2s 要快失败。同理 {@code Accept-Encoding}、{@code Range}、
 * {@code Accept}、请求方法都留在调用点，因为它们表达的是这次请求要什么，不是加固。
 *
 * <h3>谁不该走这里</h3>
 *
 * <ul>
 *   <li>{@code CNAria2} 的 loopback JSON-RPC —— 它<b>有意</b>把
 *       {@code setInstanceFollowRedirects} 设成 {@code false}：本地 RPC 不该跟任何
 *       跳转走。硬套统一口径正好把这条安全性质抹掉。
 *   <li>{@code CNWebProxy} 的取数与测速 —— 那是透明代理，要原样转发调用方的头。
 *       强设 {@code Accept-Encoding} 或缓存策略会改变被代理请求的语义。
 * </ul>
 *
 * 排除是有理由的排除，不是漏掉；{@code tools/check-http-open-contract.py} 把这两条
 * 例外连同理由一起钉住，免得以后有人「顺手统一一下」。
 */
public final class CNHttp {

    private CNHttp() {}

    /**
     * @param u         目标地址
     * @param forceDirect {@code true} = 绕开系统代理直连（{@link Proxy#NO_PROXY}）；
     *                  {@code false} = 尊重 Android 系统代理。<b>这个区别是语义性的</b>：
     *                  资源与线路表要直连（显式 NO_PROXY 才能确保不被系统代理牵走），
     *                  而版本/控制面有意尊重玩家已配好的代理链，见各调用点的注释。
     * @param connectMs 连接超时
     * @param readMs    读超时
     */
    public static HttpURLConnection open(URL u, boolean forceDirect, int connectMs, int readMs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection)
                (forceDirect ? u.openConnection(Proxy.NO_PROXY) : u.openConnection());
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        // 不吃 HTTP 缓存：这条链上取的全是「当前是什么」——线路表、版本号、分片字节。
        // 拿到缓存副本不会报错，只会让玩家停在一个旧世界里，而且极难查。
        c.setUseCaches(false);
        // 跟跳转：镜像/CDN 用 3xx 换节点是常态。JVM 默认也是 true，写出来是为了让
        // 「我们要什么」有个明确的落点，而不是继承一个别处可能被改掉的全局默认。
        c.setInstanceFollowRedirects(true);
        CNUserAgent.apply(c);
        return c;
    }
}
