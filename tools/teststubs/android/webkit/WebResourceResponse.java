package android.webkit;

import java.io.InputStream;
import java.util.Map;

/**
 * 测试用的 {@code WebResourceResponse} 实现，用来在 JVM 上盖掉 android.jar 里的桩。
 *
 * <h3>为什么需要它</h3>
 *
 * android.jar 里所有方法体都是 {@code throw new RuntimeException("Stub!")}，
 * 构造函数也不例外。而 {@link io.kamihama.magianative.CNWebProxy} 的
 * {@code fetchViaProxy} 成功路径**一定**会 new 一个出来——不盖掉它，这条路径在
 * JVM 上一步都跑不了，只能靠读代码来验，而读代码恰恰漏掉过 304 那种 bug。
 *
 * <p>用法：编译测试时把本目录放在 android.jar **前面**，先匹配者胜。
 *
 * <p>只实现 {@code CNWebProxy} 真正用到的那个 6 参构造与配套 getter。字段语义
 * 与平台一致：mimeType / encoding 是拆开传的，headers 里不含 Content-Type。
 */
public class WebResourceResponse {

    private final String mimeType;
    private final String encoding;
    private final int statusCode;
    private final String reasonPhrase;
    private final Map<String, String> responseHeaders;
    private final InputStream data;

    public WebResourceResponse(String mimeType, String encoding, InputStream data) {
        this(mimeType, encoding, 200, "OK", null, data);
    }

    public WebResourceResponse(String mimeType, String encoding, int statusCode,
                               String reasonPhrase, Map<String, String> responseHeaders,
                               InputStream data) {
        // 平台在这里是会校验的，测试里照做，免得放过「状态码越界 / reason 为空」
        // 这类只在真机上才炸的问题。
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("statusCode 越界: " + statusCode);
        }
        if (reasonPhrase == null || reasonPhrase.isEmpty()) {
            throw new IllegalArgumentException("reasonPhrase 不能为空");
        }
        this.mimeType = mimeType;
        this.encoding = encoding;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.responseHeaders = responseHeaders;
        this.data = data;
    }

    public String getMimeType()  { return mimeType; }
    public String getEncoding()  { return encoding; }
    public int    getStatusCode() { return statusCode; }
    public String getReasonPhrase() { return reasonPhrase; }
    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public InputStream getData() { return data; }
}
